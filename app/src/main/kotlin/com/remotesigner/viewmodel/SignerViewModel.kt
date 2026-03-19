package com.remotesigner.viewmodel

import android.app.Application
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remotesigner.bridge.PythonBridgeInterface
import com.remotesigner.bridge.SigningCallbackImpl
import com.remotesigner.bridge.SigningOrchestrator
import com.remotesigner.bridge.SigningResult
import com.remotesigner.data.ContactRepository
import com.remotesigner.data.InboxRepository
import com.remotesigner.nfc.NfcReadResult
import com.remotesigner.nostr.InboxItemEntity
import com.remotesigner.nostr.InboxStatus
import com.remotesigner.nostr.NostrKeyManager
import com.remotesigner.nostr.NostrReceiver
import com.remotesigner.usb.SigningBridge
import com.remotesigner.usb.TrezorUsbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TxInput(
    val address: String,
    val amount: Long,
)

data class TxOutput(
    val address: String,
    val amount: Long,
    val isChange: Boolean,
    val opReturn: String? = null,
)

data class SignerInfo(
    val fingerprint: String,
    val signed: Boolean,
    val isThisDevice: Boolean = false,
    val contactLabel: String? = null,
    val contactId: Long? = null,
)

sealed class AppState {
    data object Home : AppState()
    data class TransactionReview(
        val inputs: List<TxInput>,
        val outputs: List<TxOutput>,
        val fee: Long,
        val totalSent: Long,
        val status: String,
        val signers: List<SignerInfo>,
        val warnings: List<String>,
        val requiredSigs: Int = 0,
        val totalSigs: Int = 0,
        val network: String = "main",
        val description: String? = null,
    ) : AppState()
    data class Signing(val message: String, val log: String = "") : AppState()
    data class Result(
        val isComplete: Boolean,
        val txid: String? = null,
        val rawHex: String? = null,
        val updatedPsbt: ByteArray? = null,
        val broadcastStatus: String? = null,
        val errorMessage: String? = null,
        val network: String = "main",
    ) : AppState()
    data class Error(val message: String) : AppState()
    data object Contacts : AppState()
    data object EncryptPassphrase : AppState()
}

data class PassphraseRequest(
    val availableOnDevice: Boolean,
    val callback: SigningCallbackImpl,
)

data class AccountPathRequest(
    val callback: SigningCallbackImpl,
)

class SignerViewModel(
    application: Application,
    private val pythonBridge: PythonBridgeInterface,
    private val contactRepository: ContactRepository,
    private val inboxRepository: InboxRepository,
    private val signingOrchestrator: SigningOrchestrator,
    val trezorUsb: TrezorUsbManager,
    val keyManager: NostrKeyManager,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<AppState>(AppState.Home)
    val state: StateFlow<AppState> = _state.asStateFlow()

    val contacts = contactRepository.allWithFingerprints

    private var currentPsbtBytes: ByteArray? = null
    private var currentNetwork: String = "main"
    private var currentDescription: String? = null
    private var signingJob: Job? = null
    val passphraseRequest: StateFlow<PassphraseRequest?> = signingOrchestrator.passphraseRequest
    val accountPathRequest: StateFlow<AccountPathRequest?> = signingOrchestrator.accountPathRequest

    private val _nfcWaitingForTag = MutableStateFlow(false)
    val nfcWaitingForTag: StateFlow<Boolean> = _nfcWaitingForTag.asStateFlow()
    private val _nfcTagResult = MutableStateFlow<NfcReadResult?>(null)
    val nfcTagResult: StateFlow<NfcReadResult?> = _nfcTagResult.asStateFlow()

    fun startNfcWaiting() {
        _nfcTagResult.value = null
        _nfcWaitingForTag.value = true
    }

    fun stopNfcWaiting() {
        _nfcWaitingForTag.value = false
        _nfcTagResult.value = null
    }

    fun onNfcTagResult(result: NfcReadResult) {
        if (result is NfcReadResult.Success) {
            try {
                val (privkey, pubkey) = keyManager.getOrCreateKeyPair()
                val decrypted = com.remotesigner.nostr.Nip04.decrypt(privkey, pubkey, result.passphrase)
                _nfcTagResult.value = NfcReadResult.Success(decrypted)
            } catch (_: Exception) {
                _nfcTagResult.value = NfcReadResult.Error(
                    "Could not decrypt NFC tag \u2014 was it encrypted with this phone\u2019s key?"
                )
            }
        } else {
            _nfcTagResult.value = result
        }
    }

    fun clearNfcResult() {
        _nfcTagResult.value = null
    }

    // --- Nostr inbox ---
    val inboxItems: StateFlow<List<InboxItemEntity>> = inboxRepository.items
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var currentSigningInboxId: String? = null

    private val nostrReceiver = NostrReceiver(
        keyManager = keyManager,
        onItem = { item ->
            viewModelScope.launch(Dispatchers.IO) {
                inboxRepository.handleInboxEvent(item)
            }
        },
        scope = viewModelScope,
    )

    val relayConnectedCount: StateFlow<Int> = nostrReceiver.connectedCount
    val relayStatuses: StateFlow<Map<String, com.remotesigner.nostr.RelayStatus>> = nostrReceiver.relayStatuses

    private val inboxSeedJob: Job

    init {
        inboxSeedJob = viewModelScope.launch {
            val ids = inboxRepository.cleanupAndSeedIds()
            if (ids.isNotEmpty()) nostrReceiver.seedSeenIds(ids)
        }
    }

    fun startNostrReceiver() {
        viewModelScope.launch {
            inboxSeedJob.join()
            nostrReceiver.connect()
        }
    }

    fun stopNostrReceiver() = nostrReceiver.disconnect()

    fun signInboxItem(item: InboxItemEntity) {
        currentSigningInboxId = item.id
        currentDescription = item.label.ifBlank { null }
        viewModelScope.launch { inboxRepository.updateStatus(item.id, InboxStatus.SIGNING) }
        loadPsbt(item.psbtBytes)
    }

    fun deleteInboxItem(id: String) {
        viewModelScope.launch { inboxRepository.updateStatus(id, InboxStatus.DELETED) }
    }

    fun openInboxResult(item: InboxItemEntity) {
        if (item.status != InboxStatus.SIGNED && item.status != InboxStatus.BROADCAST) return
        currentSigningInboxId = item.id
        currentPsbtBytes = item.psbtBytes
        _state.value = AppState.Result(
            isComplete = true,
            rawHex = item.rawHex,
            txid = item.txid,
            broadcastStatus = if (item.txid != null) "Broadcast successful" else null,
            network = item.network,
        )
    }

    fun loadPsbt(uri: Uri) {
        currentDescription = null
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver
                        .openInputStream(uri)?.readBytes()
                        ?: throw IllegalStateException("Could not read file")
                }
                parsePsbt(bytes)
            } catch (e: Exception) {
                _state.value = AppState.Error("Failed to read file: ${e.message}")
            }
        }
    }

    fun loadPsbt(bytes: ByteArray) {
        viewModelScope.launch { parsePsbt(bytes) }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun parsePsbt(bytes: ByteArray) {
        try {
            val result = withContext(Dispatchers.IO) {
                pythonBridge.parsePsbt(bytes)
            }
            currentPsbtBytes = bytes
            currentNetwork = result["network"]?.toString() ?: "main"

            val inputs = (result["inputs"] as? List<Map<String, Any?>>)?.map { inp ->
                TxInput(
                    address = inp["address"]?.toString() ?: "unknown",
                    amount = (inp["amount"] as? Number)?.toLong() ?: 0,
                )
            } ?: emptyList()

            val outputs = (result["outputs"] as? List<Map<String, Any?>>)?.map { out ->
                TxOutput(
                    address = out["address"]?.toString() ?: "unknown",
                    amount = (out["amount"] as? Number)?.toLong() ?: 0,
                    isChange = out["is_change"] as? Boolean ?: false,
                    opReturn = out["op_return"]?.toString(),
                )
            } ?: emptyList()

            val fee = (result["fee"] as? Number)?.toLong() ?: 0
            val totalSent = outputs.filter { !it.isChange }.sumOf { it.amount }

            val rawSigners = (result["signers"] as? List<Map<String, Any?>>)?.map { s ->
                SignerInfo(
                    fingerprint = s["fingerprint"]?.toString() ?: "",
                    signed = s["signed"] as? Boolean ?: false,
                )
            } ?: emptyList()

            val signers = contactRepository.enrichSigners(rawSigners)

            val warnings = mutableListOf<String>()
            if (fee > 1_000_000) {
                warnings.add("Fee is unusually high: ${"%.8f".format(fee / 100_000_000.0)} BTC")
            }

            _state.value = AppState.TransactionReview(
                inputs = inputs,
                outputs = outputs,
                fee = fee,
                totalSent = totalSent,
                status = result["status"]?.toString() ?: "unknown",
                signers = signers,
                warnings = warnings,
                requiredSigs = (result["required_sigs"] as? Number)?.toInt() ?: 0,
                totalSigs = (result["total_sigs"] as? Number)?.toInt() ?: 0,
                network = currentNetwork,
                description = currentDescription,
            )
        } catch (e: Exception) {
            _state.value = AppState.Error("Invalid PSBT: ${e.message}")
        }
    }

    fun signWithTrezor() {
        val psbt = currentPsbtBytes ?: return

        _state.value = AppState.Signing("Connecting to Trezor...", log = "")

        signingJob = viewModelScope.launch {
            val result = signingOrchestrator.signWithTrezor(
                psbtBytes = psbt,
                network = currentNetwork,
                onProgress = { msg ->
                    val current = (_state.value as? AppState.Signing)?.log ?: ""
                    _state.value = AppState.Signing(msg, log = current + msg + "\n")
                },
            )
            handleSigningResult(result)
        }
    }

    @VisibleForTesting
    internal fun signWithBridge(bridge: SigningBridge, psbtBytes: ByteArray, network: String) {
        _state.value = AppState.Signing("Signing...", log = "")
        signingJob = viewModelScope.launch {
            val result = signingOrchestrator.signWithBridge(
                bridge = bridge,
                psbtBytes = psbtBytes,
                network = network,
                onProgress = { msg ->
                    val current = (_state.value as? AppState.Signing)?.log ?: ""
                    _state.value = AppState.Signing(msg, log = current + msg + "\n")
                },
                withPassphraseUI = false,
            )
            handleSigningResult(result)
        }
    }

    private suspend fun handleSigningResult(result: SigningResult) {
        // Clean up NFC state (was in doSignWithBridge.finally, now orchestrator doesn't own it)
        _nfcWaitingForTag.value = false
        _nfcTagResult.value = null

        val inboxId = currentSigningInboxId
        when (result) {
            is SigningResult.Complete -> {
                if (inboxId != null) {
                    val rawTx = result.rawHex
                    if (rawTx != null) {
                        inboxRepository.updateSigned(inboxId, InboxStatus.SIGNED, rawTx, result.network)
                    } else {
                        inboxRepository.updateStatus(inboxId, InboxStatus.SIGNED)
                    }
                }
                _state.value = AppState.Result(
                    isComplete = true,
                    rawHex = result.rawHex,
                    network = result.network,
                )
            }
            is SigningResult.Partial -> {
                _state.value = AppState.Result(
                    isComplete = false,
                    updatedPsbt = result.updatedPsbtBytes,
                    network = result.network,
                )
            }
            is SigningResult.Cancelled -> {
                if (inboxId != null) {
                    inboxRepository.updateStatus(inboxId, InboxStatus.PENDING)
                }
                if (currentPsbtBytes != null) {
                    parsePsbt(currentPsbtBytes!!)
                } else {
                    _state.value = AppState.Home
                }
            }
            is SigningResult.Error -> {
                if (inboxId != null) {
                    inboxRepository.updateStatus(inboxId, InboxStatus.FAILED)
                }
                _state.value = AppState.Error(
                    "${result.message}\n\n--- Log ---\n${result.log}"
                )
            }
        }
    }

    fun broadcast(targetNetwork: String) {
        val state = _state.value
        if (state !is AppState.Result || state.rawHex == null) return

        _state.value = state.copy(broadcastStatus = "Broadcasting...")

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                pythonBridge.broadcast(state.rawHex, targetNetwork)
            }

            if (result["status"] == "ok") {
                val txid = result["txid"]?.toString()
                _state.value = state.copy(
                    txid = txid,
                    broadcastStatus = "Broadcast successful",
                    network = targetNetwork,
                )
                val inboxId = currentSigningInboxId
                if (inboxId != null && txid != null) {
                    inboxRepository.updateBroadcast(inboxId, InboxStatus.BROADCAST, txid, targetNetwork)
                }
            } else {
                _state.value = state.copy(
                    broadcastStatus = "Broadcast failed: ${result["message"]}",
                )
            }
        }
    }

    fun cancelSigning() {
        val inboxId = currentSigningInboxId
        if (inboxId != null) {
            viewModelScope.launch { inboxRepository.updateStatus(inboxId, InboxStatus.PENDING) }
        }
        signingOrchestrator.cancel()
        signingJob?.cancel()
        signingJob = null
        _nfcWaitingForTag.value = false
        _nfcTagResult.value = null
        if (currentPsbtBytes != null) {
            viewModelScope.launch { parsePsbt(currentPsbtBytes!!) }
        } else {
            _state.value = AppState.Home
        }
    }

    fun showContacts() {
        _state.value = AppState.Contacts
    }

    fun showEncryptPassphrase() {
        _state.value = AppState.EncryptPassphrase
    }

    fun encryptForNfc(plaintext: String): String {
        val (privkey, pubkey) = keyManager.getOrCreateKeyPair()
        return com.remotesigner.nostr.Nip04.encrypt(privkey, pubkey, plaintext)
    }

    fun saveContact(label: String, fingerprint: String, existingContactId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            contactRepository.saveContact(label, fingerprint, existingContactId)
            reEnrichSigners()
        }
    }

    fun updateContact(contactId: Long, newLabel: String, npub: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            contactRepository.updateContact(contactId, newLabel, npub)
        }
    }

    fun addFingerprintToContact(contactId: Long, fingerprint: String) {
        viewModelScope.launch(Dispatchers.IO) {
            contactRepository.addFingerprint(contactId, fingerprint)
            reEnrichSigners()
        }
    }

    fun deleteContact(contactId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            contactRepository.deleteContact(contactId)
            reEnrichSigners()
        }
    }

    fun deleteFingerprint(fingerprintId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            contactRepository.deleteFingerprint(fingerprintId)
            reEnrichSigners()
        }
    }

    private suspend fun reEnrichSigners() {
        val currentState = _state.value
        if (currentState is AppState.TransactionReview) {
            val enriched = contactRepository.enrichSigners(currentState.signers)
            _state.value = currentState.copy(signers = enriched)
        }
    }

    fun goHome() {
        val inboxId = currentSigningInboxId
        if (inboxId != null) {
            val currentState = _state.value
            viewModelScope.launch {
                when (currentState) {
                    is AppState.Result -> {
                        if (currentState.txid == null) {
                            inboxRepository.updateStatus(inboxId, InboxStatus.SIGNED)
                        }
                    }
                    is AppState.Error -> inboxRepository.updateStatus(inboxId, InboxStatus.FAILED)
                    else -> {}
                }
            }
            currentSigningInboxId = null
        }
        currentPsbtBytes = null
        currentDescription = null
        _state.value = AppState.Home
    }
}

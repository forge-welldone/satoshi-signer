package com.remotesigner.viewmodel

import android.app.Application
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remotesigner.bridge.PythonBridge
import com.remotesigner.data.AppDatabase
import com.remotesigner.data.Contact
import com.remotesigner.data.ContactFingerprint
import com.remotesigner.data.ContactWithFingerprints
import com.remotesigner.data.FingerprintValidator
import com.remotesigner.bridge.SigningCallbackImpl
import com.remotesigner.nfc.NfcReadResult
import com.remotesigner.nostr.InboxItemEntity
import com.remotesigner.nostr.InboxStatus
import com.remotesigner.nostr.NostrKeyManager
import com.remotesigner.nostr.NostrReceiver
import com.remotesigner.nostr.formatBtcAmount
import com.remotesigner.usb.SigningBridge
import com.remotesigner.usb.TrezorUsbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
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

class SignerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<AppState>(AppState.Home)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val pythonBridge = PythonBridge()
    private val contactDao = AppDatabase.getInstance(application).contactDao()
    val contacts = contactDao.getAllWithFingerprints()
    val trezorUsb = TrezorUsbManager(application)

    private var currentPsbtBytes: ByteArray? = null
    private var currentNetwork: String = "main"
    private var currentDescription: String? = null
    private var currentUsbBridge: SigningBridge? = null
    private var signingJob: Job? = null
    private val _passphraseRequest = MutableStateFlow<PassphraseRequest?>(null)
    val passphraseRequest: StateFlow<PassphraseRequest?> = _passphraseRequest.asStateFlow()
    private val _accountPathRequest = MutableStateFlow<AccountPathRequest?>(null)
    val accountPathRequest: StateFlow<AccountPathRequest?> = _accountPathRequest.asStateFlow()

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

    private var currentSigningCallback: SigningCallbackImpl? = null

    // --- Nostr inbox ---
    val keyManager = NostrKeyManager(application)
    private val inboxDao = AppDatabase.getInstance(application).inboxDao()
    val inboxItems: StateFlow<List<InboxItemEntity>> = inboxDao.getAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var currentSigningInboxId: String? = null

    private val nostrReceiver = NostrReceiver(
        keyManager = keyManager,
        onItem = { item -> handleInboxEvent(item) },
        scope = viewModelScope,
    )

    val relayConnectedCount: StateFlow<Int> = nostrReceiver.connectedCount
    val relayStatuses: StateFlow<Map<String, com.remotesigner.nostr.RelayStatus>> = nostrReceiver.relayStatuses

    private val inboxSeedJob: Job

    init {
        inboxSeedJob = viewModelScope.launch {
            val now = System.currentTimeMillis() / 1000
            inboxDao.deleteExpired(
                pendingCutoff = now - 86_400,
                signedCutoff = now - 86_400 * 7,
            )
            val persisted = inboxDao.getAllOnce()
            if (persisted.isNotEmpty()) {
                nostrReceiver.seedSeenIds(persisted.map { it.id }.toSet())
            }
        }
    }

    fun startNostrReceiver() {
        viewModelScope.launch {
            inboxSeedJob.join()
            nostrReceiver.connect()
        }
    }

    fun stopNostrReceiver() = nostrReceiver.disconnect()

    private fun handleInboxEvent(item: InboxItemEntity) {
        viewModelScope.launch {
            val inserted = inboxDao.insertIgnore(item)
            if (inserted == -1L) return@launch

            try {
                val result = withContext(Dispatchers.IO) {
                    pythonBridge.parsePsbt(item.psbtBytes)
                }
                @Suppress("UNCHECKED_CAST")
                val outputs = result["outputs"] as? List<Map<String, Any?>> ?: emptyList()
                val totalSent = outputs
                    .filter { it["is_change"] as? Boolean != true }
                    .sumOf { (it["amount"] as? Number)?.toLong() ?: 0L }
                val network = result["network"]?.toString() ?: "main"
                inboxDao.updateParsedFields(
                    id = item.id,
                    amount = formatBtcAmount(totalSent),
                    network = network,
                )
            } catch (_: Exception) {
                // Keep original row if parse fails
            }
        }
    }

    fun signInboxItem(item: InboxItemEntity) {
        currentSigningInboxId = item.id
        currentDescription = item.label.ifBlank { null }
        viewModelScope.launch { inboxDao.updateStatus(item.id, InboxStatus.SIGNING) }
        loadPsbt(item.psbtBytes)
    }

    fun deleteInboxItem(id: String) {
        viewModelScope.launch { inboxDao.updateStatus(id, InboxStatus.DELETED) }
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

            val signerFingerprints = rawSigners.map { it.fingerprint }
            val contactMap = contactDao.findByFingerprints(signerFingerprints)
                .flatMap { cwf -> cwf.fingerprints.map { fp -> fp.fingerprint to cwf } }
                .toMap()
            val signers = rawSigners.map { signer ->
                val contact = contactMap[signer.fingerprint]
                signer.copy(
                    contactLabel = contact?.contact?.label,
                    contactId = contact?.contact?.id,
                )
            }

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
        val device = trezorUsb.findTrezorDevice()

        if (device == null) {
            _state.value = AppState.Signing("Connect Trezor via USB-C cable", log = "Waiting for device...")
            signingJob = viewModelScope.launch {
                // Poll for device every second until found or cancelled
                while (true) {
                    delay(1000)
                    val found = trezorUsb.findTrezorDevice()
                    if (found != null) {
                        signingJob = null
                        signWithTrezor()
                        return@launch
                    }
                }
            }
            return
        }

        if (!trezorUsb.hasPermission(device)) {
            trezorUsb.requestPermission(device) { granted ->
                if (granted) signWithTrezor()
                else _state.value = AppState.Error("USB permission denied")
            }
            return
        }

        _state.value = AppState.Signing("Connecting to Trezor...", log = "")

        signingJob = viewModelScope.launch {
            fun log(msg: String) {
                val current = (_state.value as? AppState.Signing)?.log ?: ""
                _state.value = AppState.Signing(msg, log = current + msg + "\n")
            }

            try {
                log("Opening USB connection...")
                val bridge = withContext(Dispatchers.IO) {
                    trezorUsb.openDevice(device)
                        ?: throw IllegalStateException("Failed to open USB device")
                }
                currentUsbBridge = bridge

                log(bridge.dumpDeviceInfo())

                log("Claiming interface & finding endpoints...")
                withContext(Dispatchers.IO) { bridge.open() }
                log("USB bridge opened OK")

                // Delegate signing to shared suspend function
                doSignWithBridge(bridge, psbt, currentNetwork)
            } catch (e: Exception) {
                // Only catches USB open failures — doSignWithBridge has its own try/catch
                if (_state.value is AppState.Signing) {
                    val signingLog = (_state.value as? AppState.Signing)?.log ?: ""
                    _state.value = AppState.Error("Signing error: ${e.message}\n\n--- Log ---\n$signingLog")
                }
            } finally {
                // Safety net: if doSignWithBridge didn't run, ensure bridge is closed
                currentUsbBridge?.close()
                currentUsbBridge = null
            }
        }
    }

    @VisibleForTesting
    internal fun signWithBridge(bridge: SigningBridge, psbtBytes: ByteArray, network: String) {
        _state.value = AppState.Signing("Signing...", log = "")
        signingJob = viewModelScope.launch {
            doSignWithBridge(bridge, psbtBytes, network, withPassphraseUI = false)
        }
    }

    private suspend fun doSignWithBridge(
        bridge: SigningBridge,
        psbtBytes: ByteArray,
        network: String,
        withPassphraseUI: Boolean = true,
    ) {
        fun log(msg: String) {
            val current = (_state.value as? AppState.Signing)?.log ?: ""
            _state.value = AppState.Signing(msg, log = current + msg + "\n")
        }

        try {
            log("Starting Python signing (network=$network)...")

            // When withPassphraseUI is false (tests), pass null callback to Python
            // so AndroidTrezorUi falls back to on-device passphrase automatically.
            // When true (production), create a blocking callback for the passphrase dialog.
            val signingCallback: SigningCallbackImpl? = if (withPassphraseUI) {
                SigningCallbackImpl(
                    onStatusUpdate = { status ->
                        viewModelScope.launch { log("Python: $status") }
                    },
                    onPassphraseRequest = { availableOnDevice ->
                        _passphraseRequest.value = PassphraseRequest(
                            availableOnDevice, currentSigningCallback!!
                        )
                    },
                    onPassphraseSubmitted = { _passphraseRequest.value = null },
                    onAccountPathRequest = {
                        _accountPathRequest.value = AccountPathRequest(
                            currentSigningCallback!!
                        )
                    },
                    onAccountPathSubmitted = { _accountPathRequest.value = null },
                ).also { currentSigningCallback = it }
            } else {
                null
            }

            val result = withContext(Dispatchers.IO) {
                pythonBridge.signPsbt(
                    psbtBytes = psbtBytes,
                    bridge = bridge,
                    callback = signingCallback,
                    network = network,
                )
            }
            _passphraseRequest.value = null
            _accountPathRequest.value = null
            currentSigningCallback = null

            when (result["status"]) {
                "complete" -> {
                    val inboxId = currentSigningInboxId
                    if (inboxId != null) {
                        val rawTx = result["raw_tx"]?.toString()
                        if (rawTx != null) {
                            inboxDao.updateSigned(inboxId, InboxStatus.SIGNED, rawTx, network)
                        } else {
                            inboxDao.updateStatus(inboxId, InboxStatus.SIGNED)
                        }
                    }
                    _state.value = AppState.Result(
                        isComplete = true,
                        rawHex = result["raw_tx"]?.toString(),
                        network = network,
                    )
                }
                "partial" -> {
                    val psbtB64 = result["psbt"]?.toString()
                    _state.value = AppState.Result(
                        isComplete = false,
                        updatedPsbt = psbtB64?.let {
                            android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                        },
                        network = network,
                    )
                }
                "cancelled" -> {
                    val inboxId = currentSigningInboxId
                    if (inboxId != null) {
                        inboxDao.updateStatus(inboxId, InboxStatus.PENDING)
                    }
                    if (currentPsbtBytes != null) {
                        parsePsbt(currentPsbtBytes!!)
                    } else {
                        _state.value = AppState.Home
                    }
                }
                else -> {
                    val inboxId = currentSigningInboxId
                    if (inboxId != null) {
                        inboxDao.updateStatus(inboxId, InboxStatus.FAILED)
                    }
                    _state.value = AppState.Error(
                        result["message"]?.toString() ?: "Signing failed"
                    )
                }
            }
        } catch (e: Exception) {
            val inboxId = currentSigningInboxId
            if (inboxId != null) {
                inboxDao.updateStatus(inboxId, InboxStatus.FAILED)
            }
            val signingLog = (_state.value as? AppState.Signing)?.log ?: ""
            _state.value = AppState.Error("Signing error: ${e.message}\n\n--- Log ---\n$signingLog")
        } finally {
            _passphraseRequest.value = null
            _accountPathRequest.value = null
            _nfcWaitingForTag.value = false
            _nfcTagResult.value = null
            currentSigningCallback = null
            bridge.close()
            currentUsbBridge = null
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
                    inboxDao.updateBroadcast(inboxId, InboxStatus.BROADCAST, txid, targetNetwork)
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
            viewModelScope.launch { inboxDao.updateStatus(inboxId, InboxStatus.PENDING) }
        }
        currentSigningCallback?.cancel()
        currentSigningCallback = null
        _passphraseRequest.value = null
        _accountPathRequest.value = null
        _nfcWaitingForTag.value = false
        _nfcTagResult.value = null
        signingJob?.cancel()
        signingJob = null
        currentUsbBridge?.close()
        currentUsbBridge = null
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
        val normalized = FingerprintValidator.normalize(fingerprint) ?: return
        val trimmedLabel = label.trim()

        viewModelScope.launch(Dispatchers.IO) {
            if (existingContactId != null) {
                contactDao.insertFingerprint(
                    ContactFingerprint(contactId = existingContactId, fingerprint = normalized)
                )
            } else {
                if (trimmedLabel.isEmpty() || trimmedLabel.length > 50) return@launch
                val contactId = contactDao.insertContact(Contact(label = trimmedLabel))
                contactDao.insertFingerprint(
                    ContactFingerprint(contactId = contactId, fingerprint = normalized)
                )
            }
            reEnrichSigners()
        }
    }

    fun updateContact(contactId: Long, newLabel: String, npub: String?) {
        val trimmedLabel = newLabel.trim()
        if (trimmedLabel.isEmpty() || trimmedLabel.length > 50) return

        viewModelScope.launch(Dispatchers.IO) {
            contactDao.updateContact(Contact(id = contactId, label = trimmedLabel, npub = npub?.trim()?.ifEmpty { null }))
        }
    }

    fun addFingerprintToContact(contactId: Long, fingerprint: String) {
        val normalized = FingerprintValidator.normalize(fingerprint) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            contactDao.insertFingerprint(
                ContactFingerprint(contactId = contactId, fingerprint = normalized)
            )
            reEnrichSigners()
        }
    }

    fun deleteContact(contactId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            contactDao.deleteContact(contactId)
            reEnrichSigners()
        }
    }

    fun deleteFingerprint(fingerprintId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            contactDao.deleteFingerprint(fingerprintId)
            reEnrichSigners()
        }
    }

    private suspend fun reEnrichSigners() {
        val currentState = _state.value
        if (currentState is AppState.TransactionReview) {
            val fingerprints = currentState.signers.map { it.fingerprint }
            val contactMap = contactDao.findByFingerprints(fingerprints)
                .flatMap { cwf -> cwf.fingerprints.map { fp -> fp.fingerprint to cwf } }
                .toMap()
            val enriched = currentState.signers.map { signer ->
                val contact = contactMap[signer.fingerprint]
                signer.copy(
                    contactLabel = contact?.contact?.label,
                    contactId = contact?.contact?.id,
                )
            }
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
                            inboxDao.updateStatus(inboxId, InboxStatus.SIGNED)
                        }
                    }
                    is AppState.Error -> inboxDao.updateStatus(inboxId, InboxStatus.FAILED)
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

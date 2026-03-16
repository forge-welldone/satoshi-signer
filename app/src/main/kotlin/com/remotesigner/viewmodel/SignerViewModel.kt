package com.remotesigner.viewmodel

import android.app.Application
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remotesigner.bridge.PythonBridge
import com.remotesigner.bridge.SigningCallbackImpl
import com.remotesigner.nostr.InboxItem
import com.remotesigner.nostr.InboxStatus
import com.remotesigner.nostr.NostrKeyManager
import com.remotesigner.nostr.NostrReceiver
import com.remotesigner.nostr.formatBtcAmount
import com.remotesigner.usb.SigningBridge
import com.remotesigner.usb.TrezorUsbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
)

data class SignerInfo(
    val fingerprint: String,
    val signed: Boolean,
    val isThisDevice: Boolean = false,
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
    ) : AppState()
    data class Signing(val message: String, val log: String = "") : AppState()
    data class Result(
        val isComplete: Boolean,
        val txid: String? = null,
        val rawHex: String? = null,
        val updatedPsbt: ByteArray? = null,
        val broadcastStatus: String? = null,
        val errorMessage: String? = null,
    ) : AppState()
    data class Error(val message: String) : AppState()
}

data class PassphraseRequest(
    val availableOnDevice: Boolean,
    val callback: SigningCallbackImpl,
)

class SignerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<AppState>(AppState.Home)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val pythonBridge = PythonBridge()
    val trezorUsb = TrezorUsbManager(application)

    private var currentPsbtBytes: ByteArray? = null
    private var currentNetwork: String = "main"
    private var currentUsbBridge: SigningBridge? = null
    private var signingJob: Job? = null
    private val _passphraseRequest = MutableStateFlow<PassphraseRequest?>(null)
    val passphraseRequest: StateFlow<PassphraseRequest?> = _passphraseRequest.asStateFlow()
    private var currentSigningCallback: SigningCallbackImpl? = null

    // --- Nostr inbox ---
    val keyManager = NostrKeyManager(application)
    private val _inboxItems = MutableStateFlow<List<InboxItem>>(emptyList())
    val inboxItems: StateFlow<List<InboxItem>> = _inboxItems.asStateFlow()
    private var currentSigningInboxId: String? = null

    private val nostrReceiver = NostrReceiver(
        keyManager = keyManager,
        onItem = { item -> handleInboxEvent(item) },
        scope = viewModelScope,
    )

    val relayConnectedCount: StateFlow<Int> = nostrReceiver.connectedCount

    private fun handleInboxEvent(item: InboxItem) {
        viewModelScope.launch {
            // Parse PSBT to extract display amount
            val enrichedItem = try {
                val result = withContext(Dispatchers.IO) {
                    pythonBridge.parsePsbt(item.psbtBytes)
                }
                @Suppress("UNCHECKED_CAST")
                val outputs = result["outputs"] as? List<Map<String, Any?>> ?: emptyList()
                val totalSent = outputs
                    .filter { it["is_change"] as? Boolean != true }
                    .sumOf { (it["amount"] as? Number)?.toLong() ?: 0L }
                item.copy(amount = formatBtcAmount(totalSent))
            } catch (_: Exception) {
                item // Keep without amount if parsing fails
            }
            _inboxItems.update { current -> current + enrichedItem }
        }
    }

    fun signInboxItem(item: InboxItem) {
        currentSigningInboxId = item.id
        updateInboxItemStatus(item.id, InboxStatus.SIGNING)
        loadPsbt(item.psbtBytes)
    }

    fun deleteInboxItem(id: String) {
        _inboxItems.update { current -> current.filter { it.id != id } }
    }

    private fun updateInboxItemStatus(id: String, status: InboxStatus) {
        _inboxItems.update { current ->
            current.map { if (it.id == id) it.copy(status = status) else it }
        }
    }

    fun startNostrReceiver() = nostrReceiver.connect()
    fun stopNostrReceiver() = nostrReceiver.disconnect()

    fun loadPsbt(uri: Uri) {
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
                )
            } ?: emptyList()

            val fee = (result["fee"] as? Number)?.toLong() ?: 0
            val totalSent = outputs.filter { !it.isChange }.sumOf { it.amount }

            val signers = (result["signers"] as? List<Map<String, Any?>>)?.map { s ->
                SignerInfo(
                    fingerprint = s["fingerprint"]?.toString() ?: "",
                    signed = s["signed"] as? Boolean ?: false,
                )
            } ?: emptyList()

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
            currentSigningCallback = null

            when (result["status"]) {
                "complete" -> {
                    _state.value = AppState.Result(
                        isComplete = true,
                        rawHex = result["raw_tx"]?.toString(),
                    )
                }
                "partial" -> {
                    val psbtB64 = result["psbt"]?.toString()
                    _state.value = AppState.Result(
                        isComplete = false,
                        updatedPsbt = psbtB64?.let {
                            android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                        },
                    )
                }
                else -> {
                    _state.value = AppState.Error(
                        result["message"]?.toString() ?: "Signing failed"
                    )
                }
            }
        } catch (e: Exception) {
            val signingLog = (_state.value as? AppState.Signing)?.log ?: ""
            _state.value = AppState.Error("Signing error: ${e.message}\n\n--- Log ---\n$signingLog")
        } finally {
            _passphraseRequest.value = null
            currentSigningCallback = null
            bridge.close()
            currentUsbBridge = null
        }
    }

    fun broadcast() {
        val state = _state.value
        if (state !is AppState.Result || state.rawHex == null) return

        _state.value = state.copy(broadcastStatus = "Broadcasting...")

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                pythonBridge.broadcast(state.rawHex)
            }

            if (result["status"] == "ok") {
                _state.value = state.copy(
                    txid = result["txid"]?.toString(),
                    broadcastStatus = "Broadcast successful",
                )
            } else {
                _state.value = state.copy(
                    broadcastStatus = "Broadcast failed: ${result["message"]}",
                )
            }
        }
    }

    fun cancelSigning() {
        currentSigningCallback?.cancel()
        currentSigningCallback = null
        _passphraseRequest.value = null
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

    fun goHome() {
        // Update inbox item status based on signing result
        val inboxId = currentSigningInboxId
        if (inboxId != null) {
            val currentState = _state.value
            when (currentState) {
                is AppState.Result -> updateInboxItemStatus(inboxId, InboxStatus.SIGNED)
                is AppState.Error -> updateInboxItemStatus(inboxId, InboxStatus.FAILED)
                else -> {}
            }
            currentSigningInboxId = null
        }
        currentPsbtBytes = null
        _state.value = AppState.Home
    }
}

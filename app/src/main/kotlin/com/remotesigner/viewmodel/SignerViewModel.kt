package com.remotesigner.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remotesigner.bridge.PythonBridge
import com.remotesigner.usb.TrezorUsbManager
import com.remotesigner.usb.UsbBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        val outputs: List<TxOutput>,
        val fee: Long,
        val totalSent: Long,
        val status: String,
        val signers: List<SignerInfo>,
        val warnings: List<String>,
    ) : AppState()
    data class Signing(val message: String) : AppState()
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

class SignerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<AppState>(AppState.Home)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val pythonBridge = PythonBridge()
    val trezorUsb = TrezorUsbManager(application)

    private var currentPsbtBytes: ByteArray? = null
    private var currentUsbBridge: UsbBridge? = null

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
                outputs = outputs,
                fee = fee,
                totalSent = totalSent,
                status = result["status"]?.toString() ?: "unknown",
                signers = signers,
                warnings = warnings,
            )
        } catch (e: Exception) {
            _state.value = AppState.Error("Invalid PSBT: ${e.message}")
        }
    }

    fun signWithTrezor() {
        val psbt = currentPsbtBytes ?: return
        val device = trezorUsb.findTrezorDevice()

        if (device == null) {
            _state.value = AppState.Signing("Connect Trezor via USB-C cable")
            return
        }

        if (!trezorUsb.hasPermission(device)) {
            trezorUsb.requestPermission(device) { granted ->
                if (granted) signWithTrezor()
                else _state.value = AppState.Error("USB permission denied")
            }
            return
        }

        _state.value = AppState.Signing("Connecting to Trezor...")

        viewModelScope.launch {
            try {
                val bridge = withContext(Dispatchers.IO) {
                    trezorUsb.openDevice(device)
                        ?: throw IllegalStateException("Failed to open USB device")
                }
                currentUsbBridge = bridge

                val result = withContext(Dispatchers.IO) {
                    pythonBridge.signPsbt(
                        psbtBytes = psbt,
                        bridge = bridge,
                        statusCallback = { status ->
                            viewModelScope.launch {
                                _state.value = AppState.Signing(
                                    when (status) {
                                        "confirm_on_device" -> "Confirm on your Trezor..."
                                        "signing" -> "Signing transaction..."
                                        "pin_requested" -> "Enter PIN on your Trezor..."
                                        "passphrase_on_device" -> "Enter passphrase on your Trezor..."
                                        else -> status
                                    }
                                )
                            }
                        },
                    )
                }

                when (result["status"]) {
                    "complete" -> {
                        _state.value = AppState.Result(
                            isComplete = true,
                            rawHex = result["raw_tx"]?.toString(),
                        )
                    }
                    "partial" -> {
                        _state.value = AppState.Result(
                            isComplete = false,
                            updatedPsbt = result["psbt"] as? ByteArray,
                        )
                    }
                    else -> {
                        _state.value = AppState.Error(
                            result["message"]?.toString() ?: "Signing failed"
                        )
                    }
                }
            } catch (e: Exception) {
                _state.value = AppState.Error("Signing error: ${e.message}")
            } finally {
                currentUsbBridge?.close()
                currentUsbBridge = null
            }
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

    fun goHome() {
        currentPsbtBytes = null
        _state.value = AppState.Home
    }
}

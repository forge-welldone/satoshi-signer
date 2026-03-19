package com.remotesigner.bridge

import com.remotesigner.usb.SigningBridge
import com.remotesigner.usb.TrezorUsbManager
import com.remotesigner.viewmodel.AccountPathRequest
import com.remotesigner.viewmodel.PassphraseRequest
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

sealed class SigningResult {
    data class Complete(val rawHex: String?, val network: String) : SigningResult()
    data class Partial(val updatedPsbtBytes: ByteArray?, val network: String) : SigningResult()
    data object Cancelled : SigningResult()
    data class Error(val message: String, val log: String) : SigningResult()
}

class SigningOrchestrator(
    private val pythonBridge: PythonBridgeInterface,
    private val trezorUsb: TrezorUsbManager,
) {
    private var currentUsbBridge: SigningBridge? = null
    private var currentSigningCallback: SigningCallbackImpl? = null

    private val _passphraseRequest = MutableStateFlow<PassphraseRequest?>(null)
    val passphraseRequest: StateFlow<PassphraseRequest?> = _passphraseRequest.asStateFlow()
    private val _accountPathRequest = MutableStateFlow<AccountPathRequest?>(null)
    val accountPathRequest: StateFlow<AccountPathRequest?> = _accountPathRequest.asStateFlow()

    suspend fun signWithTrezor(
        psbtBytes: ByteArray,
        network: String,
        onProgress: (String) -> Unit,
    ): SigningResult {
        val device = trezorUsb.findTrezorDevice()

        if (device == null) {
            onProgress("Connect Trezor via USB-C cable")
            while (true) {
                delay(1000)
                val found = trezorUsb.findTrezorDevice() ?: continue

                if (!trezorUsb.hasPermission(found)) {
                    val granted = suspendCancellableCoroutine { cont: CancellableContinuation<Boolean> ->
                        trezorUsb.requestPermission(found) { cont.resume(it) }
                    }
                    if (!granted) return SigningResult.Error("USB permission denied", "")
                }

                val bridge = trezorUsb.openDevice(found)
                    ?: return SigningResult.Error("Failed to open USB device", "")
                onProgress(bridge.dumpDeviceInfo())
                return doSign(bridge, psbtBytes, network, onProgress)
            }
        }

        if (!trezorUsb.hasPermission(device)) {
            val granted = suspendCancellableCoroutine { cont: CancellableContinuation<Boolean> ->
                trezorUsb.requestPermission(device) { cont.resume(it) }
            }
            if (!granted) return SigningResult.Error("USB permission denied", "")
        }

        val bridge = trezorUsb.openDevice(device)
            ?: return SigningResult.Error("Failed to open USB device", "")
        onProgress(bridge.dumpDeviceInfo())
        return doSign(bridge, psbtBytes, network, onProgress)
    }

    suspend fun signWithBridge(
        bridge: SigningBridge,
        psbtBytes: ByteArray,
        network: String,
        onProgress: (String) -> Unit,
        withPassphraseUI: Boolean = true,
    ): SigningResult {
        return doSign(bridge, psbtBytes, network, onProgress, withPassphraseUI, manageBridge = false)
    }

    private suspend fun doSign(
        bridge: SigningBridge,
        psbtBytes: ByteArray,
        network: String,
        onProgress: (String) -> Unit,
        withPassphraseUI: Boolean = true,
        manageBridge: Boolean = true,
    ): SigningResult {
        var log = ""
        fun log(msg: String) {
            log += msg + "\n"
            onProgress(msg)
        }

        try {
            if (manageBridge) {
                currentUsbBridge = bridge
                log("Opening USB connection...")
                log("Claiming interface & finding endpoints...")
                withContext(Dispatchers.IO) { bridge.open() }
                log("USB bridge opened OK")
            }

            log("Starting Python signing (network=$network)...")

            val signingCallback: SigningCallbackImpl? = if (withPassphraseUI) {
                SigningCallbackImpl(
                    onStatusUpdate = { status -> log("Python: $status") },
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

            return when (result["status"]) {
                "complete" -> SigningResult.Complete(
                    rawHex = result["raw_tx"]?.toString(),
                    network = network,
                )
                "partial" -> {
                    val psbtB64 = result["psbt"]?.toString()
                    SigningResult.Partial(
                        updatedPsbtBytes = psbtB64?.let {
                            android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                        },
                        network = network,
                    )
                }
                "cancelled" -> SigningResult.Cancelled
                else -> SigningResult.Error(
                    message = result["message"]?.toString() ?: "Signing failed",
                    log = log,
                )
            }
        } catch (e: Exception) {
            return SigningResult.Error(
                message = "Signing error: ${e.message}",
                log = log,
            )
        } finally {
            _passphraseRequest.value = null
            _accountPathRequest.value = null
            currentSigningCallback = null
            bridge.close()
            currentUsbBridge = null
        }
    }

    fun cancel() {
        currentSigningCallback?.cancel()
        currentSigningCallback = null
        _passphraseRequest.value = null
        _accountPathRequest.value = null
        currentUsbBridge?.close()
        currentUsbBridge = null
    }
}

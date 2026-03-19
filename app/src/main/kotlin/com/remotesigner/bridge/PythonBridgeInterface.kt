package com.remotesigner.bridge

import com.remotesigner.usb.SigningBridge

/** Callback interface for signing operations. Called from Python's thread via Chaquopy. */
interface SigningCallback {
    fun onStatus(status: String)
    /** Blocks the calling (Python) thread until the user responds. */
    fun requestPassphrase(availableOnDevice: Boolean): String
    /** Blocks until the user provides an account derivation path. */
    fun requestAccountPath(): String
}

interface PythonBridgeInterface {
    fun parsePsbt(psbtBytes: ByteArray): Map<String, Any?>
    fun signPsbt(
        psbtBytes: ByteArray,
        bridge: SigningBridge,
        callback: SigningCallback?,
        network: String = "main",
    ): Map<String, Any?>
    fun broadcast(rawHex: String, network: String = "main"): Map<String, Any?>
}

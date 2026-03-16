package com.remotesigner.bridge

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.remotesigner.usb.SigningBridge
import java.util.concurrent.LinkedBlockingQueue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridge between Kotlin and the Python remotesigner package.
 * All calls are blocking and must be run on a background thread.
 */
class PythonBridge {

    private val py = Python.getInstance()
    private val parserModule: PyObject = py.getModule("remotesigner.psbt_parser")
    private val signerModule: PyObject = py.getModule("remotesigner.signer")
    private val broadcasterModule: PyObject = py.getModule("remotesigner.broadcaster")
    private val jsonModule: PyObject = py.getModule("json")

    fun parsePsbt(psbtBytes: ByteArray): Map<String, Any?> {
        val result = parserModule.callAttr("parse_psbt", psbtBytes)
        return pyDictToMap(result)
    }

    fun signPsbt(
        psbtBytes: ByteArray,
        bridge: SigningBridge,
        callback: SigningCallback?,
        network: String = "main",
    ): Map<String, Any?> {
        val result = signerModule.callAttr(
            "sign_psbt", psbtBytes, bridge, callback, network
        )
        return pyDictToMap(result)
    }

    fun broadcast(rawHex: String, network: String = "main"): Map<String, Any?> {
        val result = broadcasterModule.callAttr("broadcast_transaction", rawHex, network)
        return pyDictToMap(result)
    }

    /**
     * Callback interface for signing operations.
     * Methods are called from Python's thread via Chaquopy.
     */
    interface SigningCallback {
        fun onStatus(status: String)
        /** Blocks the calling (Python) thread until the user responds. */
        fun requestPassphrase(availableOnDevice: Boolean): String
        /** Blocks until the user provides an account derivation path (e.g., "m/84'/0'/0'"). */
        fun requestAccountPath(): String
    }

    /**
     * Convert a Python dict to a Kotlin Map by round-tripping through JSON.
     * Chaquopy's toJava() doesn't recursively convert nested containers,
     * so JSON serialization is the reliable approach.
     */
    private fun pyDictToMap(obj: PyObject): Map<String, Any?> {
        val jsonStr = jsonModule.callAttr("dumps", obj).toString()
        return jsonToMap(JSONObject(jsonStr))
    }

    private fun jsonToMap(json: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (key in json.keys()) {
            map[key] = jsonToAny(json.get(key))
        }
        return map
    }

    private fun jsonToAny(value: Any?): Any? {
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> jsonToMap(value)
            is JSONArray -> (0 until value.length()).map { jsonToAny(value.get(it)) }
            else -> value // String, Number, Boolean
        }
    }
}

/**
 * Bridges Python signing callbacks to Kotlin UI.
 *
 * [requestPassphrase] blocks the Python thread on a [LinkedBlockingQueue]
 * until the UI calls [submitPassphrase] or [cancel].
 */
class SigningCallbackImpl(
    private val onStatusUpdate: (String) -> Unit,
    private val onPassphraseRequest: (availableOnDevice: Boolean) -> Unit,
    private val onPassphraseSubmitted: () -> Unit = {},
    private val onAccountPathRequest: () -> Unit = {},
    private val onAccountPathSubmitted: () -> Unit = {},
) : PythonBridge.SigningCallback {

    companion object {
        /** Sentinel value for cancellation (LinkedBlockingQueue doesn't accept null). */
        internal const val CANCEL_SENTINEL = "\u0000__CANCEL__"
    }

    private val passphraseQueue = LinkedBlockingQueue<String>(1)
    private val accountPathQueue = LinkedBlockingQueue<String>(1)

    override fun onStatus(status: String) = onStatusUpdate(status)

    /**
     * Called from Python's thread. Triggers the UI prompt, then blocks
     * until [submitPassphrase] or [cancel] is called.
     */
    override fun requestPassphrase(availableOnDevice: Boolean): String {
        onPassphraseRequest(availableOnDevice)
        val response = passphraseQueue.take()  // blocks Python thread
        if (response == CANCEL_SENTINEL) {
            throw RuntimeException("Passphrase entry cancelled")
        }
        return response
    }

    /**
     * Called from Python when the PSBT has relative derivation paths and
     * auto-detection failed.  Blocks until [submitAccountPath] or [cancel].
     */
    override fun requestAccountPath(): String {
        onAccountPathRequest()
        val response = accountPathQueue.take()
        if (response == CANCEL_SENTINEL) {
            throw RuntimeException("Account path entry cancelled")
        }
        return response
    }

    /** Called by the UI when the user submits a passphrase or chooses on-device. */
    fun submitPassphrase(passphrase: String) {
        passphraseQueue.clear()  // prevent double-submission
        passphraseQueue.put(passphrase)
        onPassphraseSubmitted()
    }

    /** Called by the UI when the user submits an account path. */
    fun submitAccountPath(path: String) {
        accountPathQueue.clear()
        accountPathQueue.put(path)
        onAccountPathSubmitted()
    }

    /** Called by cancelSigning() to unblock the Python thread. */
    fun cancel() {
        passphraseQueue.clear()
        passphraseQueue.put(CANCEL_SENTINEL)
        accountPathQueue.clear()
        accountPathQueue.put(CANCEL_SENTINEL)
    }
}

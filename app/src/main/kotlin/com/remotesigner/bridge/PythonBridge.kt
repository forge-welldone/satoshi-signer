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
class PythonBridge : PythonBridgeInterface {

    private val py = Python.getInstance()
    private val parserModule: PyObject = py.getModule("remotesigner.psbt_parser")
    private val signerModule: PyObject = py.getModule("remotesigner.signer")
    private val broadcasterModule: PyObject = py.getModule("remotesigner.broadcaster")
    private val jsonModule: PyObject = py.getModule("json")

    override fun parsePsbt(psbtBytes: ByteArray): ParsedPsbtResult {
        val result = parserModule.callAttr("parse_psbt", psbtBytes)
        return toParseResult(pyDictToMap(result))
    }

    override fun signPsbt(
        psbtBytes: ByteArray,
        bridge: SigningBridge,
        callback: SigningCallback?,
        network: String,
    ): Map<String, Any?> {
        val result = signerModule.callAttr(
            "sign_psbt", psbtBytes, bridge, callback, network
        )
        return pyDictToMap(result)
    }

    override fun broadcast(rawHex: String, network: String): BroadcastResult {
        val result = broadcasterModule.callAttr("broadcast_transaction", rawHex, network)
        return toBroadcastResult(pyDictToMap(result))
    }

    @Suppress("UNCHECKED_CAST")
    private fun toParseResult(map: Map<String, Any?>): ParsedPsbtResult {
        val inputs = (map["inputs"] as? List<Map<String, Any?>> ?: emptyList()).map { inp ->
            TxInput(
                address = inp["address"]?.toString() ?: "unknown",
                amount = (inp["amount"] as? Number)?.toLong() ?: 0,
            )
        }
        val outputs = (map["outputs"] as? List<Map<String, Any?>> ?: emptyList()).map { out ->
            TxOutput(
                address = out["address"]?.toString() ?: "unknown",
                amount = (out["amount"] as? Number)?.toLong() ?: 0,
                isChange = out["is_change"] as? Boolean ?: false,
                opReturn = out["op_return"]?.toString(),
            )
        }
        val signers = (map["signers"] as? List<Map<String, Any?>> ?: emptyList()).map { s ->
            SignerInfo(
                fingerprint = s["fingerprint"]?.toString() ?: "",
                signed = s["signed"] as? Boolean ?: false,
            )
        }
        return ParsedPsbtResult(
            inputs = inputs,
            outputs = outputs,
            fee = (map["fee"] as? Number)?.toLong() ?: 0,
            status = map["status"]?.toString() ?: "unknown",
            signers = signers,
            network = map["network"]?.toString() ?: "main",
            requiredSigs = (map["required_sigs"] as? Number)?.toInt() ?: 0,
            totalSigs = (map["total_sigs"] as? Number)?.toInt() ?: 0,
        )
    }

    private fun toBroadcastResult(map: Map<String, Any?>): BroadcastResult {
        return BroadcastResult(
            status = map["status"]?.toString() ?: "error",
            txid = map["txid"]?.toString(),
            message = map["message"]?.toString(),
            rawHex = map["raw_hex"]?.toString(),
        )
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
) : SigningCallback {

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

package com.remotesigner.bridge

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.remotesigner.usb.UsbBridge
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
        bridge: UsbBridge,
        callback: SigningCallback,
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

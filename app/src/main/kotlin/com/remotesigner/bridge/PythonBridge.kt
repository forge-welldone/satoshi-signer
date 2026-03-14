package com.remotesigner.bridge

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.remotesigner.usb.UsbBridge

/**
 * Bridge between Kotlin and the Python remotesigner package.
 * All calls are blocking and must be run on a background thread.
 */
class PythonBridge {

    private val py = Python.getInstance()
    private val parserModule: PyObject = py.getModule("remotesigner.psbt_parser")
    private val signerModule: PyObject = py.getModule("remotesigner.signer")
    private val broadcasterModule: PyObject = py.getModule("remotesigner.broadcaster")

    fun parsePsbt(psbtBytes: ByteArray): Map<String, Any?> {
        val result = parserModule.callAttr("parse_psbt", psbtBytes)
        return pyObjectToMap(result)
    }

    fun signPsbt(
        psbtBytes: ByteArray,
        bridge: UsbBridge,
        statusCallback: (String) -> Unit,
        network: String = "main",
    ): Map<String, Any?> {
        val callback = object : StatusCallback {
            override fun onStatus(status: String) {
                statusCallback(status)
            }
        }
        val result = signerModule.callAttr(
            "sign_psbt", psbtBytes, bridge, callback, network
        )
        return pyObjectToMap(result)
    }

    fun broadcast(rawHex: String, network: String = "main"): Map<String, Any?> {
        val result = broadcasterModule.callAttr("broadcast_transaction", rawHex, network)
        return pyObjectToMap(result)
    }

    interface StatusCallback {
        fun onStatus(status: String)
    }

    private fun pyObjectToMap(obj: PyObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val pyMap = obj.asMap()
        for ((key, value) in pyMap) {
            map[key.toString()] = when {
                value == null -> null
                else -> try { value.toJava(Any::class.java) } catch (e: Exception) { value.toString() }
            }
        }
        return map
    }
}

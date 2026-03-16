package com.remotesigner

import android.content.Context
import com.remotesigner.usb.SigningBridge
import org.json.JSONObject

/**
 * Replays recorded Trezor USB exchanges from a cassette JSON file.
 * Implements [SigningBridge] so it can substitute for [UsbBridge] in tests.
 *
 * Strict validation: writeChunk asserts data matches the recording exactly.
 * readChunk returns the next recorded read. Any mismatch throws immediately.
 */
class PlaybackBridge(cassetteJson: JSONObject) : SigningBridge {

    val inputPsbtB64: String
    val network: String

    private data class Exchange(val dir: String, val data: String)

    private val exchanges: List<Exchange>
    private var pos = 0

    init {
        val metadata = cassetteJson.getJSONObject("metadata")
        inputPsbtB64 = metadata.getString("input_psbt_b64")
        network = metadata.optString("network", "main")

        val arr = cassetteJson.getJSONArray("exchanges")
        exchanges = (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            Exchange(dir = obj.getString("dir"), data = obj.getString("data"))
        }
    }

    override fun open() {}
    override fun close() {}

    override fun writeChunk(data: ByteArray) {

        check(pos < exchanges.size) { "Cassette exhausted at position $pos (total ${exchanges.size})" }
        val expected = exchanges[pos]
        check(expected.dir == "w") { "Expected write at pos $pos, got read" }
        val actualHex = data.joinToString("") { "%02x".format(it) }
        check(actualHex == expected.data) {
            "Write mismatch at pos $pos:\n  expected: ${expected.data.take(32)}...\n  actual:   ${actualHex.take(32)}..."
        }
        pos++

    }

    override fun readChunk(): ByteArray {

        check(pos < exchanges.size) { "Cassette exhausted at position $pos (total ${exchanges.size})" }
        val expected = exchanges[pos]
        check(expected.dir == "r") { "Expected read at pos $pos, got write" }
        pos++
        val result = expected.data.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        return result
    }

    fun assertConsumed() {
        check(pos == exchanges.size) {
            "Cassette not fully consumed: $pos / ${exchanges.size}"
        }
    }

    companion object {
        fun fromAsset(context: Context, name: String): PlaybackBridge {
            val json = context.assets.open("cassettes/$name").bufferedReader().use { it.readText() }
            return PlaybackBridge(JSONObject(json))
        }
    }
}

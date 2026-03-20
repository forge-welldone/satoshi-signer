package com.remotesigner.broadcast

import androidx.annotation.VisibleForTesting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class BroadcastResult(
    val status: String,
    val txid: String? = null,
    val message: String? = null,
    val rawHex: String? = null,
)

class TransactionBroadcaster @VisibleForTesting internal constructor(
    private val client: OkHttpClient,
    private val endpoints: Map<String, List<String>>,
) {
    constructor(client: OkHttpClient) : this(client, PRODUCTION_ENDPOINTS)

    companion object {
        private val PRODUCTION_ENDPOINTS = mapOf(
            "main" to listOf(
                "https://mempool.space/api/tx",
                "https://blockstream.info/api/tx",
            ),
            "test" to listOf(
                "https://mempool.space/testnet/api/tx",
                "https://blockstream.info/testnet/api/tx",
            ),
            "testnet3" to listOf(
                "https://mempool.space/testnet/api/tx",
                "https://blockstream.info/testnet/api/tx",
            ),
            "testnet4" to listOf(
                "https://mempool.space/testnet4/api/tx",
            ),
            "signet" to listOf(
                "https://mempool.space/signet/api/tx",
            ),
        )
        private const val MAX_TX_BYTES = 400_000
        private const val MAX_ATTEMPTS = 2
        private val TEXT_PLAIN = "text/plain".toMediaType()
    }

    fun broadcast(rawHex: String, network: String): BroadcastResult {
        validate(rawHex, network)

        val urls = endpoints[network]!!
        var lastError = ""

        for (url in urls) {
            repeat(MAX_ATTEMPTS) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .post(rawHex.toRequestBody(TEXT_PLAIN))
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.code == 200) {
                            val txid = response.body?.string()?.trim() ?: ""
                            return BroadcastResult(status = "ok", txid = txid)
                        }
                        val body = response.body?.string()?.trim() ?: ""
                        lastError = "$url: HTTP ${response.code} - $body"
                    }
                } catch (e: IOException) {
                    lastError = "$url: ${e.message}"
                }
            }
        }

        return BroadcastResult(status = "error", message = lastError, rawHex = rawHex)
    }

    private fun validate(rawHex: String, network: String) {
        require(network in endpoints) {
            "Unknown network: '$network'. Valid: ${endpoints.keys.sorted().joinToString(", ")}"
        }
        require(rawHex.isNotEmpty()) { "raw_hex must be a non-empty string" }
        require(rawHex.length % 2 == 0) { "raw_hex has odd length — not valid hex" }
        require(rawHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "raw_hex contains non-hex characters"
        }
        require(rawHex.length / 2 <= MAX_TX_BYTES) {
            "Transaction too large: ${rawHex.length / 2} bytes (max $MAX_TX_BYTES)"
        }
    }
}

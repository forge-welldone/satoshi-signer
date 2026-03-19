package com.remotesigner.ui

private const val BASE = "https://mempool.space"

private fun networkPrefix(network: String): String = when (network) {
    "main" -> ""
    "test", "testnet3" -> "/testnet"
    "testnet4" -> "/testnet4"
    "signet" -> "/signet"
    else -> throw IllegalArgumentException("Unknown network: $network")
}

fun mempoolAddressUrl(address: String, network: String): String {
    return "$BASE${networkPrefix(network)}/address/$address"
}

fun mempoolTxUrl(txid: String, network: String): String {
    return "$BASE${networkPrefix(network)}/tx/$txid"
}

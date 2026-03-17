package com.remotesigner.ui

private const val BASE = "https://mempool.space"

fun mempoolAddressUrl(address: String, network: String): String {
    val prefix = if (network == "test") "/testnet" else ""
    return "$BASE$prefix/address/$address"
}

fun mempoolTxUrl(txid: String, network: String): String {
    val prefix = if (network == "test") "/testnet" else ""
    return "$BASE$prefix/tx/$txid"
}

package com.remotesigner.nfc

sealed class NfcReadResult {
    data class Success(val passphrase: String) : NfcReadResult()
    data class Error(val message: String) : NfcReadResult()
}

fun parseNdefTextPayload(payload: ByteArray): String? {
    if (payload.isEmpty()) return null

    val statusByte = payload[0].toInt() and 0xFF
    val isUtf16 = (statusByte and 0x80) != 0
    val langLen = statusByte and 0x3F

    if (payload.size < 1 + langLen) return null

    val textBytes = payload.copyOfRange(1 + langLen, payload.size)
    val charset = if (isUtf16) Charsets.UTF_16BE else Charsets.UTF_8
    return String(textBytes, charset)
}

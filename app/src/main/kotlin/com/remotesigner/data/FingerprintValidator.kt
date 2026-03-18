package com.remotesigner.data

object FingerprintValidator {
    private val HEX_8_REGEX = Regex("^[0-9a-f]{8}$")

    fun normalize(input: String): String? {
        val trimmed = input.trim().lowercase()
        return if (HEX_8_REGEX.matches(trimmed)) trimmed else null
    }
}

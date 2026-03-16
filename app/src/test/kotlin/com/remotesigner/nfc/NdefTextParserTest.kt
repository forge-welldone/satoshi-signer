package com.remotesigner.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NdefTextParserTest {

    @Test
    fun utf8_english_text() {
        val payload = byteArrayOf(0x02) + "en".toByteArray() + "MyPassphrase".toByteArray()
        assertEquals("MyPassphrase", parseNdefTextPayload(payload))
    }

    @Test
    fun utf8_empty_language_code() {
        val payload = byteArrayOf(0x00) + "secret123".toByteArray()
        assertEquals("secret123", parseNdefTextPayload(payload))
    }

    @Test
    fun utf8_long_language_code() {
        val payload = byteArrayOf(0x05) + "en-US".toByteArray() + "pass".toByteArray()
        assertEquals("pass", parseNdefTextPayload(payload))
    }

    @Test
    fun utf16_text() {
        val payload = byteArrayOf(0x82.toByte()) + "en".toByteArray() +
            byteArrayOf(0x00, 0x41, 0x00, 0x42)
        assertEquals("AB", parseNdefTextPayload(payload))
    }

    @Test
    fun empty_payload_returns_null() {
        assertNull(parseNdefTextPayload(byteArrayOf()))
    }

    @Test
    fun payload_too_short_for_language_code_returns_null() {
        assertNull(parseNdefTextPayload(byteArrayOf(0x05, 0x65, 0x6E)))
    }

    @Test
    fun text_portion_empty_returns_empty_string() {
        val payload = byteArrayOf(0x02) + "en".toByteArray()
        assertEquals("", parseNdefTextPayload(payload))
    }

    @Test
    fun special_characters_preserved() {
        val passphrase = "p@ss wörd!€"
        val payload = byteArrayOf(0x02) + "en".toByteArray() + passphrase.toByteArray(Charsets.UTF_8)
        assertEquals(passphrase, parseNdefTextPayload(payload))
    }
}

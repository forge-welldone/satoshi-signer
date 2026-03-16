package com.remotesigner

import com.remotesigner.nostr.Bech32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Bech32Test {

    // Jack Dorsey's well-known Nostr pubkey
    private val pubkeyHex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
    private val expectedNpub = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"

    @Test
    fun encode_knownPubkey_producesCorrectNpub() {
        val pubkey = pubkeyHex.hexToByteArray()
        val npub = Bech32.npubEncode(pubkey)
        assertEquals(expectedNpub, npub)
    }

    @Test
    fun decode_knownNpub_producesCorrectPubkey() {
        val (hrp, data) = Bech32.decode(expectedNpub)
        assertEquals("npub", hrp)
        assertArrayEquals(pubkeyHex.hexToByteArray(), data)
    }

    @Test
    fun roundTrip_randomBytes_isIdentity() {
        val bytes = ByteArray(32) { it.toByte() }
        val encoded = Bech32.npubEncode(bytes)
        val (_, decoded) = Bech32.decode(encoded)
        assertArrayEquals(bytes, decoded)
    }

    @Test
    fun nsecEncode_decodesBackToOriginal() {
        val privkey = ByteArray(32) { (it + 0x80).toByte() }
        val nsec = Bech32.nsecEncode(privkey)
        assert(nsec.startsWith("nsec1"))
        val (hrp, decoded) = Bech32.decode(nsec)
        assertEquals("nsec", hrp)
        assertArrayEquals(privkey, decoded)
    }

    private fun String.hexToByteArray(): ByteArray {
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

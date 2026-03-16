package com.remotesigner

import androidx.test.platform.app.InstrumentationRegistry
import com.remotesigner.nostr.NostrKeyManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class NostrKeyManagerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyManager = NostrKeyManager(context)

    @After
    fun cleanup() {
        context.getSharedPreferences("nostr_keys", 0).edit().clear().apply()
    }

    @Test
    fun getOrCreateKeyPair_generatesValidKeys() {
        val (privkey, pubkey) = keyManager.getOrCreateKeyPair()
        assertEquals(32, privkey.size)
        assertEquals(32, pubkey.size)
    }

    @Test
    fun getOrCreateKeyPair_returnsSameKeysOnSecondCall() {
        val (priv1, pub1) = keyManager.getOrCreateKeyPair()
        val (priv2, pub2) = keyManager.getOrCreateKeyPair()
        assertArrayEquals(priv1, priv2)
        assertArrayEquals(pub1, pub2)
    }

    @Test
    fun getNpub_startsWithNpub1() {
        val npub = keyManager.getNpub()
        assertTrue("npub should start with npub1, got: $npub", npub.startsWith("npub1"))
        assertEquals(63, npub.length) // bech32 npub is always 63 chars
    }

    @Test
    fun getHexPubkey_is64HexChars() {
        val hex = keyManager.getHexPubkey()
        assertEquals(64, hex.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun regenerateKeyPair_producesNewKeys() {
        val (priv1, _) = keyManager.getOrCreateKeyPair()
        keyManager.regenerateKeyPair()
        val (priv2, _) = keyManager.getOrCreateKeyPair()
        assertFalse("Regenerated key should differ", priv1.contentEquals(priv2))
    }
}

package com.remotesigner

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.remotesigner.nostr.NostrKeyManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NostrKeyManagerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var keyManager: NostrKeyManager

    @Before
    fun setUp() {
        context.deleteSharedPreferences("nostr_keys_encrypted")
        context.deleteSharedPreferences("nostr_keys")
        keyManager = NostrKeyManager(context)
    }

    @After
    fun cleanup() {
        context.deleteSharedPreferences("nostr_keys_encrypted")
        context.deleteSharedPreferences("nostr_keys")
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
        assertEquals(63, npub.length)
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

    @Test
    fun constructor_deletesOldPlaintextPrefs() {
        // Write a value to old plaintext prefs
        context.getSharedPreferences("nostr_keys", Context.MODE_PRIVATE)
            .edit().putString("nsec_hex", "deadbeef".repeat(8)).apply()

        // Constructing a new NostrKeyManager should delete the old file
        NostrKeyManager(context)

        val oldPrefs = context.getSharedPreferences("nostr_keys", Context.MODE_PRIVATE)
        assertNull("Old plaintext prefs should be cleared", oldPrefs.getString("nsec_hex", null))
    }

    @Test
    fun encryptedPrefs_notReadableViaPlainSharedPreferences() {
        // Generate a key
        keyManager.getOrCreateKeyPair()

        // Try to read via plain SharedPreferences — should not find nsec_hex
        val plain = context.getSharedPreferences("nostr_keys_encrypted", Context.MODE_PRIVATE)
        assertNull(
            "Key should not be readable via plain SharedPreferences",
            plain.getString("nsec_hex", null)
        )
    }
}

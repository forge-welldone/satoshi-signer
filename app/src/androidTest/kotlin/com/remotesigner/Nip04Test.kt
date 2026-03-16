package com.remotesigner

import android.util.Base64
import com.remotesigner.nostr.Nip04
import fr.acinq.secp256k1.Secp256k1
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Nip04Test {

    private val secp = Secp256k1.get()

    @Test
    fun decrypt_roundTrip_recoversPlaintext() {
        val alicePriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val alicePubXOnly = secp.pubkeyCreate(alicePriv).copyOfRange(1, 33)

        val bobPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val bobPubXOnly = secp.pubkeyCreate(bobPriv).copyOfRange(1, 33)

        val plaintext = """{"tx": "cHNidFF...", "label": "Test payment"}"""

        // Alice encrypts to Bob using NIP-04 shared secret (raw x-coordinate)
        val sharedSecret = Nip04.computeSharedSecret(alicePriv, bobPubXOnly)
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val nip04Content = Base64.encodeToString(ciphertext, Base64.NO_WRAP) +
            "?iv=" + Base64.encodeToString(iv, Base64.NO_WRAP)

        // Bob decrypts using Nip04.decrypt
        val decrypted = Nip04.decrypt(bobPriv, alicePubXOnly, nip04Content)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun decrypt_sharedSecretIsSymmetric() {
        // NIP-04 shared secret (raw x-coordinate via pubKeyTweakMul) must be
        // symmetric: computeSharedSecret(alice, bob_x) == computeSharedSecret(bob, alice_x)
        val alicePriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val alicePubXOnly = secp.pubkeyCreate(alicePriv).copyOfRange(1, 33)

        val bobPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val bobPubXOnly = secp.pubkeyCreate(bobPriv).copyOfRange(1, 33)

        val secret1 = Nip04.computeSharedSecret(alicePriv, bobPubXOnly)
        val secret2 = Nip04.computeSharedSecret(bobPriv, alicePubXOnly)
        assertEquals(secret1.toList(), secret2.toList())
    }

    @Test
    fun decrypt_invalidContent_throws() {
        val priv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pub = secp.pubkeyCreate(priv).copyOfRange(1, 33)

        try {
            Nip04.decrypt(priv, pub, "not-valid-nip04")
            throw AssertionError("Should have thrown")
        } catch (_: Exception) {
            // Expected
        }
    }
}

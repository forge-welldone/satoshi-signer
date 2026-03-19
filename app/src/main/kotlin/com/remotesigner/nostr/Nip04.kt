package com.remotesigner.nostr

import android.util.Base64
import fr.acinq.secp256k1.Secp256k1
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NIP-04 encrypted direct message decryption.
 *
 * Wire format: base64(ciphertext) + "?iv=" + base64(iv)
 * Encryption: AES-256-CBC with PKCS5 padding.
 * Shared secret: raw x-coordinate of the ECDH shared point.
 *
 * IMPORTANT: secp256k1-kmp's ecdh() returns SHA-256(compressed_shared_point),
 * NOT the raw x-coordinate. NIP-04 requires the raw x-coordinate. We use
 * pubKeyTweakMul (point multiplication) instead and extract x ourselves.
 */
object Nip04 {

    private val secp = Secp256k1.get()

    /**
     * Decrypt a NIP-04 encrypted message.
     *
     * @param ourPrivkey Our 32-byte secret key
     * @param senderXOnlyPubkey Sender's 32-byte x-only public key (from event.pubkey)
     * @param content NIP-04 encrypted content: "base64(ciphertext)?iv=base64(iv)"
     * @return Decrypted plaintext string
     */
    fun decrypt(ourPrivkey: ByteArray, senderXOnlyPubkey: ByteArray, content: String): String {
        val parts = content.split("?iv=")
        require(parts.size == 2) { "Invalid NIP-04 format: missing ?iv= separator" }

        val ciphertext = Base64.decode(parts[0], Base64.DEFAULT)
        val iv = Base64.decode(parts[1], Base64.DEFAULT)

        val sharedSecret = computeSharedSecret(ourPrivkey, senderXOnlyPubkey)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /**
     * Encrypt a plaintext string using NIP-04 (AES-256-CBC).
     *
     * @param privkey Our 32-byte secret key
     * @param recipientXOnlyPubkey Recipient's 32-byte x-only public key
     * @param plaintext The string to encrypt
     * @return NIP-04 format: "base64(ciphertext)?iv=base64(iv)"
     */
    fun encrypt(privkey: ByteArray, recipientXOnlyPubkey: ByteArray, plaintext: String): String {
        val sharedSecret = computeSharedSecret(privkey, recipientXOnlyPubkey)
        val iv = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        return Base64.encodeToString(ciphertext, Base64.NO_WRAP) +
            "?iv=" + Base64.encodeToString(iv, Base64.NO_WRAP)
    }

    fun computeSharedSecret(privkey: ByteArray, xOnlyPubkey: ByteArray): ByteArray {
        // NIP-04: shared secret is the raw x-coordinate of privkey * pubkey.
        // We use pubKeyTweakMul (point multiplication) instead of ecdh() because
        // secp256k1-kmp's ecdh() returns SHA-256(compressed_point), not the raw x.
        // Using 0x02 prefix is safe: even if the real pubkey has odd y, the resulting
        // shared point's x-coordinate is the same (negation only flips y).
        val compressedPubkey = byteArrayOf(0x02) + xOnlyPubkey
        val sharedPoint = secp.pubKeyTweakMul(compressedPubkey, privkey)
        // pubKeyTweakMul returns 65-byte uncompressed key: 04 || x (32) || y (32)
        return sharedPoint.copyOfRange(1, 33)
    }
}

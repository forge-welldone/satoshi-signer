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
 * Shared secret: x-coordinate of ECDH(our_privkey, sender_pubkey).
 *
 * secp256k1-kmp's ecdh() returns the raw 32-byte x-coordinate
 * of the shared point, which is exactly what NIP-04 expects.
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

    private fun computeSharedSecret(privkey: ByteArray, xOnlyPubkey: ByteArray): ByteArray {
        // NIP-04 convention: reconstruct compressed pubkey with 02 prefix (even parity).
        // This works because secp256k1 ECDH only uses the x-coordinate of the shared point,
        // so the y-parity of the input pubkey doesn't affect the result.
        val compressedPubkey = byteArrayOf(0x02) + xOnlyPubkey
        return secp.ecdh(privkey, compressedPubkey)
    }
}

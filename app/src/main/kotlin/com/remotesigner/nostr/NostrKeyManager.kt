package com.remotesigner.nostr

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom

/**
 * Manages the app's Nostr transport identity keypair.
 * The keypair is a random secp256k1 key stored in EncryptedSharedPreferences.
 * This is a transport identity only — it protects nothing of value,
 * but we encrypt it to prevent PSBT decryption on rooted devices.
 */
class NostrKeyManager(context: Context) {

    private val prefs: SharedPreferences
    private val secp = Secp256k1.get()

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "nostr_keys_encrypted",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        // Delete old plaintext prefs (only after encrypted prefs created successfully)
        context.deleteSharedPreferences("nostr_keys")
    }

    /**
     * Returns (privkey 32 bytes, x-only pubkey 32 bytes).
     * Generates and persists a new keypair on first call.
     */
    fun getOrCreateKeyPair(): Pair<ByteArray, ByteArray> {
        val stored = prefs.getString("nsec_hex", null)
        val privkey = if (stored != null) {
            stored.hexToByteArray()
        } else {
            ByteArray(32).also { SecureRandom().nextBytes(it) }.also { pk ->
                prefs.edit().putString("nsec_hex", pk.toHex()).apply()
            }
        }
        // pubkeyCreate returns 65-byte uncompressed key (04 || x || y)
        val uncompressed = secp.pubkeyCreate(privkey)
        val xOnly = uncompressed.copyOfRange(1, 33) // 32-byte x-coordinate
        return privkey to xOnly
    }

    fun getNpub(): String {
        val (_, pubkey) = getOrCreateKeyPair()
        return Bech32.npubEncode(pubkey)
    }

    fun getHexPubkey(): String {
        val (_, pubkey) = getOrCreateKeyPair()
        return pubkey.toHex()
    }

    fun regenerateKeyPair() {
        prefs.edit().remove("nsec_hex").apply()
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
internal fun String.hexToByteArray(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

package com.remotesigner.nostr

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
        prefs = try {
            createEncryptedPrefs(context)
        } catch (e: Exception) {
            // Keystore key invalidated (e.g., debug signing cert changed) —
            // delete corrupted file and recreate with fresh Keystore key
            Log.w(TAG, "EncryptedSharedPreferences corrupted, recreating", e)
            context.deleteSharedPreferences("nostr_keys_encrypted")
            createEncryptedPrefs(context)
        }
        context.deleteSharedPreferences("nostr_keys")
    }

    companion object {
        private const val TAG = "NostrKeyManager"
        private const val PREFS_NAME = "nostr_keys_encrypted"
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

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
internal fun String.hexToByteArray(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

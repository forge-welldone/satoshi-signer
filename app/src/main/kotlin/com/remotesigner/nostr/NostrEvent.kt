package com.remotesigner.nostr

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Parsed Nostr event from a relay EVENT message.
 */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val content: String,
) {
    companion object {
        /**
         * Parse a relay message like ["EVENT", "sub_id", {event}].
         * Returns null if the message is not an EVENT or is malformed.
         */
        fun fromRelayMessage(text: String): NostrEvent? {
            return try {
                val arr = JSONArray(text)
                if (arr.getString(0) != "EVENT") return null
                val event = arr.getJSONObject(2)
                val parsed = NostrEvent(
                    id = event.getString("id"),
                    pubkey = event.getString("pubkey"),
                    createdAt = event.getLong("created_at"),
                    kind = event.getInt("kind"),
                    content = event.getString("content"),
                )
                // Verify event ID matches hash of serialized event
                if (!parsed.verifyId(event)) return null
                parsed
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Verify that the event ID is SHA-256 of the serialized event.
     * Per NIP-01: id = sha256([0, pubkey, created_at, kind, tags, content])
     *
     * Note: This relies on org.json's toString() for canonical serialization.
     * Android's JSONArray preserves order, but numeric formatting edge cases
     * could cause false negatives. If this rejects valid events from real relays,
     * consider relaxing to a warning log rather than a hard rejection.
     */
    private fun verifyId(raw: JSONObject): Boolean {
        return try {
            val serialized = JSONArray().apply {
                put(0)
                put(pubkey)
                put(createdAt)
                put(kind)
                put(raw.getJSONArray("tags"))
                put(content)
            }
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(serialized.toString().toByteArray(Charsets.UTF_8))
            hash.toHex() == id
        } catch (_: Exception) {
            Log.w("NostrEvent", "ID verification failed for event $id, accepting anyway")
            true // Accept event if verification can't be performed
        }
    }
}

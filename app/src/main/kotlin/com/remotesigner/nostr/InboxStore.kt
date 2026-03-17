package com.remotesigner.nostr

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

class InboxStore(private val file: File) {

    fun save(items: List<InboxItem>) {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("psbt", Base64.getEncoder().encodeToString(item.psbtBytes))
            obj.put("label", item.label)
            obj.put("amount", item.amount)
            obj.put("senderNpub", item.senderNpub)
            obj.put("receivedAt", item.receivedAt)
            obj.put("status", item.status.name)
            item.rawHex?.let { obj.put("rawHex", it) }
            item.txid?.let { obj.put("txid", it) }
            obj.put("network", item.network)
            array.put(obj)
        }
        file.writeText(array.toString())
    }

    fun load(): List<InboxItem> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                InboxItem(
                    id = obj.getString("id"),
                    psbtBytes = Base64.getDecoder().decode(obj.getString("psbt")),
                    label = obj.getString("label"),
                    amount = obj.optString("amount", ""),
                    senderNpub = obj.getString("senderNpub"),
                    receivedAt = obj.getLong("receivedAt"),
                    status = try {
                        InboxStatus.valueOf(obj.getString("status"))
                    } catch (_: IllegalArgumentException) {
                        InboxStatus.PENDING
                    },
                    rawHex = obj.optString("rawHex", "").takeIf { it.isNotEmpty() },
                    txid = obj.optString("txid", "").takeIf { it.isNotEmpty() },
                    network = obj.optString("network", "main"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

private const val EXPIRY_SHORT_SECONDS = 86400L      // 24 hours
private const val EXPIRY_LONG_SECONDS = 86400L * 7    // 7 days

fun removeExpiredItems(
    items: List<InboxItem>,
    nowSeconds: Long = System.currentTimeMillis() / 1000,
): List<InboxItem> {
    return items.filter { item ->
        val age = nowSeconds - item.receivedAt
        val maxAge = when (item.status) {
            InboxStatus.PENDING, InboxStatus.SIGNING, InboxStatus.FAILED -> EXPIRY_SHORT_SECONDS
            InboxStatus.SIGNED, InboxStatus.BROADCAST -> EXPIRY_LONG_SECONDS
        }
        age <= maxAge
    }
}

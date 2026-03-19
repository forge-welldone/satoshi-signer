package com.remotesigner.nostr

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class InboxStatus { PENDING, SIGNING, SIGNED, BROADCAST, FAILED, DELETED }

enum class RelayStatus { CONNECTING, CONNECTED, DISCONNECTED, ERROR }

@Entity(tableName = "inbox_items")
data class InboxItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val psbtBytes: ByteArray,
    val label: String,
    val amount: String = "",
    val senderNpub: String,
    val receivedAt: Long,
    val status: InboxStatus = InboxStatus.PENDING,
    val rawHex: String? = null,
    val txid: String? = null,
    val network: String = "main",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InboxItemEntity) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

fun formatRelativeTime(unixSeconds: Long): String {
    val diff = System.currentTimeMillis() / 1000 - unixSeconds
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        else -> "${diff / 86400}d ago"
    }
}

fun formatBtcAmount(satoshis: Long): String = "%.8f BTC".format(satoshis / 100_000_000.0)

fun truncateNpub(npub: String): String {
    return if (npub.length > 16) "${npub.take(12)}...${npub.takeLast(4)}" else npub
}

package com.remotesigner.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class InboxStatus { PENDING, SIGNING, SIGNED, BROADCAST, FAILED, DELETED }

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
        return id == other.id &&
            label == other.label &&
            amount == other.amount &&
            senderNpub == other.senderNpub &&
            receivedAt == other.receivedAt &&
            status == other.status &&
            rawHex == other.rawHex &&
            txid == other.txid &&
            network == other.network
    }
    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + (rawHex?.hashCode() ?: 0)
        result = 31 * result + (txid?.hashCode() ?: 0)
        result = 31 * result + network.hashCode()
        return result
    }
}

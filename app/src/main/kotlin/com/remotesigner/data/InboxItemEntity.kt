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
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

package com.remotesigner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.remotesigner.nostr.InboxItemEntity
import com.remotesigner.nostr.InboxStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface InboxDao {
    @Query("SELECT * FROM inbox_items WHERE status != 'DELETED' ORDER BY receivedAt DESC")
    fun getAll(): Flow<List<InboxItemEntity>>

    @Query("SELECT * FROM inbox_items")
    suspend fun getAllOnce(): List<InboxItemEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(item: InboxItemEntity): Long

    @Query("UPDATE inbox_items SET amount = :amount, network = :network WHERE id = :id")
    suspend fun updateParsedFields(id: String, amount: String, network: String)

    @Query("SELECT COUNT(*) FROM inbox_items WHERE id = :id")
    suspend fun exists(id: String): Int

    @Upsert
    suspend fun upsert(item: InboxItemEntity)

    @Query("UPDATE inbox_items SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: InboxStatus)

    @Query("UPDATE inbox_items SET status = :status, rawHex = :rawHex, network = :network WHERE id = :id")
    suspend fun updateSigned(id: String, status: InboxStatus, rawHex: String, network: String)

    @Query("UPDATE inbox_items SET status = :status, txid = :txid, network = :network WHERE id = :id")
    suspend fun updateBroadcast(id: String, status: InboxStatus, txid: String, network: String)

    @Query("DELETE FROM inbox_items WHERE id = :id")
    suspend fun delete(id: String)

    @Query("""
        DELETE FROM inbox_items WHERE
        (status IN ('PENDING', 'SIGNING', 'FAILED', 'DELETED') AND receivedAt < :pendingCutoff)
        OR (status IN ('SIGNED', 'BROADCAST') AND receivedAt < :signedCutoff)
    """)
    suspend fun deleteExpired(pendingCutoff: Long, signedCutoff: Long)
}

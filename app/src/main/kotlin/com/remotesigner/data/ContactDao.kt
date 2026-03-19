package com.remotesigner.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Transaction
    @Query("SELECT * FROM contacts ORDER BY label ASC")
    fun getAllWithFingerprints(): Flow<List<ContactWithFingerprints>>

    @Query("""
        SELECT c.* FROM contacts c
        INNER JOIN contact_fingerprints cf ON c.id = cf.contactId
        WHERE cf.fingerprint = :fingerprint
    """)
    suspend fun findByFingerprint(fingerprint: String): Contact?

    @Transaction
    @Query("""
        SELECT c.* FROM contacts c
        INNER JOIN contact_fingerprints cf ON c.id = cf.contactId
        WHERE cf.fingerprint IN (:fingerprints)
    """)
    suspend fun findByFingerprints(fingerprints: List<String>): List<ContactWithFingerprints>

    @Insert
    suspend fun insertContact(contact: Contact): Long

    @Insert
    suspend fun insertFingerprint(fingerprint: ContactFingerprint): Long

    @Update
    suspend fun updateContact(contact: Contact)

    @Query("UPDATE contact_fingerprints SET contactId = :contactId WHERE fingerprint = :fingerprint")
    suspend fun updateFingerprintContact(contactId: Long, fingerprint: String): Int

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun deleteContact(id: Long)

    @Query("DELETE FROM contact_fingerprints WHERE id = :id")
    suspend fun deleteFingerprint(id: Long)
}

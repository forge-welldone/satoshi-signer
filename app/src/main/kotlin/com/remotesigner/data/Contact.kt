package com.remotesigner.data

import androidx.room.*

@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val npub: String? = null,
)

@Entity(
    tableName = "contact_fingerprints",
    foreignKeys = [ForeignKey(
        entity = Contact::class,
        parentColumns = ["id"],
        childColumns = ["contactId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("contactId"), Index(value = ["fingerprint"], unique = true)],
)
data class ContactFingerprint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactId: Long,
    val fingerprint: String,
)

data class ContactWithFingerprints(
    @Embedded val contact: Contact,
    @Relation(parentColumn = "id", entityColumn = "contactId")
    val fingerprints: List<ContactFingerprint>,
)

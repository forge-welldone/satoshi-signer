package com.remotesigner.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.remotesigner.bridge.SignerInfo
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ContactRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ContactRepository

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = ContactRepository(db.contactDao())
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun enrichSigners_maps_fingerprints_to_contact_labels() = runTest {
        repo.saveContact("Alice", "a1b2c3d4", existingContactId = null)
        val signers = listOf(
            SignerInfo(fingerprint = "a1b2c3d4", signed = false),
            SignerInfo(fingerprint = "ffffffff", signed = true),
        )

        val enriched = repo.enrichSigners(signers)

        assertEquals("Alice", enriched[0].contactLabel)
        assertNotNull(enriched[0].contactId)
        assertNull(enriched[1].contactLabel)
        assertNull(enriched[1].contactId)
    }

    @Test
    fun enrichSigners_returns_original_when_no_contacts() = runTest {
        val signers = listOf(SignerInfo(fingerprint = "a1b2c3d4", signed = false))
        val enriched = repo.enrichSigners(signers)
        assertNull(enriched[0].contactLabel)
    }

    @Test
    fun saveContact_creates_new_contact_and_fingerprint() = runTest {
        repo.saveContact("Bob", "e5f6a7b8", existingContactId = null)

        val enriched = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "e5f6a7b8", signed = false))
        )
        assertEquals("Bob", enriched[0].contactLabel)
    }

    @Test
    fun saveContact_adds_fingerprint_to_existing_contact() = runTest {
        repo.saveContact("Alice", "a1b2c3d4", existingContactId = null)
        val contactId = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "a1b2c3d4", signed = false))
        )[0].contactId!!

        repo.saveContact("ignored", "e5f6a7b8", existingContactId = contactId)

        val enriched = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "e5f6a7b8", signed = false))
        )
        assertEquals("Alice", enriched[0].contactLabel)
    }

    @Test
    fun saveContact_rejects_invalid_fingerprint() = runTest {
        repo.saveContact("Alice", "not-hex", existingContactId = null)

        val enriched = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "not-hex", signed = false))
        )
        assertNull(enriched[0].contactLabel)
    }

    @Test
    fun saveContact_rejects_empty_label() = runTest {
        repo.saveContact("", "a1b2c3d4", existingContactId = null)

        val enriched = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "a1b2c3d4", signed = false))
        )
        assertNull(enriched[0].contactLabel)
    }

    @Test
    fun saveContact_rejects_label_over_50_chars() = runTest {
        repo.saveContact("A".repeat(51), "a1b2c3d4", existingContactId = null)

        val enriched = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "a1b2c3d4", signed = false))
        )
        assertNull(enriched[0].contactLabel)
    }

    @Test
    fun deleteContact_removes_contact_and_fingerprints() = runTest {
        repo.saveContact("Alice", "a1b2c3d4", existingContactId = null)
        val contactId = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "a1b2c3d4", signed = false))
        )[0].contactId!!

        repo.deleteContact(contactId)

        val enriched = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "a1b2c3d4", signed = false))
        )
        assertNull(enriched[0].contactLabel)
    }

    @Test
    fun saveContact_existing_contact_with_same_fingerprint_does_not_crash() = runTest {
        repo.saveContact("Alice", "a1b2c3d4", existingContactId = null)
        val contactId = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "a1b2c3d4", signed = false))
        )[0].contactId!!

        // Re-saving same fingerprint to same contact should be a no-op, not crash
        repo.saveContact("", "a1b2c3d4", existingContactId = contactId)

        val enriched = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "a1b2c3d4", signed = false))
        )
        assertEquals("Alice", enriched[0].contactLabel)
        assertEquals(contactId, enriched[0].contactId)
    }

    @Test
    fun saveContact_moves_fingerprint_to_different_contact() = runTest {
        repo.saveContact("Alice", "a1b2c3d4", existingContactId = null)
        repo.saveContact("Bob", "e5f6a7b8", existingContactId = null)
        val bobId = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "e5f6a7b8", signed = false))
        )[0].contactId!!

        // Move Alice's fingerprint to Bob's contact
        repo.saveContact("", "a1b2c3d4", existingContactId = bobId)

        val enriched = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "a1b2c3d4", signed = false))
        )
        assertEquals("Bob", enriched[0].contactLabel)
        assertEquals(bobId, enriched[0].contactId)
    }

    @Test
    fun deleteFingerprint_keeps_contact() = runTest {
        repo.saveContact("Alice", "a1b2c3d4", existingContactId = null)
        val contactId = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "a1b2c3d4", signed = false))
        )[0].contactId!!

        repo.addFingerprint(contactId, "e5f6a7b8")

        val dao = db.contactDao()
        val cwfs = dao.findByFingerprints(listOf("a1b2c3d4"))
        val fpId = cwfs[0].fingerprints.first { it.fingerprint == "a1b2c3d4" }.id

        repo.deleteFingerprint(fpId)

        val enriched = repo.enrichSigners(listOf(
            SignerInfo(fingerprint = "a1b2c3d4", signed = false),
            SignerInfo(fingerprint = "e5f6a7b8", signed = false),
        ))
        assertNull(enriched[0].contactLabel)
        assertEquals("Alice", enriched[1].contactLabel)
    }
}

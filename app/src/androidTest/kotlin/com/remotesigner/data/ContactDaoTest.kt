package com.remotesigner.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ContactDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ContactDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.contactDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun insert_and_query_contact_with_fingerprint() = runTest {
        val contactId = dao.insertContact(Contact(label = "Alice"))
        dao.insertFingerprint(ContactFingerprint(contactId = contactId, fingerprint = "a1b2c3d4"))

        val all = dao.getAllWithFingerprints().first()
        assertEquals(1, all.size)
        assertEquals("Alice", all[0].contact.label)
        assertEquals(1, all[0].fingerprints.size)
        assertEquals("a1b2c3d4", all[0].fingerprints[0].fingerprint)
    }

    @Test
    fun findByFingerprint_returns_matching_contact() = runTest {
        val contactId = dao.insertContact(Contact(label = "Bob"))
        dao.insertFingerprint(ContactFingerprint(contactId = contactId, fingerprint = "e5f6a7b8"))

        val found = dao.findByFingerprint("e5f6a7b8")
        assertNotNull(found)
        assertEquals("Bob", found!!.label)
    }

    @Test
    fun findByFingerprint_returns_null_for_unknown() = runTest {
        assertNull(dao.findByFingerprint("00000000"))
    }

    @Test
    fun findByFingerprints_batch_query() = runTest {
        val id1 = dao.insertContact(Contact(label = "Alice"))
        dao.insertFingerprint(ContactFingerprint(contactId = id1, fingerprint = "a1b2c3d4"))
        val id2 = dao.insertContact(Contact(label = "Bob"))
        dao.insertFingerprint(ContactFingerprint(contactId = id2, fingerprint = "e5f6a7b8"))

        val results = dao.findByFingerprints(listOf("a1b2c3d4", "e5f6a7b8", "00000000"))
        assertEquals(2, results.size)
    }

    @Test
    fun cascade_delete_removes_fingerprints() = runTest {
        val contactId = dao.insertContact(Contact(label = "Alice"))
        dao.insertFingerprint(ContactFingerprint(contactId = contactId, fingerprint = "a1b2c3d4"))
        dao.insertFingerprint(ContactFingerprint(contactId = contactId, fingerprint = "e5f6a7b8"))

        dao.deleteContact(contactId)
        val all = dao.getAllWithFingerprints().first()
        assertTrue(all.isEmpty())
    }

    @Test
    fun fingerprint_unique_constraint_enforced() = runTest {
        val id1 = dao.insertContact(Contact(label = "Alice"))
        dao.insertFingerprint(ContactFingerprint(contactId = id1, fingerprint = "a1b2c3d4"))

        val id2 = dao.insertContact(Contact(label = "Bob"))
        try {
            dao.insertFingerprint(ContactFingerprint(contactId = id2, fingerprint = "a1b2c3d4"))
            fail("Expected exception for duplicate fingerprint")
        } catch (e: Exception) {
            // Expected: UNIQUE constraint violation
        }
    }

    @Test
    fun updateContact_changes_label() = runTest {
        val contactId = dao.insertContact(Contact(label = "Alice"))
        dao.updateContact(Contact(id = contactId, label = "Alice's Trezor"))

        val all = dao.getAllWithFingerprints().first()
        assertEquals("Alice's Trezor", all[0].contact.label)
    }

    @Test
    fun deleteFingerprint_keeps_contact() = runTest {
        val contactId = dao.insertContact(Contact(label = "Alice"))
        val fp1 = dao.insertFingerprint(ContactFingerprint(contactId = contactId, fingerprint = "a1b2c3d4"))
        dao.insertFingerprint(ContactFingerprint(contactId = contactId, fingerprint = "e5f6a7b8"))

        dao.deleteFingerprint(fp1)
        val all = dao.getAllWithFingerprints().first()
        assertEquals(1, all.size)
        assertEquals(1, all[0].fingerprints.size)
        assertEquals("e5f6a7b8", all[0].fingerprints[0].fingerprint)
    }

    @Test
    fun getAllWithFingerprints_ordered_by_label() = runTest {
        dao.insertContact(Contact(label = "Charlie"))
        dao.insertContact(Contact(label = "Alice"))
        dao.insertContact(Contact(label = "Bob"))

        val all = dao.getAllWithFingerprints().first()
        assertEquals(listOf("Alice", "Bob", "Charlie"), all.map { it.contact.label })
    }

    @Test
    fun contact_with_npub() = runTest {
        dao.insertContact(Contact(label = "Alice", npub = "npub1abc...xyz"))
        val all = dao.getAllWithFingerprints().first()
        assertEquals("npub1abc...xyz", all[0].contact.npub)
    }
}

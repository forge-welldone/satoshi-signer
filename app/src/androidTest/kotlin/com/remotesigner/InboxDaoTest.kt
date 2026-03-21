package com.remotesigner

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.remotesigner.data.AppDatabase
import com.remotesigner.data.InboxDao
import com.remotesigner.data.InboxItemEntity
import com.remotesigner.data.InboxStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InboxDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: InboxDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.inboxDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun makeItem(
        id: String = "event1",
        status: InboxStatus = InboxStatus.PENDING,
        receivedAt: Long = 1710700000L,
        rawHex: String? = null,
        txid: String? = null,
        network: String = "main",
    ) = InboxItemEntity(
        id = id,
        psbtBytes = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()),
        label = "Payment",
        amount = "0.01000000 BTC",
        senderNpub = "npub1test",
        receivedAt = receivedAt,
        status = status,
        rawHex = rawHex,
        txid = txid,
        network = network,
    )

    @Test
    fun insertIgnore_andGetAll_returnsItem() = runTest {
        val item = makeItem()
        dao.insertIgnore(item)
        val items = dao.getAll().first()
        assertEquals(1, items.size)
        assertEquals("event1", items[0].id)
        assertEquals(InboxStatus.PENDING, items[0].status)
        assertArrayEquals(item.psbtBytes, items[0].psbtBytes)
    }

    @Test
    fun insertIgnore_duplicateId_doesNotOverwriteExistingRow() = runTest {
        dao.insertIgnore(makeItem(id = "event1", status = InboxStatus.BROADCAST, txid = "tx1"))
        val result = dao.insertIgnore(makeItem(id = "event1", status = InboxStatus.PENDING))
        assertEquals(-1L, result)

        val items = dao.getAll().first()
        assertEquals(1, items.size)
        assertEquals(InboxStatus.BROADCAST, items[0].status)
        assertEquals("tx1", items[0].txid)
    }

    @Test
    fun updateParsedFields_updatesOnlyAmountAndNetwork() = runTest {
        dao.insertIgnore(makeItem(id = "event1", status = InboxStatus.SIGNED, rawHex = "abc", network = "main"))
        dao.updateParsedFields("event1", "0.12340000 BTC", "test")

        val items = dao.getAll().first()
        assertEquals("0.12340000 BTC", items[0].amount)
        assertEquals("test", items[0].network)
        assertEquals(InboxStatus.SIGNED, items[0].status)
        assertEquals("abc", items[0].rawHex)
    }

    @Test
    fun updateStatus_changesOnlyStatus() = runTest {
        dao.insertIgnore(makeItem())
        dao.updateStatus("event1", InboxStatus.SIGNING)
        val items = dao.getAll().first()
        assertEquals(InboxStatus.SIGNING, items[0].status)
        assertEquals("Payment", items[0].label) // other fields unchanged
    }

    @Test
    fun updateSigned_setsStatusRawHexNetwork() = runTest {
        dao.insertIgnore(makeItem())
        dao.updateSigned("event1", InboxStatus.SIGNED, "cafebabe", "test")
        val items = dao.getAll().first()
        assertEquals(InboxStatus.SIGNED, items[0].status)
        assertEquals("cafebabe", items[0].rawHex)
        assertEquals("test", items[0].network)
    }

    @Test
    fun updateBroadcast_setsStatusAndTxid() = runTest {
        dao.insertIgnore(makeItem(status = InboxStatus.SIGNED, rawHex = "deadbeef"))
        dao.updateBroadcast("event1", InboxStatus.BROADCAST, "abc123", "testnet4")
        val items = dao.getAll().first()
        assertEquals(InboxStatus.BROADCAST, items[0].status)
        assertEquals("abc123", items[0].txid)
        assertEquals("testnet4", items[0].network)
        assertEquals("deadbeef", items[0].rawHex) // rawHex preserved
    }

    @Test
    fun delete_removesItem() = runTest {
        dao.insertIgnore(makeItem())
        dao.delete("event1")
        val items = dao.getAll().first()
        assertTrue(items.isEmpty())
    }

    @Test
    fun deleteExpired_removesOnlyExpired() = runTest {
        val now = 1710700000L
        dao.insertIgnore(makeItem(id = "a1", receivedAt = now - 7200))
        dao.insertIgnore(makeItem(id = "a2", receivedAt = now - 90000))
        dao.insertIgnore(makeItem(id = "a3", status = InboxStatus.SIGNED, receivedAt = now - 86400 * 3))
        dao.insertIgnore(makeItem(id = "a4", status = InboxStatus.BROADCAST, receivedAt = now - 86400 * 8))
        dao.insertIgnore(makeItem(id = "a5", status = InboxStatus.FAILED, receivedAt = now - 90000))

        dao.deleteExpired(
            pendingCutoff = now - 86400,
            signedCutoff = now - 86400 * 7,
        )

        val items = dao.getAll().first()
        assertEquals(2, items.size)
        val ids = items.map { it.id }.toSet()
        assertTrue(ids.contains("a1"))
        assertTrue(ids.contains("a3"))
    }

    @Test
    fun getAll_orderedByReceivedAtDesc() = runTest {
        dao.insertIgnore(makeItem(id = "old", receivedAt = 100L))
        dao.insertIgnore(makeItem(id = "new", receivedAt = 300L))
        dao.insertIgnore(makeItem(id = "mid", receivedAt = 200L))
        val items = dao.getAll().first()
        assertEquals(listOf("new", "mid", "old"), items.map { it.id })
    }

    @Test
    fun getAllOnce_returnsCurrentSnapshot() = runTest {
        dao.insertIgnore(makeItem(id = "a"))
        dao.insertIgnore(makeItem(id = "b"))
        val items = dao.getAllOnce()
        assertEquals(2, items.size)
    }

    @Test
    fun getAll_excludesDeletedItems() = runTest {
        dao.insertIgnore(makeItem(id = "visible", status = InboxStatus.PENDING))
        dao.insertIgnore(makeItem(id = "hidden", status = InboxStatus.DELETED))
        val items = dao.getAll().first()
        assertEquals(1, items.size)
        assertEquals("visible", items[0].id)
    }

    @Test
    fun getAllOnce_includesDeletedItems() = runTest {
        dao.insertIgnore(makeItem(id = "visible", status = InboxStatus.PENDING))
        dao.insertIgnore(makeItem(id = "hidden", status = InboxStatus.DELETED))
        val items = dao.getAllOnce()
        assertEquals(2, items.size)
    }

    @Test
    fun deleteExpired_removesOldDeletedItems() = runTest {
        val now = 1710700000L
        dao.insertIgnore(makeItem(id = "d1", status = InboxStatus.DELETED, receivedAt = now - 90000))
        dao.insertIgnore(makeItem(id = "d2", status = InboxStatus.DELETED, receivedAt = now - 7200))
        dao.deleteExpired(
            pendingCutoff = now - 86400,
            signedCutoff = now - 86400 * 7,
        )
        val items = dao.getAllOnce()
        assertEquals(1, items.size)
        assertEquals("d2", items[0].id)
    }

    @Test
    fun softDelete_changesStatusToDeleted() = runTest {
        dao.insertIgnore(makeItem(id = "event1", status = InboxStatus.PENDING))
        dao.updateStatus("event1", InboxStatus.DELETED)
        // Not visible in getAll (UI query)
        val uiItems = dao.getAll().first()
        assertTrue(uiItems.isEmpty())
        // But still in getAllOnce (for seenIds seeding)
        val allItems = dao.getAllOnce()
        assertEquals(1, allItems.size)
        assertEquals(InboxStatus.DELETED, allItems[0].status)
    }
}

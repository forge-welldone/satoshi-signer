package com.remotesigner.nostr

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InboxStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var file: File
    private lateinit var store: InboxStore

    @Before
    fun setUp() {
        file = File(tempFolder.root, "inbox.json")
        store = InboxStore(file)
    }

    @Test
    fun roundTrip_pendingItem() {
        val item = InboxItem(
            id = "abc123",
            psbtBytes = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()),
            label = "Payment to Alice",
            amount = "0.00500000 BTC",
            senderNpub = "npub1abc...xyz",
            receivedAt = 1710700000L,
            status = InboxStatus.PENDING,
        )
        store.save(listOf(item))
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals("abc123", loaded[0].id)
        assertArrayEquals(item.psbtBytes, loaded[0].psbtBytes)
        assertEquals("Payment to Alice", loaded[0].label)
        assertEquals("0.00500000 BTC", loaded[0].amount)
        assertEquals("npub1abc...xyz", loaded[0].senderNpub)
        assertEquals(1710700000L, loaded[0].receivedAt)
        assertEquals(InboxStatus.PENDING, loaded[0].status)
        assertNull(loaded[0].rawHex)
        assertNull(loaded[0].txid)
        assertEquals("main", loaded[0].network)
    }

    @Test
    fun roundTrip_broadcastItem() {
        val item = InboxItem(
            id = "def456",
            psbtBytes = byteArrayOf(0x01, 0x02),
            label = "Payment to Bob",
            amount = "0.10000000 BTC",
            senderNpub = "npub1def...uvw",
            receivedAt = 1710700000L,
            status = InboxStatus.BROADCAST,
            rawHex = "0200000001deadbeef",
            txid = "a1b2c3d4e5f6",
            network = "test",
        )
        store.save(listOf(item))
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals(InboxStatus.BROADCAST, loaded[0].status)
        assertEquals("0200000001deadbeef", loaded[0].rawHex)
        assertEquals("a1b2c3d4e5f6", loaded[0].txid)
        assertEquals("test", loaded[0].network)
    }

    @Test
    fun missingFile_returnsEmptyList() {
        assertFalse(file.exists())
        val loaded = store.load()
        assertEquals(emptyList<InboxItem>(), loaded)
    }

    @Test
    fun corruptFile_returnsEmptyList() {
        file.writeText("not json at all {{{")
        val loaded = store.load()
        assertEquals(emptyList<InboxItem>(), loaded)
    }

    @Test
    fun multipleItems_roundTrip() {
        val items = listOf(
            InboxItem(
                id = "a1", psbtBytes = byteArrayOf(1), label = "Tx1",
                senderNpub = "npub1a", receivedAt = 100L,
            ),
            InboxItem(
                id = "a2", psbtBytes = byteArrayOf(2), label = "Tx2",
                senderNpub = "npub1b", receivedAt = 200L,
                status = InboxStatus.SIGNED, rawHex = "deadbeef", network = "test",
            ),
        )
        store.save(items)
        val loaded = store.load()
        assertEquals(2, loaded.size)
        assertEquals("a1", loaded[0].id)
        assertEquals("a2", loaded[1].id)
        assertEquals(InboxStatus.SIGNED, loaded[1].status)
    }
}

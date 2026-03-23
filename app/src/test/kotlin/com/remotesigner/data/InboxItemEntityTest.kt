package com.remotesigner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxItemEntityTest {

    private fun entity(
        id: String = "event1",
        status: InboxStatus = InboxStatus.PENDING,
        rawHex: String? = null,
        txid: String? = null,
        network: String = "main",
        label: String = "Test payment",
        amount: String = "0.00100000 BTC",
    ) = InboxItemEntity(
        id = id,
        psbtBytes = byteArrayOf(0x70, 0x73, 0x62, 0x74),
        label = label,
        amount = amount,
        senderNpub = "npub1test",
        receivedAt = 1000L,
        status = status,
        rawHex = rawHex,
        txid = txid,
        network = network,
    )

    // --- equals must detect status changes (StateFlow dedup depends on this) ---

    @Test
    fun `entities with same id but different status are not equal`() {
        val pending = entity(status = InboxStatus.PENDING)
        val signed = entity(status = InboxStatus.SIGNED)
        assertNotEquals(pending, signed)
    }

    @Test
    fun `entities with same id but different rawHex are not equal`() {
        val withoutHex = entity(status = InboxStatus.SIGNED)
        val withHex = entity(status = InboxStatus.SIGNED, rawHex = "0200abcd")
        assertNotEquals(withoutHex, withHex)
    }

    @Test
    fun `entities with same id but different txid are not equal`() {
        val withoutTxid = entity(status = InboxStatus.SIGNED, rawHex = "0200abcd")
        val withTxid = entity(status = InboxStatus.BROADCAST, rawHex = "0200abcd", txid = "tx123")
        assertNotEquals(withoutTxid, withTxid)
    }

    @Test
    fun `entities with same id but different network are not equal`() {
        val mainnet = entity(network = "main")
        val testnet = entity(network = "test")
        assertNotEquals(mainnet, testnet)
    }

    @Test
    fun `entities with same id but different amount are not equal`() {
        val a = entity(amount = "0.001 BTC")
        val b = entity(amount = "0.002 BTC")
        assertNotEquals(a, b)
    }

    // --- equals still works for identity ---

    @Test
    fun `entities with different id are not equal`() {
        val a = entity(id = "event1")
        val b = entity(id = "event2")
        assertNotEquals(a, b)
    }

    @Test
    fun `identical entities are equal`() {
        val a = entity()
        val b = entity()
        assertEquals(a, b)
    }

    @Test
    fun `entity equals itself`() {
        val a = entity()
        assertTrue(a == a)
    }

    @Test
    fun `entity is not equal to non-entity`() {
        val a = entity()
        assertFalse(a.equals("not an entity"))
    }

    @Test
    fun `entity is not equal to null`() {
        val a = entity()
        assertFalse(a.equals(null))
    }

    // --- hashCode contract ---

    @Test
    fun `equal entities have same hashCode`() {
        val a = entity()
        val b = entity()
        assertEquals(a.hashCode(), b.hashCode())
    }

    // --- StateFlow dedup regression test ---

    @Test
    fun `list equality detects status change for StateFlow dedup`() {
        val listBefore = listOf(entity(status = InboxStatus.PENDING))
        val listAfter = listOf(entity(status = InboxStatus.SIGNED, rawHex = "0200abcd"))
        assertNotEquals(listBefore, listAfter)
    }
}

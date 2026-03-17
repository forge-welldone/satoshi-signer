package com.remotesigner.nostr

import org.junit.Assert.*
import org.junit.Test

class InboxExpiryTest {

    private val now = 1710700000L

    @Test
    fun pendingItem_within24h_kept() {
        val item = InboxItem(
            id = "a1", psbtBytes = byteArrayOf(1), label = "Tx1",
            senderNpub = "npub1a", receivedAt = now - 3600,
        )
        val result = removeExpiredItems(listOf(item), nowSeconds = now)
        assertEquals(1, result.size)
    }

    @Test
    fun pendingItem_over24h_removed() {
        val item = InboxItem(
            id = "a1", psbtBytes = byteArrayOf(1), label = "Tx1",
            senderNpub = "npub1a", receivedAt = now - 86401,
        )
        val result = removeExpiredItems(listOf(item), nowSeconds = now)
        assertEquals(0, result.size)
    }

    @Test
    fun failedItem_over24h_removed() {
        val item = InboxItem(
            id = "a1", psbtBytes = byteArrayOf(1), label = "Tx1",
            senderNpub = "npub1a", receivedAt = now - 86401,
            status = InboxStatus.FAILED,
        )
        val result = removeExpiredItems(listOf(item), nowSeconds = now)
        assertEquals(0, result.size)
    }

    @Test
    fun signingItem_over24h_removed() {
        val item = InboxItem(
            id = "a1", psbtBytes = byteArrayOf(1), label = "Tx1",
            senderNpub = "npub1a", receivedAt = now - 86401,
            status = InboxStatus.SIGNING,
        )
        val result = removeExpiredItems(listOf(item), nowSeconds = now)
        assertEquals(0, result.size)
    }

    @Test
    fun signedItem_within7d_kept() {
        val item = InboxItem(
            id = "a1", psbtBytes = byteArrayOf(1), label = "Tx1",
            senderNpub = "npub1a", receivedAt = now - 86400 * 5,
            status = InboxStatus.SIGNED, rawHex = "deadbeef",
        )
        val result = removeExpiredItems(listOf(item), nowSeconds = now)
        assertEquals(1, result.size)
    }

    @Test
    fun signedItem_over7d_removed() {
        val item = InboxItem(
            id = "a1", psbtBytes = byteArrayOf(1), label = "Tx1",
            senderNpub = "npub1a", receivedAt = now - 86400 * 8,
            status = InboxStatus.SIGNED, rawHex = "deadbeef",
        )
        val result = removeExpiredItems(listOf(item), nowSeconds = now)
        assertEquals(0, result.size)
    }

    @Test
    fun broadcastItem_within7d_kept() {
        val item = InboxItem(
            id = "a1", psbtBytes = byteArrayOf(1), label = "Tx1",
            senderNpub = "npub1a", receivedAt = now - 86400 * 6,
            status = InboxStatus.BROADCAST, rawHex = "deadbeef", txid = "abc123",
        )
        val result = removeExpiredItems(listOf(item), nowSeconds = now)
        assertEquals(1, result.size)
    }

    @Test
    fun broadcastItem_over7d_removed() {
        val item = InboxItem(
            id = "a1", psbtBytes = byteArrayOf(1), label = "Tx1",
            senderNpub = "npub1a", receivedAt = now - 86400 * 8,
            status = InboxStatus.BROADCAST, rawHex = "deadbeef", txid = "abc123",
        )
        val result = removeExpiredItems(listOf(item), nowSeconds = now)
        assertEquals(0, result.size)
    }

    @Test
    fun mixedItems_onlyExpiredRemoved() {
        val items = listOf(
            InboxItem(
                id = "a1", psbtBytes = byteArrayOf(1), label = "Tx1",
                senderNpub = "n1", receivedAt = now - 3600,
            ),
            InboxItem(
                id = "a2", psbtBytes = byteArrayOf(2), label = "Tx2",
                senderNpub = "n2", receivedAt = now - 90000,
            ),
            InboxItem(
                id = "a3", psbtBytes = byteArrayOf(3), label = "Tx3",
                senderNpub = "n3", receivedAt = now - 86400 * 3,
                status = InboxStatus.SIGNED, rawHex = "beef",
            ),
        )
        val result = removeExpiredItems(items, nowSeconds = now)
        assertEquals(2, result.size)
        assertEquals("a1", result[0].id)
        assertEquals("a3", result[1].id)
    }
}

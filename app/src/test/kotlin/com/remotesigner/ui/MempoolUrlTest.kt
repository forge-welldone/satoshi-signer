package com.remotesigner.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MempoolUrlTest {

    @Test
    fun mainnet_address_url() {
        assertEquals(
            "https://mempool.space/address/bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            mempoolAddressUrl("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", "main"),
        )
    }

    @Test
    fun testnet_address_url() {
        assertEquals(
            "https://mempool.space/testnet/address/tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx",
            mempoolAddressUrl("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx", "test"),
        )
    }

    @Test
    fun mainnet_tx_url() {
        assertEquals(
            "https://mempool.space/tx/abc123def456",
            mempoolTxUrl("abc123def456", "main"),
        )
    }

    @Test
    fun testnet_tx_url() {
        assertEquals(
            "https://mempool.space/testnet/tx/abc123def456",
            mempoolTxUrl("abc123def456", "test"),
        )
    }

    @Test
    fun testnet4_tx_url() {
        assertEquals(
            "https://mempool.space/testnet4/tx/abc123def456",
            mempoolTxUrl("abc123def456", "testnet4"),
        )
    }

    @Test
    fun signet_tx_url() {
        assertEquals(
            "https://mempool.space/signet/tx/abc123def456",
            mempoolTxUrl("abc123def456", "signet"),
        )
    }

    @Test
    fun testnet3_tx_url() {
        assertEquals(
            "https://mempool.space/testnet/tx/abc123def456",
            mempoolTxUrl("abc123def456", "testnet3"),
        )
    }

    @Test
    fun testnet3_address_url() {
        assertEquals(
            "https://mempool.space/testnet/address/tb1qtest",
            mempoolAddressUrl("tb1qtest", "testnet3"),
        )
    }

    @Test
    fun testnet4_address_url() {
        assertEquals(
            "https://mempool.space/testnet4/address/tb1qtest",
            mempoolAddressUrl("tb1qtest", "testnet4"),
        )
    }

    @Test
    fun signet_address_url() {
        assertEquals(
            "https://mempool.space/signet/address/tb1qtest",
            mempoolAddressUrl("tb1qtest", "signet"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknown_network_throws() {
        mempoolTxUrl("abc123", "typo")
    }
}

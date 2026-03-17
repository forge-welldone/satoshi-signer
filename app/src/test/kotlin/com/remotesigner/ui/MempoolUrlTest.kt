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
}

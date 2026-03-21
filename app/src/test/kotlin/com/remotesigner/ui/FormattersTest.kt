package com.remotesigner.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun formatBtc_oneBitcoin() {
        assertEquals("1.00000000 BTC", formatBtcAmount(100_000_000L))
    }

    @Test
    fun formatBtc_zero() {
        assertEquals("0.00000000 BTC", formatBtcAmount(0L))
    }

    @Test
    fun formatBtc_oneSatoshi() {
        assertEquals("0.00000001 BTC", formatBtcAmount(1L))
    }

    @Test
    fun formatBtc_typicalFee() {
        assertEquals("0.00012345 BTC", formatBtcAmount(12_345L))
    }

    @Test
    fun formatBtc_largAmount() {
        assertEquals("21000000.00000000 BTC", formatBtcAmount(21_000_000_00_000_000L))
    }
}

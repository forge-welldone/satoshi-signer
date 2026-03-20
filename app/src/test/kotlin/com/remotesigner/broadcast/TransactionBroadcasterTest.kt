package com.remotesigner.broadcast

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TransactionBroadcasterTest {

    private lateinit var server: MockWebServer
    private lateinit var broadcaster: TransactionBroadcaster

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        broadcaster = TransactionBroadcaster(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun broadcasterWithServer(): TransactionBroadcaster {
        val baseUrl = server.url("/api/tx").toString()
        val secondaryUrl = server.url("/secondary/api/tx").toString()
        return TransactionBroadcaster(
            client = OkHttpClient(),
            endpoints = mapOf(
                "main" to listOf(baseUrl, secondaryUrl),
                "test" to listOf(baseUrl, secondaryUrl),
                "testnet3" to listOf(baseUrl, secondaryUrl),
                "testnet4" to listOf(baseUrl),
                "signet" to listOf(baseUrl),
            ),
        )
    }

    // --- Validation ---

    @Test(expected = IllegalArgumentException::class)
    fun `rejects empty hex`() {
        broadcaster.broadcast("", "main")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects odd-length hex`() {
        broadcaster.broadcast("abc", "main")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non-hex characters`() {
        broadcaster.broadcast("xyz123", "main")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects oversized transaction`() {
        broadcaster.broadcast("ab".repeat(400_001), "main")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unknown network`() {
        broadcaster.broadcast("deadbeef", "typo")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects testt network`() {
        broadcaster.broadcast("deadbeef", "testt")
    }

    @Test
    fun `validation error messages are descriptive`() {
        try {
            broadcaster.broadcast("", "main")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("non-empty"))
        }

        try {
            broadcaster.broadcast("abc", "main")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("odd length"))
        }

        try {
            broadcaster.broadcast("xyz123", "main")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("non-hex"))
        }

        try {
            broadcaster.broadcast("deadbeef", "typo")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Unknown network"))
        }
    }

    // --- Successful broadcast ---

    @Test
    fun `broadcasts to primary endpoint and returns txid`() {
        server.enqueue(MockResponse().setBody("abc123txid").setResponseCode(200))
        val b = broadcasterWithServer()
        val result = b.broadcast("deadbeef", "main")
        assertEquals("ok", result.status)
        assertEquals("abc123txid", result.txid)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `strips whitespace from txid`() {
        server.enqueue(MockResponse().setBody("  txid123\n ").setResponseCode(200))
        val b = broadcasterWithServer()
        val result = b.broadcast("deadbeef", "main")
        assertEquals("txid123", result.txid)
    }

    // --- Fallback ---

    @Test
    fun `falls back to secondary when primary fails`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("error"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("error"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("txid456"))
        val b = broadcasterWithServer()
        val result = b.broadcast("deadbeef", "main")
        assertEquals("ok", result.status)
        assertEquals("txid456", result.txid)
        assertEquals(3, server.requestCount)
    }

    // --- Retry ---

    @Test
    fun `retries same endpoint on first failure`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("temporary error"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("txid_retry"))
        val b = broadcasterWithServer()
        val result = b.broadcast("deadbeef", "main")
        assertEquals("ok", result.status)
        assertEquals("txid_retry", result.txid)
        assertEquals(2, server.requestCount)
    }

    // --- Total failure ---

    @Test
    fun `returns error with last message when all endpoints fail`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("server error 1"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("server error 2"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("server error 3"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("final error"))
        val b = broadcasterWithServer()
        val result = b.broadcast("deadbeef", "main")
        assertEquals("error", result.status)
        assertTrue(result.message!!.contains("final error"))
        assertEquals("deadbeef", result.rawHex)
    }

    // --- Network URL mapping ---

    @Test
    fun `uses correct URL path for each network`() {
        server.enqueue(MockResponse().setBody("txid").setResponseCode(200))
        val b = broadcasterWithServer()
        b.broadcast("deadbeef", "main")
        val request = server.takeRequest()
        assertEquals("/api/tx", request.path)
        assertEquals("deadbeef", request.body.readUtf8())
    }

    @Test
    fun `sends text_plain content type`() {
        server.enqueue(MockResponse().setBody("txid").setResponseCode(200))
        val b = broadcasterWithServer()
        b.broadcast("deadbeef", "main")
        val request = server.takeRequest()
        assertTrue(request.getHeader("Content-Type")!!.contains("text/plain"))
    }
}

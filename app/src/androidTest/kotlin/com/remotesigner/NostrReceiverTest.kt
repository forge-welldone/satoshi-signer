package com.remotesigner

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.remotesigner.nostr.*
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class NostrReceiverTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyManager = NostrKeyManager(context)
    private val secp = Secp256k1.get()
    private lateinit var mockServer: MockWebServer

    @Before
    fun setUp() {
        context.getSharedPreferences("nostr_keys", 0).edit().clear().apply()
        mockServer = MockWebServer()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        context.getSharedPreferences("nostr_keys", 0).edit().clear().apply()
    }

    @Test
    fun receiver_parsesValidEvent_callsOnItem() = runBlocking {
        val received = mutableListOf<InboxItem>()
        val done = CompletableDeferred<Unit>()
        val receiver = NostrReceiver(
            keyManager = keyManager,
            onItem = { received.add(it); done.complete(Unit) },
            scope = CoroutineScope(Dispatchers.IO),
        )

        mockServer.enqueue(MockResponse().withWebSocketUpgrade(
            object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    val event = createTestEvent(keyManager)
                    webSocket.send("""["EVENT","psbt-inbox",${event}]""")
                }
            }
        ))
        mockServer.start()

        receiver.connectToRelays(listOf(mockServer.url("/").toString().replace("http://", "ws://")))
        withTimeout(5000) { done.await() }
        receiver.disconnect()

        assertEquals(1, received.size)
        assertEquals("Test payment", received[0].label)
    }

    @Test
    fun receiver_deduplicatesByEventId() = runBlocking {
        val received = mutableListOf<InboxItem>()
        val done = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.IO)
        var count = 0
        val receiver = NostrReceiver(
            keyManager = keyManager,
            onItem = {
                received.add(it)
                // Complete after a brief window to let potential duplicates arrive
                if (++count == 1) {
                    scope.launch {
                        kotlinx.coroutines.delay(500)
                        done.complete(Unit)
                    }
                }
            },
            scope = scope,
        )

        mockServer.enqueue(MockResponse().withWebSocketUpgrade(
            object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    val event = createTestEvent(keyManager)
                    webSocket.send("""["EVENT","psbt-inbox",${event}]""")
                    webSocket.send("""["EVENT","psbt-inbox",${event}]""") // duplicate
                }
            }
        ))
        mockServer.start()

        receiver.connectToRelays(listOf(mockServer.url("/").toString().replace("http://", "ws://")))
        withTimeout(5000) { done.await() }
        receiver.disconnect()

        assertEquals("Duplicate should be dropped", 1, received.size)
    }

    /**
     * Create a valid NIP-04 encrypted Nostr kind 4 event targeting the given keyManager.
     */
    private fun createTestEvent(km: NostrKeyManager): String {
        val senderPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val senderPub = secp.pubkeyCreate(senderPriv)            // 65-byte uncompressed
        val senderPubXOnly = senderPub.copyOfRange(1, 33)        // 32-byte x-coordinate

        val (_, receiverPub) = km.getOrCreateKeyPair()

        // NIP-04 encrypt
        val payload = """{"tx": "cHNidA==", "label": "Test payment"}"""
        val sharedSecret = secp.ecdh(senderPriv, byteArrayOf(0x02) + receiverPub)
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(payload.toByteArray())
        val content = Base64.encodeToString(ciphertext, Base64.NO_WRAP) +
            "?iv=" + Base64.encodeToString(iv, Base64.NO_WRAP)

        val createdAt = System.currentTimeMillis() / 1000
        val tags = JSONArray().apply {
            put(JSONArray().apply { put("p"); put(receiverPub.toHex()) })
        }

        // Compute event ID per NIP-01
        val serialized = JSONArray().apply {
            put(0)
            put(senderPubXOnly.toHex())
            put(createdAt)
            put(4)
            put(tags)
            put(content)
        }
        val id = MessageDigest.getInstance("SHA-256")
            .digest(serialized.toString().toByteArray()).toHex()

        return JSONObject().apply {
            put("id", id)
            put("pubkey", senderPubXOnly.toHex())
            put("created_at", createdAt)
            put("kind", 4)
            put("tags", tags)
            put("content", content)
            put("sig", "0".repeat(128))
        }.toString()
    }
}

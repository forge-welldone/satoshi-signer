package com.remotesigner

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.remotesigner.nostr.*
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class NostrReceiverTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var keyManager: NostrKeyManager
    private val secp = Secp256k1.get()
    private lateinit var mockServer: MockWebServer
    private var activeReceiver: NostrReceiver? = null

    @Before
    fun setUp() {
        context.deleteSharedPreferences("nostr_keys_encrypted")
        keyManager = NostrKeyManager(context)
        // Pre-generate the keypair before any concurrent access.
        // Without this, receiver's onOpen and mock server's onOpen race to call
        // getOrCreateKeyPair(), each generating a different random key.
        keyManager.getOrCreateKeyPair()
        mockServer = MockWebServer()
    }

    @After
    fun tearDown() {
        // Disconnect receiver FIRST to stop reconnection attempts,
        // otherwise MockWebServer.shutdown() hangs waiting for its queue.
        activeReceiver?.disconnect()
        activeReceiver = null
        try { mockServer.shutdown() } catch (_: Exception) { }
        context.deleteSharedPreferences("nostr_keys_encrypted")
    }

    @Test
    fun eventHelper_producesDecryptableContent() {
        val eventJson = createTestEvent(keyManager)
        val event = JSONObject(eventJson)
        val content = event.getString("content")
        val senderPub = event.getString("pubkey")

        val (privkey, _) = keyManager.getOrCreateKeyPair()
        val plaintext = Nip04.decrypt(privkey, senderPub.hexToByteArray(), content)
        val payload = JSONObject(plaintext)
        assertEquals("Test payment", payload.getString("label"))
    }

    @Test
    fun eventHelper_survivesRelayMessageRoundTrip() {
        val eventJson = createTestEvent(keyManager)
        val relayMessage = """["EVENT","psbt-inbox",${eventJson}]"""

        val parsed = NostrEvent.fromRelayMessage(relayMessage)
        assertNotNull("Event should parse from relay message", parsed)

        val (privkey, _) = keyManager.getOrCreateKeyPair()
        val plaintext = Nip04.decrypt(privkey, parsed!!.pubkey.hexToByteArray(), parsed.content)
        val payload = JSONObject(plaintext)
        assertEquals("Test payment", payload.getString("label"))
    }

    @Test
    fun receiver_parsesValidEvent_callsOnItem() {
        val received = CopyOnWriteArrayList<InboxItemEntity>()
        val latch = CountDownLatch(1)
        val receiver = NostrReceiver(
            keyManager = keyManager,
            onItem = { received.add(it); latch.countDown() },
            scope = CoroutineScope(Dispatchers.IO),
        ).also { activeReceiver = it }

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
        assertTrue("Should receive event within 5s", latch.await(5, TimeUnit.SECONDS))

        assertEquals(1, received.size)
        assertEquals("Test payment", received[0].label)
    }

    @Test
    fun receiver_deduplicatesByEventId() {
        val received = CopyOnWriteArrayList<InboxItemEntity>()
        val sentinelLatch = CountDownLatch(1)
        val receiver = NostrReceiver(
            keyManager = keyManager,
            onItem = {
                received.add(it)
                if (it.label == "Sentinel") sentinelLatch.countDown()
            },
            scope = CoroutineScope(Dispatchers.IO),
        ).also { activeReceiver = it }

        val sentinelEvent = createTestEvent(keyManager, "Sentinel")
        mockServer.enqueue(MockResponse().withWebSocketUpgrade(
            object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    val event = createTestEvent(keyManager)
                    webSocket.send("""["EVENT","psbt-inbox",${event}]""")
                    webSocket.send("""["EVENT","psbt-inbox",${event}]""") // duplicate
                    webSocket.send("""["EVENT","psbt-inbox",${sentinelEvent}]""")
                }
            }
        ))
        mockServer.start()

        receiver.connectToRelays(listOf(mockServer.url("/").toString().replace("http://", "ws://")))
        assertTrue("Sentinel should arrive within 5s", sentinelLatch.await(5, TimeUnit.SECONDS))

        // Sentinel arrived, so duplicate was already processed (or dropped).
        // Expect: original event + sentinel = 2 items (duplicate dropped).
        assertEquals("Duplicate should be dropped", 2, received.size)
        assertEquals("Sentinel", received[1].label)
    }

    @Test
    fun receiver_connectedCount_survivesRelayFailure() {
        // Bug: when a relay fails, onFailure decrements _connectedCount even though
        // onOpen was never called for that connection. After reconnection attempts,
        // the count drains to 0 even though other relays are connected.
        val workingServer = MockWebServer()
        workingServer.enqueue(MockResponse().withWebSocketUpgrade(
            object : okhttp3.WebSocketListener() {}
        ))
        workingServer.start()

        // Get a port that's definitely closed (start a server, grab its port, shut it down)
        val failingServer = MockWebServer()
        failingServer.start()
        val failingPort = failingServer.port
        failingServer.shutdown()

        val connectedLatch = CountDownLatch(1)
        val receiver = NostrReceiver(
            keyManager = keyManager,
            onItem = {},
            scope = CoroutineScope(Dispatchers.IO),
        ).also { activeReceiver = it }

        val workingUrl = workingServer.url("/").toString().replace("http://", "ws://")
        val failingUrl = "ws://127.0.0.1:$failingPort"

        // Observe relay statuses to know when the working relay connects
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            receiver.relayStatuses.collect { statuses ->
                if (statuses[workingUrl] == RelayStatus.CONNECTED) {
                    connectedLatch.countDown()
                }
            }
        }

        receiver.connectToRelays(listOf(workingUrl, failingUrl))

        // Wait for the working relay to reach CONNECTED state
        assertTrue(
            "Working relay should connect within 5s",
            connectedLatch.await(5, TimeUnit.SECONDS),
        )

        // The working relay is connected, so count must be 1 (not 0)
        assertEquals(
            "Connected count should reflect actually-connected relays",
            1, receiver.connectedCount.value,
        )

        // Verify relay statuses show correct states
        val statuses = receiver.relayStatuses.value
        assertEquals(RelayStatus.CONNECTED, statuses[workingUrl])

        receiver.disconnect()
        try { workingServer.shutdown() } catch (_: Exception) { }
    }

    @Test
    fun fromRelayMessage_rejectsEvent_whenVerifyIdThrows() {
        // Event with all required fields for parsing but missing "tags".
        // verifyId() will throw on getJSONArray("tags") and should reject.
        val event = JSONObject().apply {
            put("id", "a".repeat(64))
            put("pubkey", "b".repeat(64))
            put("created_at", 1234567890L)
            put("kind", 4)
            put("content", "test content")
            // No "tags" — causes verifyId to throw
        }
        val relayMessage = """["EVENT","sub",${event}]"""

        val parsed = NostrEvent.fromRelayMessage(relayMessage)
        assertNull("Event with unverifiable ID should be rejected", parsed)
    }

    @Test
    fun receiver_parsesEventWithSlashesInContent() {
        val received = CopyOnWriteArrayList<InboxItemEntity>()
        val latch = CountDownLatch(1)
        val receiver = NostrReceiver(
            keyManager = keyManager,
            onItem = { received.add(it); latch.countDown() },
            scope = CoroutineScope(Dispatchers.IO),
        ).also { activeReceiver = it }

        mockServer.enqueue(MockResponse().withWebSocketUpgrade(
            object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    val event = createTestEventWithPythonId(keyManager)
                    webSocket.send("""["EVENT","psbt-inbox",${event}]""")
                }
            }
        ))
        mockServer.start()

        receiver.connectToRelays(listOf(mockServer.url("/").toString().replace("http://", "ws://")))
        assertTrue("Should receive event within 5s", latch.await(5, TimeUnit.SECONDS))

        assertEquals(1, received.size)
    }

    // ===== handleMessage tests (issue #16): malformed events, invalid NIP-04, dedup =====

    @Test
    fun handleMessage_ignoresNoticeAndEoseMessages() {
        val items = sendMessagesExpectingSentinel(
            """["NOTICE","Rate limited"]""",
            """["EOSE","psbt-inbox"]""",
        )
        assertEquals("Only sentinel should arrive", 1, items.size)
        assertEquals("Sentinel", items[0].label)
    }

    @Test
    fun handleMessage_ignoresEventsWithMissingFields() {
        // EVENT with incomplete JSON — fromRelayMessage returns null
        val incomplete = """["EVENT","psbt-inbox",{"id":"${"a".repeat(64)}"}]"""
        val items = sendMessagesExpectingSentinel(incomplete)
        assertEquals("Only sentinel should arrive", 1, items.size)
        assertEquals("Sentinel", items[0].label)
    }

    @Test
    fun handleMessage_ignoresNonKind4Events() {
        val kind1Event = createEventWithKind(1)
        val items = sendMessagesExpectingSentinel(
            """["EVENT","psbt-inbox",$kind1Event]"""
        )
        assertEquals("Only sentinel should arrive", 1, items.size)
        assertEquals("Sentinel", items[0].label)
    }

    @Test
    fun handleMessage_dropsEventWithInvalidNip04Content() {
        // Event with valid ID but content is not NIP-04 format (no ?iv= separator)
        val badEvent = createEventWithRawContent("not-encrypted-content")
        val items = sendMessagesExpectingSentinel(
            """["EVENT","psbt-inbox",$badEvent]"""
        )
        assertEquals("Only sentinel should arrive", 1, items.size)
        assertEquals("Sentinel", items[0].label)
    }

    @Test
    fun handleMessage_dropsEventWhenDecryptedPayloadIsNotJson() {
        val badEvent = createEncryptedEventWithPayload(keyManager, "this is not json")
        val items = sendMessagesExpectingSentinel(
            """["EVENT","psbt-inbox",$badEvent]"""
        )
        assertEquals("Only sentinel should arrive", 1, items.size)
        assertEquals("Sentinel", items[0].label)
    }

    @Test
    fun handleMessage_dropsEventWhenPayloadMissingTxField() {
        val badEvent = createEncryptedEventWithPayload(keyManager, """{"label":"no tx here"}""")
        val items = sendMessagesExpectingSentinel(
            """["EVENT","psbt-inbox",$badEvent]"""
        )
        assertEquals("Only sentinel should arrive", 1, items.size)
        assertEquals("Sentinel", items[0].label)
    }

    @Test
    fun handleMessage_skipsPreSeededEventIds() {
        val validEvent = createTestEvent(keyManager)
        val eventId = JSONObject(validEvent).getString("id")

        val received = CopyOnWriteArrayList<InboxItemEntity>()
        val sentinelLatch = CountDownLatch(1)
        val receiver = NostrReceiver(
            keyManager = keyManager,
            onItem = {
                received.add(it)
                if (it.label == "Sentinel") sentinelLatch.countDown()
            },
            scope = CoroutineScope(Dispatchers.IO),
        ).also { activeReceiver = it }

        // Pre-seed the event ID — simulates startup seeding from Room
        receiver.seedSeenIds(setOf(eventId))

        val sentinelEvent = createTestEvent(keyManager, "Sentinel")
        mockServer.enqueue(MockResponse().withWebSocketUpgrade(
            object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    webSocket.send("""["EVENT","psbt-inbox",$validEvent]""")
                    webSocket.send("""["EVENT","psbt-inbox",$sentinelEvent]""")
                }
            }
        ))
        mockServer.start()

        receiver.connectToRelays(listOf(mockServer.url("/").toString().replace("http://", "ws://")))
        assertTrue("Sentinel should arrive within 5s", sentinelLatch.await(5, TimeUnit.SECONDS))

        assertEquals("Pre-seeded event should be skipped", 1, received.size)
        assertEquals("Sentinel", received[0].label)
    }

    // ===== Helpers =====

    /**
     * Send messages through MockWebServer, followed by a valid sentinel event.
     * Returns the list of items received. The sentinel (label="Sentinel") gates
     * the latch, so by the time it arrives all prior messages have been processed.
     */
    private fun sendMessagesExpectingSentinel(vararg messages: String): List<InboxItemEntity> {
        val received = CopyOnWriteArrayList<InboxItemEntity>()
        val sentinelLatch = CountDownLatch(1)
        val receiver = NostrReceiver(
            keyManager = keyManager,
            onItem = {
                received.add(it)
                if (it.label == "Sentinel") sentinelLatch.countDown()
            },
            scope = CoroutineScope(Dispatchers.IO),
        ).also { activeReceiver = it }

        val sentinelEvent = createTestEvent(keyManager, "Sentinel")
        mockServer.enqueue(MockResponse().withWebSocketUpgrade(
            object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    messages.forEach { webSocket.send(it) }
                    webSocket.send("""["EVENT","psbt-inbox",$sentinelEvent]""")
                }
            }
        ))
        mockServer.start()

        receiver.connectToRelays(listOf(mockServer.url("/").toString().replace("http://", "ws://")))
        assertTrue("Sentinel event should arrive within 5s", sentinelLatch.await(5, TimeUnit.SECONDS))
        return received.toList()
    }

    /**
     * Create a kind-4 event with arbitrary (non-encrypted) content and valid ID.
     * Passes ID verification but NIP-04 decryption will fail.
     */
    private fun createEventWithRawContent(content: String): String {
        val senderPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val senderPub = secp.pubkeyCreate(senderPriv)
        val senderPubXOnly = senderPub.copyOfRange(1, 33)
        val (_, receiverPub) = keyManager.getOrCreateKeyPair()

        val pubHex = senderPubXOnly.toHex()
        val recvHex = receiverPub.toHex()
        val createdAt = System.currentTimeMillis() / 1000

        val canonical = """[0,"$pubHex",$createdAt,4,[["p","$recvHex"]],"${content.replace("\"", "\\\"")}"]"""
        val id = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray()).toHex()

        return JSONObject().apply {
            put("id", id)
            put("pubkey", pubHex)
            put("created_at", createdAt)
            put("kind", 4)
            put("tags", JSONArray().apply {
                put(JSONArray().apply { put("p"); put(recvHex) })
            })
            put("content", content)
            put("sig", "0".repeat(128))
        }.toString()
    }

    /**
     * Create a kind-4 event with NIP-04 encrypted custom plaintext and valid ID.
     * Decryption succeeds but payload parsing may fail depending on plaintext.
     */
    private fun createEncryptedEventWithPayload(km: NostrKeyManager, plaintext: String): String {
        val senderPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val senderPub = secp.pubkeyCreate(senderPriv)
        val senderPubXOnly = senderPub.copyOfRange(1, 33)
        val (_, receiverPub) = km.getOrCreateKeyPair()

        val sharedSecret = Nip04.computeSharedSecret(senderPriv, receiverPub)
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray())
        val content = Base64.encodeToString(ciphertext, Base64.NO_WRAP) +
            "?iv=" + Base64.encodeToString(iv, Base64.NO_WRAP)

        val pubHex = senderPubXOnly.toHex()
        val recvHex = receiverPub.toHex()
        val createdAt = System.currentTimeMillis() / 1000

        val canonical = """[0,"$pubHex",$createdAt,4,[["p","$recvHex"]],"${content.replace("\"", "\\\"")}"]"""
        val id = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray()).toHex()

        return JSONObject().apply {
            put("id", id)
            put("pubkey", pubHex)
            put("created_at", createdAt)
            put("kind", 4)
            put("tags", JSONArray().apply {
                put(JSONArray().apply { put("p"); put(recvHex) })
            })
            put("content", content)
            put("sig", "0".repeat(128))
        }.toString()
    }

    /**
     * Create an event with valid ID and specified kind (non-kind-4).
     * Passes ID verification but is filtered by the kind check.
     */
    private fun createEventWithKind(kind: Int): String {
        val senderPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val senderPub = secp.pubkeyCreate(senderPriv)
        val senderPubXOnly = senderPub.copyOfRange(1, 33)
        val (_, receiverPub) = keyManager.getOrCreateKeyPair()

        val pubHex = senderPubXOnly.toHex()
        val recvHex = receiverPub.toHex()
        val createdAt = System.currentTimeMillis() / 1000
        val content = "Hello world"

        val canonical = """[0,"$pubHex",$createdAt,$kind,[["p","$recvHex"]],"$content"]"""
        val id = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray()).toHex()

        return JSONObject().apply {
            put("id", id)
            put("pubkey", pubHex)
            put("created_at", createdAt)
            put("kind", kind)
            put("tags", JSONArray().apply {
                put(JSONArray().apply { put("p"); put(recvHex) })
            })
            put("content", content)
            put("sig", "0".repeat(128))
        }.toString()
    }

    /**
     * Create a test event where the ID is computed like Python/aionostr does it —
     * without escaping forward slashes. This is what real relay events look like.
     */
    private fun createTestEventWithPythonId(km: NostrKeyManager): String {
        val senderPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val senderPub = secp.pubkeyCreate(senderPriv)
        val senderPubXOnly = senderPub.copyOfRange(1, 33)

        val (_, receiverPub) = km.getOrCreateKeyPair()

        val payload = """{"tx":"cHNidA==","label":"Slash test"}"""
        val sharedSecret = Nip04.computeSharedSecret(senderPriv, receiverPub)
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(payload.toByteArray())
        val content = Base64.encodeToString(ciphertext, Base64.NO_WRAP) +
            "?iv=" + Base64.encodeToString(iv, Base64.NO_WRAP)

        val createdAt = System.currentTimeMillis() / 1000
        val pubHex = senderPubXOnly.toHex()
        val recvHex = receiverPub.toHex()

        // Compute event ID like Python does — no \/ escaping
        val canonical = """[0,"$pubHex",$createdAt,4,[["p","$recvHex"]],"${content.replace("\"", "\\\"")}"]"""
        val id = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray()).toHex()

        return JSONObject().apply {
            put("id", id)
            put("pubkey", pubHex)
            put("created_at", createdAt)
            put("kind", 4)
            put("tags", JSONArray().apply {
                put(JSONArray().apply { put("p"); put(recvHex) })
            })
            put("content", content)
            put("sig", "0".repeat(128))
        }.toString()
    }

    /**
     * Create a valid NIP-04 encrypted Nostr kind 4 event targeting the given keyManager.
     */
    private fun createTestEvent(km: NostrKeyManager, label: String = "Test payment"): String {
        val senderPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val senderPub = secp.pubkeyCreate(senderPriv)            // 65-byte uncompressed
        val senderPubXOnly = senderPub.copyOfRange(1, 33)        // 32-byte x-coordinate

        val (_, receiverPub) = km.getOrCreateKeyPair()

        // NIP-04 encrypt
        val payload = """{"tx": "cHNidA==", "label": "$label"}"""
        val sharedSecret = Nip04.computeSharedSecret(senderPriv, receiverPub)
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(payload.toByteArray())
        val content = Base64.encodeToString(ciphertext, Base64.NO_WRAP) +
            "?iv=" + Base64.encodeToString(iv, Base64.NO_WRAP)

        val createdAt = System.currentTimeMillis() / 1000

        // Compute event ID per NIP-01 — canonical JSON without \/ escaping
        val pubHex = senderPubXOnly.toHex()
        val recvHex = receiverPub.toHex()
        val canonical = """[0,"$pubHex",$createdAt,4,[["p","$recvHex"]],"${content.replace("\"", "\\\"")}"]"""
        val id = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray()).toHex()

        return JSONObject().apply {
            put("id", id)
            put("pubkey", senderPubXOnly.toHex())
            put("created_at", createdAt)
            put("kind", 4)
            put("tags", JSONArray().apply {
                put(JSONArray().apply { put("p"); put(receiverPub.toHex()) })
            })
            put("content", content)
            put("sig", "0".repeat(128))
        }.toString()
    }
}

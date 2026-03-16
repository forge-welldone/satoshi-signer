package com.remotesigner.nostr

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Connects to Nostr relays via WebSocket, subscribes for kind 4 events
 * tagged with our pubkey, decrypts NIP-04 content, and delivers InboxItems.
 */
class NostrReceiver(
    private val keyManager: NostrKeyManager,
    private val onItem: (InboxItem) -> Unit,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "NostrReceiver"
        val DEFAULT_RELAYS = listOf(
            "wss://nos.lol",
            "wss://relay.damus.io",
            "wss://relay.primal.net",
        )
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val webSockets = ConcurrentHashMap<String, WebSocket>()
    private val seenIds = mutableSetOf<String>()
    private val backoffMs = ConcurrentHashMap<String, Long>()
    private val _relayStatuses = MutableStateFlow<Map<String, RelayStatus>>(emptyMap())
    @Volatile private var active = false

    val connectedCount: StateFlow<Int> = _relayStatuses.map { statuses ->
        statuses.count { it.value == RelayStatus.CONNECTED }
    }.stateIn(scope, SharingStarted.Eagerly, 0)
    val relayStatuses: StateFlow<Map<String, RelayStatus>> = _relayStatuses.asStateFlow()

    fun connect() = connectToRelays(DEFAULT_RELAYS)

    fun connectToRelays(relays: List<String>) {
        active = true
        _relayStatuses.value = relays.associateWith { RelayStatus.CONNECTING }
        relays.forEach { connectToRelay(it) }
    }

    private fun connectToRelay(url: String) {
        if (!active) return
        _relayStatuses.update { it + (url to RelayStatus.CONNECTING) }
        val request = Request.Builder().url(url).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to $url")
                _relayStatuses.update { it + (url to RelayStatus.CONNECTED) }
                backoffMs[url] = 1000L
                sendSubscription(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "onMessage (${text.length} chars): ${text.take(120)}...")
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Connection failed to $url: ${t.message}")
                webSockets.remove(url)
                _relayStatuses.update { it + (url to RelayStatus.ERROR) }
                reconnect(url)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closed $url: $reason")
                webSockets.remove(url)
                _relayStatuses.update { it + (url to RelayStatus.DISCONNECTED) }
            }
        })
        webSockets[url] = ws
    }

    private fun sendSubscription(ws: WebSocket) {
        val hexPubkey = keyManager.getHexPubkey()
        val since = System.currentTimeMillis() / 1000 - 86400
        val req = """["REQ","psbt-inbox",{"kinds":[4],"#p":["$hexPubkey"],"since":$since}]"""
        Log.d(TAG, "Subscribing with pubkey=${hexPubkey.take(16)}... since=$since")
        ws.send(req)
    }

    private fun handleMessage(text: String) {
        val event = NostrEvent.fromRelayMessage(text)
        if (event == null) {
            if (text.startsWith("[\"EVENT\"")) {
                Log.w(TAG, "EVENT failed to parse — likely ID verification mismatch")
            }
            return
        }
        Log.d(TAG, "Parsed event: kind=${event.kind} id=${event.id.take(8)}... from=${event.pubkey.take(8)}...")
        if (event.kind != 4) return

        synchronized(seenIds) {
            if (!seenIds.add(event.id)) return // deduplicate
        }

        try {
            val (privkey, _) = keyManager.getOrCreateKeyPair()
            val plaintext = Nip04.decrypt(privkey, event.pubkey.hexToByteArray(), event.content)
            val payload = JSONObject(plaintext)

            val psbtBase64 = payload.getString("tx")
            val psbtBytes = Base64.decode(psbtBase64, Base64.DEFAULT)
            val label = payload.optString("label", "Unsigned transaction")

            val senderNpub = Bech32.npubEncode(event.pubkey.hexToByteArray())

            val item = InboxItem(
                id = event.id,
                psbtBytes = psbtBytes,
                label = label,
                senderNpub = truncateNpub(senderNpub),
                receivedAt = event.createdAt,
            )
            onItem(item)
        } catch (e: Exception) {
            Log.d(TAG, "Dropping malformed event ${event.id}: ${e.message}")
        }
    }

    private fun reconnect(url: String) {
        if (!active) return // Don't reconnect after intentional disconnect
        val delay = backoffMs.getOrPut(url) { 1000L }
        backoffMs[url] = (delay * 2).coerceAtMost(60_000L)
        scope.launch {
            delay(delay)
            connectToRelay(url)
        }
    }

    fun disconnect() {
        active = false
        webSockets.values.forEach { it.close(1000, "App stopped") }
        webSockets.clear()
        _relayStatuses.value = emptyMap()
    }
}

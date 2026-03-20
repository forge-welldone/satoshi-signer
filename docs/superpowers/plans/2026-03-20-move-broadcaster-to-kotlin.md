# Move Broadcaster to Kotlin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Python `broadcaster.py` with a Kotlin `TransactionBroadcaster` class using OkHttp, removing one bridge method and shrinking the Python surface area.

**Architecture:** New `TransactionBroadcaster` in `com.remotesigner.broadcast` package — a blocking class injected into ViewModel. Same retry logic, endpoint map, and validation as the Python original. `BroadcastResult` data class moves from `BridgeModels.kt` to the new package.

**Tech Stack:** OkHttp 4.12.0 (already in project), MockWebServer for testing, mockk for ViewModel test mocking.

**Spec:** `docs/superpowers/specs/2026-03-20-move-broadcaster-to-kotlin-design.md`

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `app/src/main/kotlin/com/remotesigner/broadcast/TransactionBroadcaster.kt` | Broadcaster class + `BroadcastResult` data class |
| Create | `app/src/test/kotlin/com/remotesigner/broadcast/TransactionBroadcasterTest.kt` | JVM unit tests with MockWebServer |
| Modify | `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt:73-81,330-363` | Add `TransactionBroadcaster` param, rewire `broadcast()` |
| Modify | `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModelFactory.kt:18-34` | Create and inject `TransactionBroadcaster` |
| Modify | `app/src/test/kotlin/com/remotesigner/viewmodel/SignerViewModelTest.kt:1-10,48-78,301-368,611-701` | Mock `TransactionBroadcaster` instead of bridge |
| Modify | `app/src/main/kotlin/com/remotesigner/bridge/PythonBridgeInterface.kt:22` | Remove `broadcast()` method |
| Modify | `app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt:19,39-42,78-85` | Remove broadcast-related code |
| Modify | `app/src/main/kotlin/com/remotesigner/bridge/BridgeModels.kt:34-39` | Remove `BroadcastResult` |
| Modify | `app/src/androidTest/kotlin/com/remotesigner/data/InboxRepositoryTest.kt:5,44` | Remove `broadcast()` from `FakePythonBridge`, remove `BroadcastResult` import |
| Modify | `app/build.gradle.kts:91-92` | Add `testImplementation(libs.okhttp.mockwebserver)` |
| Modify | `app/src/main/python/remotesigner/validate_deps.py:88-93` | Remove `requests` import check |
| Delete | `app/src/main/python/remotesigner/broadcaster.py` | Python broadcaster (replaced) |
| Delete | `tests/test_broadcaster.py` | Python broadcaster tests (replaced) |
| Modify | `CLAUDE.md` | Update architecture docs |

---

### Task 1: Add MockWebServer to JVM test dependencies

**Files:**
- Modify: `app/build.gradle.kts:90-92`

- [ ] **Step 1: Add testImplementation dependency**

In `app/build.gradle.kts`, after line 92 (`testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")`), add:

```kotlin
    testImplementation(libs.okhttp.mockwebserver)
```

- [ ] **Step 2: Verify Gradle sync**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew testDebugUnitTest --dry-run 2>&1 | tail -5`

Expected: BUILD SUCCESSFUL (dry run resolves dependencies)

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: add MockWebServer to JVM test dependencies"
```

---

### Task 2: Create TransactionBroadcaster with validation tests (TDD)

**Files:**
- Create: `app/src/test/kotlin/com/remotesigner/broadcast/TransactionBroadcasterTest.kt`
- Create: `app/src/main/kotlin/com/remotesigner/broadcast/TransactionBroadcaster.kt`

- [ ] **Step 1: Write validation tests**

Create `app/src/test/kotlin/com/remotesigner/broadcast/TransactionBroadcasterTest.kt`:

```kotlin
package com.remotesigner.broadcast

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew testDebugUnitTest --tests "com.remotesigner.broadcast.TransactionBroadcasterTest" 2>&1 | tail -10`

Expected: FAIL — class `TransactionBroadcaster` not found

- [ ] **Step 3: Write BroadcastResult and TransactionBroadcaster with validation only**

Create `app/src/main/kotlin/com/remotesigner/broadcast/TransactionBroadcaster.kt`:

```kotlin
package com.remotesigner.broadcast

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class BroadcastResult(
    val status: String,
    val txid: String? = null,
    val message: String? = null,
    val rawHex: String? = null,
)

class TransactionBroadcaster(private val client: OkHttpClient) {

    companion object {
        private val ENDPOINTS = mapOf(
            "main" to listOf(
                "https://mempool.space/api/tx",
                "https://blockstream.info/api/tx",
            ),
            "test" to listOf(
                "https://mempool.space/testnet/api/tx",
                "https://blockstream.info/testnet/api/tx",
            ),
            "testnet3" to listOf(
                "https://mempool.space/testnet/api/tx",
                "https://blockstream.info/testnet/api/tx",
            ),
            "testnet4" to listOf(
                "https://mempool.space/testnet4/api/tx",
            ),
            "signet" to listOf(
                "https://mempool.space/signet/api/tx",
            ),
        )
        private const val MAX_TX_BYTES = 400_000
        private val TEXT_PLAIN = "text/plain".toMediaType()
    }

    fun broadcast(rawHex: String, network: String): BroadcastResult {
        validate(rawHex, network)
        // HTTP logic in next step
        return BroadcastResult(status = "error", message = "Not implemented", rawHex = rawHex)
    }

    private fun validate(rawHex: String, network: String) {
        require(network in ENDPOINTS) {
            "Unknown network: '$network'. Valid: ${ENDPOINTS.keys.sorted().joinToString(", ")}"
        }
        require(rawHex.isNotEmpty()) { "raw_hex must be a non-empty string" }
        require(rawHex.length % 2 == 0) { "raw_hex has odd length — not valid hex" }
        require(rawHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "raw_hex contains non-hex characters"
        }
        require(rawHex.length / 2 <= MAX_TX_BYTES) {
            "Transaction too large: ${rawHex.length / 2} bytes (max $MAX_TX_BYTES)"
        }
    }
}
```

- [ ] **Step 4: Run validation tests — verify they pass**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew testDebugUnitTest --tests "com.remotesigner.broadcast.TransactionBroadcasterTest" 2>&1 | tail -10`

Expected: All 7 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/broadcast/TransactionBroadcaster.kt \
       app/src/test/kotlin/com/remotesigner/broadcast/TransactionBroadcasterTest.kt
git commit -m "feat: add TransactionBroadcaster with validation (TDD)"
```

---

### Task 3: Add HTTP broadcast logic with tests (TDD)

**Files:**
- Modify: `app/src/test/kotlin/com/remotesigner/broadcast/TransactionBroadcasterTest.kt`
- Modify: `app/src/main/kotlin/com/remotesigner/broadcast/TransactionBroadcaster.kt`

The broadcaster must support injectable base URLs so tests can point at MockWebServer instead of production endpoints. The approach: add a `@VisibleForTesting` internal constructor that takes endpoint overrides.

- [ ] **Step 1: Write HTTP broadcast tests**

Add to `TransactionBroadcasterTest.kt`:

```kotlin
import okhttp3.mockwebserver.MockResponse

class TransactionBroadcasterTest {
    // ... existing setUp/tearDown/validation tests ...

    private fun broadcasterWithServer(): TransactionBroadcaster {
        val baseUrl = server.url("/api/tx").toString()
        val secondaryUrl = server.url("/secondary/api/tx").toString()
        return TransactionBroadcaster(
            client = OkHttpClient(),
            endpointOverrides = mapOf(
                "main" to listOf(baseUrl, secondaryUrl),
                "test" to listOf(baseUrl, secondaryUrl),
                "testnet3" to listOf(baseUrl, secondaryUrl),
                "testnet4" to listOf(baseUrl),
                "signet" to listOf(baseUrl),
            ),
        )
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
        // Primary: 2 attempts fail
        server.enqueue(MockResponse().setResponseCode(500).setBody("error"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("error"))
        // Secondary: succeeds
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
        // Primary: 2 attempts
        server.enqueue(MockResponse().setResponseCode(500).setBody("server error 1"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("server error 2"))
        // Secondary: 2 attempts
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
        // Test that the production endpoint map has correct paths.
        // We use the real broadcaster (not server-based) but just check
        // the ENDPOINTS map indirectly by checking the companion object.
        // Instead, we verify with MockWebServer by checking request paths.
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
```

- [ ] **Step 2: Run tests — verify new tests fail**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew testDebugUnitTest --tests "com.remotesigner.broadcast.TransactionBroadcasterTest" 2>&1 | tail -15`

Expected: New HTTP tests FAIL (no `endpointOverrides` param yet, broadcast returns stub)

- [ ] **Step 3: Implement HTTP broadcast logic**

Update `TransactionBroadcaster.kt` — add the `endpointOverrides` parameter and implement the retry loop:

```kotlin
package com.remotesigner.broadcast

import androidx.annotation.VisibleForTesting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class BroadcastResult(
    val status: String,
    val txid: String? = null,
    val message: String? = null,
    val rawHex: String? = null,
)

class TransactionBroadcaster @VisibleForTesting internal constructor(
    private val client: OkHttpClient,
    private val endpoints: Map<String, List<String>>,
) {
    constructor(client: OkHttpClient) : this(client, PRODUCTION_ENDPOINTS)

    companion object {
        private val PRODUCTION_ENDPOINTS = mapOf(
            "main" to listOf(
                "https://mempool.space/api/tx",
                "https://blockstream.info/api/tx",
            ),
            "test" to listOf(
                "https://mempool.space/testnet/api/tx",
                "https://blockstream.info/testnet/api/tx",
            ),
            "testnet3" to listOf(
                "https://mempool.space/testnet/api/tx",
                "https://blockstream.info/testnet/api/tx",
            ),
            "testnet4" to listOf(
                "https://mempool.space/testnet4/api/tx",
            ),
            "signet" to listOf(
                "https://mempool.space/signet/api/tx",
            ),
        )
        private const val MAX_TX_BYTES = 400_000
        private const val MAX_ATTEMPTS = 2
        private val TEXT_PLAIN = "text/plain".toMediaType()
    }

    fun broadcast(rawHex: String, network: String): BroadcastResult {
        validate(rawHex, network)

        val urls = endpoints[network]!!
        var lastError = ""

        for (url in urls) {
            repeat(MAX_ATTEMPTS) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .post(rawHex.toRequestBody(TEXT_PLAIN))
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.code == 200) {
                            val txid = response.body?.string()?.trim() ?: ""
                            return BroadcastResult(status = "ok", txid = txid)
                        }
                        val body = response.body?.string()?.trim() ?: ""
                        lastError = "$url: HTTP ${response.code} - $body"
                    }
                } catch (e: IOException) {
                    lastError = "$url: ${e.message}"
                }
            }
        }

        return BroadcastResult(status = "error", message = lastError, rawHex = rawHex)
    }

    private fun validate(rawHex: String, network: String) {
        require(network in endpoints) {
            "Unknown network: '$network'. Valid: ${endpoints.keys.sorted().joinToString(", ")}"
        }
        require(rawHex.isNotEmpty()) { "raw_hex must be a non-empty string" }
        require(rawHex.length % 2 == 0) { "raw_hex has odd length — not valid hex" }
        require(rawHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "raw_hex contains non-hex characters"
        }
        require(rawHex.length / 2 <= MAX_TX_BYTES) {
            "Transaction too large: ${rawHex.length / 2} bytes (max $MAX_TX_BYTES)"
        }
    }
}
```

Note: The test constructor uses `endpointOverrides` parameter name in the test helper, but the actual constructor parameter is `endpoints`. Update the test helper to use the named parameter:

```kotlin
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
```

- [ ] **Step 4: Run all broadcaster tests — verify they pass**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew testDebugUnitTest --tests "com.remotesigner.broadcast.TransactionBroadcasterTest" 2>&1 | tail -15`

Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/broadcast/TransactionBroadcaster.kt \
       app/src/test/kotlin/com/remotesigner/broadcast/TransactionBroadcasterTest.kt
git commit -m "feat: implement HTTP broadcast with retry and fallback (TDD)"
```

---

### Task 4: Integrate TransactionBroadcaster into ViewModel

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt:73-81,330-363`
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModelFactory.kt:14-35`
- Modify: `app/src/test/kotlin/com/remotesigner/viewmodel/SignerViewModelTest.kt`

- [ ] **Step 1: Update ViewModel tests to use TransactionBroadcaster mock**

In `SignerViewModelTest.kt`:

1. Replace import `com.remotesigner.bridge.BroadcastResult` with `com.remotesigner.broadcast.BroadcastResult`
2. Add import `com.remotesigner.broadcast.TransactionBroadcaster`
3. Add field: `private lateinit var broadcaster: TransactionBroadcaster`
4. In `setUp()`, add: `broadcaster = mockk()`
5. Update VM construction to pass `broadcaster`:

```kotlin
vm = SignerViewModel(
    app, pythonBridge, contactRepo, inboxRepo,
    orchestrator, trezorUsb, keyManager, broadcaster,
)
```

6. In all broadcast tests (lines 301-368 and 611-701), replace `pythonBridge.broadcast(...)` with `broadcaster.broadcast(...)`:

Search for: `coEvery { pythonBridge.broadcast(`
Replace with: `every { broadcaster.broadcast(`

(Use `every` not `coEvery` because `broadcast()` is a regular blocking function, not a suspend function.)

- [ ] **Step 2: Run ViewModel tests — verify broadcast tests fail**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew testDebugUnitTest --tests "com.remotesigner.viewmodel.SignerViewModelTest" 2>&1 | tail -15`

Expected: FAIL — `SignerViewModel` constructor doesn't accept `TransactionBroadcaster` yet

- [ ] **Step 3: Update SignerViewModel to accept and use TransactionBroadcaster**

In `SignerViewModel.kt`:

1. Add import: `import com.remotesigner.broadcast.TransactionBroadcaster`
2. Add constructor parameter after `keyManager`:

```kotlin
class SignerViewModel(
    application: Application,
    private val pythonBridge: PythonBridgeInterface,
    private val contactRepository: ContactRepository,
    private val inboxRepository: InboxRepository,
    private val signingOrchestrator: SigningOrchestrator,
    val trezorUsb: TrezorUsbManager,
    val keyManager: NostrKeyManager,
    private val broadcaster: TransactionBroadcaster,
) : AndroidViewModel(application) {
```

3. Update `broadcast()` method (lines 330-363) — replace `pythonBridge.broadcast()` with `broadcaster.broadcast()`:

```kotlin
fun broadcast(targetNetwork: String) {
    val state = _state.value
    if (state !is AppState.Result || state.rawHex == null) return

    _state.value = state.copy(broadcastStatus = "Broadcasting...")

    viewModelScope.launch {
        try {
            val result = withContext(Dispatchers.IO) {
                broadcaster.broadcast(state.rawHex, targetNetwork)
            }

            if (result.status == "ok") {
                _state.value = state.copy(
                    txid = result.txid,
                    broadcastStatus = "Broadcast successful",
                    network = targetNetwork,
                )
                val inboxId = currentSigningInboxId
                if (inboxId != null && result.txid != null) {
                    inboxRepository.updateBroadcast(inboxId, InboxStatus.BROADCAST, result.txid, targetNetwork)
                }
            } else {
                _state.value = state.copy(
                    broadcastStatus = "Broadcast failed: ${result.message}",
                )
            }
        } catch (e: Exception) {
            _state.value = state.copy(
                broadcastStatus = "Broadcast failed: ${e.message}",
            )
        }
    }
}
```

4. Remove the `BroadcastResult` import from `com.remotesigner.bridge` if it was imported (it may have been imported via `PythonBridgeInterface`'s return type — but after we remove `broadcast()` from the interface in Task 5, that import goes away).

- [ ] **Step 4: Update SignerViewModelFactory**

In `SignerViewModelFactory.kt`:

1. Add imports:
```kotlin
import com.remotesigner.broadcast.TransactionBroadcaster
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
```

2. Create broadcaster and pass to ViewModel:

```kotlin
override fun <T : ViewModel> create(modelClass: Class<T>): T {
    val db = AppDatabase.getInstance(application)
    val pythonBridge = PythonBridge()
    val contactRepo = ContactRepository(db.contactDao())
    val inboxRepo = InboxRepository(db.inboxDao(), pythonBridge)
    val trezorUsb = TrezorUsbManager(application)
    val orchestrator = SigningOrchestrator(pythonBridge, trezorUsb)
    val broadcastClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    val broadcaster = TransactionBroadcaster(broadcastClient)
    return SignerViewModel(
        application = application,
        pythonBridge = pythonBridge,
        contactRepository = contactRepo,
        inboxRepository = inboxRepo,
        signingOrchestrator = orchestrator,
        trezorUsb = trezorUsb,
        keyManager = NostrKeyManager(application),
        broadcaster = broadcaster,
    ) as T
}
```

- [ ] **Step 5: Run ViewModel tests — verify they pass**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew testDebugUnitTest --tests "com.remotesigner.viewmodel.SignerViewModelTest" 2>&1 | tail -15`

Expected: All tests PASS

- [ ] **Step 6: Run all JVM tests to check nothing else broke**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew testDebugUnitTest 2>&1 | tail -15`

Expected: All tests PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt \
       app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModelFactory.kt \
       app/src/test/kotlin/com/remotesigner/viewmodel/SignerViewModelTest.kt
git commit -m "refactor: wire TransactionBroadcaster into ViewModel replacing bridge"
```

---

### Task 5: Remove broadcast from Python bridge

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/bridge/PythonBridgeInterface.kt:22`
- Modify: `app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt:19,39-42,78-85`
- Modify: `app/src/main/kotlin/com/remotesigner/bridge/BridgeModels.kt:34-39`

- [ ] **Step 1: Remove broadcast() from PythonBridgeInterface**

In `PythonBridgeInterface.kt`, remove line 22:
```kotlin
    fun broadcast(rawHex: String, network: String = "main"): BroadcastResult
```

- [ ] **Step 2: Remove broadcast code from PythonBridge**

In `PythonBridge.kt`:
1. Remove line 19: `private val broadcasterModule: PyObject = py.getModule("remotesigner.broadcaster")`
2. Remove the `broadcast()` override (lines 39-42):
```kotlin
    override fun broadcast(rawHex: String, network: String): BroadcastResult {
        val result = broadcasterModule.callAttr("broadcast_transaction", rawHex, network)
        return toBroadcastResult(pyDictToMap(result))
    }
```
3. Remove `toBroadcastResult()` (lines 78-85):
```kotlin
    private fun toBroadcastResult(map: Map<String, Any?>): BroadcastResult {
        return BroadcastResult(
            status = map["status"]?.toString() ?: "error",
            txid = map["txid"]?.toString(),
            message = map["message"]?.toString(),
            rawHex = map["raw_hex"]?.toString(),
        )
    }
```

- [ ] **Step 3: Remove BroadcastResult from BridgeModels**

In `BridgeModels.kt`, remove lines 34-39:
```kotlin
data class BroadcastResult(
    val status: String,
    val txid: String? = null,
    val message: String? = null,
    val rawHex: String? = null,
)
```

- [ ] **Step 4: Update FakePythonBridge in InboxRepositoryTest**

In `app/src/androidTest/kotlin/com/remotesigner/data/InboxRepositoryTest.kt`:

1. Remove line 5: `import com.remotesigner.bridge.BroadcastResult`
2. Remove line 44: `override fun broadcast(rawHex: String, network: String) = BroadcastResult(status = "ok")`

(The `FakePythonBridge` implements `PythonBridgeInterface` — since `broadcast()` is no longer in the interface, the override must be removed or the code won't compile.)

- [ ] **Step 5: Run all JVM tests**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew testDebugUnitTest 2>&1 | tail -15`

Expected: All tests PASS (ViewModel tests now use the new broadcaster, no code references old bridge broadcast)

- [ ] **Step 6: Verify compilation**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew assembleDebug 2>&1 | tail -10`

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/bridge/PythonBridgeInterface.kt \
       app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt \
       app/src/main/kotlin/com/remotesigner/bridge/BridgeModels.kt \
       app/src/androidTest/kotlin/com/remotesigner/data/InboxRepositoryTest.kt
git commit -m "refactor: remove broadcast from Python bridge interface"
```

---

### Task 6: Remove Python broadcaster files and clean up validate_deps

**Files:**
- Delete: `app/src/main/python/remotesigner/broadcaster.py`
- Delete: `tests/test_broadcaster.py`
- Modify: `app/src/main/python/remotesigner/validate_deps.py:88-93`

- [ ] **Step 1: Delete Python broadcaster**

```bash
rm app/src/main/python/remotesigner/broadcaster.py
rm tests/test_broadcaster.py
```

- [ ] **Step 2: Remove requests check from validate_deps.py**

In `validate_deps.py`, remove lines 88-93:
```python
    # 6. Test requests
    try:
        import requests
        results["requests"] = {"status": "ok", "detail": "requests imported"}
    except Exception as e:
        results["requests"] = {"status": "error", "detail": str(e)}
```

- [ ] **Step 3: Verify Python tests still pass (remaining tests should not import broadcaster)**

Run: `cd /Users/sasha/Projects/remote_signer && source .venv/bin/activate && python -m pytest tests/ -v 2>&1 | tail -15`

Expected: All remaining Python tests PASS (broadcaster tests gone, other tests unaffected)

- [ ] **Step 4: Verify Android build still works**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew assembleDebug 2>&1 | tail -10`

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git rm app/src/main/python/remotesigner/broadcaster.py \
      tests/test_broadcaster.py
git add app/src/main/python/remotesigner/validate_deps.py
git commit -m "chore: remove Python broadcaster and tests (replaced by Kotlin)"
```

---

### Task 7: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update architecture references**

In `CLAUDE.md`, make these changes:

1. In the architecture diagram, remove the broadcasting reference from `PythonBridge` description. The `PythonBridge` line should focus on PSBT parsing and signing only.

2. In **"Python-Kotlin error convention"** section, remove references to `broadcast_transaction` returning status dicts. The broadcasting now uses Kotlin's `TransactionBroadcaster` which throws `IllegalArgumentException` for validation and returns `BroadcastResult` for outcomes.

3. In **"PythonBridge uses JSON round-trip with typed models"** section, remove the `broadcast()` / `toBroadcastResult()` references. Update to reflect that `PythonBridge` now only handles `parsePsbt()` and `signPsbt()`.

4. In **"Testnet variant selection at broadcast time"** section, replace references to `broadcaster.py` with `TransactionBroadcaster`. Update the data flow: `ViewModel.broadcast()` → `TransactionBroadcaster.broadcast()` → OkHttp POST.

5. In **Source Layout**, remove "broadcaster" from the Python modules description. Add the new `broadcast/` package to the Kotlin source layout.

6. In **Key Dependencies**, the `requests` pip dependency description should note it's now only needed transitively by trezorlib, not used directly by the app.

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md for Kotlin broadcaster migration"
```

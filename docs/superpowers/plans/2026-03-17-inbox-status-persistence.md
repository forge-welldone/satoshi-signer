# Inbox Status, Persistence & Expiry Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make inbox item status visually prominent, persist inbox state to survive app restarts, and auto-expire old items.

**Architecture:** Add `rawHex`, `txid`, `network` fields to `InboxItem` and a new `BROADCAST` status. New `InboxStore` class handles JSON file persistence. ViewModel loads on init, debounce-saves on changes, flushes on destroy. Expiry runs on init (24h for pending/failed/signing, 7d for signed/broadcast). UI gets prominent status chips and tappable signed/broadcast cards that navigate to the existing Result screen.

**Tech Stack:** Kotlin, Compose Material 3, org.json (Android SDK), coroutines Flow with debounce

**Spec:** `docs/superpowers/specs/2026-03-17-inbox-status-persistence-design.md`

---

## Chunk 1: Model, Persistence & Expiry (data layer)

### Task 1: Extend InboxItem model

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/nostr/NostrInbox.kt`

- [ ] **Step 1: Add BROADCAST to InboxStatus and new fields to InboxItem**

```kotlin
// NostrInbox.kt line 3 — add BROADCAST
enum class InboxStatus { PENDING, SIGNING, SIGNED, BROADCAST, FAILED }
```

```kotlin
// NostrInbox.kt lines 7-14 — add rawHex, txid, network fields
data class InboxItem(
    val id: String,
    val psbtBytes: ByteArray,
    val label: String,
    val amount: String = "",
    val senderNpub: String,
    val receivedAt: Long,
    val status: InboxStatus = InboxStatus.PENDING,
    val rawHex: String? = null,
    val txid: String? = null,
    val network: String = "main",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InboxItem) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}
```

- [ ] **Step 2: Build and verify compilation**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (existing code that references InboxItem compiles because new fields have defaults)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/nostr/NostrInbox.kt
git commit -m "feat(inbox): add BROADCAST status and rawHex/txid/network fields to InboxItem"
```

---

### Task 2: InboxStore — JSON persistence with tests

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/nostr/InboxStore.kt`
- Create: `app/src/test/kotlin/com/remotesigner/nostr/InboxStoreTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/remotesigner/nostr/InboxStoreTest.kt`:

```kotlin
package com.remotesigner.nostr

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InboxStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var file: File
    private lateinit var store: InboxStore

    @Before
    fun setUp() {
        file = File(tempFolder.root, "inbox.json")
        store = InboxStore(file)
    }

    @Test
    fun roundTrip_pendingItem() {
        val item = InboxItem(
            id = "abc123",
            psbtBytes = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()),
            label = "Payment to Alice",
            amount = "0.00500000 BTC",
            senderNpub = "npub1abc...xyz",
            receivedAt = 1710700000L,
            status = InboxStatus.PENDING,
        )
        store.save(listOf(item))
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals("abc123", loaded[0].id)
        assertArrayEquals(item.psbtBytes, loaded[0].psbtBytes)
        assertEquals("Payment to Alice", loaded[0].label)
        assertEquals("0.00500000 BTC", loaded[0].amount)
        assertEquals("npub1abc...xyz", loaded[0].senderNpub)
        assertEquals(1710700000L, loaded[0].receivedAt)
        assertEquals(InboxStatus.PENDING, loaded[0].status)
        assertNull(loaded[0].rawHex)
        assertNull(loaded[0].txid)
        assertEquals("main", loaded[0].network)
    }

    @Test
    fun roundTrip_broadcastItem() {
        val item = InboxItem(
            id = "def456",
            psbtBytes = byteArrayOf(0x01, 0x02),
            label = "Payment to Bob",
            amount = "0.10000000 BTC",
            senderNpub = "npub1def...uvw",
            receivedAt = 1710700000L,
            status = InboxStatus.BROADCAST,
            rawHex = "0200000001deadbeef",
            txid = "a1b2c3d4e5f6",
            network = "test",
        )
        store.save(listOf(item))
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals(InboxStatus.BROADCAST, loaded[0].status)
        assertEquals("0200000001deadbeef", loaded[0].rawHex)
        assertEquals("a1b2c3d4e5f6", loaded[0].txid)
        assertEquals("test", loaded[0].network)
    }

    @Test
    fun missingFile_returnsEmptyList() {
        assertFalse(file.exists())
        val loaded = store.load()
        assertEquals(emptyList<InboxItem>(), loaded)
    }

    @Test
    fun corruptFile_returnsEmptyList() {
        file.writeText("not json at all {{{")
        val loaded = store.load()
        assertEquals(emptyList<InboxItem>(), loaded)
    }

    @Test
    fun multipleItems_roundTrip() {
        val items = listOf(
            InboxItem(
                id = "a1", psbtBytes = byteArrayOf(1), label = "Tx1",
                senderNpub = "npub1a", receivedAt = 100L,
            ),
            InboxItem(
                id = "a2", psbtBytes = byteArrayOf(2), label = "Tx2",
                senderNpub = "npub1b", receivedAt = 200L,
                status = InboxStatus.SIGNED, rawHex = "deadbeef", network = "test",
            ),
        )
        store.save(items)
        val loaded = store.load()
        assertEquals(2, loaded.size)
        assertEquals("a1", loaded[0].id)
        assertEquals("a2", loaded[1].id)
        assertEquals(InboxStatus.SIGNED, loaded[1].status)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.remotesigner.nostr.InboxStoreTest" 2>&1 | tail -5`
Expected: FAIL — `InboxStore` class doesn't exist

- [ ] **Step 3: Add org.json test dependency to build.gradle.kts**

`org.json.JSONArray`/`JSONObject` are bundled with Android but not available in JVM unit tests. Add to `app/build.gradle.kts` in the `dependencies` block:

```kotlin
testImplementation("org.json:json:20231013")
```

- [ ] **Step 4: Implement InboxStore**

Create `app/src/main/kotlin/com/remotesigner/nostr/InboxStore.kt`.

Uses `java.util.Base64` (not `android.util.Base64`) so the class works in both Android and JVM unit tests. Available since API 26; our minSdk is 28.

```kotlin
package com.remotesigner.nostr

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

class InboxStore(private val file: File) {

    fun save(items: List<InboxItem>) {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("psbt", Base64.getEncoder().encodeToString(item.psbtBytes))
            obj.put("label", item.label)
            obj.put("amount", item.amount)
            obj.put("senderNpub", item.senderNpub)
            obj.put("receivedAt", item.receivedAt)
            obj.put("status", item.status.name)
            item.rawHex?.let { obj.put("rawHex", it) }
            item.txid?.let { obj.put("txid", it) }
            obj.put("network", item.network)
            array.put(obj)
        }
        file.writeText(array.toString())
    }

    fun load(): List<InboxItem> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                InboxItem(
                    id = obj.getString("id"),
                    psbtBytes = Base64.getDecoder().decode(obj.getString("psbt")),
                    label = obj.getString("label"),
                    amount = obj.optString("amount", ""),
                    senderNpub = obj.getString("senderNpub"),
                    receivedAt = obj.getLong("receivedAt"),
                    status = try {
                        InboxStatus.valueOf(obj.getString("status"))
                    } catch (_: IllegalArgumentException) {
                        InboxStatus.PENDING
                    },
                    rawHex = obj.optString("rawHex", "").takeIf { it.isNotEmpty() },
                    txid = obj.optString("txid", "").takeIf { it.isNotEmpty() },
                    network = obj.optString("network", "main"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.remotesigner.nostr.InboxStoreTest" 2>&1 | tail -10`
Expected: All 5 tests PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/nostr/InboxStore.kt \
       app/src/test/kotlin/com/remotesigner/nostr/InboxStoreTest.kt \
       app/build.gradle.kts
git commit -m "feat(inbox): add InboxStore for JSON file persistence with tests"
```

---

### Task 3: Expiry logic with tests

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/nostr/InboxStore.kt`
- Create: `app/src/test/kotlin/com/remotesigner/nostr/InboxExpiryTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/remotesigner/nostr/InboxExpiryTest.kt`:

```kotlin
package com.remotesigner.nostr

import org.junit.Assert.*
import org.junit.Test

class InboxExpiryTest {

    private val now = 1710700000L // fixed "now" for tests

    @Test
    fun pendingItem_within24h_kept() {
        val item = InboxItem(
            id = "a1", psbtBytes = byteArrayOf(1), label = "Tx1",
            senderNpub = "npub1a", receivedAt = now - 3600, // 1h ago
        )
        val result = removeExpiredItems(listOf(item), nowSeconds = now)
        assertEquals(1, result.size)
    }

    @Test
    fun pendingItem_over24h_removed() {
        val item = InboxItem(
            id = "a1", psbtBytes = byteArrayOf(1), label = "Tx1",
            senderNpub = "npub1a", receivedAt = now - 86401, // >24h ago
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
            senderNpub = "npub1a", receivedAt = now - 86400 * 5, // 5d ago
            status = InboxStatus.SIGNED, rawHex = "deadbeef",
        )
        val result = removeExpiredItems(listOf(item), nowSeconds = now)
        assertEquals(1, result.size)
    }

    @Test
    fun signedItem_over7d_removed() {
        val item = InboxItem(
            id = "a1", psbtBytes = byteArrayOf(1), label = "Tx1",
            senderNpub = "npub1a", receivedAt = now - 86400 * 8, // 8d ago
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
            InboxItem( // kept: pending, 1h old
                id = "a1", psbtBytes = byteArrayOf(1), label = "Tx1",
                senderNpub = "n1", receivedAt = now - 3600,
            ),
            InboxItem( // removed: pending, 25h old
                id = "a2", psbtBytes = byteArrayOf(2), label = "Tx2",
                senderNpub = "n2", receivedAt = now - 90000,
            ),
            InboxItem( // kept: signed, 3d old
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.remotesigner.nostr.InboxExpiryTest" 2>&1 | tail -5`
Expected: FAIL — `removeExpiredItems` function doesn't exist

- [ ] **Step 3: Implement expiry logic**

Add to `app/src/main/kotlin/com/remotesigner/nostr/InboxStore.kt` (top-level function, outside the class):

```kotlin
private const val EXPIRY_SHORT_SECONDS = 86400L      // 24 hours
private const val EXPIRY_LONG_SECONDS = 86400L * 7    // 7 days

fun removeExpiredItems(
    items: List<InboxItem>,
    nowSeconds: Long = System.currentTimeMillis() / 1000,
): List<InboxItem> {
    return items.filter { item ->
        val age = nowSeconds - item.receivedAt
        val maxAge = when (item.status) {
            InboxStatus.PENDING, InboxStatus.SIGNING, InboxStatus.FAILED -> EXPIRY_SHORT_SECONDS
            InboxStatus.SIGNED, InboxStatus.BROADCAST -> EXPIRY_LONG_SECONDS
        }
        age <= maxAge
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.remotesigner.nostr.InboxExpiryTest" 2>&1 | tail -10`
Expected: All 9 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/nostr/InboxStore.kt \
       app/src/test/kotlin/com/remotesigner/nostr/InboxExpiryTest.kt
git commit -m "feat(inbox): add expiry logic — 24h for pending, 7d for signed/broadcast"
```

---

## Chunk 2: ViewModel integration (status updates, persistence, merge)

### Task 4: Add seedSeenIds to NostrReceiver

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/nostr/NostrReceiver.kt`

- [ ] **Step 1: Add seedSeenIds method**

Add after line 47 (`private val seenIds = mutableSetOf<String>()`):

```kotlin
fun seedSeenIds(ids: Set<String>) {
    synchronized(seenIds) { seenIds.addAll(ids) }
}
```

- [ ] **Step 2: Build and verify compilation**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/nostr/NostrReceiver.kt
git commit -m "feat(inbox): add seedSeenIds to NostrReceiver for persistence merge"
```

---

### Task 5: Integrate persistence and expiry into ViewModel

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt`

- [ ] **Step 1: Add InboxStore and updateInboxItem helper**

Add import at the top of SignerViewModel.kt:
```kotlin
import com.remotesigner.nostr.InboxStore
import com.remotesigner.nostr.removeExpiredItems
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.io.File
```

Inside the class, after `private var currentSigningInboxId: String? = null` (line 128), add:

```kotlin
private val inboxStore = InboxStore(File(application.filesDir, "inbox.json"))

init {
    // Load persisted inbox, clean expired, seed seen IDs
    val persisted = removeExpiredItems(inboxStore.load())
    if (persisted.isNotEmpty()) {
        _inboxItems.value = persisted.sortedByDescending { it.receivedAt }
        nostrReceiver.seedSeenIds(persisted.map { it.id }.toSet())
    }
    // Auto-save on changes (debounced)
    @OptIn(FlowPreview::class)
    _inboxItems
        .debounce(500)
        .onEach { items -> inboxStore.save(items) }
        .launchIn(viewModelScope)
}
```

Replace the existing `updateInboxItemStatus` (lines 169-173) with:

```kotlin
private fun updateInboxItem(id: String, transform: (InboxItem) -> InboxItem) {
    _inboxItems.update { current ->
        current.map { if (it.id == id) transform(it) else it }
    }
}
```

Update `signInboxItem` (line 161) to use new helper:
```kotlin
fun signInboxItem(item: InboxItem) {
    currentSigningInboxId = item.id
    updateInboxItem(item.id) { it.copy(status = InboxStatus.SIGNING) }
    loadPsbt(item.psbtBytes)
}
```

Update `goHome()` (lines 467-481) to use new helper:
```kotlin
fun goHome() {
    val inboxId = currentSigningInboxId
    if (inboxId != null) {
        val currentState = _state.value
        when (currentState) {
            is AppState.Result -> updateInboxItem(inboxId) {
                // Don't overwrite BROADCAST back to SIGNED
                if (it.status != InboxStatus.BROADCAST) it.copy(status = InboxStatus.SIGNED) else it
            }
            is AppState.Error -> updateInboxItem(inboxId) { it.copy(status = InboxStatus.FAILED) }
            else -> {}
        }
        currentSigningInboxId = null
    }
    currentPsbtBytes = null
    _state.value = AppState.Home
}
```

Add `onCleared()` flush:
```kotlin
override fun onCleared() {
    super.onCleared()
    inboxStore.save(_inboxItems.value)
}
```

- [ ] **Step 2: Add dedup check and network extraction to handleInboxEvent**

Replace `handleInboxEvent` (lines 139-157) with:

```kotlin
private fun handleInboxEvent(item: InboxItem) {
    // Skip if already persisted (e.g., SIGNED/BROADCAST item re-delivered by relay)
    if (_inboxItems.value.any { it.id == item.id }) return

    viewModelScope.launch {
        val enrichedItem = try {
            val result = withContext(Dispatchers.IO) {
                pythonBridge.parsePsbt(item.psbtBytes)
            }
            @Suppress("UNCHECKED_CAST")
            val outputs = result["outputs"] as? List<Map<String, Any?>> ?: emptyList()
            val totalSent = outputs
                .filter { it["is_change"] as? Boolean != true }
                .sumOf { (it["amount"] as? Number)?.toLong() ?: 0L }
            val network = result["network"]?.toString() ?: "main"
            item.copy(amount = formatBtcAmount(totalSent), network = network)
        } catch (_: Exception) {
            item
        }
        _inboxItems.update { current ->
            (current + enrichedItem).sortedByDescending { it.receivedAt }
        }
    }
}
```

- [ ] **Step 3: Build and verify compilation**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt
git commit -m "feat(inbox): integrate persistence, expiry, dedup and sorted order in ViewModel"
```

---

### Task 6: Status updates at signing/broadcast/cancel time

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt`

- [ ] **Step 1: Update doSignWithBridge to set SIGNED/FAILED/PENDING on inbox item**

In `doSignWithBridge`, in the `when (result["status"])` block:

After the `"complete"` branch (line 382-387), before `_state.value = AppState.Result(...)`, add inbox update:
```kotlin
"complete" -> {
    val inboxId = currentSigningInboxId
    if (inboxId != null) {
        updateInboxItem(inboxId) {
            it.copy(
                status = InboxStatus.SIGNED,
                rawHex = result["raw_tx"]?.toString(),
                network = network,
            )
        }
    }
    _state.value = AppState.Result(
        isComplete = true,
        rawHex = result["raw_tx"]?.toString(),
        network = network,
    )
}
```

In the `"cancelled"` branch (lines 398-404), add inbox revert:
```kotlin
"cancelled" -> {
    val inboxId = currentSigningInboxId
    if (inboxId != null) {
        updateInboxItem(inboxId) { it.copy(status = InboxStatus.PENDING) }
    }
    if (currentPsbtBytes != null) {
        parsePsbt(currentPsbtBytes!!)
    } else {
        _state.value = AppState.Home
    }
}
```

In the `else` (error) branch (lines 405-409), add inbox update:
```kotlin
else -> {
    val inboxId = currentSigningInboxId
    if (inboxId != null) {
        updateInboxItem(inboxId) { it.copy(status = InboxStatus.FAILED) }
    }
    _state.value = AppState.Error(
        result["message"]?.toString() ?: "Signing failed"
    )
}
```

In the `catch` block (lines 411-413), add inbox update:
```kotlin
} catch (e: Exception) {
    val inboxId = currentSigningInboxId
    if (inboxId != null) {
        updateInboxItem(inboxId) { it.copy(status = InboxStatus.FAILED) }
    }
    val signingLog = (_state.value as? AppState.Signing)?.log ?: ""
    _state.value = AppState.Error("Signing error: ${e.message}\n\n--- Log ---\n$signingLog")
}
```

- [ ] **Step 2: Update broadcast() to set BROADCAST status on inbox item**

Replace `broadcast()` (lines 425-447):

```kotlin
fun broadcast() {
    val state = _state.value
    if (state !is AppState.Result || state.rawHex == null) return

    _state.value = state.copy(broadcastStatus = "Broadcasting...")

    viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            pythonBridge.broadcast(state.rawHex)
        }

        if (result["status"] == "ok") {
            val txid = result["txid"]?.toString()
            _state.value = state.copy(
                txid = txid,
                broadcastStatus = "Broadcast successful",
            )
            val inboxId = currentSigningInboxId
            if (inboxId != null && txid != null) {
                updateInboxItem(inboxId) {
                    it.copy(status = InboxStatus.BROADCAST, txid = txid)
                }
            }
        } else {
            _state.value = state.copy(
                broadcastStatus = "Broadcast failed: ${result["message"]}",
            )
        }
    }
}
```

- [ ] **Step 3: Update cancelSigning() to revert inbox item to PENDING**

At the beginning of `cancelSigning()` (before line 450), add:

```kotlin
fun cancelSigning() {
    val inboxId = currentSigningInboxId
    if (inboxId != null) {
        updateInboxItem(inboxId) { it.copy(status = InboxStatus.PENDING) }
    }
    currentSigningCallback?.cancel()
    // ... rest unchanged
```

- [ ] **Step 4: Build and verify compilation**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt
git commit -m "feat(inbox): update inbox status at signing/broadcast/cancel time"
```

---

### Task 7: openInboxResult — navigate from inbox card to Result screen

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt`

- [ ] **Step 1: Add openInboxResult method**

Add after `deleteInboxItem` in the ViewModel:

```kotlin
fun openInboxResult(item: InboxItem) {
    if (item.status != InboxStatus.SIGNED && item.status != InboxStatus.BROADCAST) return
    currentSigningInboxId = item.id
    currentPsbtBytes = item.psbtBytes
    _state.value = AppState.Result(
        isComplete = true,
        rawHex = item.rawHex,
        txid = item.txid,
        broadcastStatus = if (item.txid != null) "Broadcast successful" else null,
        network = item.network,
    )
}
```

- [ ] **Step 2: Build and verify compilation**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt
git commit -m "feat(inbox): add openInboxResult to navigate from inbox card to Result screen"
```

---

## Chunk 3: UI changes (status chips, card taps, txid links)

### Task 8: Update InboxSection UI with status chips and card interactions

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/InboxSection.kt`

- [ ] **Step 1: Update InboxSection and InboxItemCard**

Replace the entire `InboxSection.kt` with:

```kotlin
package com.remotesigner.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.remotesigner.nostr.InboxItem
import com.remotesigner.nostr.InboxStatus
import com.remotesigner.nostr.formatRelativeTime

@Composable
fun InboxSection(
    items: List<InboxItem>,
    onSign: (InboxItem) -> Unit,
    onDelete: (InboxItem) -> Unit,
    onItemTap: (InboxItem) -> Unit = {},
) {
    if (items.isEmpty()) return

    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
    Text("Inbox", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    items.forEach { item ->
        InboxItemCard(item = item, onSign = onSign, onDelete = onDelete, onItemTap = onItemTap)
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun InboxItemCard(
    item: InboxItem,
    onSign: (InboxItem) -> Unit,
    onDelete: (InboxItem) -> Unit,
    onItemTap: (InboxItem) -> Unit = {},
) {
    val isTappable = item.status == InboxStatus.SIGNED || item.status == InboxStatus.BROADCAST

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isTappable) Modifier.clickable { onItemTap(item) } else Modifier),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Top row: label + status chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.label,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusChip(item.status)
            }

            if (item.amount.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(item.amount, style = MaterialTheme.typography.bodyMedium)
            }

            // Txid link for broadcast items
            if (item.status == InboxStatus.BROADCAST && item.txid != null) {
                Spacer(modifier = Modifier.height(4.dp))
                val context = LocalContext.current
                val txUrl = mempoolTxUrl(item.txid, item.network)
                Text(
                    "txid: ${item.txid.take(8)}...${item.txid.takeLast(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(txUrl)))
                    },
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "from ${item.senderNpub}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatRelativeTime(item.receivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (item.status == InboxStatus.PENDING || item.status == InboxStatus.FAILED) {
                    OutlinedButton(onClick = { onSign(item) }) {
                        Text("Sign")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                OutlinedButton(onClick = { onDelete(item) }) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: InboxStatus) {
    val (label, color) = when (status) {
        InboxStatus.PENDING -> "pending" to MaterialTheme.colorScheme.primary
        InboxStatus.SIGNING -> "signing" to MaterialTheme.colorScheme.tertiary
        InboxStatus.SIGNED -> "signed" to MaterialTheme.colorScheme.secondary
        InboxStatus.BROADCAST -> "broadcast" to MaterialTheme.colorScheme.secondary
        InboxStatus.FAILED -> "failed" to MaterialTheme.colorScheme.error
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
```

- [ ] **Step 2: Build and verify compilation**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/InboxSection.kt
git commit -m "feat(inbox): prominent status chip, tappable cards, txid links"
```

---

### Task 9: Wire onItemTap through HomeScreen and AppNavigation

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/HomeScreen.kt`
- Modify: `app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt`

- [ ] **Step 1: Add onItemTap parameter to HomeScreen**

In `HomeScreen.kt`, add parameter to the function signature (after `onDeleteInboxItem`):

```kotlin
@Composable
fun HomeScreen(
    npub: String,
    relayCount: Int,
    relayStatuses: Map<String, RelayStatus>,
    inboxItems: List<InboxItem>,
    onPsbtSelected: (Uri) -> Unit,
    onSignInboxItem: (InboxItem) -> Unit,
    onDeleteInboxItem: (InboxItem) -> Unit,
    onItemTap: (InboxItem) -> Unit = {},
) {
```

Pass it to `InboxSection` (around line 130):

```kotlin
InboxSection(
    items = inboxItems,
    onSign = onSignInboxItem,
    onDelete = onDeleteInboxItem,
    onItemTap = onItemTap,
)
```

- [ ] **Step 2: Wire in AppNavigation**

In `AppNavigation.kt`, update the `HomeScreen` call (around line 61) to pass `onItemTap`:

```kotlin
is AppState.Home -> HomeScreen(
    npub = viewModel.keyManager.getNpub(),
    relayCount = relayCount,
    relayStatuses = relayStatuses,
    inboxItems = inboxItems,
    onPsbtSelected = { uri -> viewModel.loadPsbt(uri) },
    onSignInboxItem = { item -> viewModel.signInboxItem(item) },
    onDeleteInboxItem = { item -> viewModel.deleteInboxItem(item.id) },
    onItemTap = { item -> viewModel.openInboxResult(item) },
)
```

- [ ] **Step 3: Build and verify compilation**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/HomeScreen.kt \
       app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt
git commit -m "feat(inbox): wire onItemTap through HomeScreen to openInboxResult"
```

---

## Chunk 4: Update existing tests

### Task 10: Update TestFixtures and InboxScreenTest

**Files:**
- Modify: `app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt`
- Modify: `app/src/androidTest/kotlin/com/remotesigner/InboxScreenTest.kt`

- [ ] **Step 1: Add new fixtures for signed/broadcast items**

In `TestFixtures.kt`, add after `sampleInboxItems`:

```kotlin
val signedInboxItem = com.remotesigner.nostr.InboxItem(
    id = "event3",
    psbtBytes = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()),
    label = "Payment to Bob",
    amount = "0.01000000 BTC",
    senderNpub = "npub1c5z9...n7h4",
    receivedAt = System.currentTimeMillis() / 1000 - 300,
    status = com.remotesigner.nostr.InboxStatus.SIGNED,
    rawHex = "0200000001deadbeef",
    network = "test",
)

val broadcastInboxItem = com.remotesigner.nostr.InboxItem(
    id = "event4",
    psbtBytes = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()),
    label = "Payment to Carol",
    amount = "0.00250000 BTC",
    senderNpub = "npub1d6a0...p8j5",
    receivedAt = System.currentTimeMillis() / 1000 - 600,
    status = com.remotesigner.nostr.InboxStatus.BROADCAST,
    rawHex = "0200000001cafebabe",
    txid = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2",
    network = "main",
)
```

- [ ] **Step 2: Add tests for new status chips and interactions**

Add to `InboxScreenTest.kt`:

```kotlin
@Test
fun inboxItemCard_broadcastStatus_showsChipAndTxid() {
    composeTestRule.setContent {
        SatoshiSignerTheme {
            InboxSection(
                items = listOf(TestFixtures.broadcastInboxItem),
                onSign = {},
                onDelete = {},
            )
        }
    }

    composeTestRule.onNodeWithText("broadcast").assertIsDisplayed()
    composeTestRule.onNodeWithText("Sign").assertDoesNotExist()
    // txid link should be displayed (truncated)
    composeTestRule.onNodeWithText("txid: a1b2c3d4...e9f0a1b2").assertIsDisplayed()
}

@Test
fun inboxItemCard_signedStatus_showsChip() {
    composeTestRule.setContent {
        SatoshiSignerTheme {
            InboxSection(
                items = listOf(TestFixtures.signedInboxItem),
                onSign = {},
                onDelete = {},
            )
        }
    }

    composeTestRule.onNodeWithText("signed").assertIsDisplayed()
    composeTestRule.onNodeWithText("Sign").assertDoesNotExist()
}

@Test
fun inboxItemCard_pendingStatus_showsChip() {
    composeTestRule.setContent {
        SatoshiSignerTheme {
            InboxSection(
                items = listOf(TestFixtures.sampleInboxItems[0]),
                onSign = {},
                onDelete = {},
            )
        }
    }

    composeTestRule.onNodeWithText("pending").assertIsDisplayed()
    composeTestRule.onNodeWithText("Sign").assertIsDisplayed()
}

@Test
fun inboxItemCard_signedStatus_cardTappable() {
    var tappedItem: com.remotesigner.nostr.InboxItem? = null
    composeTestRule.setContent {
        SatoshiSignerTheme {
            InboxSection(
                items = listOf(TestFixtures.signedInboxItem),
                onSign = {},
                onDelete = {},
                onItemTap = { tappedItem = it },
            )
        }
    }

    composeTestRule.onNodeWithText("Payment to Bob").performClick()
    assert(tappedItem != null) { "onItemTap should have been called for signed item" }
}
```

- [ ] **Step 3: Update existing signedStatus test**

The existing `inboxItemCard_signedStatus_hidesSignButton` test (line 65-77) creates a signed item without the new fields. Update it to use `TestFixtures.signedInboxItem` or leave as-is (it still works since new fields have defaults). Leave it as-is — it tests that a minimal signed item still hides the Sign button.

- [ ] **Step 4: Update HomeScreen tests to include onItemTap**

Update `homeScreen_relayStatus_showsConnectedCount` and `homeScreen_relayList_expandsOnTap` tests to include the new `onItemTap` parameter:

```kotlin
// Add to both HomeScreen test calls:
onItemTap = {},
```

- [ ] **Step 5: Build and run all tests**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -10`
Expected: All JVM unit tests PASS

Run: `./gradlew compileDebugKotlin compileDebugAndroidTestKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (Android instrumented tests compile; running them requires emulator)

- [ ] **Step 6: Commit**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt \
       app/src/androidTest/kotlin/com/remotesigner/InboxScreenTest.kt
git commit -m "test(inbox): update fixtures and tests for status chips, broadcast items, card taps"
```

---

## Summary

| Task | What | Files |
|------|------|-------|
| 1 | Extend InboxItem model | `NostrInbox.kt` |
| 2 | InboxStore persistence + tests | `InboxStore.kt`, `InboxStoreTest.kt` |
| 3 | Expiry logic + tests | `InboxStore.kt`, `InboxExpiryTest.kt` |
| 4 | seedSeenIds on NostrReceiver | `NostrReceiver.kt` |
| 5 | ViewModel persistence/expiry/merge | `SignerViewModel.kt` |
| 6 | Status updates at signing/broadcast/cancel | `SignerViewModel.kt` |
| 7 | openInboxResult navigation | `SignerViewModel.kt` |
| 8 | InboxSection UI (chips, taps, txid) | `InboxSection.kt` |
| 9 | Wire onItemTap through screens | `HomeScreen.kt`, `AppNavigation.kt` |
| 10 | Update test fixtures and tests | `TestFixtures.kt`, `InboxScreenTest.kt` |

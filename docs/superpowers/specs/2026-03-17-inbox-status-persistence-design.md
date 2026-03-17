# Inbox Status, Persistence & Expiry

## Problem

After signing and broadcasting a transaction received from Nostr, the inbox card looks identical to unsigned transactions. There's no way to see at a glance which transactions are pending, signed, or broadcast. Additionally, inbox state is entirely in-memory — a killed process loses signed-but-not-broadcast transactions with no way to recover.

## Design

### 1. Enhanced InboxItem Model

Add fields to `InboxItem` to track signing result and broadcast state:

```kotlin
data class InboxItem(
    val id: String,
    val psbtBytes: ByteArray,
    val label: String,
    val amount: String = "",
    val senderNpub: String,
    val receivedAt: Long,
    val status: InboxStatus = InboxStatus.PENDING,
    val rawHex: String? = null,    // signed transaction hex (set after signing)
    val txid: String? = null,       // transaction ID (set after broadcast)
    val network: String = "main",   // for mempool.space link (main vs test)
)
```

Add `BROADCAST` status to the enum:

```kotlin
enum class InboxStatus { PENDING, SIGNING, SIGNED, BROADCAST, FAILED }
```

### 2. Status Updates in Signing/Broadcast Flow

Current flow updates inbox status only in `goHome()`. Change to update at the actual event:

- **After signing succeeds** (in `doSignWithBridge` completion): set status to `SIGNED`, store `rawHex` and `network` on the `InboxItem`
- **After broadcast succeeds** (in `broadcast()`): set status to `BROADCAST`, store `txid` on the `InboxItem`. Guard on `currentSigningInboxId != null` so file-picker PSBTs (no inbox item) are unaffected:
  ```kotlin
  // in broadcast(), after successful result:
  val inboxId = currentSigningInboxId
  if (inboxId != null) {
      updateInboxItem(inboxId) { it.copy(status = InboxStatus.BROADCAST, txid = txid) }
  }
  ```
- **On error** (in `doSignWithBridge` error path): set status to `FAILED`
- **On cancel** (`cancelSigning()` or Trezor-side `"cancelled"` result): revert status from `SIGNING` back to `PENDING` so the user can retry. In `cancelSigning()`, add before the existing logic:
  ```kotlin
  val inboxId = currentSigningInboxId
  if (inboxId != null) {
      updateInboxItem(inboxId) { it.copy(status = InboxStatus.PENDING) }
  }
  ```
  In `doSignWithBridge`, add the same revert in the `"cancelled"` branch.
- **`goHome()`**: keeps its existing status-update logic as a safety net (idempotent — re-applying `SIGNED` to an already-`SIGNED` item is harmless). Still clears `currentSigningInboxId` and navigates.

New helper to update multiple fields at once (replaces existing `updateInboxItemStatus` which becomes redundant):

```kotlin
private fun updateInboxItem(id: String, transform: (InboxItem) -> InboxItem) {
    _inboxItems.update { current ->
        current.map { if (it.id == id) transform(it) else it }
    }
}
```

The old `updateInboxItemStatus(id, status)` calls should be migrated to use `updateInboxItem(id) { it.copy(status = ...) }`.

### 3. Navigate to Result Screen from Inbox Card

Tapping a signed/broadcast inbox card reopens the Result screen:

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

This reuses the existing Result screen — broadcast button works if `rawHex` is present and `txid` is null; txid link shown if `txid` is present.

### 4. Prominent Status Chip in Inbox Cards

Replace the small `labelSmall` status text with a filled chip in the card's top row:

```
┌─────────────────────────────────┐
│ Payment to Alice     [pending]  │
│ 0.00150000 BTC                  │
│ from npub1abc...     2 min ago  │
│                         [Sign]  │
└─────────────────────────────────┘

┌─────────────────────────────────┐
│ Payment to Bob        [signed]  │
│ 0.00250000 BTC                  │
│ from npub1def...     5 min ago  │
└─────────────────────────────────┘

┌──────────────────────────────────────┐
│ Payment to Carol     [broadcast]     │
│ 0.00100000 BTC                       │
│ txid: abc123...def  (tappable link)  │
│ from npub1ghi...     1 hour ago      │
└──────────────────────────────────────┘
```

Chip colors:
- **PENDING**: `primary` (blue)
- **SIGNING**: `tertiary` (animating or pulsing optional)
- **SIGNED**: `secondary` (green)
- **BROADCAST**: `secondary` variant or a distinct color
- **FAILED**: `error` (red)

Interaction per status:
- **PENDING / FAILED**: "Sign" + "Delete" buttons
- **SIGNED**: entire card tappable → opens Result screen; "Delete" button
- **BROADCAST**: shows tappable txid link to mempool.space; "Delete" button

### 5. Persistence via JSON File

Store inbox state in a JSON file in app-internal storage. No new dependencies — use `org.json` (already available via Android SDK) or `kotlinx.serialization`.

**File location**: `context.filesDir / "inbox.json"`

**Serialization format**:
```json
[
  {
    "id": "nostr-event-id",
    "psbt": "<base64>",
    "label": "Payment to Alice",
    "amount": "0.00150000 BTC",
    "senderNpub": "npub1abc...wxyz",
    "receivedAt": 1710700000,
    "status": "SIGNED",
    "rawHex": "0200000001...",
    "txid": null,
    "network": "test"
  }
]
```

**When to save**: after every `_inboxItems` update (debounced, e.g., 500ms after last change to avoid excessive writes). Additionally, flush synchronously in `ViewModel.onCleared()` to ensure the most recent state (e.g., a just-broadcast txid) is not lost if the user immediately kills the app.

**When to load**: on ViewModel init, before connecting to relays.

**Merge logic on relay reconnect**: when `handleInboxEvent` receives an event from the relay, check if the ID already exists in `_inboxItems` **before** calling `parsePsbt`. If it does, skip the incoming item (the persisted version has richer state — status, rawHex, txid). If it doesn't, enrich and append as today:

```kotlin
private fun handleInboxEvent(item: InboxItem) {
    // Skip if already persisted (e.g., SIGNED/BROADCAST item re-delivered by relay)
    if (_inboxItems.value.any { it.id == item.id }) return

    viewModelScope.launch {
        val enrichedItem = try {
            val result = withContext(Dispatchers.IO) { pythonBridge.parsePsbt(item.psbtBytes) }
            val outputs = result["outputs"] as? List<Map<String, Any?>> ?: emptyList()
            val totalSent = outputs.filter { it["is_change"] != true }
                .sumOf { (it["amount"] as? Number)?.toLong() ?: 0L }
            val network = result["network"]?.toString() ?: "main"
            item.copy(amount = formatBtcAmount(totalSent), network = network)
        } catch (_: Exception) { item }
        _inboxItems.update { current -> current + enrichedItem }
    }
}
```

Additionally, seed `NostrReceiver.seenIds` with persisted item IDs on startup to avoid unnecessary decryption work. Add a public method to `NostrReceiver`:

```kotlin
fun seedSeenIds(ids: Set<String>) { seenIds.addAll(ids) }
```

Called from ViewModel init after loading persisted items, before `connect()`.

**Implementation**: a small `InboxStore` class that takes a `File` (not `Context`) for testability:

```kotlin
class InboxStore(private val file: File) {
    fun save(items: List<InboxItem>) { /* serialize to JSON, write to file */ }
    fun load(): List<InboxItem> { /* read file, deserialize, return empty list if missing/corrupt */ }
}
```

Created in ViewModel as `InboxStore(File(application.filesDir, "inbox.json"))`. `psbtBytes` is Base64-encoded for JSON serialization.

Persistence is called from the ViewModel by collecting the `_inboxItems` flow with debounce.

### 6. Expiry Policy

Clean up old items automatically with different lifetimes by status:

| Status | Lifetime | Rationale |
|--------|----------|-----------|
| PENDING | 24 hours | Re-fetchable from relay within 24h subscription window |
| FAILED | 24 hours | Can retry; relay still has the event |
| SIGNING | 24 hours | Stale if app was killed mid-signing |
| SIGNED | 7 days | User needs time to broadcast |
| BROADCAST | 7 days | Record-keeping; txid visible on mempool.space anyway |

**When to clean**: on ViewModel init (after loading from disk) and periodically while the app is running (e.g., every hour via a coroutine timer, or simply on each `handleInboxEvent` call).

**Sort order**: items sorted by `receivedAt` descending (newest first). Applied after merge and after expiry cleanup.

**Network at receive time**: `handleInboxEvent` should extract the `network` field from the `parsePsbt` result and store it on the `InboxItem` at enrichment time (not only after signing). This makes the network available for display on inbox cards before signing.

## Files to Change

| File | Change |
|------|--------|
| `nostr/NostrInbox.kt` | Add `BROADCAST` to enum; add `rawHex`, `txid`, `network` fields to `InboxItem` |
| `viewmodel/SignerViewModel.kt` | Update status at signing/broadcast time; add `openInboxResult()`; add `InboxStore` integration; add expiry cleanup; add `updateInboxItem()` helper |
| `ui/InboxSection.kt` | Prominent status chip; tappable card for signed/broadcast; txid link; adjust button visibility |
| `ui/HomeScreen.kt` | Pass `onItemTap` callback for signed/broadcast card taps |
| `ui/AppNavigation.kt` | Wire `onItemTap` to `viewModel.openInboxResult()` |
| New: `nostr/InboxStore.kt` | JSON serialization/deserialization, file I/O |

## Testing

- **JVM unit tests** for `InboxStore`: round-trip serialize/deserialize, missing file returns empty, corrupt file returns empty, large PSBT round-trips correctly
- **JVM unit tests** for expiry logic: items older than threshold are removed, items within threshold are kept, different thresholds per status
- **JVM unit tests** for merge logic: persisted SIGNED item is not overwritten by incoming PENDING relay event with same ID
- **Android instrumented tests** for `InboxSection`: chip displayed per status, card tappable for SIGNED/BROADCAST, txid link shown for BROADCAST, Sign button only for PENDING/FAILED
- **Existing tests**: update `InboxScreenTest` for new chip UI and new status; update any test that constructs `InboxItem` with new fields

## Out of Scope

- Nostr event deletion (NIP-09) to signal the sender that a PSBT was signed
- Push notifications for new inbox items
- Partial signing status tracking (multi-sig progress)

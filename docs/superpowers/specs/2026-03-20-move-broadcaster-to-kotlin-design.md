# Move Broadcasting Logic from Python to Kotlin

## Problem

Broadcasting is the one Python function with zero dependency on Bitcoin libraries — it's just HTTP POSTs to mempool.space/blockstream.info. Currently it routes through the Python bridge unnecessarily. Moving it to Kotlin shrinks the Python/bridge surface area to only what needs Python: PSBT parsing (embit) and Trezor signing (trezorlib).

## Design

### New: `TransactionBroadcaster`

**Package:** `com.remotesigner.broadcast`

**File:** `TransactionBroadcaster.kt` (class + `BroadcastResult` data class)

**Constructor:** `TransactionBroadcaster(client: OkHttpClient)`

**Public API:**
```kotlin
fun broadcast(rawHex: String, network: String): BroadcastResult
```

**`BroadcastResult`** (moved from `BridgeModels.kt`):
```kotlin
data class BroadcastResult(
    val status: String,           // "ok" or "error"
    val txid: String? = null,
    val message: String? = null,
    val rawHex: String? = null,
)
```

**Validation** — throws `IllegalArgumentException`:
- `rawHex`: non-empty, even length, valid hex chars, <=400KB
- `network`: must be one of `main`, `testnet3`, `testnet4`, `signet`, `test`

**Endpoint map:**

| Network | Primary | Secondary |
|---------|---------|-----------|
| main | mempool.space/api/tx | blockstream.info/api/tx |
| testnet3 / test | mempool.space/testnet/api/tx | blockstream.info/testnet/api/tx |
| testnet4 | mempool.space/testnet4/api/tx | _(none)_ |
| signet | mempool.space/signet/api/tx | _(none)_ |

**Retry logic:** For each endpoint in order, try up to 2 times. If all endpoints exhausted, return error result with last error message and original `rawHex`.

**HTTP:** OkHttp synchronous `POST`, `text/plain` body, 10-second read/connect timeout. HTTP 200 response body = txid. Note: Python used `requests.post(data=...)` which defaults to `application/x-www-form-urlencoded`; the Kotlin version uses `text/plain` which is what the mempool.space API documents.

### Integration Changes

**`SignerViewModel`:**
- Constructor gains `TransactionBroadcaster` parameter
- `broadcast()` calls `broadcaster.broadcast()` instead of `pythonBridge.broadcast()`
- Catches `IllegalArgumentException` for validation errors (replaces Python `ValueError`)
- Threading unchanged — `withContext(Dispatchers.IO)`

**`SignerViewModelFactory`:**
- Creates `TransactionBroadcaster(OkHttpClient())` and injects into ViewModel

**Bridge cleanup — remove:**
- `broadcast()` from `PythonBridgeInterface`
- `broadcast()` and `toBroadcastResult()` from `PythonBridge`
- `BroadcastResult` from `BridgeModels.kt`

**Note on `requests` pip dependency:** `requests` is a transitive dependency of `trezor` (trezorlib uses it internally). The explicit `install("requests>=2.28")` line in `build.gradle.kts` stays — it ensures a minimum version for Chaquopy. Broadcasting no longer uses it directly, but trezorlib still needs it.

**Python cleanup — remove:**
- `app/src/main/python/remotesigner/broadcaster.py`
- `tests/test_broadcaster.py`
- `requests` import check from `validate_deps.py` (trezorlib still imports it, but it's no longer a direct app dependency worth validating)

**Build dependency — add:**
- `testImplementation(libs.okhttp.mockwebserver)` (currently only `androidTestImplementation`)

**Doc cleanup — update CLAUDE.md:**
- Update architecture descriptions that reference `broadcaster.py`, `PythonBridge.broadcast()`, and the broadcast flow
- Update "Testnet variant selection at broadcast time" section to reference Kotlin broadcaster

### Testing

**New: `TransactionBroadcasterTest.kt`** (JVM, `app/src/test/kotlin/com/remotesigner/broadcast/`)

Uses OkHttp `MockWebServer`. Coverage:
- Successful broadcast (primary endpoint, returns txid)
- Primary fails, secondary succeeds (fallback)
- Retry within same endpoint (first attempt fails, second succeeds)
- All endpoints fail (error result with last message)
- Each network maps to correct URL path
- Validation: empty hex, odd length, invalid chars, oversized, unknown network
- Timeout handling

**Modified: `SignerViewModelTest.kt`**
- Mock `TransactionBroadcaster` instead of `pythonBridge.broadcast()`
- Same 7 existing broadcast test cases, different mock target
- Import path changes: `com.remotesigner.bridge.BroadcastResult` → `com.remotesigner.broadcast.BroadcastResult`

**No changes:** `InboxDaoTest.kt`, Android instrumented tests (mock at ViewModel level).

## Approach

Blocking class with OkHttp synchronous API. No coroutine dependency in the broadcaster itself — the ViewModel owns threading. Chosen over suspend function approach because the broadcaster is a leaf dependency doing synchronous HTTP, and this keeps testing simple (no `runTest` needed).

# Broadcaster Network Validation & Testnet Variant Support

> Fixes refactoring-todos.md items #4 (silent mainnet fallback) and #5 (no raw_hex validation).
> Adds broadcast support for testnet3, testnet4, and signet.

## Problem

1. **Item #4:** `ENDPOINTS.get(network, ENDPOINTS["main"])` silently falls back to mainnet when an unrecognized network string is passed. A typo like `network="testt"` broadcasts a testnet transaction to mainnet, where it fails silently.

2. **Item #5:** `broadcast_transaction` accepts any string as `raw_hex` with no validation. Invalid data is sent directly to mempool.space/blockstream.

3. **Missing networks:** The app only supports `"main"` and `"test"` but mempool.space serves testnet3, testnet4, and signet. Users on testnet4 or signet cannot broadcast.

## Constraints

- BIP32 coin_type is `1` for all three testnet variants — PSBT auto-detection (`_detect_network`) cannot distinguish them.
- trezorlib accepts only `"Bitcoin"` or `"Testnet"` as coin_name — signing is identical across all testnet variants.
- embit's `NETWORKS` has `"main"`, `"test"`, `"signet"`, `"regtest"` — address encoding works for all. (`"regtest"` is out of scope — no public broadcast endpoint.)
- Existing inbox items in the database have `network = "test"`. The broadcaster must accept `"test"` to avoid breaking reopened inbox items.

Therefore, the testnet variant distinction only matters for **broadcasting** and **block explorer links**.

## Design

### Network string taxonomy

| Context | Valid values | Notes |
|---------|-------------|-------|
| PSBT auto-detection | `"main"`, `"test"` | Can only detect coin_type 0 vs 1 |
| Signing (signer.py) | `"main"`, `"test"` | Maps to trezorlib coin_name |
| Broadcasting | `"main"`, `"test"`, `"testnet3"`, `"testnet4"`, `"signet"` | `"test"` maps to testnet3 endpoints for backward compat |
| Explorer links | `"main"`, `"test"`, `"testnet3"`, `"testnet4"`, `"signet"` | `"test"` maps to `/testnet` for backward compat |

### 1. broadcaster.py

**Endpoint map:**

```python
ENDPOINTS = {
    "main":     [
        "https://mempool.space/api/tx",
        "https://blockstream.info/api/tx",
    ],
    "test":     [  # backward compat alias for testnet3
        "https://mempool.space/testnet/api/tx",
        "https://blockstream.info/testnet/api/tx",
    ],
    "testnet3": [
        "https://mempool.space/testnet/api/tx",
        "https://blockstream.info/testnet/api/tx",
    ],
    "testnet4": [
        "https://mempool.space/testnet4/api/tx",
    ],
    "signet":   [
        "https://mempool.space/signet/api/tx",
    ],
}
```

Blockstream has mainnet and testnet3 endpoints. Testnet4/signet use mempool.space only.
`"test"` is kept as a backward-compat alias for `"testnet3"` so existing database rows and the default auto-detection flow still work.

**Network validation (fixes #4):**

```python
if network not in ENDPOINTS:
    raise ValueError(f"Unknown network: {network!r}. Valid: {', '.join(sorted(ENDPOINTS))}")
```

Raised before any HTTP request. No silent fallback.

**raw_hex validation (fixes #5):**

```python
if not raw_hex or not isinstance(raw_hex, str):
    raise ValueError("raw_hex must be a non-empty string")
if len(raw_hex) % 2 != 0:
    raise ValueError("raw_hex has odd length — not valid hex")
try:
    bytes.fromhex(raw_hex)
except ValueError:
    raise ValueError("raw_hex contains non-hex characters")
MAX_TX_SIZE = 400_000  # 400KB, Bitcoin's max standard tx is ~400KB
if len(raw_hex) // 2 > MAX_TX_SIZE:
    raise ValueError(f"Transaction too large: {len(raw_hex) // 2} bytes (max {MAX_TX_SIZE})")
```

### 2. MempoolUrl.kt

Replace inline `if` with exhaustive mapping:

```kotlin
private fun networkPrefix(network: String): String = when (network) {
    "main" -> ""
    "test", "testnet3" -> "/testnet"
    "testnet4" -> "/testnet4"
    "signet" -> "/signet"
    else -> throw IllegalArgumentException("Unknown network: $network")
}
```

Unknown networks throw `IllegalArgumentException` instead of silently producing mainnet URLs.

`mempoolTxUrl` and `mempoolAddressUrl` use `networkPrefix(network)` instead of the current inline `if`.

### 3. Result screen UI

**Callback signature change:** `onBroadcast: () -> Unit` becomes `onBroadcast: (String) -> Unit`, where the string is the target broadcast network. This change ripples to:
- `AppNavigation.kt`: `onBroadcast = { viewModel.broadcast() }` → `onBroadcast = { network -> viewModel.broadcast(network) }`
- `ScreenRenderTest.kt`: `onBroadcast = {}` — Kotlin infers `(String) -> Unit` from the composable parameter, no syntax change needed
- `NavigationTest.kt`: same

**Button layout:**

When `state.network == "test"` (any testnet variant detected from PSBT), the broadcast section shows three buttons instead of one:

- **"Broadcast to Testnet4"** — visually prominent (filled/primary style, default choice)
- **"Broadcast to Testnet3"** — secondary/outlined style
- **"Broadcast to Signet"** — secondary/outlined style

When `state.network == "main"`, single "Broadcast" button (unchanged).

When `state.network == "test"` from a reopened signed-but-not-broadcast inbox item, the same three-button UI appears — the user picks their target variant just like for a fresh signing result.

When `state.network` is already a specific variant (e.g., `"testnet4"` from a reopened inbox item that was already broadcast), the explorer link uses that variant and the broadcast button state follows existing logic (already broadcast = disabled).

Each button calls `onBroadcast(targetNetwork)` with the specific variant string (`"testnet4"`, `"testnet3"`, `"signet"`).

**Disable logic:** All three buttons share a single `broadcastClicked` state. While any broadcast is in flight, all three are disabled. On failure, all three re-enable (the user might want to try a different network).

### 4. ViewModel changes

`broadcast()` signature changes to accept a `targetNetwork: String` parameter:

- Mainnet callers pass `"main"`
- Testnet callers pass the specific variant (`"testnet4"`, `"testnet3"`, `"signet"`)

After successful broadcast, use `targetNetwork` (not `state.network`) when updating state:
- Copy `AppState.Result` with `network = targetNetwork` so the explorer link resolves correctly
- Update `InboxItemEntity.network` to `targetNetwork`

**Important:** The state update must use the `targetNetwork` parameter, not the snapshot's `state.network`, to avoid the stale-read pattern noted in refactoring-todos item #19. The rest of the snapshot (rawHex, txid, etc.) is safe to use because the Result screen blocks other navigation — no concurrent state mutations are possible.

**DAO change:** `InboxDao.updateBroadcast()` needs a `network` parameter added:

```kotlin
@Query("UPDATE inbox_items SET status = :status, txid = :txid, network = :network WHERE id = :id")
suspend fun updateBroadcast(id: String, status: InboxStatus, txid: String, network: String)
```

This persists the specific variant so reopened inbox items show the correct explorer link.

### 5. PythonBridge

No signature change needed — `broadcast(rawHex, network)` already accepts a network string. The only change is that callers now pass `"testnet4"` / `"testnet3"` / `"signet"` instead of always `"test"`.

## What does NOT change

- `psbt_parser.py` — `_detect_network` still returns `"main"` or `"test"`
- `signer.py` — still maps `network != "main"` to `coin_name="Testnet"`
- Signing flow — identical for all testnet variants
- Screens before Result — display "Testnet" generically
- Database schema — `network` column is already a String, no migration needed

## Testing

### Python tests (tests/test_broadcaster.py)

- Test that `broadcast_transaction("ab01", "main")` hits mainnet endpoints
- Test that `broadcast_transaction("ab01", "testnet4")` hits testnet4 endpoint
- Test that `broadcast_transaction("ab01", "signet")` hits signet endpoint
- Test that `broadcast_transaction("ab01", "testnet3")` hits testnet3 endpoint
- Test that `broadcast_transaction("ab01", "test")` hits testnet3 endpoints (backward compat)
- Test that `broadcast_transaction("ab01", "typo")` raises `ValueError`
- Test that `broadcast_transaction("", "main")` raises `ValueError` (empty hex)
- Test that `broadcast_transaction("xyz", "main")` raises `ValueError` (invalid hex)
- Test that `broadcast_transaction("abc", "main")` raises `ValueError` (odd-length hex)
- Test max size validation with oversized hex string

### Kotlin JVM tests (MempoolUrlTest)

- Test `mempoolTxUrl(txid, "testnet4")` → `/testnet4/tx/{txid}`
- Test `mempoolTxUrl(txid, "signet")` → `/signet/tx/{txid}`
- Test `mempoolTxUrl(txid, "testnet3")` → `/testnet/tx/{txid}`
- Test backward compat: `mempoolTxUrl(txid, "test")` → `/testnet/tx/{txid}`
- Test `mempoolAddressUrl(addr, "testnet4")` → `/testnet4/address/{addr}`
- Test `mempoolAddressUrl(addr, "signet")` → `/signet/address/{addr}`
- Test unknown network throws `IllegalArgumentException`

### Android instrumented tests

- Update `ScreenRenderTest` and `NavigationTest` for new `onBroadcast: (String) -> Unit` signature
- Update `InboxDaoTest.updateBroadcast_setsStatusAndTxid` for new `updateBroadcast` signature with `network` param
- Add render test for Result screen with `network = "test"` verifying three broadcast buttons appear
- Add render test for Result screen with `network = "main"` verifying single broadcast button
- Existing `test_broadcaster.py` tests for `"main"` and `"test"` remain compatible (both keys still valid)

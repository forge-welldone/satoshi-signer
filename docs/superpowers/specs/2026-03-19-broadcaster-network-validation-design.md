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
- embit's `NETWORKS` has `"main"`, `"test"`, `"signet"`, `"regtest"` — address encoding works for all.

Therefore, the testnet variant distinction only matters for **broadcasting** and **block explorer links**.

## Design

### Network string taxonomy

| Context | Valid values | Notes |
|---------|-------------|-------|
| PSBT auto-detection | `"main"`, `"test"` | Can only detect coin_type 0 vs 1 |
| Signing (signer.py) | `"main"`, `"test"` | Maps to trezorlib coin_name |
| Broadcasting | `"main"`, `"testnet3"`, `"testnet4"`, `"signet"` | Specific endpoints per variant |
| Explorer links | `"main"`, `"test"`, `"testnet3"`, `"testnet4"`, `"signet"` | `"test"` maps to `/testnet` for backward compat |

### 1. broadcaster.py

**Endpoint map:**

```python
ENDPOINTS = {
    "main":     [
        "https://mempool.space/api/tx",
        "https://blockstream.info/api/tx",
    ],
    "testnet3": [
        "https://mempool.space/testnet/api/tx",
    ],
    "testnet4": [
        "https://mempool.space/testnet4/api/tx",
    ],
    "signet":   [
        "https://mempool.space/signet/api/tx",
    ],
}
```

Blockstream only has mainnet and testnet3 endpoints, so testnet4/signet use mempool.space only.

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

Add network-to-path mapping:

```kotlin
private fun networkPrefix(network: String): String = when (network) {
    "test", "testnet3" -> "/testnet"
    "testnet4" -> "/testnet4"
    "signet" -> "/signet"
    else -> ""  // "main" and any unknown
}
```

`mempoolTxUrl` and `mempoolAddressUrl` use `networkPrefix(network)` instead of the current inline `if`.

### 3. Result screen UI

When `state.network == "test"` (i.e., any testnet variant detected from PSBT), the broadcast section shows three buttons instead of one:

- **"Broadcast to Testnet4"** — visually prominent (default)
- **"Broadcast to Testnet3"** — secondary style
- **"Broadcast to Signet"** — secondary style

When `state.network == "main"`, single "Broadcast" button (unchanged).

Each button calls `viewModel.broadcast(targetNetwork)` with the specific variant string (`"testnet4"`, `"testnet3"`, `"signet"`).

### 4. ViewModel changes

`broadcast()` signature changes from no network param to accepting a `targetNetwork: String`:

- Mainnet callers pass `"main"`
- Testnet callers pass the specific variant

After successful broadcast:
- Update `AppState.Result.network` to the specific variant (e.g., `"testnet4"`) so the explorer link resolves correctly
- Update `InboxItemEntity.network` to the specific variant via `inboxDao.updateBroadcast()`

### 5. PythonBridge

No signature change needed — `broadcast(rawHex, network)` already accepts a network string. The only change is that callers now pass `"testnet4"` / `"testnet3"` / `"signet"` instead of `"test"`.

## What does NOT change

- `psbt_parser.py` — `_detect_network` still returns `"main"` or `"test"`
- `signer.py` — still maps `network != "main"` to `coin_name="Testnet"`
- Signing flow — identical for all testnet variants
- Screens before Result — display "Testnet" generically
- Database schema — `network` column is already a String

## Testing

### Python tests (tests/test_broadcaster.py)

- Test that `broadcast_transaction("ab01", "main")` hits mainnet endpoints
- Test that `broadcast_transaction("ab01", "testnet4")` hits testnet4 endpoint
- Test that `broadcast_transaction("ab01", "signet")` hits signet endpoint
- Test that `broadcast_transaction("ab01", "testnet3")` hits testnet3 endpoint
- Test that `broadcast_transaction("ab01", "test")` raises `ValueError` (no longer valid for broadcasting)
- Test that `broadcast_transaction("ab01", "typo")` raises `ValueError`
- Test that `broadcast_transaction("", "main")` raises `ValueError` (empty hex)
- Test that `broadcast_transaction("xyz", "main")` raises `ValueError` (invalid hex)
- Test that `broadcast_transaction("ab", "main")` raises `ValueError` (odd-length hex) — actually "ab" is even length and valid; test "abc" for odd
- Test max size validation

### Kotlin JVM tests (MempoolUrlTest)

- Test `mempoolTxUrl(txid, "testnet4")` → `/testnet4/tx/{txid}`
- Test `mempoolTxUrl(txid, "signet")` → `/signet/tx/{txid}`
- Test `mempoolTxUrl(txid, "testnet3")` → `/testnet/tx/{txid}`
- Test backward compat: `mempoolTxUrl(txid, "test")` → `/testnet/tx/{txid}`

# Typed Bridge Response Models

> Refactoring TODO #7 — Create typed bridge response models for the Python-Kotlin boundary.

## Problem

Python bridge functions return `Map<String, Any?>`. Kotlin consumers interpret results via unchecked casts (`result["outputs"] as? List<Map<String, Any?>>`). A Python key rename silently produces null at runtime. Two call sites (`SignerViewModel.parsePsbt()` and `InboxRepository.handleInboxEvent()`) duplicate the same map-parsing logic.

## Scope

- `parsePsbt()` and `broadcast()` responses get typed models
- `signPsbt()` is **out of scope** — `SigningOrchestrator` already maps it to the `SigningResult` sealed class

## Design

### New file: `bridge/BridgeModels.kt`

Two data classes matching Python return shapes:

```kotlin
data class ParsedPsbtResult(
    val inputs: List<TxInput>,
    val outputs: List<TxOutput>,
    val fee: Long,
    val status: String,
    val signers: List<SignerInfo>,
    val network: String,
    val requiredSigs: Int,
    val totalSigs: Int,
)

data class BroadcastResult(
    val status: String,
    val txid: String?,
    val message: String?,
)
```

`TxInput`, `TxOutput`, and `SignerInfo` already exist in `SignerViewModel.kt`. They move to `BridgeModels.kt` since they're now part of the bridge contract. `opReturn` field on `TxOutput` is preserved.

### PythonBridgeInterface changes

```kotlin
fun parsePsbt(psbtBytes: ByteArray): ParsedPsbtResult   // was Map<String, Any?>
fun broadcast(rawHex: String, network: String): BroadcastResult  // was Map<String, Any?>
fun signPsbt(...): Map<String, Any?>  // unchanged
```

### Parsing moves into PythonBridge

`PythonBridge.parsePsbt()` calls `pyDictToMap()` as before, then converts the map to `ParsedPsbtResult` in a private `toParseResult()` helper. Same pattern for `broadcast()` → `toBroadcastResult()`. All `@Suppress("UNCHECKED_CAST")` annotations move here (single location) or are eliminated by the structured parsing.

### Consumer simplification

**SignerViewModel.parsePsbt()** — receives `ParsedPsbtResult`, directly constructs `AppState.TransactionReview`:
- `result.inputs` instead of `(result["inputs"] as? List<Map<String, Any?>>)?.map { ... }`
- `result.network` instead of `result["network"]?.toString() ?: "main"`
- `result.fee` instead of `(result["fee"] as? Number)?.toLong() ?: 0`
- Removes `@Suppress("UNCHECKED_CAST")`

**InboxRepository.handleInboxEvent()** — receives `ParsedPsbtResult`, reads `.outputs` and `.network`:
- `result.outputs.filter { !it.isChange }.sumOf { it.amount }` instead of cast-heavy equivalent
- Removes `@Suppress("UNCHECKED_CAST")`

**SignerViewModel.broadcast()** — receives `BroadcastResult`:
- `result.status == "ok"` (same but now type-safe String, not `Any?`)
- `result.txid` instead of `result["txid"]?.toString()`
- `result.message` instead of `result["message"]`

### Python side: TypedDicts

Add TypedDict classes to each module for documentation and optional mypy checking:

```python
# psbt_parser.py
class InputInfo(TypedDict):
    index: int
    txid: str
    vout: int
    amount: int
    address: str

class OutputInfo(TypedDict):
    index: int
    address: str
    amount: int
    is_change: bool
    op_return: NotRequired[str]

class SignerStatus(TypedDict):
    fingerprint: str
    signed: bool

class ParseResult(TypedDict):
    inputs: list[InputInfo]
    outputs: list[OutputInfo]
    fee: int
    status: str
    signers: list[SignerStatus]
    network: str
    required_sigs: NotRequired[int]
    total_sigs: NotRequired[int]
```

```python
# broadcaster.py
class BroadcastOk(TypedDict):
    status: str  # "ok"
    txid: str

class BroadcastError(TypedDict):
    status: str  # "error"
    message: str
    raw_hex: str
```

No runtime behavior change — TypedDicts are annotation-only at runtime.

### Test updates

- **FakePythonBridge** (`InboxRepositoryTest.kt`): returns `ParsedPsbtResult(...)` instead of `mapOf("outputs" to listOf(mapOf(...)))`. Simpler and type-safe.
- **ChaquopyE2ETest**: assertions change from `result["network"]` to `result.network` etc. The test now verifies that `PythonBridge` correctly produces typed models from real Chaquopy output.
- **Python tests**: no changes needed — TypedDicts are still dicts, existing assertions work.

### What does NOT change

- `signPsbt()` return type stays `Map<String, Any?>` — `SigningOrchestrator.doSign()` already maps to `SigningResult` sealed class
- `SigningResult` sealed class stays in `SigningOrchestrator`
- `pyDictToMap()` stays in `PythonBridge` — still needed for `signPsbt()`
- Python runtime behavior — TypedDicts are annotations only
- `AppState` sealed class stays in `SignerViewModel.kt`

## Files touched

| File | Change |
|------|--------|
| `bridge/BridgeModels.kt` | **New** — `ParsedPsbtResult`, `BroadcastResult`, moved `TxInput`, `TxOutput`, `SignerInfo` |
| `bridge/PythonBridgeInterface.kt` | Return types: `ParsedPsbtResult`, `BroadcastResult` |
| `bridge/PythonBridge.kt` | Add `toParseResult()`, `toBroadcastResult()` private helpers |
| `viewmodel/SignerViewModel.kt` | Remove data classes, simplify `parsePsbt()` and `broadcast()` |
| `data/InboxRepository.kt` | Simplify `handleInboxEvent()` |
| `remotesigner/psbt_parser.py` | Add TypedDict annotations |
| `remotesigner/broadcaster.py` | Add TypedDict annotations |
| `androidTest/.../InboxRepositoryTest.kt` | Update FakePythonBridge |
| `androidTest/.../ChaquopyE2ETest.kt` | Update assertions |

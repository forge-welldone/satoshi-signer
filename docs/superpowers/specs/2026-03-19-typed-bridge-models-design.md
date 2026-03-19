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
    val requiredSigs: Int = 0,
    val totalSigs: Int = 0,
)

data class BroadcastResult(
    val status: String,
    val txid: String? = null,
    val message: String? = null,
    val rawHex: String? = null,
)
```

`TxInput`, `TxOutput`, and `SignerInfo` already exist in `SignerViewModel.kt`. They move to `BridgeModels.kt` since they're now part of the bridge contract. `opReturn` field on `TxOutput` is preserved.

Default values on `requiredSigs`/`totalSigs` (0) match the existing behavior — Python only includes these keys for multisig PSBTs. Default `null` on `BroadcastResult` fields reflects that success has `txid` but no `message`, and error has `message` and `rawHex` but no `txid`.

### PythonBridgeInterface changes

```kotlin
fun parsePsbt(psbtBytes: ByteArray): ParsedPsbtResult   // was Map<String, Any?>
fun broadcast(rawHex: String, network: String): BroadcastResult  // was Map<String, Any?>
fun signPsbt(...): Map<String, Any?>  // unchanged
```

### Parsing moves into PythonBridge

`PythonBridge.parsePsbt()` calls `pyDictToMap()` as before, then converts the map to `ParsedPsbtResult` via a private helper. The `@Suppress("UNCHECKED_CAST")` concentrates in one place — the initial `List<Map<String, Any?>>` casts for nested lists. Snake_case → camelCase mapping happens here:

```kotlin
@Suppress("UNCHECKED_CAST")
private fun toParseResult(map: Map<String, Any?>): ParsedPsbtResult {
    val inputs = (map["inputs"] as? List<Map<String, Any?>> ?: emptyList()).map { inp ->
        TxInput(
            address = inp["address"]?.toString() ?: "unknown",
            amount = (inp["amount"] as? Number)?.toLong() ?: 0,
        )
    }
    val outputs = (map["outputs"] as? List<Map<String, Any?>> ?: emptyList()).map { out ->
        TxOutput(
            address = out["address"]?.toString() ?: "unknown",
            amount = (out["amount"] as? Number)?.toLong() ?: 0,
            isChange = out["is_change"] as? Boolean ?: false,
            opReturn = out["op_return"]?.toString(),
        )
    }
    val signers = (map["signers"] as? List<Map<String, Any?>> ?: emptyList()).map { s ->
        SignerInfo(
            fingerprint = s["fingerprint"]?.toString() ?: "",
            signed = s["signed"] as? Boolean ?: false,
        )
    }
    return ParsedPsbtResult(
        inputs = inputs,
        outputs = outputs,
        fee = (map["fee"] as? Number)?.toLong() ?: 0,
        status = map["status"]?.toString() ?: "unknown",
        signers = signers,
        network = map["network"]?.toString() ?: "main",
        requiredSigs = (map["required_sigs"] as? Number)?.toInt() ?: 0,
        totalSigs = (map["total_sigs"] as? Number)?.toInt() ?: 0,
    )
}

private fun toBroadcastResult(map: Map<String, Any?>): BroadcastResult {
    return BroadcastResult(
        status = map["status"]?.toString() ?: "error",
        txid = map["txid"]?.toString(),
        message = map["message"]?.toString(),
        rawHex = map["raw_hex"]?.toString(),
    )
}
```

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
- `result.status == "ok"` (now type-safe String, not `Any?`)
- `result.txid` instead of `result["txid"]?.toString()`
- `result.message` instead of `result["message"]`

### Python side: TypedDicts

Add TypedDict classes for documentation and optional mypy checking. Desktop Python is 3.9 so `NotRequired` needs a conditional import:

```python
import sys
if sys.version_info >= (3, 11):
    from typing import NotRequired, TypedDict
else:
    from typing_extensions import NotRequired, TypedDict
```

Add `typing_extensions>=4.0` to `requirements-dev.txt`.

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
- **ChaquopyE2ETest**: no assertion changes needed — tests operate at the ViewModel/UI level via `loadPsbt()`, not direct bridge calls.
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
| `data/ContactRepository.kt` | Update import: `viewmodel.SignerInfo` → `bridge.SignerInfo` |
| `ui/TransactionReviewScreen.kt` | Update imports: `viewmodel.{TxInput,TxOutput,SignerInfo}` → `bridge.{...}` |
| `remotesigner/psbt_parser.py` | Add TypedDict annotations |
| `remotesigner/broadcaster.py` | Add TypedDict annotations |
| `requirements-dev.txt` | Add `typing_extensions>=4.0` |
| `androidTest/.../InboxRepositoryTest.kt` | Update FakePythonBridge to return typed models |
| `androidTest/.../TestFixtures.kt` | Update imports: `viewmodel.{TxInput,TxOutput,SignerInfo}` → `bridge.{...}` |
| `androidTest/.../ContactRepositoryTest.kt` | Update import: `viewmodel.SignerInfo` → `bridge.SignerInfo` |

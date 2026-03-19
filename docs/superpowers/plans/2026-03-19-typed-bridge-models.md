# Typed Bridge Response Models — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `Map<String, Any?>` returns from `PythonBridge.parsePsbt()` and `broadcast()` with typed Kotlin data classes, and add Python TypedDict annotations.

**Architecture:** Parsing logic concentrates in `PythonBridge` via two private helpers (`toParseResult`, `toBroadcastResult`). `TxInput`, `TxOutput`, `SignerInfo` move from ViewModel to `bridge/BridgeModels.kt`. Consumers receive typed objects directly — no casts needed.

**Tech Stack:** Kotlin data classes, Python `TypedDict` (with `typing_extensions` for Python 3.9 desktop compat)

**Spec:** `docs/superpowers/specs/2026-03-19-typed-bridge-models-design.md`

---

### Task 1: Create BridgeModels.kt with data classes

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/bridge/BridgeModels.kt`

- [ ] **Step 1: Create the models file**

```kotlin
package com.remotesigner.bridge

data class TxInput(
    val address: String,
    val amount: Long,
)

data class TxOutput(
    val address: String,
    val amount: Long,
    val isChange: Boolean,
    val opReturn: String? = null,
)

data class SignerInfo(
    val fingerprint: String,
    val signed: Boolean,
    val isThisDevice: Boolean = false,
    val contactLabel: String? = null,
    val contactId: Long? = null,
)

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

- [ ] **Step 2: Remove data classes from SignerViewModel.kt**

Remove the `TxInput`, `TxOutput`, and `SignerInfo` data class declarations from the top of `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt` (lines 31-49). Keep `AppState`, `PassphraseRequest`, and `AccountPathRequest`. Add import for the new location:

```kotlin
import com.remotesigner.bridge.SignerInfo
import com.remotesigner.bridge.TxInput
import com.remotesigner.bridge.TxOutput
```

- [ ] **Step 3: Update imports in all consuming files**

Update these files to import from `com.remotesigner.bridge` instead of `com.remotesigner.viewmodel`:

**`app/src/main/kotlin/com/remotesigner/ui/TransactionReviewScreen.kt`** — change lines 22-24:
```kotlin
// OLD:
import com.remotesigner.viewmodel.SignerInfo
import com.remotesigner.viewmodel.TxInput
import com.remotesigner.viewmodel.TxOutput
// NEW:
import com.remotesigner.bridge.SignerInfo
import com.remotesigner.bridge.TxInput
import com.remotesigner.bridge.TxOutput
```

**`app/src/main/kotlin/com/remotesigner/data/ContactRepository.kt`** — change line 3:
```kotlin
// OLD:
import com.remotesigner.viewmodel.SignerInfo
// NEW:
import com.remotesigner.bridge.SignerInfo
```

**`app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt`** — change lines 6-8:
```kotlin
// OLD:
import com.remotesigner.viewmodel.SignerInfo
import com.remotesigner.viewmodel.TxInput
import com.remotesigner.viewmodel.TxOutput
// NEW:
import com.remotesigner.bridge.SignerInfo
import com.remotesigner.bridge.TxInput
import com.remotesigner.bridge.TxOutput
```

**`app/src/androidTest/kotlin/com/remotesigner/data/ContactRepositoryTest.kt`** — change line 5:
```kotlin
// OLD:
import com.remotesigner.viewmodel.SignerInfo
// NEW:
import com.remotesigner.bridge.SignerInfo
```

- [ ] **Step 4: Verify compilation**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/bridge/BridgeModels.kt \
  app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt \
  app/src/main/kotlin/com/remotesigner/ui/TransactionReviewScreen.kt \
  app/src/main/kotlin/com/remotesigner/data/ContactRepository.kt \
  app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt \
  app/src/androidTest/kotlin/com/remotesigner/data/ContactRepositoryTest.kt
git commit -m "refactor: move TxInput, TxOutput, SignerInfo to bridge/BridgeModels.kt"
```

---

### Task 2: Change PythonBridgeInterface and PythonBridge for parsePsbt

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/bridge/PythonBridgeInterface.kt:15`
- Modify: `app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt:22-25`

- [ ] **Step 1: Update PythonBridgeInterface.parsePsbt return type**

In `app/src/main/kotlin/com/remotesigner/bridge/PythonBridgeInterface.kt`, change line 15:
```kotlin
// OLD:
fun parsePsbt(psbtBytes: ByteArray): Map<String, Any?>
// NEW:
fun parsePsbt(psbtBytes: ByteArray): ParsedPsbtResult
```

- [ ] **Step 2: Add toParseResult helper and update PythonBridge.parsePsbt**

In `app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt`, replace the `parsePsbt` method (lines 22-25):

```kotlin
// OLD:
override fun parsePsbt(psbtBytes: ByteArray): Map<String, Any?> {
    val result = parserModule.callAttr("parse_psbt", psbtBytes)
    return pyDictToMap(result)
}

// NEW:
override fun parsePsbt(psbtBytes: ByteArray): ParsedPsbtResult {
    val result = parserModule.callAttr("parse_psbt", psbtBytes)
    return toParseResult(pyDictToMap(result))
}
```

Add the `toParseResult` private helper before the `pyDictToMap` method:

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
```

- [ ] **Step 3: Simplify SignerViewModel.parsePsbt()**

In `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt`, replace the `parsePsbt` method (lines 227-285). Remove the `@Suppress("UNCHECKED_CAST")` annotation and the manual map parsing:

```kotlin
private suspend fun parsePsbt(bytes: ByteArray) {
    try {
        val result = withContext(Dispatchers.IO) {
            pythonBridge.parsePsbt(bytes)
        }
        currentPsbtBytes = bytes
        currentNetwork = result.network

        val totalSent = result.outputs.filter { !it.isChange }.sumOf { it.amount }
        val signers = contactRepository.enrichSigners(result.signers)

        val warnings = mutableListOf<String>()
        if (result.fee > 1_000_000) {
            warnings.add("Fee is unusually high: ${"%.8f".format(result.fee / 100_000_000.0)} BTC")
        }

        _state.value = AppState.TransactionReview(
            inputs = result.inputs,
            outputs = result.outputs,
            fee = result.fee,
            totalSent = totalSent,
            status = result.status,
            signers = signers,
            warnings = warnings,
            requiredSigs = result.requiredSigs,
            totalSigs = result.totalSigs,
            network = currentNetwork,
            description = currentDescription,
        )
    } catch (e: Exception) {
        _state.value = AppState.Error("Invalid PSBT: ${e.message}")
    }
}
```

- [ ] **Step 4: Simplify InboxRepository.handleInboxEvent()**

In `app/src/main/kotlin/com/remotesigner/data/InboxRepository.kt`, replace the `handleInboxEvent` method (lines 26-48). Remove the `@Suppress("UNCHECKED_CAST")` annotation:

```kotlin
suspend fun handleInboxEvent(item: InboxItemEntity) {
    val inserted = inboxDao.insertIgnore(item)
    if (inserted == -1L) return

    try {
        val result = withContext(Dispatchers.IO) {
            pythonBridge.parsePsbt(item.psbtBytes)
        }
        val totalSent = result.outputs
            .filter { !it.isChange }
            .sumOf { it.amount }
        inboxDao.updateParsedFields(
            id = item.id,
            amount = formatBtcAmount(totalSent),
            network = result.network,
        )
    } catch (_: Exception) {
        // Keep original row if parse fails
    }
}
```

- [ ] **Step 5: Update FakePythonBridge in InboxRepositoryTest.kt**

In `app/src/androidTest/kotlin/com/remotesigner/data/InboxRepositoryTest.kt`, replace the `FakePythonBridge` inner class (lines 22-38):

```kotlin
private class FakePythonBridge(
    private val parseResult: ParsedPsbtResult = ParsedPsbtResult(
        inputs = emptyList(),
        outputs = listOf(TxOutput(address = "test", amount = 50000L, isChange = false)),
        fee = 0,
        status = "unsigned",
        signers = emptyList(),
        network = "test",
    ),
    private val shouldThrow: Boolean = false,
) : PythonBridgeInterface {
    override fun parsePsbt(psbtBytes: ByteArray): ParsedPsbtResult {
        if (shouldThrow) throw RuntimeException("parse failed")
        return parseResult
    }
    override fun signPsbt(
        psbtBytes: ByteArray, bridge: SigningBridge,
        callback: SigningCallback?, network: String,
    ) = emptyMap<String, Any?>()
    override fun broadcast(rawHex: String, network: String) = emptyMap<String, Any?>()
}
```

Add imports at the top:
```kotlin
import com.remotesigner.bridge.ParsedPsbtResult
import com.remotesigner.bridge.TxOutput
```

- [ ] **Step 6: Verify compilation**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/bridge/PythonBridgeInterface.kt \
  app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt \
  app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt \
  app/src/main/kotlin/com/remotesigner/data/InboxRepository.kt \
  app/src/androidTest/kotlin/com/remotesigner/data/InboxRepositoryTest.kt
git commit -m "refactor: type parsePsbt return as ParsedPsbtResult

Move map-to-model parsing into PythonBridge.toParseResult().
Simplify consumers in SignerViewModel and InboxRepository."
```

---

### Task 3: Change PythonBridgeInterface and PythonBridge for broadcast

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/bridge/PythonBridgeInterface.kt:22`
- Modify: `app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt:39-42`
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt:373-401`
- Modify: `app/src/androidTest/kotlin/com/remotesigner/data/InboxRepositoryTest.kt` (FakePythonBridge)

- [ ] **Step 1: Update PythonBridgeInterface.broadcast return type**

In `app/src/main/kotlin/com/remotesigner/bridge/PythonBridgeInterface.kt`, change the broadcast line:
```kotlin
// OLD:
fun broadcast(rawHex: String, network: String = "main"): Map<String, Any?>
// NEW:
fun broadcast(rawHex: String, network: String = "main"): BroadcastResult
```

- [ ] **Step 2: Add toBroadcastResult helper and update PythonBridge.broadcast**

In `app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt`, replace the `broadcast` method:
```kotlin
// OLD:
override fun broadcast(rawHex: String, network: String): Map<String, Any?> {
    val result = broadcasterModule.callAttr("broadcast_transaction", rawHex, network)
    return pyDictToMap(result)
}

// NEW:
override fun broadcast(rawHex: String, network: String): BroadcastResult {
    val result = broadcasterModule.callAttr("broadcast_transaction", rawHex, network)
    return toBroadcastResult(pyDictToMap(result))
}
```

Add the `toBroadcastResult` helper near `toParseResult`:

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

- [ ] **Step 3: Simplify SignerViewModel.broadcast()**

In `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt`, replace the broadcast method body (lines 379-400):

```kotlin
viewModelScope.launch {
    val result = withContext(Dispatchers.IO) {
        pythonBridge.broadcast(state.rawHex, targetNetwork)
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
}
```

- [ ] **Step 4: Update FakePythonBridge.broadcast in InboxRepositoryTest.kt**

In the `FakePythonBridge` class, update the broadcast method:
```kotlin
// OLD:
override fun broadcast(rawHex: String, network: String) = emptyMap<String, Any?>()
// NEW:
override fun broadcast(rawHex: String, network: String) = BroadcastResult(status = "ok")
```

Add import:
```kotlin
import com.remotesigner.bridge.BroadcastResult
```

- [ ] **Step 5: Verify compilation**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/bridge/PythonBridgeInterface.kt \
  app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt \
  app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt \
  app/src/androidTest/kotlin/com/remotesigner/data/InboxRepositoryTest.kt
git commit -m "refactor: type broadcast return as BroadcastResult

Move map parsing into PythonBridge.toBroadcastResult().
Simplify SignerViewModel.broadcast() to use typed fields."
```

---

### Task 4: Add Python TypedDict annotations

**Files:**
- Modify: `app/src/main/python/remotesigner/psbt_parser.py`
- Modify: `app/src/main/python/remotesigner/broadcaster.py`
- Modify: `requirements-dev.txt`

- [ ] **Step 1: Add typing_extensions to requirements-dev.txt**

Add `typing_extensions>=4.0` to `requirements-dev.txt`.

- [ ] **Step 2: Install updated requirements**

Run: `cd /Users/sasha/Projects/remote_signer && source .venv/bin/activate && pip install -r requirements-dev.txt`

- [ ] **Step 3: Add TypedDict annotations to psbt_parser.py**

Add these type definitions after the existing imports at the top of `app/src/main/python/remotesigner/psbt_parser.py`, before the `PSBT_MAGIC` constant:

```python
import sys
if sys.version_info >= (3, 11):
    from typing import NotRequired, TypedDict
else:
    from typing_extensions import NotRequired, TypedDict


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

Update the `parse_psbt` function signature return type annotation:
```python
# OLD:
def parse_psbt(psbt_bytes: bytes, network: str = "main") -> dict:
# NEW:
def parse_psbt(psbt_bytes: bytes, network: str = "main") -> ParseResult:
```

- [ ] **Step 4: Add TypedDict annotations to broadcaster.py**

Add these type definitions after the existing imports at the top of `app/src/main/python/remotesigner/broadcaster.py`, before `ENDPOINTS`:

```python
import sys
if sys.version_info >= (3, 11):
    from typing import TypedDict
else:
    from typing_extensions import TypedDict


class BroadcastOk(TypedDict):
    status: str
    txid: str


class BroadcastError(TypedDict):
    status: str
    message: str
    raw_hex: str
```

No function signature change needed — `broadcast_transaction` returns either `BroadcastOk` or `BroadcastError`, and Python's type system can't express this union cleanly with TypedDict. The annotation serves as documentation.

- [ ] **Step 5: Run Python tests to verify no breakage**

Run: `cd /Users/sasha/Projects/remote_signer && source .venv/bin/activate && python -m pytest tests/ -v 2>&1 | tail -20`
Expected: All existing tests pass (TypedDicts are annotation-only, no runtime change).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/python/remotesigner/psbt_parser.py \
  app/src/main/python/remotesigner/broadcaster.py \
  requirements-dev.txt
git commit -m "refactor: add Python TypedDict annotations for bridge responses

Documents the exact dict shapes returned by parse_psbt and
broadcast_transaction. Uses typing_extensions for Python 3.9 compat."
```

---

### Task 5: Update refactoring-todos.md

**Files:**
- Modify: `docs/refactoring-todos.md`

- [ ] **Step 1: Mark item #7 as fixed**

In `docs/refactoring-todos.md`, update item #7 (lines 120-131) with the same strikethrough + "FIXED" pattern used for items 1-6. Add a summary of what was done.

- [ ] **Step 2: Commit**

```bash
git add docs/refactoring-todos.md
git commit -m "docs: mark refactoring-todo #7 as fixed"
```

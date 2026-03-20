# Fix StateFlow Race Conditions in ViewModel

**Date:** 2026-03-20
**Refactoring TODO:** #19
**Priority:** P2

## Problem

`SignerViewModel` has three read-modify-write race conditions on `_state: MutableStateFlow<AppState>`. Each reads `_state.value`, does work or suspends, then writes back a `.copy()` of the original snapshot — potentially overwriting a state change that occurred in between.

### Race condition 1: `reEnrichSigners()` (lines 431-437)

Reads `_state.value`, suspends for `contactRepository.enrichSigners()`, writes back. If state changed during suspension (e.g., user cancelled signing, state became Home), the stale TransactionReview overwrites it.

### Race condition 2: `broadcast()` (lines 332-365)

Reads `_state.value` once at the top, then uses that stale snapshot for all subsequent writes: "Broadcasting...", success, and error. If user navigates away during the async broadcast (goHome sets state to Home), the completion write overwrites Home with stale Result.

### Race condition 3: Progress callbacks (lines 256-257, 273-274)

Reads Signing log, appends message, writes back. Two concurrent progress messages could drop one. Lower severity since Trezor progress messages are typically serial.

## Solution

Replace all read-modify-write patterns with `_state.update { currentState -> ... }` — the atomic update API on `MutableStateFlow`. The lambda receives the current state and returns the new state without any read-write gap. Each update lambda includes a type guard (e.g., `is AppState.Result`) so it becomes a no-op if the state type changed.

This pattern is already used in `NostrReceiver.kt` in this codebase.

## Changes

### 1. `reEnrichSigners()`

Compute enrichment before the atomic update. The update lambda only applies the result if still in TransactionReview state.

```kotlin
private suspend fun reEnrichSigners() {
    val currentSigners = (_state.value as? AppState.TransactionReview)?.signers ?: return
    val enriched = contactRepository.enrichSigners(currentSigners)
    _state.update { state ->
        if (state is AppState.TransactionReview) state.copy(signers = enriched)
        else state
    }
}
```

Trade-off: enrichment runs against a potentially stale signer list, but the atomic update prevents overwriting a different state type. Worst case is a no-op enrichment.

### 2. `broadcast()`

Each write point gets its own `_state.update { }` with `is AppState.Result` guard. The initial read for `rawHex` is kept as a local val (input data, not mutable state).

```kotlin
fun broadcast(targetNetwork: String) {
    val rawHex = (_state.value as? AppState.Result)?.rawHex ?: return

    _state.update { state ->
        if (state is AppState.Result) state.copy(broadcastStatus = "Broadcasting...")
        else state
    }

    viewModelScope.launch {
        try {
            val result = withContext(Dispatchers.IO) {
                broadcaster.broadcast(rawHex, targetNetwork)
            }
            if (result.status == "ok") {
                _state.update { state ->
                    if (state is AppState.Result) state.copy(
                        txid = result.txid,
                        broadcastStatus = "Broadcast successful",
                        network = targetNetwork,
                    ) else state
                }
                // database updates unchanged
            } else {
                _state.update { state ->
                    if (state is AppState.Result) state.copy(
                        broadcastStatus = "Broadcast failed: ${result.message}",
                    ) else state
                }
            }
        } catch (e: Exception) {
            _state.update { state ->
                if (state is AppState.Result) state.copy(
                    broadcastStatus = "Broadcast failed: ${e.message}",
                ) else state
            }
        }
    }
}
```

### 3. Progress callbacks

Both `signWithTrezor` and `signWithBridge` progress lambdas get atomic updates.

```kotlin
onProgress = { msg ->
    _state.update { current ->
        if (current is AppState.Signing) current.copy(message = msg, log = current.log + msg + "\n")
        else current
    }
}
```

## Testing

### Existing tests (must still pass)

- Broadcast success/error paths
- Contact CRUD with re-enrichment
- State transitions through signing flow
- goHome from broadcast Result

### New tests

1. **broadcast ignores completion when state changed** — start broadcast, set state to Home before async completion, verify Home state preserved.
2. **reEnrichSigners is no-op when state changed** — trigger re-enrichment, change state to Home before update applies, verify Home state preserved.
3. **progress callback is no-op when state changed** — simulate progress message arriving after signing completes (state is Result), verify Result state preserved.

## Scope

- Only the three race condition sites in `SignerViewModel.kt`
- No changes to `AppState` sealed class
- No new dependencies
- Existing tests must pass unchanged

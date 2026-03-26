# Satoshi Signer — Refactoring TODOs

> Code review by 5 specialists: Rubyist, Pythonista, Rubocop Fan, System Architect,
> and Test Coverage Expert. Focused on readability, maintainability, and extendability.
>
> Date: 2026-03-21
>
> For resolved items from prior reviews, see [refactoring-done.md](refactoring-done.md).

---

## How to Read This Document

- **Priority tiers**: P0 (silent failures — fix now), P1 (architecture — fix before adding features), P2 (quality — fix as you touch the code), P3 (polish — nice to have)
- **Consensus column**: How many of the 5 reviewers independently flagged this issue
- Items marked with 🧪 directly expand automated test coverage

---

## P0 — Critical (Silent failures, data corruption risk)

### 1. 🧪 Bridge serialization untested — malformed Python returns silently default
| | |
|---|---|
| **File** | `bridge/PythonBridge.kt:38-70` (`toParseResult()`) |
| **Consensus** | Test Expert, Rubyist (2/5) |
| **Impact** | If Python returns unexpected shapes, Kotlin silently defaults to `"unknown"` / `0` — user sees wrong data |

`toParseResult()` does `inp["address"]?.toString() ?: "unknown"` and `(inp["amount"] as? Number)?.toLong() ?: 0`. No test verifies behavior when Python returns: `null` inputs list, string where number expected, missing keys, or unknown status values.

**Fix:** Add JVM unit tests for `toParseResult()` with malformed input maps: null values, wrong types, missing keys, empty lists.

---

### 2. 🧪 Passphrase cancellation flow untested
| | |
|---|---|
| **File** | `bridge/PythonBridge.kt:114-116` (`CANCEL_SENTINEL`), `bridge/SigningOrchestrator.kt` |
| **Consensus** | Test Expert (1/5) |
| **Impact** | Normal user action (cancel passphrase dialog) with zero test coverage — final state unknown |

`CANCEL_SENTINEL` → `LinkedBlockingQueue` → Python `get_passphrase()` raises `RuntimeError("Cancelled")` → `sign_psbt` returns `{"status": "cancelled"}` → `SigningOrchestrator` maps to `SigningResult.Cancelled` → ViewModel re-parses PSBT. None of this chain is tested.

**Fix:** Add ViewModel test: cancel passphrase → verify state returns to `TransactionReview`. Add Python test: cancel sentinel → verify `sign_psbt` returns `cancelled` status.

---

### 3. 🧪 Account path callback untested
| | |
|---|---|
| **File** | `bridge/SigningOrchestrator.kt`, `bridge/PythonBridge.kt` (SigningCallback) |
| **Consensus** | Test Expert (1/5) |
| **Impact** | Multisig PSBTs with relative derivation paths trigger `requestAccountPath()` — untested, likely crashes |

When Python encounters relative BIP32 paths, it calls `callback.requestAccountPath()`. This blocks the Python thread while UI shows a dialog. The entire flow has zero tests.

**Fix:** Add ViewModel test: `accountPathRequest` emitted → submit valid path → signing continues. Test invalid path → error. Test cancel → signing cancelled.

---

## P1 — Architecture (Fix Before Adding Features)

### 4. `signPsbt()` still returns `Map<String, Any?>` — type-unsafe boundary
| | |
|---|---|
| **File** | `bridge/PythonBridgeInterface.kt`, `bridge/SigningOrchestrator.kt:140-159` |
| **Consensus** | Rubyist, Architect (2/5) |

`parsePsbt()` returns typed `ParsedPsbtResult` but `signPsbt()` returns `Map<String, Any?>`. `SigningOrchestrator.doSign()` accesses `result["status"]`, `result["raw_tx"]?.toString()` without compile-time safety.

**Fix:** Create `SigningResponse` data class. Parse in `PythonBridge.signPsbt()` via a `toSigningResponse()` helper.

---

### 5. Dependency direction: SigningOrchestrator imports viewmodel types
| | |
|---|---|
| **File** | `bridge/SigningOrchestrator.kt` imports `viewmodel.PassphraseRequest`, `viewmodel.AccountPathRequest` |
| **Consensus** | Architect (1/5) |
| **Impact** | Bridge layer depends on UI layer — blocks reuse (e.g., background signing service) |

**Fix:** Move `PassphraseRequest` and `AccountPathRequest` to `bridge/CallbackModels.kt`.

---

### 6. Dependency direction: Repos import UI constants
| | |
|---|---|
| **Files** | `data/InboxRepository.kt`, `data/ContactRepository.kt` import from `ui/Formatters.kt` |
| **Consensus** | Architect (1/5) |
| **Impact** | Data layer depends on UI layer — backwards dependency |

**Fix:** Move constants and `formatBtcAmount()` to a `config/` or `domain/` package.

---

### 7. No linting/formatting tools configured
| | |
|---|---|
| **Files** | `build.gradle.kts`, project root |
| **Consensus** | Rubocop Fan (1/5) |
| **Impact** | Code is clean today (8.5/10 consistency) but will drift without enforcement |

**Fix:** Add ktlint + detekt (Kotlin), ruff (Python), `.editorconfig`. Consider pre-commit hook.

---

### 8. 🧪 Nostr relay reconnection/backoff untested
| | |
|---|---|
| **File** | `nostr/NostrReceiver.kt` |
| **Consensus** | Test Expert (1/5) |
| **Impact** | Core PSBT delivery feature; backoff logic could silently break |

**Fix:** Add instrumented tests: relay failure → verify backoff, relay recovery → verify reset.

---

### 9. Result state conflates signing and broadcast concerns
| | |
|---|---|
| **File** | `viewmodel/Models.kt` (`AppState.Result`) |
| **Consensus** | Architect (1/5) |
| **Impact** | `broadcastStatus` is a magic string; `errorMessage` field exists but is never set |

**Fix:** Replace `broadcastStatus: String?` with sealed class. Remove unused `errorMessage`.

---

### 10. Error state too generic
| | |
|---|---|
| **File** | `viewmodel/Models.kt` (`AppState.Error`) |
| **Consensus** | Architect (1/5) |
| **Impact** | No distinction between parse/sign/broadcast errors; blocks retry logic |

**Fix:** Subtype into `Parse`, `Signing`, `Broadcast` error variants.

---

## P2 — Quality & Testability (Fix as You Touch the Code)

### 11. Network strings should be an enum
| | |
|---|---|
| **Files** | `viewmodel/Models.kt`, `broadcast/TransactionBroadcaster.kt`, `ui/MempoolUrl.kt`, `ui/ResultScreen.kt` |
| **Consensus** | Rubyist, Architect (2/5) |

`"test"`, `"main"`, `"testnet3"`, `"testnet4"`, `"signet"` as raw strings. Typo = silent failure.

**Fix:** Create `enum class Network`. Parse from Python's string output once in bridge layer.

---

### 12. Python type hints: inconsistent generic syntax
| | |
|---|---|
| **Files** | `psbt_parser.py`, `script_utils.py`, `signer.py` |
| **Consensus** | Pythonista (1/5) |

Mixing `List[bytes]` (old) and `list[InputInfo]` (new). Python 3.13 target makes old syntax unnecessary.

**Fix:** Replace all `List[X]` → `list[X]`, `Dict[K, V]` → `dict[K, V]`, etc.

---

### 13. `ParsedTransaction` dataclass is intermediate clutter
| | |
|---|---|
| **File** | `psbt_parser.py:50-58` |
| **Consensus** | Pythonista, Rubyist (2/5) |

Dataclass built then manually deconstructed to dict. Fields untyped (`list` instead of `list[InputInfo]`), `raw_psbt: object = None` is vague.

**Fix:** Return dataclass directly (using `asdict()`) or build the dict without the intermediate object.

---

### 14. Silent exception swallowing in Kotlin
| | |
|---|---|
| **Files** | `data/InboxRepository.kt:28-47`, `viewmodel/SignerViewModel.kt:71-85` |
| **Consensus** | Rubyist (1/5) |

`catch (_: Exception)` hides bugs. NFC decryption conflates "wrong key" with "keystore locked".

**Fix:** Add `Log.w()` to `InboxRepository`. Catch specific exceptions in `onNfcTagResult()`.

---

### 15. `ContactRepository.saveContact()` silently returns on validation failure
| | |
|---|---|
| **File** | `data/ContactRepository.kt:26-43` |
| **Consensus** | Rubyist (1/5) |

**Fix:** Return sealed class `SaveResult.Success` / `SaveResult.ValidationError(reason)`.

---

### 16. Overly broad exception handling in Python
| | |
|---|---|
| **Files** | `signer.py:407,641,927,968`, `trezor_ui.py:50,118` |
| **Consensus** | Pythonista (1/5) |

`except Exception: continue` hides real bugs. Callback safety catch is justified but undocumented.

**Fix:** Use specific exceptions where possible. Document intentional broad catches.

---

### 17. 🧪 Missing cassette scenarios
| | |
|---|---|
| **Files** | `tests/cassettes/`, `app/src/androidTest/assets/cassettes/` |
| **Consensus** | Test Expert (1/5) |

Only 2 cassettes (single-sig P2WPKH, multisig testnet3). Missing: P2PKH, P2SH, Taproot, mainnet.

**Fix:** Record additional cassettes with `sign_cli.py --record`.

---

### 18. No state machine transition guards
| | |
|---|---|
| **File** | `viewmodel/SignerViewModel.kt` |
| **Consensus** | Architect (1/5) |

Any state can transition to any other. `Signing → Contacts` is technically possible.

**Fix:** Add `validTransition()` check or type guards in `_state.update {}`.

---

### 19. SigningOrchestrator race condition on `currentUsbBridge`
| | |
|---|---|
| **File** | `bridge/SigningOrchestrator.kt` |
| **Consensus** | Architect (1/5) |

`cancel()` sets `currentUsbBridge = null` while `doSign()` may be using it — non-atomic.

**Fix:** Use `AtomicReference<SigningBridge?>` or synchronize access.

---

### 20. Broadcast button DRY violation in ResultScreen
| | |
|---|---|
| **File** | `ui/ResultScreen.kt:77-107` |
| **Consensus** | Rubyist (1/5) |

Three nearly identical button blocks for testnet variants.

**Fix:** Loop over `listOf("testnet4" to "Testnet4", ...)` or extract `BroadcastButton` composable.

---

## P3 — Polish (Nice to Have)

### 21. `formatBtcAmount()` is locale-unaware
| | |
|---|---|
| **File** | `ui/Formatters.kt:15` |
| **Consensus** | Rubyist (1/5) |

**Fix:** `String.format(Locale.ROOT, "%.8f BTC", satoshis / 100_000_000.0)`

---

### 22. InputRow/OutputRow duplication in TransactionReviewScreen
| | |
|---|---|
| **File** | `ui/TransactionReviewScreen.kt:165-200` |
| **Consensus** | Rubyist (1/5) |

**Fix:** Extract shared `AddressRow(address, amount, prefix, network)` composable.

---

### 23. Unicode arrow literals should be named constants
| | |
|---|---|
| **File** | `ui/TransactionReviewScreen.kt:89,111` |
| **Consensus** | Rubyist (1/5) |

**Fix:** `const val OUTGOING_ARROW = "→"` / `const val RETURN_ARROW = "←"`

---

### 24. `HARDENED` constant redefined in multiple places
| | |
|---|---|
| **Files** | `psbt_parser.py:175`, `tests/test_psbt_parser.py:120,197,286` |
| **Consensus** | Pythonista (1/5) |

**Fix:** Define once at module level in `psbt_parser.py`, import in tests.

---

### 25. Magic byte `0x13` undocumented in signer.py
| | |
|---|---|
| **File** | `signer.py:228` |
| **Consensus** | Pythonista (1/5) |

**Fix:** `PSBT_IN_TAP_KEY_SIG = b"\x13"` with BIP-371 reference.

---

### 26. `match/case` modernization opportunity
| | |
|---|---|
| **File** | `signer.py:101-142` (`detect_script_type`) |
| **Consensus** | Pythonista (1/5) |

Long if/elif chain could use Python 3.10+ structural pattern matching. Optional.

---

## Recommended Execution Order

```
Phase 1: Quick wins (< 2 hours each)
  #5  Move PassphraseRequest/AccountPathRequest to bridge layer
  #6  Move constants from ui/ to config/ package
  #21 Add Locale.ROOT to formatBtcAmount()
  #20 Extract broadcast button loop
  #14 Add Log.w() to silent catch blocks
  #12 Standardize Python type hints
  #23 Named constants for Unicode arrows
  #24 HARDENED as module-level constant
  #25 Named constant for 0x13

Phase 2: Test coverage (high ROI)
  #1  Bridge serialization tests (malformed data)
  #2  Passphrase cancellation flow tests
  #3  Account path callback tests
  #8  Nostr relay reconnection tests
  #17 Record additional cassettes

Phase 3: Type safety & architecture
  #4  Type signPsbt() return as sealed class
  #11 Create Network enum
  #9  Refine Result state (broadcast sealed class)
  #10 Error state subtypes
  #18 State machine transition guards
  #19 AtomicReference for currentUsbBridge

Phase 4: Polish (fix as you touch)
  #7  Set up ktlint + ruff
  #13 Clean up ParsedTransaction dataclass
  #15 SaveContact return type
  #16 Specific Python exception types
  #22 Extract AddressRow composable
  #26 Consider match/case (optional)
```

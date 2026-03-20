# Satoshi Signer — Refactoring TODOs

> Comprehensive code review by 4 specialists: Android Engineer, Python Engineer,
> System Architect, and Rubyist (fresh-eyes reviewer). Findings deduplicated and
> prioritized by consensus across reviewers.
>
> Date: 2026-03-18

---

## How to Read This Document

- **Priority tiers**: P0 (bugs/security — fix now), P1 (architecture — fix before adding features), P2 (quality — fix as you touch the code), P3 (polish — nice to have)
- **Consensus column**: How many of the 4 reviewers independently flagged this issue
- **Test impact**: Whether fixing this also improves automated test coverage
- Items marked with 🧪 directly expand automated test coverage

---

## P0 — Bugs & Security (Fix Now)

### ~~1. Broadcast doesn't pass network parameter — RUNTIME BUG~~ ✅ FIXED
| | |
|---|---|
| **File** | `viewmodel/SignerViewModel.kt:513` |
| **Consensus** | System Architect |
| **Impact** | Testnet transactions submitted to mainnet endpoints, failing silently |

~~`pythonBridge.broadcast(state.rawHex)` doesn't pass the network. `PythonBridge.broadcast()` defaults to `"main"`. A testnet broadcast from the Result screen goes to mainnet.~~

~~**Fix:** Change to `pythonBridge.broadcast(state.rawHex, state.network)`.~~

**Fixed:** Now passes `state.network` to `pythonBridge.broadcast()`.

---

### ~~2. NostrEvent.verifyId accepts events when verification throws~~ ✅ FIXED
| | |
|---|---|
| **File** | `nostr/NostrEvent.kt:67-69` |
| **Consensus** | System Architect, Android Engineer, Rubyist (3/4) |
| **Impact** | Malformed events bypass integrity check; attacker can craft events that throw |

~~The catch block returns `true`, meaning any exception during SHA-256 verification causes the event to be accepted. For an app processing financial data (PSBTs), unverifiable events should be rejected.~~

~~**Fix:** Return `false` in the catch block.~~

**Fixed:** Catch block now returns `false`, rejecting events when ID verification throws. Test added in `NostrReceiverTest`.

---

### ~~3. PKCS7 unpad has no validation — padding oracle in nostr_signer~~ ✅ FIXED
| | |
|---|---|
| **File** | `nostr_signer/nostr_signer.py:91-93` |
| **Consensus** | Python Engineer |
| **Impact** | Wrong key/corrupted data produces silent garbage instead of clean error |

~~`_pkcs7_unpad` trusts the last byte blindly. `pad_len = 0` returns entire buffer; `pad_len > len(data)` silently slices wrong.~~

~~**Fix:** Validate pad_len range (1–16), verify all padding bytes equal pad_len, raise ValueError on mismatch.~~

**Fixed:** `_pkcs7_unpad` now validates: pad_len in range 1–16, pad_len ≤ data length, and all padding bytes equal pad_len. Raises `ValueError` on any mismatch. 10 tests added in `TestPkcs7Padding` including corrupted-ciphertext integration test.

---

### ~~4. Broadcaster silently falls back to mainnet for unknown network values~~ ✅ FIXED
| | |
|---|---|
| **File** | `remotesigner/broadcaster.py:28` |
| **Consensus** | Python Engineer |
| **Impact** | Typo like `network="testt"` broadcasts to mainnet without warning |

~~`ENDPOINTS.get(network, ENDPOINTS["main"])` silently falls back. For a transaction broadcast function, this is dangerous.~~

~~**Fix:** Raise `ValueError` for unrecognized network values.~~

**Fixed:** `broadcast_transaction` now raises `ValueError` for any network not in `ENDPOINTS`. Added testnet3, testnet4, and signet endpoints. `"test"` kept as backward-compat alias for testnet3.

---

### ~~5. No input validation on broadcast raw_hex parameter~~ ✅ FIXED
| | |
|---|---|
| **File** | `remotesigner/broadcaster.py:19` |
| **Consensus** | Python Engineer |
| **Impact** | Any string sent directly to mempool.space/blockstream |

~~No validation that the string is valid hex, parses as a transaction, or has reasonable length.~~

~~**Fix:** Validate hex encoding and add a size limit before HTTP request.~~

**Fixed:** `broadcast_transaction` now validates: non-empty string, valid hex encoding, even length, and 400KB max transaction size. Raises `ValueError` on any mismatch.

---

## P1 — Architecture (Fix Before Adding Features)

### ~~6. Extract responsibilities from SignerViewModel (God Object)~~ ✅ FIXED
| | |
|---|---|
| **File** | `viewmodel/SignerViewModel.kt` (652 lines) |
| **Consensus** | All 4 reviewers (4/4) |
| **Test impact** | 🧪 Enables ViewModel unit testing (currently impossible) |

~~The ViewModel handles: PSBT parsing, signing orchestration, USB polling, Nostr lifecycle, inbox CRUD, contact CRUD, NFC state, passphrase/account-path callbacks, and broadcast. Mutable `var` fields (`currentPsbtBytes`, `currentUsbBridge`, `currentSigningCallback`, `currentSigningInboxId`, `signingJob`) must be coordinated manually.~~

~~**Recommended decomposition:**~~
~~- `SigningOrchestrator` — manages signing flow, bridge lifecycle, callback wiring~~
~~- `InboxRepository` — wraps InboxDao, handles event parsing, inbox lifecycle~~
~~- `ContactRepository` — wraps ContactDao, fingerprint enrichment logic~~
~~- `SignerViewModel` — composition root, holds state, delegates to above, drives navigation~~

~~**Why this matters:** Every feature change requires reading 652 lines. Side-effect interactions between unrelated flows are invisible. Most importantly, the ViewModel can't be unit-tested because it directly instantiates `PythonBridge`, `TrezorUsbManager`, and `AppDatabase`.~~

**Fixed:** Extracted `ContactRepository`, `InboxRepository`, and `SigningOrchestrator`. Created `PythonBridgeInterface` for testability. ViewModel accepts all dependencies via constructor injection through `SignerViewModelFactory`. ViewModel reduced from ~680 to ~350 lines.

---

### ~~7. Create typed bridge response models~~ ✅ FIXED
| | |
|---|---|
| **File** | `bridge/PythonBridge.kt`, `viewmodel/SignerViewModel.kt:252-276` |
| **Consensus** | System Architect, Python Engineer (2/4) |
| **Test impact** | 🧪 Makes bridge contract testable at compile time |

~~Python returns `Map<String, Any?>`, Kotlin interprets via unchecked casts (`result["outputs"] as? List<Map<String, Any?>>`). A Python key rename silently produces null at runtime.~~

~~**Fix:** Define Kotlin data classes (`ParsedPsbtResult`, `SignResult`, `BroadcastResult`) and parse once in `PythonBridge`. On the Python side, define `TypedDict` return types. Two call sites parsing the same output (`parsePsbt` and `handleInboxEvent`) should share the same model.~~

**Fixed:** Created `ParsedPsbtResult` and `BroadcastResult` data classes in `bridge/BridgeModels.kt`. Moved `TxInput`, `TxOutput`, `SignerInfo` to the bridge package. `PythonBridge` now returns typed models via `toParseResult()` and `toBroadcastResult()` helpers. Consumers (`SignerViewModel`, `InboxRepository`) use typed property access — no more unchecked casts. Python TypedDicts added to `psbt_parser.py` and `broadcaster.py`. `signPsbt()` left as `Map<String, Any?>` since `SigningOrchestrator` already provides the typed `SigningResult` sealed class.

---

### ~~8. Unify error handling across Python-Kotlin boundary~~ ✅ FIXED
| | |
|---|---|
| **File** | Multiple (signer.py, broadcaster.py, psbt_parser.py, PythonBridge.kt) |
| **Consensus** | System Architect, Rubyist (2/4) |

~~Three different error patterns:~~
~~- `parse_psbt` raises `ValueError`~~
~~- `sign_psbt` returns `{"status": "error", "message": ...}`~~
~~- `broadcast_transaction` returns `{"status": "error", ...}` but failures become a string field, not Error state~~

~~**Fix:** Establish convention: Python functions either return dict with `"status"` key or raise. Create a Kotlin helper that maps the dict error pattern to `AppState.Error`. Document in CLAUDE.md.~~

**Fixed:** Documented two-pattern convention in CLAUDE.md: (1) validation functions raise exceptions, (2) operations with multiple outcomes return status dicts. Fixed bug where `SignerViewModel.broadcast()` didn't catch `ValueError` from Python input validation — added try-catch so invalid input shows a user-facing error instead of crashing. No Kotlin helper needed since typed models (`ParsedPsbtResult`, `BroadcastResult`, `SigningResult`) already handle error mapping at their respective call sites.

---

### ~~9. Refactor sign_psbt in Python (200-line function)~~ ✅ FIXED
| | |
|---|---|
| **File** | `remotesigner/signer.py:749-955` |
| **Consensus** | Python Engineer, Rubyist (2/4) |
| **Test impact** | 🧪 Smaller functions are independently testable |

~~A 200-line try/finally/try/except block handling: PSBT parsing, transport creation, fingerprint reading, path resolution, signing, and signature insertion.~~

~~**Fix:** Extract into: `_connect_and_get_fingerprint()`, `_resolve_paths()`, `_perform_signing()`, `_insert_signatures()`.~~

**Fixed:** Extracted `_connect_and_get_fingerprint()`, `_resolve_paths()`, `_perform_signing()`, `_insert_signatures()` from the 200-line `sign_psbt()`. Orchestrator is now ~35 lines. All existing tests pass unchanged. Fixed stale docstring (said "signed"/"error", actual statuses are "complete"/"partial"/"cancelled"/"error").

---

### ~~10. Deduplicate multisig script parsing~~ ✅ FIXED
| | |
|---|---|
| **File** | `psbt_parser.py:174-194` and `signer.py:159-238` |
| **Consensus** | Python Engineer |
| **Test impact** | 🧪 Single function easier to test exhaustively |

~~`_parse_multisig_info` in parser and `_parse_multisig_script` in signer both parse OP_CHECKMULTISIG scripts. Different scope but same core byte parsing.~~

~~**Fix:** Extract shared m/n/pubkeys parsing into `remotesigner/script_utils.py`.~~

**Fixed:** Extracted `parse_multisig_script()` and `MultisigInfo` dataclass into `remotesigner/script_utils.py`. `psbt_parser.py` and `signer.py` both delegate byte parsing to the shared function. 11 direct unit tests added in `test_script_utils.py`.

---

### ~~11. Deduplicate taproot vs. ECDSA derivation matching~~ ✅ FIXED
| | |
|---|---|
| **File** | `signer.py` (lines 492-515, 590-629, 389-404) |
| **Consensus** | Rubyist, Python Engineer (2/4) |

~~The pattern "if taproot, iterate taproot_bip32_derivations; else iterate bip32_derivations, checking fp_to_prefix then master_fp" appears 3 times with slight variations.~~

~~**Fix:** Extract `find_matching_derivation(scope, master_fp, fp_to_prefix, is_taproot)`.~~

**Fixed:** Extracted `_find_matching_derivation(scope, master_fp, fp_to_prefix, is_taproot)` which returns the resolved `address_n` or `None`. `psbt_to_trezor_inputs` and `psbt_to_trezor_outputs` both delegate to it. `_find_key_origin` simplified to iterate both derivation types in a single loop. 10 direct unit tests added in `TestFindMatchingDerivation`.

---

## P2 — Quality & Testability (Fix as You Touch the Code)

### ~~12. 🧪 Add ViewModel unit tests~~ ✅ FIXED
| | |
|---|---|
| **File** | New: `app/src/test/kotlin/com/remotesigner/viewmodel/SignerViewModelTest.kt` |
| **Consensus** | System Architect, Android Engineer (2/4) |
| **Depends on** | #6 (extract responsibilities to make testable) |

~~The most complex component has zero JVM unit tests. With extracted dependencies (#6), test:~~
~~- State transitions: Home → TransactionReview → Signing → Result~~
~~- Cancellation mid-signing~~
~~- Inbox status transitions~~
~~- Contact CRUD methods~~
~~- Error propagation from Python bridge~~

**Fixed:** 55 JVM unit tests added using mockk + kotlinx-coroutines-test. Covers: state transitions (Home → TransactionReview → Signing → Result), cancellation mid-signing, inbox status transitions (SIGNING/SIGNED/BROADCAST/FAILED/PENDING/DELETED), contact CRUD with signer re-enrichment, broadcast success/error/exception paths, NFC state management, goHome inbox status preservation, and error propagation from Python bridge. Added `unitTests.isReturnDefaultValues = true` to enable JVM testing of AndroidViewModel without Robolectric.

---

### ~~13. 🧪 Add direct tests for _is_psbt_fully_signed~~ ✅ FIXED
| | |
|---|---|
| **File** | New test cases in `tests/test_signer.py` |
| **Consensus** | Python Engineer |

~~This critical function determines broadcast-readiness. Three code paths (taproot key-path, multisig threshold, single-sig ECDSA) — none have direct unit tests.~~

**Fixed:** 25 direct unit tests added in `TestIsPsbtFullySigned` covering all three code paths: taproot key-path (PSBT_IN_TAP_KEY_SIG), multisig threshold (2-of-3, 1-of-2, 3-of-3, excess sigs), and single-sig ECDSA. Also covers: witness_script vs redeem_script priority, P2SH-P2WSH witness program bypass, bare P2SH multisig, mixed input types, and edge cases (empty PSBT, short scripts, non-multisig witness scripts).

---

### 14. 🧪 Fix permanently skipped Python tests (missing fixtures)
| | |
|---|---|
| **File** | `tests/test_psbt_parser.py` |
| **Consensus** | Python Engineer |

`TestParseMultisigPsbt` and `TestParseOpReturnPsbt` reference PSBT files (`trezor.multisig.2.a-ads-7d42c2e3.psbt`, `aa_cold3_watch-f1516d7b.psbt`) that don't exist in `tests/psbts/`. These regression tests are permanently skipped.

**Fix:** Add missing PSBT fixtures or rewrite tests with synthetic PSBT data.

---

### ~~15. 🧪 Move Bech32Test to JVM tests~~ ✅ FIXED
| | |
|---|---|
| **File** | `app/src/androidTest/.../Bech32Test.kt` → `app/src/test/.../Bech32Test.kt` |
| **Consensus** | Android Engineer |

~~Pure Kotlin with no Android dependencies, but runs as instrumented test (requires emulator). Moving to `test/` makes it a fast JVM test.~~

**Fixed:** Moved to `app/src/test/kotlin/com/remotesigner/nostr/Bech32Test.kt` as a JVM unit test. Package updated to `com.remotesigner.nostr` to colocate with `Bech32.kt` source. All 4 tests (encode, decode, round-trip, nsec) pass without emulator.

---

### ~~16. 🧪 Add NostrReceiver message handling tests~~ ✅ FIXED
| | |
|---|---|
| **File** | Expand `app/src/androidTest/.../NostrReceiverTest.kt` |
| **Consensus** | System Architect |

~~`handleMessage` does event parsing, deduplication, NIP-04 decryption, and PSBT extraction. Should cover: malformed events, duplicate events, events with invalid NIP-04 content.~~

**Fixed:** 7 handleMessage tests added using a sentinel-event pattern (no Thread.sleep). Covers: NOTICE/EOSE messages ignored, events with missing JSON fields dropped, non-kind-4 events filtered, invalid NIP-04 content dropped, non-JSON decrypted payload dropped, missing "tx" field dropped, and pre-seeded event IDs skipped via `seedSeenIds()`.

---

### 17. 🧪 Add ContactsScreen Compose UI tests
| | |
|---|---|
| **File** | New: `app/src/androidTest/.../ContactsScreenTest.kt` |
| **Consensus** | Android Engineer |

ContactsScreen has add/edit/delete dialogs and fingerprint management but no Compose UI tests. Other screens are well-covered by `ScreenRenderTest`.

---

### 18. 🧪 Add negative-path Python signing tests
| | |
|---|---|
| **File** | Expand `tests/test_signer.py` |
| **Consensus** | System Architect |

What happens with: truncated PSBT? No matching fingerprint? Relative paths and no callback? These error paths need coverage.

---

### 19. Fix StateFlow race conditions in ViewModel (partially fixed)
| | |
|---|---|
| **File** | `viewmodel/SignerViewModel.kt` (lines 613, 506-531) |
| **Consensus** | Android Engineer |

`reEnrichSigners()` and `broadcast()` both read-modify-write `_state` non-atomically. Meanwhile other flows may also update state.

**Partially fixed:** `goHome()` had a race where it read a stale `inboxItems` StateFlow snapshot (which hadn't yet propagated the `updateBroadcast` Room write) and overwrote BROADCAST status back to SIGNED. Fixed by checking the authoritative in-memory `AppState.Result.txid` instead of the stale Flow.

**Remaining:** Use `_state.update { currentState -> ... }` (atomic update API on MutableStateFlow) instead of `val s = _state.value; _state.value = s.copy(...)` for `reEnrichSigners()` and `broadcast()`.

---

### 20. AppState.Result ByteArray equality issue
| | |
|---|---|
| **File** | `viewmodel/SignerViewModel.kt:73` |
| **Consensus** | Android Engineer |

`AppState.Result` is a data class with `ByteArray` field. Data class `equals()`/`hashCode()` use reference equality for arrays, causing unnecessary recompositions.

**Fix:** Override `equals`/`hashCode` or wrap `ByteArray` in an inline class with structural equality.

---

### 21. Nostr seenIds grows unboundedly
| | |
|---|---|
| **File** | `nostr/NostrReceiver.kt:47` |
| **Consensus** | Android Engineer |

`seenIds` accumulates event IDs for the ViewModel lifetime. Long sessions could accumulate thousands.

**Fix:** Use a bounded `LinkedHashSet` with max size check, or clear old IDs periodically.

---

### 22. Nostr private key in plaintext SharedPreferences
| | |
|---|---|
| **File** | `nostr/NostrKeyManager.kt:14` |
| **Consensus** | System Architect |

The Nostr secret key (used for NIP-04 PSBT decryption) is stored as hex in `SharedPreferences`. On rooted devices, another app could decrypt all incoming PSBTs.

**Fix:** Use `EncryptedSharedPreferences` from Jetpack Security library.

---

### 23. No PSBT size limit
| | |
|---|---|
| **File** | `psbt_parser.py:34`, `signer.py:789` |
| **Consensus** | System Architect, Python Engineer (2/4) |

Both `parse_psbt` and `sign_psbt` accept arbitrary `psbt_bytes` without size limit. PSBTs arrive from untrusted Nostr relays.

**Fix:** Enforce a reasonable size limit (e.g., 1MB) before parsing.

---

### 24. Replace Thread.sleep in tests with deterministic synchronization
| | |
|---|---|
| **File** | `app/src/androidTest/.../NostrReceiverTest.kt:131,165` |
| **Consensus** | Android Engineer |

`Thread.sleep(500)` and `Thread.sleep(3000)` are inherently flaky. The same file uses `CountDownLatch` elsewhere.

**Fix:** Use `CountDownLatch` or polling with timeout for all assertions.

---

### 25. Undeclared `ecdsa` dependency in nostr_signer
| | |
|---|---|
| **File** | `nostr_signer/nostr_signer.py:69` |
| **Consensus** | Python Engineer |

`from ecdsa import SECP256k1, SigningKey` — relies on transitive dependency from `trezor`. If `trezor` drops `ecdsa`, nostr_signer breaks.

**Fix:** Add `ecdsa` to requirements or refactor to use `embit` for ECDH.

---

## P3 — Polish (Nice to Have)

### 26. Move data classes out of SignerViewModel
| | |
|---|---|
| **Files** | `viewmodel/SignerViewModel.kt:34-89` |
| **Consensus** | System Architect, Rubyist (2/4) |

`TxInput`, `TxOutput`, `SignerInfo`, `AppState`, `PassphraseRequest`, `AccountPathRequest` — shared domain types referenced by all UI code. Move to `viewmodel/Models.kt`.

---

### 27. Move InboxItemEntity to data package
| | |
|---|---|
| **File** | `nostr/NostrInbox.kt` → `data/InboxItemEntity.kt` |
| **Consensus** | System Architect, Rubyist (2/4) |

Room entity in `nostr` package creates circular dependency with `data` package. Entity belongs with other Room entities.

---

### 28. Consolidate formatBtc / formatBtcAmount
| | |
|---|---|
| **Files** | `ui/TransactionReviewScreen.kt:211`, `nostr/NostrInbox.kt:42` |
| **Consensus** | System Architect, Android Engineer, Rubyist (3/4) |

Two functions doing `"%.8f BTC".format(satoshis / 100_000_000.0)`. Create a shared `Formatters.kt` utility.

---

### 29. Consolidate clipboard copy pattern
| | |
|---|---|
| **Files** | `ui/HomeScreen.kt:86-89`, `ui/SigningScreen.kt:121-124`, `ui/ErrorScreen.kt:36-38` |
| **Consensus** | Rubyist |

Same ClipboardManager + ClipData + Toast pattern repeated 3 times.

**Fix:** Extract `copyToClipboard(context, label, text)` utility.

---

### 30. Extract magic numbers as named constants
| | |
|---|---|
| **File** | `viewmodel/SignerViewModel.kt` (lines 155, 292, 570), `nostr/NostrReceiver.kt:105` |
| **Consensus** | Rubyist |

`86_400`, `86_400 * 7`, `1_000_000` (fee threshold), `50` (label max), `86400` (subscription lookback).

**Fix:** Named constants: `PENDING_EXPIRY_SECONDS`, `SIGNED_EXPIRY_SECONDS`, `HIGH_FEE_THRESHOLD_SATS`, `MAX_LABEL_LENGTH`.

---

### 31. Remove unused navigation-compose dependency
| | |
|---|---|
| **File** | `app/build.gradle.kts:74` |
| **Consensus** | System Architect, Android Engineer (2/4) |

App uses sealed-class state machine, not Jetpack Navigation. Dead dependency.

---

### 32. Remove unused DAO methods (exists, upsert)
| | |
|---|---|
| **File** | `data/InboxDao.kt:26-30` |
| **Consensus** | Android Engineer |

`exists()` and `upsert()` declared but only used in tests, not production. Production uses `insertIgnore` and targeted updates.

---

### 33. Enable R8 minification for release builds
| | |
|---|---|
| **File** | `app/build.gradle.kts:29` |
| **Consensus** | System Architect, Android Engineer (2/4) |

`isMinifyEnabled = false` means release APK ships with full debug symbols. Requires ProGuard rules for Chaquopy, Room, and secp256k1-kmp.

---

### 34. Lazy-initialize PythonBridge
| | |
|---|---|
| **File** | `viewmodel/SignerViewModel.kt:96` |
| **Consensus** | Android Engineer |

`PythonBridge()` calls `Python.getInstance()` eagerly in ViewModel constructor, blocking the main thread on first launch.

**Fix:** `private val pythonBridge by lazy { PythonBridge() }`.

---

### 35. Pin embit dependency version
| | |
|---|---|
| **Files** | `requirements-dev.txt`, `app/build.gradle.kts` |
| **Consensus** | Python Engineer |

`embit>=0.7` is unpinned while `trezor==0.13.9` is pinned. A breaking embit change could silently break PSBT parsing.

---

### 36. Fix dead TEST_PSBT_B64 assignment in test_psbt_parser.py
| | |
|---|---|
| **File** | `tests/test_psbt_parser.py:14-25` |
| **Consensus** | Python Engineer |

First assignment contains a space in the base64 and is immediately overwritten by the corrected version. Dead code.

---

### 37. Taproot signature marking is imprecise for multi-key taproot
| | |
|---|---|
| **File** | `remotesigner/psbt_parser.py:230-235` |
| **Consensus** | Python Engineer |

When `has_tap_sig` is True, ALL signers in `taproot_bip32_derivations` are marked signed regardless of which key actually signed.

**Fix:** Match specific pubkey in `taproot_sigs` against the derivation's pubkey.

---

## Testing Coverage Map

### Current state (what's well-tested)

| Area | Test Type | Coverage |
|------|-----------|----------|
| PSBT parsing | Python unit tests | Good — single-sig, multisig, taproot, address types |
| Trezor signing E2E | Cassette replay (Python + Android) | Good — happy path |
| Room DAOs | Instrumented tests | Good — ContactDao, InboxDao, migration |
| Compose screens | Instrumented UI tests | Good — HomeScreen, TransactionReview, SigningScreen, InboxScreen, navigation |
| Nostr crypto | Instrumented + JVM tests | Good — Bech32 (JVM), NIP-04, key management |
| NDEF parsing | JVM unit tests | Good — all text encoding variants |
| Pure utilities | JVM unit tests | Good — FingerprintValidator, MempoolUrl |

### Gaps to close (ordered by risk)

| Gap | Test Type Needed | Risk Level | Depends On |
|-----|-----------------|------------|------------|
| ViewModel state machine | JVM unit tests | **High** | #6 (extract deps) |
| `_is_psbt_fully_signed` | Python unit tests | **High** | None |
| Signing error paths | Python unit tests | **Medium** | None |
| ~~NostrReceiver message handling~~ | ~~Instrumented tests~~ | ~~**Medium**~~ | ~~Done (#16)~~ |
| ContactsScreen UI | Instrumented UI tests | **Medium** | None |
| Broadcast integration | Python integration tests | **Low** | None |
| ~~Bech32 (move to JVM)~~ | ~~JVM unit tests~~ | ~~**Low**~~ | ~~Done (#15)~~ |

### Testing recommendations

1. **ViewModel unit tests** are the single highest-impact addition. Today, the most complex component is only tested through full-stack E2E requiring an emulator and Chaquopy. Extract dependencies (#6) to enable mock/fake injection, then test state transitions, error propagation, and edge cases.

2. **Python negative-path tests** for signing would catch errors that cassettes can't — cassettes only replay happy paths. Test: truncated PSBT, no matching fingerprint, network mismatch, PSBT size limits.

3. **Move pure-Kotlin tests to JVM** (`Bech32Test`, potentially `NostrEvent` parsing) to reduce the emulator-required test surface. Faster feedback loop, runs in CI without emulator.

4. **Replace Thread.sleep with deterministic sync** in `NostrReceiverTest` to eliminate flaky tests.

---

## Recommended Execution Order

```
Phase 1: Fix bugs (P0, items 1-5)
  └── All independent, no prerequisites, low risk

Phase 2: Architecture foundations (P1, items 6-8)
  └── #6 Extract ViewModel → enables #12 ViewModel unit tests
  └── #7 Typed bridge models → enables compile-time contract checking
  └── #8 Unified error handling → establishes pattern for all future work

Phase 3: Python refactoring (P1, items 9-11)
  └── #9 Break up sign_psbt → enables #13 and #18 (function-level tests)
  └── #10-11 Deduplicate script/derivation parsing → reduces bug surface

Phase 4: Test coverage expansion (P2, items 12-18)
  └── Depends on Phase 2-3 decomposition
  └── Each test item is independent of others

Phase 5: Quality & polish (P2-P3, items 19-37)
  └── Fix as you touch the code — no dedicated sprint needed
```

---

## What All Reviewers Agreed to Preserve

- **Two-language bridge pattern** — clean boundary, well-documented rationale
- **USB bridge inversion** — correct solution to a hard problem, great comments
- **Cassette-based E2E testing** — elegant hardware-free testing strategy
- **Sealed class state machine** — right level of abstraction for this app
- **Python desktop testability** — all Python modules are Android-agnostic
- **Room database design** — clean schema, targeted SQL updates, proper migrations
- **CLAUDE.md documentation** — "one of the best project documentation files" (Rubyist)

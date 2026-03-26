# Satoshi Signer — Resolved Refactoring Items

> Archive of completed items from code reviews. Moved here to keep the active
> TODO list scannable. See `refactoring-todos.md` for open items.

---

## Round 1 (2026-03-18)

> Review by 4 specialists: Android Engineer, Python Engineer, System Architect,
> and Rubyist (fresh-eyes reviewer). All 37 items resolved.

### P0 — Bugs & Security

#### 1. Broadcast doesn't pass network parameter — RUNTIME BUG ✅
| | |
|---|---|
| **File** | `viewmodel/SignerViewModel.kt:513` |
| **Consensus** | System Architect |

Fixed: Now passes `state.network` to `pythonBridge.broadcast()`.

---

#### 2. NostrEvent.verifyId accepts events when verification throws ✅
| | |
|---|---|
| **File** | `nostr/NostrEvent.kt:67-69` |
| **Consensus** | System Architect, Android Engineer, Rubyist (3/4) |

Fixed: Catch block now returns `false`. Test added in `NostrReceiverTest`.

---

#### 3. PKCS7 unpad has no validation — padding oracle in nostr_signer ✅
| | |
|---|---|
| **File** | `nostr_signer/nostr_signer.py:91-93` |
| **Consensus** | Python Engineer |

Fixed: `_pkcs7_unpad` validates pad_len range, byte consistency. 10 tests added.

---

#### 4. Broadcaster silently falls back to mainnet for unknown network values ✅
| | |
|---|---|
| **File** | `remotesigner/broadcaster.py:28` |
| **Consensus** | Python Engineer |

Fixed: Raises `ValueError` for unrecognized network. Added testnet3/4/signet endpoints.

---

#### 5. No input validation on broadcast raw_hex parameter ✅
| | |
|---|---|
| **File** | `remotesigner/broadcaster.py:19` |
| **Consensus** | Python Engineer |

Fixed: Validates hex encoding, even length, 400KB max.

---

### P1 — Architecture

#### 6. Extract responsibilities from SignerViewModel (God Object) ✅
| | |
|---|---|
| **File** | `viewmodel/SignerViewModel.kt` (652 lines) |
| **Consensus** | All 4 reviewers (4/4) |

Fixed: Extracted `ContactRepository`, `InboxRepository`, `SigningOrchestrator`. Created `PythonBridgeInterface`. ViewModel reduced from ~680 to ~350 lines.

---

#### 7. Create typed bridge response models ✅
| | |
|---|---|
| **File** | `bridge/PythonBridge.kt`, `viewmodel/SignerViewModel.kt:252-276` |
| **Consensus** | System Architect, Python Engineer (2/4) |

Fixed: Created `ParsedPsbtResult`, `BroadcastResult` in `bridge/BridgeModels.kt`. `signPsbt()` left as `Map<String, Any?>` since `SigningOrchestrator` already provides typed `SigningResult`.

---

#### 8. Unify error handling across Python-Kotlin boundary ✅
| | |
|---|---|
| **File** | Multiple |
| **Consensus** | System Architect, Rubyist (2/4) |

Fixed: Documented two-pattern convention in CLAUDE.md. Fixed bug where `broadcast()` didn't catch `ValueError`.

---

#### 9. Refactor sign_psbt in Python (200-line function) ✅
| | |
|---|---|
| **File** | `remotesigner/signer.py:749-955` |
| **Consensus** | Python Engineer, Rubyist (2/4) |

Fixed: Extracted `_connect_and_get_fingerprint()`, `_resolve_paths()`, `_perform_signing()`, `_insert_signatures()`. Orchestrator now ~35 lines.

---

#### 10. Deduplicate multisig script parsing ✅
| | |
|---|---|
| **File** | `psbt_parser.py:174-194` and `signer.py:159-238` |
| **Consensus** | Python Engineer |

Fixed: Shared `parse_multisig_script()` in `script_utils.py`. 11 tests added.

---

#### 11. Deduplicate taproot vs. ECDSA derivation matching ✅
| | |
|---|---|
| **File** | `signer.py` (lines 492-515, 590-629, 389-404) |
| **Consensus** | Rubyist, Python Engineer (2/4) |

Fixed: Extracted `_find_matching_derivation()`. 10 tests added.

---

### P2 — Quality & Testability

#### 12. 🧪 Add ViewModel unit tests ✅
| | |
|---|---|
| **File** | New: `app/src/test/kotlin/com/remotesigner/viewmodel/SignerViewModelTest.kt` |
| **Consensus** | System Architect, Android Engineer (2/4) |

Fixed: 55 JVM unit tests covering state transitions, cancellation, inbox, contacts, broadcast, NFC, error propagation.

---

#### 13. 🧪 Add direct tests for _is_psbt_fully_signed ✅
| | |
|---|---|
| **File** | `tests/test_signer.py` |
| **Consensus** | Python Engineer |

Fixed: 25 tests covering taproot key-path, multisig threshold, single-sig ECDSA.

---

#### 14. 🧪 Fix permanently skipped Python tests (missing fixtures) ✅
| | |
|---|---|
| **File** | `tests/test_psbt_parser.py` |
| **Consensus** | Python Engineer |

Fixed: Replaced file-based fixtures with inline embit-constructed PSBTs. All 8 tests run.

---

#### 15. 🧪 Move Bech32Test to JVM tests ✅
| | |
|---|---|
| **File** | `app/src/androidTest/.../Bech32Test.kt` → `app/src/test/.../Bech32Test.kt` |
| **Consensus** | Android Engineer |

Fixed: Moved to JVM. All 4 tests pass without emulator.

---

#### 16. 🧪 Add NostrReceiver message handling tests ✅
| | |
|---|---|
| **File** | `app/src/androidTest/.../NostrReceiverTest.kt` |
| **Consensus** | System Architect |

Fixed: 7 handleMessage tests using sentinel-event pattern.

---

#### 17. 🧪 Add ContactsScreen Compose UI tests ✅
| | |
|---|---|
| **File** | New: `app/src/androidTest/.../ContactsScreenTest.kt` |
| **Consensus** | Android Engineer |

Fixed: 20 Compose UI tests covering CRUD, validation, dialogs.

---

#### 18. 🧪 Add negative-path Python signing tests ✅
| | |
|---|---|
| **File** | `tests/test_signer.py` |
| **Consensus** | System Architect |

Fixed: 11 negative-path tests covering truncated PSBT, USB failure, missing fingerprint, relative paths.

---

#### 19. Fix StateFlow race conditions in ViewModel ✅
| | |
|---|---|
| **File** | `viewmodel/SignerViewModel.kt` |
| **Consensus** | Android Engineer |

Fixed: All read-modify-write patterns replaced with `_state.update {}` atomic API. 3 regression tests.

---

#### 20. AppState.Result ByteArray equality issue ✅
| | |
|---|---|
| **File** | `viewmodel/SignerViewModel.kt:73` |
| **Consensus** | Android Engineer |

Fixed: Overrode `equals()`/`hashCode()` with `contentEquals()`/`contentHashCode()`. 5 tests.

---

#### 21. Nostr seenIds grows unboundedly — WON'T FIX
| | |
|---|---|
| **File** | `nostr/NostrReceiver.kt:47` |
| **Consensus** | Android Engineer |

Won't fix: A few KB at most in practice. Bounding adds complexity for a non-problem.

---

#### 22. Nostr private key in plaintext SharedPreferences ✅
| | |
|---|---|
| **File** | `nostr/NostrKeyManager.kt:14` |
| **Consensus** | System Architect |

Fixed: Replaced with `EncryptedSharedPreferences`. Old plaintext prefs deleted on construction.

---

#### 23. No PSBT size limit ✅
| | |
|---|---|
| **File** | `psbt_parser.py:34`, `signer.py:789` |
| **Consensus** | System Architect, Python Engineer (2/4) |

Fixed: `MAX_PSBT_SIZE = 1_048_576`. 5 tests added.

---

#### 24. Replace Thread.sleep in tests with deterministic synchronization ✅
| | |
|---|---|
| **File** | `app/src/androidTest/.../NostrReceiverTest.kt` |
| **Consensus** | Android Engineer |

Fixed: Sentinel-event pattern and `CountDownLatch` replace all `Thread.sleep`.

---

#### 25. Undeclared `ecdsa` dependency in nostr_signer ✅
| | |
|---|---|
| **File** | `nostr_signer/nostr_signer.py:69` |
| **Consensus** | Python Engineer |

Fixed: Refactored to use embit's secp256k1 bindings. Transitive dependency eliminated.

---

### P3 — Polish

#### 26. Move data classes out of SignerViewModel ✅
| | |
|---|---|
| **Files** | `viewmodel/SignerViewModel.kt:34-89` |
| **Consensus** | System Architect, Rubyist (2/4) |

Fixed: Moved to `viewmodel/Models.kt` and `bridge/BridgeModels.kt`.

---

#### 27. Move InboxItemEntity to data package ✅
| | |
|---|---|
| **File** | `nostr/NostrInbox.kt` → `data/InboxItemEntity.kt` |
| **Consensus** | System Architect, Rubyist (2/4) |

Fixed: Updated imports across 13 files.

---

#### 28. Consolidate formatBtc / formatBtcAmount ✅
| | |
|---|---|
| **Files** | `ui/TransactionReviewScreen.kt:211`, `nostr/NostrInbox.kt:42` |
| **Consensus** | System Architect, Android Engineer, Rubyist (3/4) |

Fixed: Single `formatBtcAmount()` in `ui/Formatters.kt`. 5 tests.

---

#### 29. Consolidate clipboard copy pattern ✅
| | |
|---|---|
| **Files** | `ui/HomeScreen.kt`, `ui/SigningScreen.kt`, `ui/ErrorScreen.kt` |
| **Consensus** | Rubyist |

Fixed: Extracted `copyToClipboard()` into `ui/Formatters.kt`. 4 duplicate patterns removed.

---

#### 30. Extract magic numbers as named constants ✅
| | |
|---|---|
| **File** | `viewmodel/SignerViewModel.kt`, `nostr/NostrReceiver.kt` |
| **Consensus** | Rubyist |

Fixed: 6 named constants in `ui/Formatters.kt`. Replaced across 7 files.

---

#### 31. Remove unused navigation-compose dependency ✅
| | |
|---|---|
| **File** | `app/build.gradle.kts:74` |
| **Consensus** | System Architect, Android Engineer (2/4) |

Fixed: Removed from `build.gradle.kts` and `libs.versions.toml`.

---

#### 32. Remove unused DAO methods (exists, upsert) ✅
| | |
|---|---|
| **File** | `data/InboxDao.kt:26-30` |
| **Consensus** | Android Engineer |

Fixed: Removed `exists()` and `upsert()`. Updated tests.

---

#### 33. Enable R8 minification for release builds ✅
| | |
|---|---|
| **File** | `app/build.gradle.kts:29` |
| **Consensus** | System Architect, Android Engineer (2/4) |

Fixed: R8 enabled. ProGuard rules for Chaquopy, secp256k1-kmp, ZXing. APK 68MB → 40MB.

---

#### 34. Lazy-initialize PythonBridge ✅
| | |
|---|---|
| **File** | `bridge/PythonBridge.kt` |
| **Consensus** | Android Engineer |

Fixed: All 4 properties lazy. Python runtime only initializes on first bridge call.

---

#### 35. Pin embit dependency version ✅
| | |
|---|---|
| **Files** | `requirements-dev.txt`, `app/build.gradle.kts` |
| **Consensus** | Python Engineer |

Fixed: Pinned `embit==0.8.0` in both files.

---

#### 36. Fix dead TEST_PSBT_B64 assignment in test_psbt_parser.py ✅
| | |
|---|---|
| **File** | `tests/test_psbt_parser.py:14-25` |
| **Consensus** | Python Engineer |

Fixed: Removed dead multi-line assignment.

---

#### 37. Taproot signature marking is imprecise for multi-key taproot ✅
| | |
|---|---|
| **File** | `remotesigner/psbt_parser.py:230-235` |
| **Consensus** | Python Engineer |

Fixed: Per-pubkey matching via `tap_signed_pubs` set. 3 tests added.

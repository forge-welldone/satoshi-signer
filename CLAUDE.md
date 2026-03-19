# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

Satoshi Signer — an Android app for signing Bitcoin PSBTs with a Trezor hardware wallet via USB-C. PSBTs arrive via file picker, Nostr relay, or clipboard; passphrase entry supports on-device, on-phone, or NFC tag import. Kotlin/Compose UI talks to Python backend (embedded via Chaquopy) which handles PSBT parsing, Trezor signing, and transaction broadcasting.

## Build & Test Commands

```bash
# Build debug APK (Chaquopy auto-downloads Python 3.13 + pip deps on first build)
./gradlew assembleDebug

# Build and install on connected device
./gradlew installDebug

# Run Android smoke tests (requires running emulator)
./gradlew connectedDebugAndroidTest

# Run a specific Android test class
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.AppLaunchTest

# Run Python tests (desktop, no Android needed)
python -m pytest tests/ -v

# Run a single test file
python -m pytest tests/test_psbt_parser.py -v

# Run a single test
python -m pytest tests/test_psbt_parser.py::test_function_name -v

# Desktop signing with real Trezor (requires libusb: brew install libusb)
python tests/sign_cli.py --network test parse path/to/file.psbt
python tests/sign_cli.py --network test sign path/to/file.psbt
python tests/sign_cli.py --network test sign path/to/file.psbt --record tests/cassettes/name.json

# Run JVM unit tests (no emulator needed)
./gradlew testDebugUnitTest
```

Python test setup requires a venv: `python3 -m venv .venv && source .venv/bin/activate && pip install -r requirements-dev.txt`

Desktop signing also requires `brew install libusb` (Trezor Safe 3 uses WebUSB on macOS).

Android test setup requires a running emulator: `emulator -avd test_device -no-audio &`

## Architecture

**Two-language bridge pattern:** Kotlin handles UI, USB, and Android lifecycle. Python handles all Bitcoin logic (PSBT parsing, trezorlib signing, broadcasting). They communicate via Chaquopy.

```
Compose UI (7 screens) → SignerViewModel (sealed class state machine)
    → PythonBridge (Chaquopy) → Python modules
    → TrezorUsbManager / UsbBridge (Android USB Host API)
    → NostrReceiver (WebSocket) → Nostr relays (PSBT delivery)
    → NFC reader mode (Activity) → NDEF text tags (passphrase import)
```

**State machine drives navigation** — no NavController. `SignerViewModel` holds a `StateFlow<AppState>` with states: `Home → TransactionReview → Signing → Result` (plus `Error`, `Contacts`, and `EncryptPassphrase`). UI renders the screen matching current state.

**USB bridge inversion:** Python's trezorlib needs USB access, but Android USB APIs are Kotlin-only. Solution: Kotlin's `UsbBridge` does raw 64-byte interrupt endpoint I/O, Python's `AndroidTransport`/`AndroidHandle` wrap it to satisfy trezorlib's `Transport` protocol. Python calls back into Kotlin for every USB read/write.

**Key constraint:** Trezor uses interrupt endpoints (not bulk). Must use `UsbRequest.queue()` + `requestWait()`, not `bulkTransfer()`. USB read timeout is 10 minutes to accommodate slow passphrase entry on the Trezor's screen.

**USB lifecycle owned by Kotlin** — `UsbBridge.open()`/`close()` must be called from Kotlin (IO dispatcher), not from Chaquopy's Python thread. `claimInterface()` fails when called from Chaquopy's native thread. `AndroidHandle.open()`/`close()` are no-ops; the ViewModel opens the bridge before calling Python and closes it in `finally`.

**Trezor exposes two USB interfaces** — Interface 0: VENDOR_SPEC (0xFF, WebUSB), Interface 1: HID (0x03). Both have interrupt endpoints. Firmware responds on WebUSB (interface 0), so prefer VENDOR_SPEC over HID when claiming.

**Network auto-detected from PSBT** — `parse_psbt` reads BIP32 derivation paths: coin_type 1 = testnet, coin_type 0 = mainnet. The detected network is passed through to `sign_psbt` so the correct coin name ("Bitcoin" vs "Testnet") is used. Since BIP32 coin_type is `1` for all testnet variants (testnet3, testnet4, signet), auto-detection can only distinguish mainnet vs "not mainnet" — the specific testnet variant is chosen by the user at broadcast time.

**PythonBridge uses JSON round-trip** — Chaquopy's `toJava(Object.class)` doesn't recursively convert nested Python dicts/lists. `PythonBridge` serializes Python return values with `json.dumps`, then parses in Kotlin with `org.json.JSONObject`.

## Source Layout

- `app/src/main/kotlin/com/remotesigner/` — Kotlin source (UI, ViewModel, USB, bridge, Nostr)
- `app/src/main/kotlin/com/remotesigner/data/` — Room database, entities (`Contact`, `ContactFingerprint`, `InboxItemEntity`), DAOs, fingerprint validation
- `app/src/main/kotlin/com/remotesigner/nfc/` — NFC NDEF text parsing (`NdefTextParser`, `NfcReadResult`)
- `app/src/main/kotlin/com/remotesigner/nostr/` — Nostr transport (keypair, NIP-04 crypto, WebSocket receiver, inbox model)
- `app/src/main/python/remotesigner/` — Python modules (psbt_parser, signer, broadcaster, usb_transport, trezor_ui)
- `app/src/androidTest/kotlin/com/remotesigner/` — Android instrumented tests (Compose UI + Chaquopy E2E with cassette replay)
- `app/src/androidTest/assets/cassettes/` — Cassette copies for Android E2E tests (copied from `tests/cassettes/`)
- `app/pip_wheels/` — Pre-built Python wheels for Chaquopy (embit)
- `app/src/test/kotlin/com/remotesigner/nfc/` — JVM unit tests for NDEF parsing (no Android needed)
- `app/schemas/` — Room schema JSON exports for migration testing
- `tests/` — Desktop Python tests (pytest), desktop bridge classes, CLI, recorded cassettes
- `tests/cassettes/` — Recorded Trezor USB exchanges for hardware-free E2E test replay
- `docs/superpowers/specs/` — Design specifications
- `nostr_signer/` — Electrum plugin for sending PSBTs over Nostr (uses `electrum_aionostr` + `electrum_ecc`)

## Key Dependencies

- **Chaquopy 17.0.0** — Embeds Python 3.13 in Android
- **trezor 0.13.9** (Python) — Official Trezor signing library
- **embit ≥0.7** (Python) — Lightweight PSBT parsing
- **Compose BOM 2024.12.01** — Jetpack Compose UI
- **OkHttp 4.12.0** — WebSocket client for Nostr relay connections
- **secp256k1-kmp 0.22.0** (`fr.acinq.secp256k1:secp256k1-kmp-jni-android`) — secp256k1 ECDH for NIP-04 encryption/decryption. Lightweight JNI wrapper around Bitcoin's libsecp256k1.
- **ZXing 3.5.3** (`com.google.zxing:core`) — QR code generation for npub display
- **Room 2.6.1** (`androidx.room`) — Local SQLite database for contacts and inbox (with KSP annotation processor)
- Versions managed in `gradle/libs.versions.toml`

## Design Decisions to Preserve

- **Direct trezorlib, not HWI** — HWI's dependency tree (hidapi, pyserial) doesn't work under Chaquopy. We use trezorlib directly with a custom transport.
- **Minimal state app** — No wallet storage, no caching. Killed process loses the in-progress transaction. Persisted state: Room database (`satoshi-signer.db`) for cosigner contacts and Nostr inbox items.
- **App never touches private keys** — All signing happens on Trezor's secure element. No seed phrases or key material on phone.
- **Python modules are desktop-testable** — The bridge pattern keeps Python code Android-agnostic so `pytest` works without an emulator. Desktop signing uses `DesktopUsbBridge` (WebUSB/HID) in place of Kotlin's `UsbBridge`. Recorded USB cassettes enable E2E test replay without hardware.
- **`SigningBridge` interface** — Common interface (`open`/`close`/`writeChunk`/`readChunk`) implemented by `UsbBridge` (production) and `PlaybackBridge` (tests). Enables cassette-driven E2E tests on the Android emulator via `signWithBridge()`. Test path passes `null` callback to avoid passphrase dialog deadlock (Python falls back to on-device passphrase).
- **Safe 3 PIN is on-device only** — No host-side PIN matrix. `get_pin()` raises. Passphrase entry is user's choice: on-device (default) or on-phone. When trezorlib calls `get_passphrase()`, a dialog lets the user choose. `SigningCallbackImpl` bridges the UI via a `LinkedBlockingQueue`.
- **Screen stays on during signing** — `FLAG_KEEP_SCREEN_ON` is set while the Signing screen is displayed. Android suspends USB when the screen locks, killing the Trezor connection mid-signing.
- **USB_DEVICE_ATTACHED intent filter required** — The manifest must declare the USB device filter so our app claims the Trezor when plugged in. Without it, other apps (e.g., Trezor Suite) steal the USB device exclusively. `singleTask` launch mode prevents activity recreation when the intent fires. The ViewModel polls for device attachment when the Trezor isn't connected yet.
- **Nostr PSBT delivery** — PSBTs can be received from Electrum over Nostr relays (kind 4 events, NIP-04 encryption). `NostrReceiver` connects via OkHttp WebSocket in `onStart()`/`onStop()`. No background service — PSBTs wait on the relay.
- **Nostr keypair is transport identity only** — Random secp256k1 key in SharedPreferences (`nostr_keys`). Not a signing key, protects nothing of value. npub displayed on Home screen as QR + copyable text for sharing with Electrum. Also reused for NFC passphrase encryption (see below).
- **secp256k1-kmp point multiplication for NIP-04** — `Secp256k1.get().ecdh()` returns SHA-256(compressed_shared_point), NOT the raw x-coordinate NIP-04 needs. Instead, `Nip04.computeSharedSecret()` uses `pubKeyTweakMul(compressedPubkey, privkey)` to get the shared point, then extracts the 32-byte x-coordinate (bytes 1-33 of the 65-byte uncompressed result). The 0x02 prefix is always used for x-only pubkeys (even parity assumption — works because the x-coordinate is the same regardless of y-parity).
- **Inbox persisted via Room** — `InboxItemEntity` is a Room `@Entity` in `AppDatabase` (version 2). `InboxDao` provides reactive `Flow<List<InboxItemEntity>>` collected via `stateIn` in the ViewModel. Write operations use targeted SQL updates (`updateStatus`, `updateSigned`, `updateBroadcast`) to avoid race conditions. On startup, `deleteExpired()` removes old items (24h for pending/failed/signing/deleted, 7d for signed/broadcast), then `getAllOnce()` seeds `NostrReceiver.seenIds` so relays don't overwrite richer local state. Deduplication by Nostr event ID uses conflict-safe `insertIgnore` (not `exists()` + `upsert()`). Signed/broadcast items store `rawHex`, `txid`, and `network` so the Result screen can be reopened from an inbox card. User deletion is a soft delete (`DELETED` status) — the row stays in the DB so its event ID remains in `seenIds` on next startup, preventing relays from re-delivering it. `getAll()` excludes `DELETED` items from the UI; `getAllOnce()` includes them for seeding.
- **Passphrase input disables keyboard learning** — The on-phone passphrase `OutlinedTextField` uses `KeyboardType.Password` + `autoCorrect = false` so the IME never learns, suggests, or autocompletes passphrases. `PasswordVisualTransformation` alone only masks display — `KeyboardOptions` are required to control IME behavior.
- **Cosigner contacts with fingerprint resolution** — Room database stores contacts with one-to-many fingerprints. `TransactionReview` batch-resolves signer fingerprints → labels via `findByFingerprints()`. Quick-add dialog on signer rows creates/assigns contacts without leaving the review screen (modal dialogs, not navigation). Separate `ContactsScreen` for full CRUD. Fingerprints are validated as exactly 8 hex chars, stored lowercase, unique across all contacts. Contact `npub` field exists for future PSBT forwarding via Nostr but is not yet wired to sending logic.
- **NFC passphrase import with encryption** — Passphrases on NFC tags are encrypted using NIP-04 (AES-256-CBC + secp256k1 ECDH) with the app's Nostr keypair, encrypting to its own pubkey. This means an attacker needs both the phone and the NFC tag — the tag alone is useless. The `EncryptPassphraseScreen` (accessible from Home) lets the user encrypt a passphrase and copy the ciphertext for writing to a tag externally. On read, `onNfcTagResult()` in the ViewModel decrypts before passing to the passphrase dialog. Plaintext tags are not supported — users must re-encrypt after the update. Uses `enableReaderMode()` on the Activity (not foreground dispatch) — enabled in `onResume()`, disabled in `onPause()`. Reader mode is always active while the Activity is in the foreground to prevent other NFC-handling apps from intercepting tags and stealing focus; the `onTagDiscovered` callback silently ignores tags unless `nfcWaitingForTag` is true. NDEF RTD_TEXT parsing is a pure function (`parseNdefTextPayload`) for testability. NFC is optional (`android:required="false"`) — the option is hidden on devices without NFC. The passphrase feeds into the same `submitPassphrase()` → `LinkedBlockingQueue` path as keyboard input — zero Python changes.

- **Testnet variant selection at broadcast time** — The network string `"test"` flows unchanged through PSBT parsing and signing (trezorlib only needs "Bitcoin" vs "Testnet"). The testnet variant (testnet3, testnet4, signet) only matters for broadcasting and block explorer links. When `network == "test"`, the Result screen shows three broadcast buttons (Testnet4 as primary, Testnet3, Signet). The user's choice is passed as `targetNetwork` through `ViewModel.broadcast()` → `PythonBridge.broadcast()` → `broadcaster.py`, and persisted via `InboxDao.updateBroadcast()` so reopened inbox items show the correct explorer link. `"test"` is kept as a backward-compat alias for testnet3 in `broadcaster.py` and `MempoolUrl.kt`. `broadcaster.py` raises `ValueError` for unrecognized network values and validates `raw_hex` (hex encoding, even length, 400KB size limit).

## Development Practices

- **Always use TDD** — Write or update tests before writing implementation code when writing or refactoring code.
- **Keep all test suites in sync** — When changing behavior, update both Android instrumented tests (`app/src/androidTest/`) and Python tests (`tests/`) as needed. Don't leave tests broken or stale.

## Targets

- Android SDK 35 (min SDK 28 / Android 9.0)
- 64-bit only: arm64-v8a, x86_64
- Primary hardware target: Trezor Safe 3 via USB-C OTG

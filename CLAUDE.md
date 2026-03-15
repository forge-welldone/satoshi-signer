# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

Satoshi Signer — an Android app for signing Bitcoin PSBTs with a Trezor hardware wallet via USB-C. Kotlin/Compose UI talks to Python backend (embedded via Chaquopy) which handles PSBT parsing, Trezor signing, and transaction broadcasting.

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
```

Python test setup requires a venv: `python3 -m venv .venv && source .venv/bin/activate && pip install -r requirements-dev.txt`

Desktop signing also requires `brew install libusb` (Trezor Safe 3 uses WebUSB on macOS).

Android test setup requires a running emulator: `emulator -avd test_device -no-audio &`

## Architecture

**Two-language bridge pattern:** Kotlin handles UI, USB, and Android lifecycle. Python handles all Bitcoin logic (PSBT parsing, trezorlib signing, broadcasting). They communicate via Chaquopy.

```
Compose UI (4 screens) → SignerViewModel (sealed class state machine)
    → PythonBridge (Chaquopy) → Python modules
    → TrezorUsbManager / UsbBridge (Android USB Host API)
```

**State machine drives navigation** — no NavController. `SignerViewModel` holds a `StateFlow<AppState>` with states: `Home → TransactionReview → Signing → Result` (plus `Error`). UI renders the screen matching current state.

**USB bridge inversion:** Python's trezorlib needs USB access, but Android USB APIs are Kotlin-only. Solution: Kotlin's `UsbBridge` does raw 64-byte interrupt endpoint I/O, Python's `AndroidTransport`/`AndroidHandle` wrap it to satisfy trezorlib's `Transport` protocol. Python calls back into Kotlin for every USB read/write.

**Key constraint:** Trezor uses interrupt endpoints (not bulk). Must use `UsbRequest.queue()` + `requestWait()`, not `bulkTransfer()`. USB read timeout is 10 minutes to accommodate slow passphrase entry on the Trezor's screen.

**USB lifecycle owned by Kotlin** — `UsbBridge.open()`/`close()` must be called from Kotlin (IO dispatcher), not from Chaquopy's Python thread. `claimInterface()` fails when called from Chaquopy's native thread. `AndroidHandle.open()`/`close()` are no-ops; the ViewModel opens the bridge before calling Python and closes it in `finally`.

**Trezor exposes two USB interfaces** — Interface 0: VENDOR_SPEC (0xFF, WebUSB), Interface 1: HID (0x03). Both have interrupt endpoints. Firmware responds on WebUSB (interface 0), so prefer VENDOR_SPEC over HID when claiming.

**Network auto-detected from PSBT** — `parse_psbt` reads BIP32 derivation paths: coin_type 1 = testnet, coin_type 0 = mainnet. The detected network is passed through to `sign_psbt` so the correct coin name ("Bitcoin" vs "Testnet") is used.

**PythonBridge uses JSON round-trip** — Chaquopy's `toJava(Object.class)` doesn't recursively convert nested Python dicts/lists. `PythonBridge` serializes Python return values with `json.dumps`, then parses in Kotlin with `org.json.JSONObject`.

## Source Layout

- `app/src/main/kotlin/com/remotesigner/` — Kotlin source (UI, ViewModel, USB, bridge)
- `app/src/main/python/remotesigner/` — Python modules (psbt_parser, signer, broadcaster, usb_transport, trezor_ui)
- `app/src/androidTest/kotlin/com/remotesigner/` — Android instrumented smoke tests (Compose UI)
- `app/pip_wheels/` — Pre-built Python wheels for Chaquopy (embit)
- `tests/` — Desktop Python tests (pytest), desktop bridge classes, CLI, recorded cassettes
- `tests/cassettes/` — Recorded Trezor USB exchanges for hardware-free E2E test replay
- `docs/superpowers/specs/` — Design specifications

## Key Dependencies

- **Chaquopy 17.0.0** — Embeds Python 3.13 in Android
- **trezor 0.13.9** (Python) — Official Trezor signing library
- **embit ≥0.7** (Python) — Lightweight PSBT parsing
- **Compose BOM 2024.12.01** — Jetpack Compose UI
- Versions managed in `gradle/libs.versions.toml`

## Design Decisions to Preserve

- **Direct trezorlib, not HWI** — HWI's dependency tree (hidapi, pyserial) doesn't work under Chaquopy. We use trezorlib directly with a custom transport.
- **Stateless app** — No database, no wallet storage, no caching. Killed process just loses the in-progress transaction.
- **App never touches private keys** — All signing happens on Trezor's secure element. No seed phrases or key material on phone.
- **Python modules are desktop-testable** — The bridge pattern keeps Python code Android-agnostic so `pytest` works without an emulator. Desktop signing uses `DesktopUsbBridge` (WebUSB/HID) in place of Kotlin's `UsbBridge`. Recorded USB cassettes enable E2E test replay without hardware.
- **Safe 3 PIN is on-device only** — No host-side PIN matrix. `get_pin()` raises. Passphrase entry is user's choice: on-device (default) or on-phone. When trezorlib calls `get_passphrase()`, a dialog lets the user choose. `SigningCallbackImpl` bridges the UI via a `LinkedBlockingQueue`.
- **Screen stays on during signing** — `FLAG_KEEP_SCREEN_ON` is set while the Signing screen is displayed. Android suspends USB when the screen locks, killing the Trezor connection mid-signing.
- **USB_DEVICE_ATTACHED intent filter required** — The manifest must declare the USB device filter so our app claims the Trezor when plugged in. Without it, other apps (e.g., Trezor Suite) steal the USB device exclusively. `singleTask` launch mode prevents activity recreation when the intent fires. The ViewModel polls for device attachment when the Trezor isn't connected yet.

## Targets

- Android SDK 35 (min SDK 28 / Android 9.0)
- 64-bit only: arm64-v8a, x86_64
- Primary hardware target: Trezor Safe 3 via USB-C OTG

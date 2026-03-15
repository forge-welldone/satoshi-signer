# Satoshi Signer

An Android app that imports unsigned Bitcoin PSBTs (Partially Signed Bitcoin Transactions), signs them with a Trezor hardware wallet connected via USB-C, and broadcasts the signed transaction to the Bitcoin network.

## Why

Electrum requires a desktop computer to interact with hardware wallets. No existing Android app supports the full workflow of importing external PSBTs and signing them with a Trezor via USB. Satoshi Signer fills this gap — create unsigned transactions in Electrum on a remote machine, transfer the PSBT file to your phone, sign with Trezor, and broadcast. No laptop needed.

## Workflow

1. Create unsigned transaction in Electrum on a remote machine
2. Send the `.psbt` file to your phone (email, cloud storage, messenger, etc.)
3. Open the file with Satoshi Signer
4. Review transaction details: destinations, change outputs, fee, multisig status
5. Connect Trezor via USB-C OTG cable
6. Sign on the Trezor
7. Broadcast to the Bitcoin network (or export updated PSBT for multisig)

## Features

- **Single-sig and multisig** PSBT support (P2WPKH, P2SH-P2WPKH, P2TR, P2WSH)
- **Change output detection** via BIP32 derivation path matching
- **Multisig status tracking** — shows which signers have signed (by fingerprint)
- **Transaction broadcasting** to mempool.space / blockstream.info with retry and fallback
- **Manual broadcast fallback** — copy raw hex if API broadcast fails
- **PSBT export** for partially-signed multisig transactions (via Android share sheet)
- **Intent filter** — open `.psbt` files directly from file managers and email apps

## Architecture

```
Kotlin/Jetpack Compose (thin shell)     Python backend (via Chaquopy)
┌──────────────────────────┐            ┌──────────────────────────┐
│ UI Screens (4 screens)   │            │ psbt_parser (embit)      │
│ SignerViewModel           │◄─bridge──►│ signer (trezorlib)       │
│ USB Bridge (UsbRequest)  │            │ broadcaster (requests)   │
│ File picker / intents    │            │ usb_transport (custom)   │
└──────────┬───────────────┘            │ trezor_ui (callbacks)    │
           │ USB-C OTG                  └──────────────────────────┘
     ┌─────▼─────┐
     │  Trezor   │
     │  Safe 3   │
     └───────────┘
```

**Kotlin side** handles UI (Jetpack Compose), Android file picker, USB permission management, and interrupt endpoint I/O via `UsbRequest`. All Bitcoin and Trezor logic lives in Python.

**Python side** uses `trezorlib` (official Trezor library) for device communication and `embit` for PSBT parsing. The PSBT-to-trezorlib conversion logic is ported from [HWI](https://github.com/bitcoin-core/HWI). A custom `trezorlib` transport bridges Android's USB stack to Python via Kotlin callbacks.

**Chaquopy** embeds Python 3.13 in the Android app, bridging Kotlin and Python with automatic type conversion.

## Target Hardware

- **Trezor Safe 3** via USB-C OTG (on-device PIN and passphrase)
- **Android 9.0+** (API 28) with USB Host support

Other Trezor models with USB-C should work but are untested. Models requiring host-side PIN entry (Model One with old firmware) are not supported.

## Building

### Prerequisites

- JDK 17 (`brew install openjdk@17`)
- Android SDK 35 (`brew install --cask android-commandlinetools`, then use `sdkmanager`)
- An Android device with USB-C OTG support (for on-device testing)

No Android Studio required. See below for full command-line setup.

### Command-Line SDK Setup (macOS)

```bash
# Install JDK and Android tools
brew install openjdk@17
brew install --cask android-commandlinetools

# Add to ~/.zshrc
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

# Install SDK components
mkdir -p "$ANDROID_HOME"
sdkmanager --sdk_root="$ANDROID_HOME" \
  "platforms;android-35" "build-tools;35.0.0" "platform-tools" \
  "emulator" "system-images;android-35;google_apis;arm64-v8a" \
  "cmdline-tools;latest"
sdkmanager --sdk_root="$ANDROID_HOME" --licenses

# Create local.properties
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

# Create emulator
avdmanager create avd -n test_device \
  -k "system-images;android-35;google_apis;arm64-v8a" -d "pixel_7"
```

### Build

```bash
./gradlew assembleDebug
```

Chaquopy automatically downloads Python 3.13 and pip-installs `trezor`, `embit`, and `requests` during the build.

### Install

```bash
./gradlew installDebug
```

### Run Android Tests

```bash
# Start emulator
emulator -avd test_device -no-audio &
adb wait-for-device

# Run smoke tests
./gradlew connectedDebugAndroidTest
```

Smoke tests verify all 4 screens render correctly and state-machine navigation works.

### Run Tests (Python, desktop)

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements-dev.txt
python -m pytest tests/ -v
```

Tests include unit tests for all Python modules plus end-to-end signing tests that replay pre-recorded Trezor USB exchanges (no hardware needed).

### Desktop Signing with Real Trezor

You can test the full signing flow on your Mac without deploying to Android. Requires `libusb` (`brew install libusb`).

```bash
# Parse a PSBT (no hardware needed)
python tests/sign_cli.py --network test parse path/to/file.psbt

# Sign with a real Trezor plugged in via USB
python tests/sign_cli.py --network test sign path/to/file.psbt

# Sign and record USB exchanges for replay in automated tests
python tests/sign_cli.py --network test sign path/to/file.psbt \
  --record tests/cassettes/scenario-name.json
```

Recorded cassettes are replayed by `tests/test_signing_e2e.py` — this tests the entire `sign_psbt()` code path (protobuf construction, wire protocol, signature insertion) without a Trezor.

## Project Structure

```
app/src/androidTest/kotlin/com/remotesigner/
│   ├── TestFixtures.kt             # Mock AppState instances for tests
│   ├── AppLaunchTest.kt            # App launch smoke test
│   ├── ScreenRenderTest.kt         # Screen render smoke tests
│   └── NavigationTest.kt           # State machine navigation test
app/src/main/
├── kotlin/com/remotesigner/
│   ├── MainActivity.kt              # Entry point, intent handling
│   ├── bridge/PythonBridge.kt       # Chaquopy bridge to Python
│   ├── viewmodel/SignerViewModel.kt # State machine (Home→Review→Sign→Result)
│   ├── usb/
│   │   ├── TrezorUsbManager.kt     # Device discovery + USB permissions
│   │   └── UsbBridge.kt            # 64-byte interrupt endpoint I/O
│   └── ui/
│       ├── HomeScreen.kt           # File picker
│       ├── TransactionReviewScreen.kt # TX details, change, fee, signers
│       ├── SigningScreen.kt         # Progress spinner
│       ├── ResultScreen.kt          # Broadcast / export
│       └── AppNavigation.kt        # State-based routing
├── python/remotesigner/
│   ├── psbt_parser.py              # Parse PSBT, detect change outputs
│   ├── signer.py                   # PSBT→trezorlib conversion + signing
│   ├── broadcaster.py              # HTTP broadcast to public APIs
│   ├── usb_transport.py            # Custom trezorlib Handle/Transport
│   ├── trezor_ui.py                # Safe 3 UI callbacks
│   └── validate_deps.py            # Dependency validation for Chaquopy
└── res/
    ├── xml/usb_device_filter.xml   # Trezor USB vendor ID filter
    └── xml/file_paths.xml          # FileProvider for PSBT export

tests/
├── desktop_bridge.py               # DesktopUsbBridge, RecordingBridge, PlaybackBridge
├── sign_cli.py                     # CLI for desktop signing + cassette recording
├── test_signing_e2e.py             # E2E tests replaying recorded cassettes
├── test_psbt_parser.py             # PSBT parsing tests
├── test_signer.py                  # Signer module tests
├── test_broadcaster.py             # Broadcasting tests
├── test_usb_transport.py           # USB transport tests
├── test_desktop_bridge.py          # Desktop bridge unit tests
├── cassettes/                      # Recorded USB exchange JSON files
│   └── single-sig-p2wpkh.json
└── psbts/                          # Test PSBT files
```

## Dependencies

### Python (bundled via Chaquopy)
- `trezor` 0.13.9 — Trezor device communication (protobuf, signing protocol)
- `embit` — Lightweight Bitcoin library (PSBT parsing, transaction handling)
- `requests` — HTTP client for broadcasting

### Kotlin/Android
- Jetpack Compose with Material 3
- Android USB Host API
- Chaquopy 17.0.0

## Security

- The app **never touches private keys** — all signing happens on the Trezor's secure element
- No seed phrases, no key material stored on the phone
- USB communication is direct (no network intermediary)
- Signing works fully offline; only broadcasting requires network
- The app is stateless — no databases, no wallet storage, no caching

## Known Limitations

- **Android only** — iOS does not expose USB HID to apps (Trezor Safe 7 with Bluetooth would be needed)
- **On-device PIN/passphrase only** — host-side PIN matrix (old Model One firmware) not supported
- **Intent filter for `.psbt` files is best-effort** — Android's `pathPattern` doesn't reliably match `content://` URIs. The file picker is the primary import path.
- **Embit wheel vendored** — `embit` is pure Python but only distributed as sdist on PyPI; Chaquopy requires wheels, so a pre-built wheel is checked in at `app/pip_wheels/`

## Future Enhancements

- Nostr-based PSBT transfer from remote machine
- QR code scanning for PSBT import
- User-assigned labels for multisig signer fingerprints
- Ledger support (Bluetooth transport)
- Testnet/signet toggle in production UI

## License

TBD

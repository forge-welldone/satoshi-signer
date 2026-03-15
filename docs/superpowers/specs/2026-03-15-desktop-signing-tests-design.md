# Desktop Signing Tests with USB Cassette Recording

## Problem

Testing the Python signing code requires deploying to a real Android phone with a Trezor connected via USB-C OTG. This makes the edit-test cycle slow. The Python code has no Android dependencies — all USB I/O goes through a 4-method bridge interface (`open`, `close`, `writeChunk`, `readChunk`) — so it can run on desktop if we provide a desktop-compatible bridge.

## Goal

1. Run the full `sign_psbt()` flow on macOS with a real Trezor plugged in via USB.
2. Record USB exchanges to cassette files (VCR-style) for replay in CI/automated tests without hardware.
3. No changes to production code — everything lives in `tests/`.

## Scope

- Single-sig signing only (P2WPKH, P2TR). Multisig cassettes added later.

## Design

### Desktop USB Bridge

**File:** `tests/desktop_bridge.py`

`DesktopUsbBridge` uses the `hidapi` Python package to communicate with a real Trezor on macOS. It implements the same interface as Kotlin's `UsbBridge`:

- `open()` — enumerates HID devices, finds Trezor by vendor ID `0x1209` and product IDs `[0x53C0, 0x53C1]`, opens the device.
- `close()` — closes the HID device.
- `writeChunk(data: bytes)` — writes 64 bytes with HID report ID prefix (`0x00`).
- `readChunk() -> bytes` — reads 64 bytes from the device.

This plugs directly into `sign_psbt(psbt_bytes, bridge=DesktopUsbBridge())` — the identical code path as Android.

**Dependency:** `hidapi` added to `requirements-dev.txt`. Only needed on desktop, not bundled in the Android build.

### Recording Bridge

`RecordingBridge` wraps any bridge and intercepts all I/O:

- Delegates `open/close/writeChunk/readChunk` to the wrapped bridge.
- Logs every exchange as `{"dir": "w"|"r", "data": "hex_encoded_bytes"}`.
- `save_cassette(path)` serializes metadata + exchanges to a JSON file.

### Playback Bridge

`PlaybackBridge` replays a recorded cassette without hardware:

- Loads a cassette JSON file on init.
- `writeChunk(data)` — asserts `data` matches the next expected write in the sequence. Raises `AssertionError` with a diff if it diverges.
- `readChunk()` — returns the next recorded read response.
- On `close()`, asserts all exchanges were consumed.

### Cassette Format

**Directory:** `tests/cassettes/`

```json
{
  "metadata": {
    "recorded_at": "2026-03-15T14:30:00Z",
    "scenario": "single-sig-p2wpkh"
  },
  "exchanges": [
    {"dir": "w", "data": "3f2300..."},
    {"dir": "r", "data": "3f2300..."}
  ]
}
```

Each entry is one 64-byte USB chunk. Writes and reads alternate as dictated by the trezorlib wire protocol.

### CLI Script

**File:** `tests/sign_cli.py`

```
python tests/sign_cli.py sign <psbt_file> [--record <cassette_path>] [--network main|test]
python tests/sign_cli.py parse <psbt_file>
```

- `sign` — opens `DesktopUsbBridge` (wrapped in `RecordingBridge` if `--record`), calls `sign_psbt()`, prints the result, saves cassette if recording.
- `parse` — calls `parse_psbt()` only, no hardware needed.
- Accepts base64-encoded or raw binary PSBT files.

### E2E Tests

**File:** `tests/test_signing_e2e.py`

- Uses `PlaybackBridge` with recorded cassettes.
- Tests the full `sign_psbt()` flow: protobuf message construction, Trezor signing protocol, signature insertion back into PSBT, serialization.
- `pytest.mark.skipif` when cassette files don't exist (so CI doesn't fail before first recording).
- Parametrized by scenario name — adding multisig later means recording a new cassette and adding a test parameter.

### File Layout

```
tests/
  desktop_bridge.py        # DesktopUsbBridge, RecordingBridge, PlaybackBridge
  sign_cli.py              # CLI for manual signing + cassette recording
  test_signing_e2e.py      # Automated tests using PlaybackBridge
  cassettes/               # Recorded USB exchange JSON files
    .gitkeep
```

### Dependencies

Added to `requirements-dev.txt`:
```
hidapi
```

No changes to production `pip` dependencies in `app/build.gradle.kts`.

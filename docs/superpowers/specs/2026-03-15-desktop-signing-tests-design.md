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

`DesktopUsbBridge` is a thin adapter wrapping trezorlib's transport handles. It tries WebUSB first (needed for Trezor Safe 3 on macOS, which only exposes a WebUSB interface), then falls back to HID. Both handle types expose the same `write_chunk`/`read_chunk` interface. It exposes the same interface as Kotlin's `UsbBridge`:

- `open()` — tries `WebUsbTransport.enumerate()` first, then `HidTransport.enumerate()`, takes the first device's handle, calls `handle.open()`.
- `close()` — calls `handle.close()`.
- `writeChunk(data: bytes)` — receives 64 bytes (matching the Kotlin bridge contract), delegates to `handle.write_chunk(data)`.
- `readChunk() -> bytes` — delegates to `handle.read_chunk()`, returns 64 bytes.

This plugs directly into `sign_psbt(psbt_bytes, bridge=DesktopUsbBridge())` — the identical code path as Android.

**Dependencies:** `hidapi` added to `requirements-dev.txt`. Also requires `libusb` system library (`brew install libusb` on macOS) for WebUSB transport.

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
- `close()` — no-op (trezorlib may close/reopen the transport mid-session during passphrase handling).
- `assert_consumed()` — verifies all exchanges were replayed. Called explicitly by tests after the full operation.

### Cassette Format

**Directory:** `tests/cassettes/`

```json
{
  "metadata": {
    "recorded_at": "2026-03-15T14:30:00Z",
    "scenario": "single-sig-p2wpkh",
    "network": "test",
    "trezorlib_version": "0.13.9",
    "trezor_model": "Safe 3",
    "firmware_version": "2.8.1"
  },
  "exchanges": [
    {"dir": "w", "data": "3f2300..."},
    {"dir": "r", "data": "3f2300..."}
  ]
}
```

Each entry is one 64-byte USB chunk. Writes and reads alternate as dictated by the trezorlib wire protocol. Version metadata allows detecting cassette staleness if trezorlib or firmware changes wire format.

### CLI Script

**File:** `tests/sign_cli.py`

```
python tests/sign_cli.py sign <psbt_file> [--record <cassette_path>] [--network main|test]
python tests/sign_cli.py parse <psbt_file>
```

- `sign` — opens `DesktopUsbBridge` (wrapped in `RecordingBridge` if `--record`), calls `sign_psbt()` with a print-based `status_callback`, prints the result, saves cassette if recording.
- `parse` — calls `parse_psbt()` (from `remotesigner.psbt_parser`) only, no hardware needed.
- Accepts base64-encoded or raw binary PSBT files.
- The `status_callback` prints Trezor button prompts to stdout so the user knows when to confirm on-device.

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

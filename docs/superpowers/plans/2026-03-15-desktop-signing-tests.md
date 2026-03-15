# Desktop Signing Tests Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable desktop testing of the Python signing code with a real Trezor, and record USB exchanges as cassettes for hardware-free replay in automated tests.

**Architecture:** Three bridge classes (`DesktopUsbBridge`, `RecordingBridge`, `PlaybackBridge`) implementing the same 4-method interface (`open`/`close`/`writeChunk`/`readChunk`) that `AndroidHandle` in `usb_transport.py` expects. A CLI script orchestrates manual signing + recording. E2E tests use playback cassettes.

**Tech Stack:** Python 3, pytest, hidapi (via trezorlib's `HidHandle`), trezorlib, embit

**Spec:** `docs/superpowers/specs/2026-03-15-desktop-signing-tests-design.md`

---

## Chunk 1: Infrastructure + PlaybackBridge + RecordingBridge

### Task 1: Add hidapi dependency and create cassettes directory

**Files:**
- Modify: `requirements-dev.txt`
- Create: `tests/cassettes/.gitkeep`

- [ ] **Step 1: Add hidapi to requirements-dev.txt**

```
trezor==0.13.9
embit>=0.7
requests>=2.28
pytest>=7.0
pytest-mock>=3.10
hidapi
```

- [ ] **Step 2: Create cassettes directory**

```bash
mkdir -p tests/cassettes
touch tests/cassettes/.gitkeep
```

- [ ] **Step 3: Install updated dependencies**

Run: `cd /Users/sasha/Projects/remote_signer && source .venv/bin/activate && pip install hidapi`
Expected: Successful install of hidapi

- [ ] **Step 4: Commit**

```bash
git add requirements-dev.txt tests/cassettes/.gitkeep
git commit -m "chore: add hidapi dependency and cassettes directory"
```

---

### Task 2: PlaybackBridge — test and implement

The `PlaybackBridge` replays a recorded cassette file. It's fully testable without hardware — we just create cassette dicts in memory.

**Files:**
- Create: `tests/desktop_bridge.py`
- Create: `tests/test_desktop_bridge.py`

- [ ] **Step 1: Write failing tests for PlaybackBridge**

Create `tests/test_desktop_bridge.py`:

```python
"""Tests for desktop bridge classes (PlaybackBridge, RecordingBridge)."""

import json
import os
import pytest


# ---------------------------------------------------------------------------
# PlaybackBridge tests
# ---------------------------------------------------------------------------

class TestPlaybackBridge:
    def _make_cassette(self, exchanges):
        """Helper: build a cassette dict with minimal metadata."""
        return {
            "metadata": {
                "recorded_at": "2026-01-01T00:00:00Z",
                "scenario": "test",
            },
            "exchanges": exchanges,
        }

    def test_replays_read_after_write(self):
        from desktop_bridge import PlaybackBridge

        cassette = self._make_cassette([
            {"dir": "w", "data": "aa" * 64},
            {"dir": "r", "data": "bb" * 64},
        ])
        bridge = PlaybackBridge(cassette)
        bridge.open()
        bridge.writeChunk(bytes.fromhex("aa" * 64))
        result = bridge.readChunk()
        assert result == bytes.fromhex("bb" * 64)
        bridge.close()

    def test_write_mismatch_raises(self):
        from desktop_bridge import PlaybackBridge

        cassette = self._make_cassette([
            {"dir": "w", "data": "aa" * 64},
        ])
        bridge = PlaybackBridge(cassette)
        bridge.open()
        with pytest.raises(AssertionError, match="write mismatch"):
            bridge.writeChunk(bytes.fromhex("ff" * 64))

    def test_read_when_write_expected_raises(self):
        from desktop_bridge import PlaybackBridge

        cassette = self._make_cassette([
            {"dir": "w", "data": "aa" * 64},
        ])
        bridge = PlaybackBridge(cassette)
        bridge.open()
        with pytest.raises(AssertionError, match="expected write"):
            bridge.readChunk()

    def test_write_when_read_expected_raises(self):
        from desktop_bridge import PlaybackBridge

        cassette = self._make_cassette([
            {"dir": "r", "data": "bb" * 64},
        ])
        bridge = PlaybackBridge(cassette)
        bridge.open()
        with pytest.raises(AssertionError, match="expected read"):
            bridge.writeChunk(bytes.fromhex("aa" * 64))

    def test_close_with_unconsumed_exchanges_raises(self):
        from desktop_bridge import PlaybackBridge

        cassette = self._make_cassette([
            {"dir": "w", "data": "aa" * 64},
            {"dir": "r", "data": "bb" * 64},
        ])
        bridge = PlaybackBridge(cassette)
        bridge.open()
        with pytest.raises(AssertionError, match="unconsumed"):
            bridge.close()

    def test_close_after_all_consumed_ok(self):
        from desktop_bridge import PlaybackBridge

        cassette = self._make_cassette([
            {"dir": "w", "data": "aa" * 64},
        ])
        bridge = PlaybackBridge(cassette)
        bridge.open()
        bridge.writeChunk(bytes.fromhex("aa" * 64))
        bridge.close()  # should not raise

    def test_exhausted_cassette_raises_on_read(self):
        from desktop_bridge import PlaybackBridge

        cassette = self._make_cassette([])
        bridge = PlaybackBridge(cassette)
        bridge.open()
        with pytest.raises(AssertionError, match="exhausted"):
            bridge.readChunk()

    def test_exhausted_cassette_raises_on_write(self):
        from desktop_bridge import PlaybackBridge

        cassette = self._make_cassette([])
        bridge = PlaybackBridge(cassette)
        bridge.open()
        with pytest.raises(AssertionError, match="exhausted"):
            bridge.writeChunk(bytes(64))

    def test_load_from_file(self, tmp_path):
        from desktop_bridge import PlaybackBridge

        cassette = self._make_cassette([
            {"dir": "w", "data": "cc" * 64},
        ])
        path = tmp_path / "test.json"
        path.write_text(json.dumps(cassette))

        bridge = PlaybackBridge.from_file(str(path))
        bridge.open()
        bridge.writeChunk(bytes.fromhex("cc" * 64))
        bridge.close()

    def test_multiple_round_trips(self):
        from desktop_bridge import PlaybackBridge

        cassette = self._make_cassette([
            {"dir": "w", "data": "01" * 64},
            {"dir": "r", "data": "02" * 64},
            {"dir": "w", "data": "03" * 64},
            {"dir": "r", "data": "04" * 64},
        ])
        bridge = PlaybackBridge(cassette)
        bridge.open()
        bridge.writeChunk(bytes.fromhex("01" * 64))
        assert bridge.readChunk() == bytes.fromhex("02" * 64)
        bridge.writeChunk(bytes.fromhex("03" * 64))
        assert bridge.readChunk() == bytes.fromhex("04" * 64)
        bridge.close()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_desktop_bridge.py::TestPlaybackBridge -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'desktop_bridge'`

- [ ] **Step 3: Implement PlaybackBridge**

Create `tests/desktop_bridge.py`:

```python
"""Desktop USB bridge classes for testing Python signing code without Android.

Three bridge implementations sharing the same 4-method interface
(open/close/writeChunk/readChunk) that AndroidHandle in usb_transport.py expects:

- DesktopUsbBridge: real Trezor via hidapi (macOS/Linux desktop)
- RecordingBridge:  wraps any bridge, logs exchanges to a cassette
- PlaybackBridge:   replays a recorded cassette, no hardware needed
"""

import json
from datetime import datetime, timezone


class PlaybackBridge:
    """Replays a recorded USB cassette without hardware.

    Raises AssertionError if the caller's writes diverge from the recording.
    """

    def __init__(self, cassette: dict) -> None:
        self._exchanges = list(cassette["exchanges"])
        self._pos = 0

    @classmethod
    def from_file(cls, path: str) -> "PlaybackBridge":
        with open(path) as f:
            return cls(json.load(f))

    def open(self) -> None:
        pass

    def close(self) -> None:
        remaining = len(self._exchanges) - self._pos
        assert remaining == 0, (
            f"Cassette has {remaining} unconsumed exchange(s) "
            f"(pos {self._pos} of {len(self._exchanges)})"
        )

    def writeChunk(self, data: bytes) -> None:
        assert self._pos < len(self._exchanges), "Cassette exhausted on write"
        entry = self._exchanges[self._pos]
        assert entry["dir"] == "w", (
            f"At position {self._pos}: expected read, got writeChunk call"
        )
        expected = bytes.fromhex(entry["data"])
        assert data == expected, (
            f"Cassette write mismatch at position {self._pos}:\n"
            f"  expected: {expected.hex()}\n"
            f"  got:      {data.hex()}"
        )
        self._pos += 1

    def readChunk(self) -> bytes:
        assert self._pos < len(self._exchanges), "Cassette exhausted on read"
        entry = self._exchanges[self._pos]
        assert entry["dir"] == "r", (
            f"At position {self._pos}: expected write, got readChunk call"
        )
        self._pos += 1
        return bytes.fromhex(entry["data"])
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_desktop_bridge.py::TestPlaybackBridge -v`
Expected: All 10 tests PASS

- [ ] **Step 5: Commit**

```bash
git add tests/desktop_bridge.py tests/test_desktop_bridge.py
git commit -m "feat: PlaybackBridge for replaying USB cassettes in tests"
```

---

### Task 3: RecordingBridge — test and implement

The `RecordingBridge` wraps any bridge and logs exchanges. Testable with a mock inner bridge.

**Files:**
- Modify: `tests/desktop_bridge.py`
- Modify: `tests/test_desktop_bridge.py`

- [ ] **Step 1: Write failing tests for RecordingBridge**

Append to `tests/test_desktop_bridge.py`:

```python
# ---------------------------------------------------------------------------
# RecordingBridge tests
# ---------------------------------------------------------------------------

class MockInnerBridge:
    """Minimal bridge that echoes back fixed data for testing RecordingBridge."""

    def __init__(self, read_responses):
        self._read_responses = list(read_responses)
        self._read_pos = 0
        self.opened = False
        self.closed = False

    def open(self):
        self.opened = True

    def close(self):
        self.closed = True

    def writeChunk(self, data):
        pass

    def readChunk(self):
        resp = self._read_responses[self._read_pos]
        self._read_pos += 1
        return resp


class TestRecordingBridge:
    def test_records_write_and_read(self):
        from desktop_bridge import RecordingBridge

        inner = MockInnerBridge(read_responses=[bytes.fromhex("bb" * 64)])
        rec = RecordingBridge(inner)
        rec.open()
        rec.writeChunk(bytes.fromhex("aa" * 64))
        result = rec.readChunk()
        assert result == bytes.fromhex("bb" * 64)
        rec.close()

        cassette = rec.get_cassette(scenario="test-scenario")
        assert len(cassette["exchanges"]) == 2
        assert cassette["exchanges"][0] == {"dir": "w", "data": "aa" * 64}
        assert cassette["exchanges"][1] == {"dir": "r", "data": "bb" * 64}
        assert cassette["metadata"]["scenario"] == "test-scenario"
        assert "recorded_at" in cassette["metadata"]

    def test_delegates_open_close(self):
        from desktop_bridge import RecordingBridge

        inner = MockInnerBridge(read_responses=[])
        rec = RecordingBridge(inner)
        rec.open()
        assert inner.opened
        rec.close()
        assert inner.closed

    def test_save_cassette_to_file(self, tmp_path):
        from desktop_bridge import RecordingBridge

        inner = MockInnerBridge(read_responses=[bytes(64)])
        rec = RecordingBridge(inner)
        rec.open()
        rec.writeChunk(bytes(64))
        rec.readChunk()
        rec.close()

        path = tmp_path / "cassette.json"
        rec.save_cassette(str(path), scenario="save-test")

        with open(path) as f:
            loaded = json.load(f)
        assert len(loaded["exchanges"]) == 2
        assert loaded["metadata"]["scenario"] == "save-test"

    def test_metadata_includes_versions(self):
        from desktop_bridge import RecordingBridge

        inner = MockInnerBridge(read_responses=[])
        rec = RecordingBridge(inner)
        rec.open()
        rec.close()

        cassette = rec.get_cassette(
            scenario="test",
            network="test",
            trezor_model="Safe 3",
            firmware_version="2.8.1",
        )
        assert cassette["metadata"]["network"] == "test"
        assert cassette["metadata"]["trezor_model"] == "Safe 3"
        assert cassette["metadata"]["firmware_version"] == "2.8.1"
        assert "trezorlib_version" in cassette["metadata"]

    def test_metadata_stores_input_psbt(self):
        from desktop_bridge import RecordingBridge

        inner = MockInnerBridge(read_responses=[])
        rec = RecordingBridge(inner)
        rec.open()
        rec.close()

        cassette = rec.get_cassette(
            scenario="test",
            input_psbt_b64="cHNidP8BAAAAAAA=",
        )
        assert cassette["metadata"]["input_psbt_b64"] == "cHNidP8BAAAAAAA="

    def test_cassette_replays_correctly(self):
        """Record, then play back — full round-trip."""
        from desktop_bridge import RecordingBridge, PlaybackBridge

        read_data = bytes.fromhex("dd" * 64)
        write_data = bytes.fromhex("cc" * 64)
        inner = MockInnerBridge(read_responses=[read_data])
        rec = RecordingBridge(inner)
        rec.open()
        rec.writeChunk(write_data)
        rec.readChunk()
        rec.close()

        cassette = rec.get_cassette(scenario="roundtrip")
        playback = PlaybackBridge(cassette)
        playback.open()
        playback.writeChunk(write_data)
        assert playback.readChunk() == read_data
        playback.close()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_desktop_bridge.py::TestRecordingBridge -v`
Expected: FAIL — `ImportError: cannot import name 'RecordingBridge'`

- [ ] **Step 3: Implement RecordingBridge**

Add to `tests/desktop_bridge.py`, after the `PlaybackBridge` class:

```python
class RecordingBridge:
    """Wraps any bridge and records all USB exchanges to a cassette.

    After use, call get_cassette() or save_cassette() to retrieve the recording.
    """

    def __init__(self, inner_bridge) -> None:
        self._inner = inner_bridge
        self._exchanges: list = []

    def open(self) -> None:
        self._inner.open()

    def close(self) -> None:
        self._inner.close()

    def writeChunk(self, data: bytes) -> None:
        self._exchanges.append({"dir": "w", "data": bytes(data).hex()})
        self._inner.writeChunk(data)

    def readChunk(self) -> bytes:
        data = self._inner.readChunk()
        self._exchanges.append({"dir": "r", "data": bytes(data).hex()})
        return data

    def get_cassette(self, **extra_metadata) -> dict:
        """Return the recorded cassette as a dict.

        All keyword arguments are stored in metadata. Typical keys:
        scenario, network, trezor_model, firmware_version, input_psbt_b64.
        trezorlib_version is added automatically.
        """
        import trezorlib  # lazy import — only needed at save time

        metadata = {
            "recorded_at": datetime.now(timezone.utc).isoformat(),
            "trezorlib_version": trezorlib.__version__,
        }
        metadata.update(extra_metadata)

        return {
            "metadata": metadata,
            "exchanges": list(self._exchanges),
        }

    def save_cassette(self, path: str, **kwargs) -> None:
        """Write the cassette to a JSON file."""
        cassette = self.get_cassette(**kwargs)
        with open(path, "w") as f:
            json.dump(cassette, f, indent=2)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_desktop_bridge.py -v`
Expected: All 16 tests PASS (10 PlaybackBridge + 6 RecordingBridge)

- [ ] **Step 5: Commit**

```bash
git add tests/desktop_bridge.py tests/test_desktop_bridge.py
git commit -m "feat: RecordingBridge for logging USB exchanges to cassettes"
```

---

## Chunk 2: DesktopUsbBridge + CLI + E2E Tests

### Task 4: DesktopUsbBridge

Thin adapter wrapping trezorlib's `HidHandle`. Cannot be unit-tested without hardware, but we add a smoke test that verifies the class exists and has the right interface.

**Files:**
- Modify: `tests/desktop_bridge.py`
- Modify: `tests/test_desktop_bridge.py`

- [ ] **Step 1: Write interface smoke test**

Append to `tests/test_desktop_bridge.py`:

```python
# ---------------------------------------------------------------------------
# DesktopUsbBridge tests (interface only — hardware tests are manual)
# ---------------------------------------------------------------------------

class TestDesktopUsbBridge:
    def test_has_bridge_interface(self):
        from desktop_bridge import DesktopUsbBridge

        bridge = DesktopUsbBridge()
        assert callable(bridge.open)
        assert callable(bridge.close)
        assert callable(bridge.writeChunk)
        assert callable(bridge.readChunk)

    def test_open_without_trezor_raises(self):
        """On a machine without a Trezor plugged in, open() should raise."""
        from desktop_bridge import DesktopUsbBridge

        bridge = DesktopUsbBridge()
        with pytest.raises(RuntimeError, match="No Trezor"):
            bridge.open()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_desktop_bridge.py::TestDesktopUsbBridge::test_has_bridge_interface -v`
Expected: FAIL — `ImportError: cannot import name 'DesktopUsbBridge'`

- [ ] **Step 3: Implement DesktopUsbBridge**

Add to `tests/desktop_bridge.py`, after the `RecordingBridge` class:

```python
class DesktopUsbBridge:
    """USB bridge using trezorlib's HidHandle for desktop testing.

    Wraps trezorlib's tested HID handling (wirelink filtering, HID version
    probing, nonblocking read polling) and exposes the same interface as
    Kotlin's UsbBridge.
    """

    def __init__(self) -> None:
        self._handle = None

    def open(self) -> None:
        from trezorlib.transport.hid import HidTransport
        from trezorlib.models import TREZORS

        devices = list(HidTransport.enumerate(models=TREZORS))
        if not devices:
            raise RuntimeError(
                "No Trezor found. Is the device plugged in and unlocked?"
            )
        # Use the first device's handle directly
        self._handle = devices[0].handle
        self._handle.open()

    def close(self) -> None:
        if self._handle is not None:
            self._handle.close()
            self._handle = None

    def writeChunk(self, data: bytes) -> None:
        self._handle.write_chunk(data)

    def readChunk(self) -> bytes:
        return self._handle.read_chunk()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_desktop_bridge.py::TestDesktopUsbBridge -v`
Expected: Both tests PASS (the `test_open_without_trezor_raises` test passes on machines without a Trezor; if a Trezor IS connected, mark it xfail or skip based on environment)

Note: If you have a Trezor plugged in when running this, `test_open_without_trezor_raises` will fail. That's expected — it only applies to CI/no-hardware environments. Add a conditional skip:

Replace the test with:

```python
    def test_open_without_trezor_raises(self):
        """On a machine without a Trezor plugged in, open() should raise."""
        from desktop_bridge import DesktopUsbBridge

        bridge = DesktopUsbBridge()
        try:
            bridge.open()
            # Trezor is connected — skip this test
            bridge.close()
            pytest.skip("Trezor is connected, cannot test no-device error")
        except RuntimeError as e:
            assert "No Trezor" in str(e)
```

- [ ] **Step 5: Run full test suite**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/ -v`
Expected: All existing tests + new tests PASS

- [ ] **Step 6: Commit**

```bash
git add tests/desktop_bridge.py tests/test_desktop_bridge.py
git commit -m "feat: DesktopUsbBridge wrapping trezorlib HidHandle for desktop signing"
```

---

### Task 5: CLI script for manual signing and recording

**Files:**
- Create: `tests/sign_cli.py`

- [ ] **Step 1: Create the CLI script**

Create `tests/sign_cli.py`:

```python
#!/usr/bin/env python3
"""CLI for desktop PSBT signing and cassette recording.

Usage:
    python tests/sign_cli.py parse <psbt_file>
    python tests/sign_cli.py sign <psbt_file> [--record <cassette_path>] [--network main|test]
"""

import argparse
import base64
import json
import sys
import os

# Add Python source to path (same as conftest.py)
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'python'))
sys.path.insert(0, os.path.dirname(__file__))

from remotesigner.psbt_parser import parse_psbt
from remotesigner.signer import sign_psbt


class PrintStatusCallback:
    """Status callback that prints to stdout."""

    def onStatus(self, message):
        print(f"  [{message}]")


def load_psbt(path: str) -> bytes:
    """Load a PSBT from file. Accepts base64-encoded or raw binary."""
    with open(path, "rb") as f:
        raw = f.read()

    # Check for PSBT magic bytes (raw binary)
    if raw.startswith(b"psbt\xff"):
        return raw

    # Try base64 decode (strip whitespace first)
    try:
        return base64.b64decode(raw.strip())
    except Exception:
        raise ValueError(f"Cannot parse {path}: not a valid PSBT (binary or base64)")


def cmd_parse(args):
    """Parse and display PSBT contents."""
    psbt_bytes = load_psbt(args.psbt_file)
    result = parse_psbt(psbt_bytes, network=args.network)

    print(f"\nPSBT Summary ({result['status']}):")
    print(f"  Inputs:  {len(result['inputs'])}")
    print(f"  Outputs: {len(result['outputs'])}")
    print(f"  Fee:     {result['fee']} sats")

    for inp in result["inputs"]:
        print(f"\n  Input #{inp['index']}:")
        print(f"    TXID:   {inp['txid']}")
        print(f"    Vout:   {inp['vout']}")
        print(f"    Amount: {inp['amount']} sats")

    for out in result["outputs"]:
        change = " (change)" if out["is_change"] else ""
        print(f"\n  Output #{out['index']}{change}:")
        print(f"    Address: {out['address']}")
        print(f"    Amount:  {out['amount']} sats")

    if result["signers"]:
        print("\n  Signers:")
        for s in result["signers"]:
            status = "signed" if s["signed"] else "unsigned"
            print(f"    {s['fingerprint']}: {status}")


def _query_device_info() -> dict:
    """Query Trezor model and firmware version. Returns metadata dict."""
    try:
        from trezorlib.transport.hid import HidTransport
        from trezorlib.models import TREZORS
        from trezorlib.client import TrezorClient
        from remotesigner.trezor_ui import AndroidTrezorUi

        devices = list(HidTransport.enumerate(models=TREZORS))
        if devices:
            transport = devices[0]
            transport.open()
            client = TrezorClient(transport, ui=AndroidTrezorUi())
            features = client.features
            info = {
                "trezor_model": features.model or "",
                "firmware_version": (
                    f"{features.fw_major}.{features.fw_minor}.{features.fw_patch}"
                    if features.fw_major is not None
                    else ""
                ),
            }
            client.close()
            return info
    except Exception:
        pass
    return {"trezor_model": "", "firmware_version": ""}


def cmd_sign(args):
    """Sign a PSBT with a real Trezor, optionally recording USB exchanges."""
    from desktop_bridge import DesktopUsbBridge, RecordingBridge

    psbt_bytes = load_psbt(args.psbt_file)

    # Query device info BEFORE signing (avoids opening a second USB connection after)
    device_info = {}
    if args.record:
        print("Querying Trezor device info...")
        device_info = _query_device_info()

    # Build the bridge stack
    bridge = DesktopUsbBridge()
    recorder = None
    if args.record:
        recorder = RecordingBridge(bridge)
        bridge = recorder

    print(f"Signing PSBT from {args.psbt_file} (network={args.network})...")
    if args.record:
        print(f"Recording USB exchanges to {args.record}")

    callback = PrintStatusCallback()
    result = sign_psbt(psbt_bytes, bridge, status_callback=callback, network=args.network)

    if result["status"] == "signed":
        print(f"\nSigning successful!")
        print(f"  Signed PSBT (base64): {result['psbt'][:80]}...")
        if "raw_tx" in result:
            print(f"  Raw TX (hex): {result['raw_tx'][:80]}...")
    else:
        print(f"\nSigning failed: {result.get('error', 'unknown error')}")
        sys.exit(1)

    # Save cassette if recording
    if recorder and args.record:
        recorder.save_cassette(
            args.record,
            scenario=os.path.splitext(os.path.basename(args.record))[0],
            network=args.network,
            input_psbt_b64=base64.b64encode(psbt_bytes).decode(),
            **device_info,
        )
        print(f"  Cassette saved to {args.record}")


def main():
    parser = argparse.ArgumentParser(
        description="Desktop PSBT signing and cassette recording"
    )
    parser.add_argument(
        "--network", default="main", choices=["main", "test"],
        help="Bitcoin network (default: main)"
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    # parse command
    p_parse = subparsers.add_parser("parse", help="Parse and display a PSBT")
    p_parse.add_argument("psbt_file", help="Path to PSBT file (binary or base64)")

    # sign command
    p_sign = subparsers.add_parser("sign", help="Sign a PSBT with Trezor")
    p_sign.add_argument("psbt_file", help="Path to PSBT file (binary or base64)")
    p_sign.add_argument("--record", help="Path to save cassette JSON file")

    args = parser.parse_args()

    if args.command == "parse":
        cmd_parse(args)
    elif args.command == "sign":
        cmd_sign(args)


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Verify the parse command works**

Run: `cd /Users/sasha/Projects/remote_signer && python tests/sign_cli.py parse --help`
Expected: Help text showing parse subcommand usage

- [ ] **Step 3: Commit**

```bash
git add tests/sign_cli.py
git commit -m "feat: CLI script for desktop PSBT signing and cassette recording"
```

---

### Task 6: E2E test file using PlaybackBridge

**Files:**
- Create: `tests/test_signing_e2e.py`

- [ ] **Step 1: Create the E2E test file**

Create `tests/test_signing_e2e.py`:

```python
"""End-to-end signing tests using recorded USB cassettes.

These tests replay pre-recorded Trezor USB exchanges via PlaybackBridge,
testing the full sign_psbt() flow without hardware.

To record a new cassette:
    python tests/sign_cli.py sign <psbt_file> --record tests/cassettes/<name>.json
"""

import base64
import json
import os
import pytest

from remotesigner.signer import sign_psbt

CASSETTES_DIR = os.path.join(os.path.dirname(__file__), "cassettes")


def cassette_path(name: str) -> str:
    return os.path.join(CASSETTES_DIR, f"{name}.json")


def has_cassette(name: str) -> bool:
    return os.path.exists(cassette_path(name))


def load_cassette(name: str) -> dict:
    with open(cassette_path(name)) as f:
        return json.load(f)


@pytest.mark.skipif(
    not has_cassette("single-sig-p2wpkh"),
    reason="Cassette not recorded yet. Run: python tests/sign_cli.py sign <psbt> --record tests/cassettes/single-sig-p2wpkh.json",
)
class TestSingleSigP2wpkh:
    """E2E signing test for a single-sig P2WPKH transaction."""

    @pytest.fixture
    def cassette(self):
        return load_cassette("single-sig-p2wpkh")

    @pytest.fixture
    def bridge(self, cassette):
        from desktop_bridge import PlaybackBridge

        return PlaybackBridge(cassette)

    def test_sign_returns_signed_psbt(self, bridge, cassette):
        """sign_psbt() produces a signed PSBT from the recorded exchange."""
        psbt_b64 = cassette["metadata"].get("input_psbt_b64")
        if not psbt_b64:
            pytest.skip("Cassette missing input_psbt_b64 in metadata")

        psbt_bytes = base64.b64decode(psbt_b64)
        network = cassette["metadata"].get("network", "main")
        result = sign_psbt(psbt_bytes, bridge, network=network)

        assert result["status"] == "signed"
        assert "psbt" in result
        # Verify the signed PSBT is valid base64
        signed_bytes = base64.b64decode(result["psbt"])
        assert signed_bytes.startswith(b"psbt\xff")

    def test_sign_inserts_signatures(self, bridge, cassette):
        """Signed PSBT has partial_sigs populated."""
        from embit.psbt import PSBT

        psbt_b64 = cassette["metadata"].get("input_psbt_b64")
        if not psbt_b64:
            pytest.skip("Cassette missing input_psbt_b64 in metadata")

        psbt_bytes = base64.b64decode(psbt_b64)
        network = cassette["metadata"].get("network", "main")
        result = sign_psbt(psbt_bytes, bridge, network=network)

        signed_psbt = PSBT.parse(base64.b64decode(result["psbt"]))
        for inp in signed_psbt.inputs:
            assert len(inp.partial_sigs) > 0 or inp.unknown.get(b"\x13")
```

- [ ] **Step 2: Run tests to confirm they skip gracefully**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_signing_e2e.py -v`
Expected: Tests SKIPPED with message about missing cassette

- [ ] **Step 3: Run full test suite**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/ -v`
Expected: All tests PASS or SKIP (E2E tests skip, everything else passes)

- [ ] **Step 4: Commit**

```bash
git add tests/test_signing_e2e.py
git commit -m "feat: E2E signing tests with cassette playback"
```

---

## Recording Your First Cassette

After all tasks are complete, record a cassette with a real Trezor:

1. Create a testnet PSBT (e.g., using Sparrow Wallet or Electrum)
2. Plug in your Trezor Safe 3
3. Run:
   ```bash
   python tests/sign_cli.py sign path/to/testnet.psbt \
     --network test \
     --record tests/cassettes/single-sig-p2wpkh.json
   ```
4. Confirm the transaction on the Trezor
5. The CLI automatically stores `input_psbt_b64` in the cassette metadata
6. Run the E2E tests: `python -m pytest tests/test_signing_e2e.py -v`
7. Commit the cassette: `git add tests/cassettes/ && git commit -m "test: record single-sig-p2wpkh cassette"`

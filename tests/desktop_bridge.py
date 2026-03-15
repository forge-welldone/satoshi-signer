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

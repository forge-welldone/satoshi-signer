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

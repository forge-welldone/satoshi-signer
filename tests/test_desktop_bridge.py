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

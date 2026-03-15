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

    def test_assert_consumed_with_unconsumed_raises(self):
        from desktop_bridge import PlaybackBridge

        cassette = self._make_cassette([
            {"dir": "w", "data": "aa" * 64},
            {"dir": "r", "data": "bb" * 64},
        ])
        bridge = PlaybackBridge(cassette)
        bridge.open()
        with pytest.raises(AssertionError, match="unconsumed"):
            bridge.assert_consumed()

    def test_assert_consumed_after_all_consumed_ok(self):
        from desktop_bridge import PlaybackBridge

        cassette = self._make_cassette([
            {"dir": "w", "data": "aa" * 64},
        ])
        bridge = PlaybackBridge(cassette)
        bridge.open()
        bridge.writeChunk(bytes.fromhex("aa" * 64))
        bridge.assert_consumed()  # should not raise

    def test_close_is_noop(self):
        """close() does not assert — trezorlib may close mid-session."""
        from desktop_bridge import PlaybackBridge

        cassette = self._make_cassette([
            {"dir": "w", "data": "aa" * 64},
        ])
        bridge = PlaybackBridge(cassette)
        bridge.open()
        bridge.close()  # should not raise even with unconsumed exchanges

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
        playback.assert_consumed()


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
        try:
            bridge.open()
            # Trezor is connected — skip this test
            bridge.close()
            pytest.skip("Trezor is connected, cannot test no-device error")
        except RuntimeError as e:
            assert "No Trezor" in str(e)

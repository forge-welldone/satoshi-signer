import pytest
from remotesigner.usb_transport import AndroidTransport


class MockBridge:
    """Simulates the Kotlin UsbBridge object."""

    def __init__(self):
        self.written_chunks = []
        self.read_queue = []

    def writeChunk(self, data):
        self.written_chunks.append(bytes(data))

    def readChunk(self):
        if self.read_queue:
            return self.read_queue.pop(0)
        return bytes(64)

    def open(self):
        pass

    def close(self):
        pass


class TestAndroidTransport:
    def test_write_chunk_delegates_to_bridge(self):
        bridge = MockBridge()
        transport = AndroidTransport(bridge)
        transport.open()
        chunk = bytes(64)
        transport.write_chunk(chunk)
        assert bridge.written_chunks == [chunk]

    def test_read_chunk_delegates_to_bridge(self):
        bridge = MockBridge()
        expected = bytes(range(64))
        bridge.read_queue.append(expected)
        transport = AndroidTransport(bridge)
        transport.open()
        result = transport.read_chunk()
        assert result == expected

    def test_chunk_size_is_64(self):
        bridge = MockBridge()
        transport = AndroidTransport(bridge)
        assert transport.CHUNK_SIZE == 64

    def test_open_close_lifecycle(self):
        bridge = MockBridge()
        transport = AndroidTransport(bridge)
        transport.open()
        transport.close()
        # Should not raise

    def test_write_chunk_wrong_size_raises(self):
        bridge = MockBridge()
        transport = AndroidTransport(bridge)
        transport.open()
        with pytest.raises(ValueError, match="64"):
            transport.write_chunk(bytes(32))

    def test_read_chunk_bad_size_from_bridge_raises(self):
        class BadBridge(MockBridge):
            def readChunk(self):
                return bytes(32)  # wrong size

        transport = AndroidTransport(BadBridge())
        transport.open()
        with pytest.raises(ValueError, match="64"):
            transport.read_chunk()

    def test_multiple_chunks_written_in_order(self):
        bridge = MockBridge()
        transport = AndroidTransport(bridge)
        transport.open()
        chunks = [bytes([i] * 64) for i in range(3)]
        for chunk in chunks:
            transport.write_chunk(chunk)
        assert bridge.written_chunks == chunks

    def test_get_path_returns_android_prefix(self):
        bridge = MockBridge()
        transport = AndroidTransport(bridge)
        assert transport.get_path().startswith("android")

    def test_handle_uses_protocol_v1(self):
        from trezorlib.transport.protocol import ProtocolV1
        bridge = MockBridge()
        transport = AndroidTransport(bridge)
        assert isinstance(transport.protocol, ProtocolV1)

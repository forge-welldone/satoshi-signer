"""Custom trezorlib Transport that delegates USB I/O to a Kotlin Android bridge.

Architecture mirrors trezorlib's HID transport:

    AndroidHandle(bridge)          -- physical layer: open/close/read_chunk/write_chunk
        |
    ProtocolV1(handle)             -- message framing (ProtocolV1 wire format)
        |
    AndroidTransport(protocol)     -- Transport subclass exposed to trezorlib callers

The Kotlin bridge object (passed from Android via Chaquopy) must implement:
    open()               -> None
    close()              -> None
    writeChunk(bytes)    -> None
    readChunk()          -> bytes  (always 64 bytes)
"""

from trezorlib.transport.protocol import ProtocolBasedTransport, ProtocolV1

CHUNK_SIZE = 64


class AndroidHandle:
    """Physical layer Handle that delegates 64-byte chunk I/O to a Kotlin USB bridge.

    Conforms to the structural Handle protocol defined in trezorlib.transport.protocol.
    """

    def __init__(self, bridge) -> None:
        self._bridge = bridge

    def open(self) -> None:
        self._bridge.open()

    def close(self) -> None:
        self._bridge.close()

    def write_chunk(self, chunk: bytes) -> None:
        if len(chunk) != CHUNK_SIZE:
            raise ValueError(f"Chunk must be exactly {CHUNK_SIZE} bytes, got {len(chunk)}")
        self._bridge.writeChunk(chunk)

    def read_chunk(self) -> bytes:
        data = bytes(self._bridge.readChunk())
        if len(data) != CHUNK_SIZE:
            raise ValueError(f"Expected {CHUNK_SIZE}-byte chunk from bridge, got {len(data)}")
        return data


class AndroidTransport(ProtocolBasedTransport):
    """trezorlib Transport for Android USB via a Kotlin Chaquopy bridge.

    Usage::

        transport = AndroidTransport(kotlin_usb_bridge)
        client = TrezorClient(transport, ui=AndroidUI())
    """

    PATH_PREFIX = "android"
    CHUNK_SIZE = CHUNK_SIZE

    def __init__(self, bridge) -> None:
        self.handle = AndroidHandle(bridge)
        super().__init__(protocol=ProtocolV1(self.handle))

    def get_path(self) -> str:
        return f"{self.PATH_PREFIX}:usb"

    # write_chunk / read_chunk are convenience pass-throughs so tests can exercise
    # the handle layer without going through ProtocolV1 framing.
    def write_chunk(self, chunk: bytes) -> None:
        self.handle.write_chunk(chunk)

    def read_chunk(self) -> bytes:
        return self.handle.read_chunk()

    def open(self) -> None:
        self.handle.open()

    def close(self) -> None:
        self.handle.close()

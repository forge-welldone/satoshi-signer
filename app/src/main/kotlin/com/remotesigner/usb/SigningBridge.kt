package com.remotesigner.usb

/**
 * Common interface for Trezor USB communication bridges.
 * Implemented by UsbBridge (production) and PlaybackBridge (tests).
 * Python's AndroidHandle calls writeChunk/readChunk via Chaquopy proxy.
 */
interface SigningBridge {
    fun open()
    fun close()
    fun writeChunk(data: ByteArray)
    fun readChunk(): ByteArray
}

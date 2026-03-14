package com.remotesigner.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer

/**
 * USB bridge for Trezor communication. Passed to Python via Chaquopy.
 *
 * Provides writeChunk/readChunk methods that Python's AndroidHandle calls.
 * Uses UsbRequest for interrupt endpoint transfers.
 */
class UsbBridge(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
) {
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var isOpen = false

    companion object {
        const val CHUNK_SIZE = 64
        const val TIMEOUT_MS = 5000L
    }

    fun open() {
        if (isOpen) return

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_HID) {
                usbInterface = intf
                break
            }
        }

        val intf = usbInterface
            ?: throw IllegalStateException("No HID interface found on Trezor device")

        if (!connection.claimInterface(intf, true)) {
            throw IllegalStateException("Failed to claim USB interface")
        }

        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    endpointIn = ep
                } else {
                    endpointOut = ep
                }
            }
        }

        if (endpointIn == null || endpointOut == null) {
            throw IllegalStateException(
                "Could not find interrupt endpoints (IN: $endpointIn, OUT: $endpointOut)"
            )
        }

        isOpen = true
    }

    fun close() {
        if (!isOpen) return
        usbInterface?.let { connection.releaseInterface(it) }
        connection.close()
        isOpen = false
    }

    @Synchronized
    fun writeChunk(data: ByteArray) {
        val ep = endpointOut
            ?: throw IllegalStateException("USB not open: no OUT endpoint")

        require(data.size == CHUNK_SIZE) { "Expected $CHUNK_SIZE bytes, got ${data.size}" }

        val request = UsbRequest()
        try {
            if (!request.initialize(connection, ep)) {
                throw IllegalStateException("Failed to initialize USB write request")
            }
            val buffer = ByteBuffer.wrap(data)
            if (!request.queue(buffer)) {
                throw IllegalStateException("Failed to queue USB write request")
            }
            val completed = connection.requestWait(TIMEOUT_MS)
            if (completed != request) {
                throw IllegalStateException("USB write request failed or timed out")
            }
        } finally {
            request.close()
        }
    }

    @Synchronized
    fun readChunk(): ByteArray {
        val ep = endpointIn
            ?: throw IllegalStateException("USB not open: no IN endpoint")

        val request = UsbRequest()
        try {
            if (!request.initialize(connection, ep)) {
                throw IllegalStateException("Failed to initialize USB read request")
            }
            val buffer = ByteBuffer.allocate(CHUNK_SIZE)
            if (!request.queue(buffer)) {
                throw IllegalStateException("Failed to queue USB read request")
            }
            val completed = connection.requestWait(TIMEOUT_MS)
            if (completed != request) {
                throw IllegalStateException("USB read request failed or timed out")
            }
            buffer.rewind()
            val result = ByteArray(CHUNK_SIZE)
            buffer.get(result)
            return result
        } finally {
            request.close()
        }
    }
}

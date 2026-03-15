package com.remotesigner.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbRequest
import android.util.Log
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
    @Volatile private var isOpen = false

    companion object {
        private const val TAG = "UsbBridge"
        const val CHUNK_SIZE = 64
        const val TIMEOUT_MS = 600_000L  // 10 min — passphrase entry on Trezor can be slow
    }

    /** Dump all USB interfaces and endpoints for diagnostics. */
    fun dumpDeviceInfo(): String {
        val sb = StringBuilder()
        sb.appendLine("Device: ${device.deviceName} VID=0x${"%04X".format(device.vendorId)} PID=0x${"%04X".format(device.productId)}")
        sb.appendLine("Interfaces: ${device.interfaceCount}")
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            val className = when (intf.interfaceClass) {
                UsbConstants.USB_CLASS_HID -> "HID"
                UsbConstants.USB_CLASS_VENDOR_SPEC -> "VENDOR_SPEC"
                UsbConstants.USB_CLASS_CDC_DATA -> "CDC_DATA"
                UsbConstants.USB_CLASS_MASS_STORAGE -> "MASS_STORAGE"
                else -> "0x${"%02X".format(intf.interfaceClass)}"
            }
            sb.appendLine("  [$i] class=$className subclass=${intf.interfaceSubclass} protocol=${intf.interfaceProtocol} endpoints=${intf.endpointCount}")
            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                val type = when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                    UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
                    else -> "UNKNOWN(${ep.type})"
                }
                sb.appendLine("    ep[$j] $dir $type maxPacket=${ep.maxPacketSize} addr=0x${"%02X".format(ep.address)}")
            }
        }
        return sb.toString()
    }

    fun open() {
        if (isOpen) return

        val info = dumpDeviceInfo()
        Log.i(TAG, "Opening device:\n$info")

        // Trezor exposes both WebUSB (0xFF) and HID (0x03) interfaces.
        // Firmware responds on WebUSB, so prefer it (matches desktop trezorlib).
        // If claiming one fails, try the other.
        val targetClasses = intArrayOf(UsbConstants.USB_CLASS_VENDOR_SPEC, UsbConstants.USB_CLASS_HID)
        var claimed = false

        for (targetClass in targetClasses) {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == targetClass) {
                    Log.i(TAG, "Trying interface [$i] class=0x${"%02X".format(targetClass)}")
                    if (connection.claimInterface(intf, true)) {
                        usbInterface = intf
                        claimed = true
                        Log.i(TAG, "Claimed interface [$i] OK")
                    } else {
                        Log.w(TAG, "Failed to claim interface [$i], trying next...")
                    }
                    break
                }
            }
            if (claimed) break
        }

        if (!claimed) {
            throw IllegalStateException("Failed to claim any USB interface on Trezor.\n$info")
        }

        // Look for interrupt endpoints first, fall back to bulk
        val intf = usbInterface!!
        for (epType in intArrayOf(UsbConstants.USB_ENDPOINT_XFER_INT, UsbConstants.USB_ENDPOINT_XFER_BULK)) {
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type == epType) {
                    if (ep.direction == UsbConstants.USB_DIR_IN && endpointIn == null) {
                        endpointIn = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT && endpointOut == null) {
                        endpointOut = ep
                    }
                }
            }
            if (endpointIn != null && endpointOut != null) break
        }

        Log.i(TAG, "Endpoints: IN=${endpointIn?.let { "0x${"%02X".format(it.address)} ${if (it.type == UsbConstants.USB_ENDPOINT_XFER_INT) "INT" else "BULK"}" }} OUT=${endpointOut?.let { "0x${"%02X".format(it.address)} ${if (it.type == UsbConstants.USB_ENDPOINT_XFER_INT) "INT" else "BULK"}" }}")

        if (endpointIn == null || endpointOut == null) {
            throw IllegalStateException(
                "Could not find endpoints (IN: $endpointIn, OUT: $endpointOut).\n$info"
            )
        }

        isOpen = true
        Log.i(TAG, "Device opened successfully")
    }

    fun close() {
        try {
            usbInterface?.let { connection.releaseInterface(it) }
        } catch (e: Exception) {
            Log.w(TAG, "releaseInterface failed: ${e.message}")
        }
        try {
            connection.close()
        } catch (e: Exception) {
            Log.w(TAG, "connection.close failed: ${e.message}")
        }
        isOpen = false
        usbInterface = null
        endpointIn = null
        endpointOut = null
        Log.i(TAG, "Device closed")
    }

    @Synchronized
    fun writeChunk(data: ByteArray) {
        val ep = endpointOut
            ?: throw IllegalStateException("USB not open: no OUT endpoint")

        require(data.size == CHUNK_SIZE) { "Expected $CHUNK_SIZE bytes, got ${data.size}" }

        Log.d(TAG, "writeChunk: ${data.take(8).joinToString("") { "%02X".format(it) }}...")

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
            Log.d(TAG, "writeChunk: OK")
        } finally {
            request.close()
        }
    }

    @Synchronized
    fun readChunk(): ByteArray {
        val ep = endpointIn
            ?: throw IllegalStateException("USB not open: no IN endpoint")

        Log.d(TAG, "readChunk: waiting...")

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
            Log.d(TAG, "readChunk: ${result.take(8).joinToString("") { "%02X".format(it) }}...")
            return result
        } finally {
            request.close()
        }
    }
}

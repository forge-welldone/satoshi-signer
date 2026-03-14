package com.remotesigner.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

class TrezorUsbManager(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "com.remotesigner.USB_PERMISSION"
        const val TREZOR_VENDOR_ID = 0x1209
        val TREZOR_PRODUCT_IDS = setOf(0x53C0, 0x53C1, 0x53B0, 0x53B1, 0x53A0, 0x53A1, 0x01)
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findTrezorDevice(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { device ->
            device.vendorId == TREZOR_VENDOR_ID &&
                device.productId in TREZOR_PRODUCT_IDS
        }
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    fun requestPermission(device: UsbDevice, callback: (Boolean) -> Unit) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), flags
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    callback(granted)
                    context.unregisterReceiver(this)
                }
            }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        usbManager.requestPermission(device, permissionIntent)
    }

    fun openDevice(device: UsbDevice): UsbBridge? {
        val connection = usbManager.openDevice(device) ?: return null
        return UsbBridge(device, connection)
    }
}

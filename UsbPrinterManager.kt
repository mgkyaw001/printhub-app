package com.printhub

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

data class UsbPrinterInfo(
    val deviceId: Int,
    val deviceName: String,
    val productName: String,
    val manufacturerName: String,
    val vendorId: Int,
    val productId: Int,
    val usbClass: Int,
    val isPrinterClass: Boolean,
    val hasPermission: Boolean,
    val isConnected: Boolean
)

class UsbPrinterManager(
    private val context: Context,
    private val onDeviceListChanged: () -> Unit,
    private val onPermissionResult: (device: UsbDevice, granted: Boolean) -> Unit
) {
    companion object {
        const val ACTION_USB_PERMISSION = "com.printhub.USB_PERMISSION"
        val KNOWN_PRINTER_VENDORS = setOf(0x03F0, 0x04B8, 0x04A9, 0x04F9, 0x0A5F, 0x0519, 0x0416, 0x0FE6)
    }

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var isReceiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null) {
                        onPermissionResult(device, granted)
                        onDeviceListChanged()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    onDeviceListChanged()
                }
            }
        }
    }

    fun register() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(usbReceiver, filter)
            }
            isReceiverRegistered = true
        }
    }

    fun unregister() {
        if (isReceiverRegistered) {
            context.unregisterReceiver(usbReceiver)
            isReceiverRegistered = false
        }
    }

    fun getConnectedDevices(): List<UsbPrinterInfo> {
        val list = mutableListOf<UsbPrinterInfo>()
        for ((_, device) in usbManager.deviceList) {
            list.add(
                UsbPrinterInfo(
                    deviceId = device.deviceId,
                    deviceName = device.deviceName,
                    productName = device.productName ?: "USB Device (${device.deviceId})",
                    manufacturerName = device.manufacturerName ?: "Unknown",
                    vendorId = device.vendorId,
                    productId = device.productId,
                    usbClass = device.deviceClass,
                    isPrinterClass = isPrinterDevice(device),
                    hasPermission = usbManager.hasPermission(device),
                    isConnected = false
                )
            )
        }
        return list
    }

    fun findDeviceById(deviceId: Int): UsbDevice? = usbManager.deviceList.values.find { it.deviceId == deviceId }

    fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
        usbManager.requestPermission(device, pi)
    }

    private fun isPrinterDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_PRINTER) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_PRINTER) return true
        }
        return KNOWN_PRINTER_VENDORS.contains(device.vendorId)
    }
}
package com.printhub

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

enum class PrinterProtocol {
    ESC_POS,
    PCL_RASTER,
    PWG_RASTER,
    ANDROID_PRINT_FRAMEWORK,
    UNSUPPORTED_DIRECT
}

data class PrinterCapabilities(
    val supportsColor: Boolean,
    val supportsA4: Boolean,
    val supportsPhoto: Boolean,
    val maxDpi: Int,
    val protocol: PrinterProtocol,
    val explanationEn: String,
    val explanationMy: String
)

class PrinterManager(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var transport: UsbPrinterTransport? = null
    var activeDevice: UsbDevice? = null
        private set

    val isConnected: Boolean
        get() = transport?.isConnected == true

    fun getCapabilities(device: UsbDevice?): PrinterCapabilities {
        if (device == null) {
            return PrinterCapabilities(false, false, false, 203, PrinterProtocol.UNSUPPORTED_DIRECT,
                "No printer connected.", "Printer မချိတ်ထားသေးပါ။")
        }

        val vid = device.vendorId
        val name = (device.productName ?: "").lowercase()

        // Thermal / POS
        if (vid == 0x0416 || vid == 0x0FE6 || name.contains("thermal") || name.contains("pos") || name.contains("xprinter")) {
            return PrinterCapabilities(
                supportsColor = false,
                supportsA4 = false,
                supportsPhoto = false,
                maxDpi = 203,
                protocol = PrinterProtocol.ESC_POS,
                explanationEn = "Thermal / POS printer detected (Monochrome only, ESC/POS).",
                explanationMy = "Thermal/POS printer ဖြစ်သောကြောင့် အဖြူအမည်းသာ ထောက်ပံ့ပါသည်။"
            )
        }

        // Office Inkjet & Color Printers (HP, Epson, Canon, Brother)
        val isColor = !name.contains("monochrome") && !name.contains("mono")
        return PrinterCapabilities(
            supportsColor = isColor,
            supportsA4 = true,
            supportsPhoto = true,
            maxDpi = 600,
            protocol = PrinterProtocol.ANDROID_PRINT_FRAMEWORK,
            explanationEn = "Office / Photo printer detected. Full color & document printing supported.",
            explanationMy = "Office/Photo printer ဖြစ်ပြီး အရောင်အပြည့်နှင့် A4 စာရွက်များ Print ထုတ်နိုင်ပါသည်။"
        )
    }

    fun connect(device: UsbDevice): Boolean {
        disconnect()
        val trans = UsbPrinterTransport(usbManager, device)
        if (trans.open()) {
            transport = trans
            activeDevice = device
            return true
        }
        return false
    }

    fun disconnect() {
        transport?.close()
        transport = null
        activeDevice = null
    }
}
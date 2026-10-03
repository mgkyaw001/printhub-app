package com.printhub

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    private lateinit var usbPrinterManager: UsbPrinterManager
    private lateinit var printerManager: PrinterManager
    private lateinit var printJobManager: PrintJobManager
    private lateinit var fileManager: FileManager
    private lateinit var pdfPrintRenderer: PdfPrintRenderer
    private lateinit var webAppInterface: WebAppInterface

    private var currentCameraPhotoUri: Uri? = null
    private val gson = Gson()

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        if (!uris.isNullOrEmpty()) {
            handleSelectedUris(uris)
        }
    }

    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success && currentCameraPhotoUri != null) {
            handleSelectedUris(listOf(currentCameraPhotoUri!!))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        initManagers()
        setupWebView()

        intent?.let { handleUsbIntent(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent) {
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED" == intent.action) {
            notifyUsbDevicesChanged()
        }
    }

    private fun initManagers() {
        printerManager = PrinterManager(this)
        printJobManager = PrintJobManager(this)
        fileManager = FileManager(this)
        pdfPrintRenderer = PdfPrintRenderer(this)

        usbPrinterManager = UsbPrinterManager(
            context = this,
            onDeviceListChanged = {
                runOnUiThread { notifyUsbDevicesChanged() }
            },
            onPermissionResult = { device: UsbDevice, granted: Boolean ->
                runOnUiThread {
                    if (granted) {
                        Toast.makeText(this, "USB Permission: ${device.productName}", Toast.LENGTH_SHORT).show()
                        val success = printerManager.connect(device)
                        notifyUsbDevicesChanged()
                        if (success) {
                            evalJs("window.onPrinterConnected && window.onPrinterConnected('${device.productName}')")
                        }
                    } else {
                        Toast.makeText(this, "USB Permission Denied", Toast.LENGTH_SHORT).show()
                        evalJs("window.onPrinterError && window.onPrinterError('USB permission denied')")
                    }
                }
            }
        )
        usbPrinterManager.register()

        webAppInterface = WebAppInterface(
            activity = this,
            usbPrinterManager = usbPrinterManager,
            printerManager = printerManager,
            printJobManager = printJobManager,
            fileManager = fileManager,
            pdfPrintRenderer = pdfPrintRenderer,
            onPickFileRequested = { mimeType ->
                filePickerLauncher.launch(arrayOf(mimeType, "image/*", "application/pdf", "text/*"))
            },
            onTakePhotoRequested = { launchCamera() }
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.addJavascriptInterface(webAppInterface, "Android")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                progressBar.progress = newProgress
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                notifyUsbDevicesChanged()
            }
        }

        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun launchCamera() {
        try {
            val photosDir = File(cacheDir, "photos").apply { mkdirs() }
            val photoFile = File(photosDir, "photo_${System.currentTimeMillis()}.jpg")
            val photoUri = FileProvider.getUriForFile(this, "com.printhub.fileprovider", photoFile)
            currentCameraPhotoUri = photoUri
            takePhotoLauncher.launch(photoUri)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSelectedUris(uris: List<Uri>) {
        val filePayloads = mutableListOf<Map<String, Any>>()
        for (uri in uris) {
            val meta = fileManager.getFileMetadata(uri)
            val item = mutableMapOf<String, Any>(
                "name" to meta.displayName,
                "mimeType" to meta.mimeType,
                "size" to meta.sizeBytes,
                "isImage" to meta.isImage,
                "isPdf" to meta.isPdf,
                "isText" to meta.isText
            )
            if (meta.isImage) {
                val bmp = fileManager.loadSampledBitmap(uri, 1200)
                if (bmp != null) {
                    val stream = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                    val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                    item["dataUrl"] = "data:image/jpeg;base64,$base64"
                }
            } else if (meta.isText) {
                item["textContent"] = fileManager.readTextContent(uri)
            } else if (meta.isPdf) {
                val info = pdfPrintRenderer.openPdf(uri, meta.displayName)
                if (info != null) {
                    item["pageCount"] = info.pageCount
                }
            }
            filePayloads.add(item)
        }
        val json = gson.toJson(filePayloads)
        evalJs("window.onNativeFilesSelected && window.onNativeFilesSelected($json)")
    }

    private fun notifyUsbDevicesChanged() {
        val devices = usbPrinterManager.getConnectedDevices()
        evalJs("window.onUsbDevicesUpdated && window.onUsbDevicesUpdated(${gson.toJson(devices)})")
    }

    private fun evalJs(script: String) {
        runOnUiThread { webView.evaluateJavascript(script, null) }
    }

    override fun onDestroy() {
        super.onDestroy()
        usbPrinterManager.unregister()
        printerManager.disconnect()
        pdfPrintRenderer.close()
    }
}
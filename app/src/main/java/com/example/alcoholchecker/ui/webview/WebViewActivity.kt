package com.example.alcoholchecker.ui.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.alcoholchecker.ble.BleBridgeServer
import com.example.alcoholchecker.ble.BleDeviceManager
import com.example.alcoholchecker.databinding.ActivityWebviewBinding
import com.example.alcoholchecker.nfc.CardType
import com.example.alcoholchecker.nfc.NfcBridgeServer
import com.example.alcoholchecker.nfc.NfcReader
import com.example.alcoholchecker.serial.Fc1200BridgeServer
import com.example.alcoholchecker.serial.UsbSerialManager
import org.java_websocket.server.WebSocketServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebviewBinding

    private var nfcAdapter: NfcAdapter? = null
    private val nfcReader = NfcReader()
    private var nfcBridgeServer: NfcBridgeServer? = null

    private var bleDeviceManager: BleDeviceManager? = null
    private var bleBridgeServer: BleBridgeServer? = null

    private var usbSerialManager: UsbSerialManager? = null
    private var fc1200BridgeServer: Fc1200BridgeServer? = null

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        fileChooserCallback?.onReceiveValue(uris.toTypedArray())
        fileChooserCallback = null
    }

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startBleScan()
        } else {
            Log.w(TAG, "BLE permissions denied")
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        pendingPermissionRequest?.let { request ->
            val granted = permissions.entries.all { it.value }
            if (granted) {
                request.grant(request.resources)
            } else {
                request.deny()
            }
            pendingPermissionRequest = null
        }
    }

    private var pendingPermissionRequest: PermissionRequest? = null

    companion object {
        private const val TAG = "WebViewActivity"
        private const val BASE_URL = "https://alc-app.m-tama-ramu.workers.dev"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupSwipeRefresh()
        setupBackNavigation()
        setupNfc()
        startNfcBridgeServer()
        setupBle()
        setupSerial()

        binding.webView.loadUrl("$BASE_URL/login")
    }

    private fun setupNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Log.w(TAG, "NFC not available on this device")
        }
    }

    private fun startNfcBridgeServer() {
        nfcBridgeServer = NfcBridgeServer().apply {
            isReuseAddr = true
            connectionLostTimeout = 5
            startSafe(TAG, "NFC")
        }
    }

    override fun onResume() {
        super.onResume()
        enableNfcForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        disableNfcForegroundDispatch()
    }

    override fun onDestroy() {
        super.onDestroy()
        bleDeviceManager?.destroy()
        usbSerialManager?.destroy()
        try {
            bleBridgeServer?.stop(1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE bridge server", e)
        }
        try {
            nfcBridgeServer?.stop(1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping NFC bridge server", e)
        }
        try {
            fc1200BridgeServer?.stop(1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping FC-1200 bridge server", e)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TAG_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action
        ) {
            @Suppress("DEPRECATION")
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
            handleNfcTag(tag)
        }
    }

    private fun enableNfcForegroundDispatch() {
        val adapter = nfcAdapter ?: return
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
        adapter.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    private fun disableNfcForegroundDispatch() {
        nfcAdapter?.disableForegroundDispatch(this)
    }

    private fun handleNfcTag(tag: Tag) {
        lifecycleScope.launch {
            val cardData = withContext(Dispatchers.IO) {
                nfcReader.readCard(tag)
            }

            Log.d(TAG, "NFC card read: type=${cardData.cardType}, id=${cardData.cardId}")

            val server = nfcBridgeServer ?: return@launch

            when (cardData.cardType) {
                CardType.DRIVER_LICENSE -> {
                    server.broadcastLicenseRead(cardData)
                }
                else -> {
                    server.broadcastNfcRead(cardData.cardId)
                }
            }

            Toast.makeText(
                this@WebViewActivity,
                "NFC: カード読み取り完了",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupBle() {
        // Start BLE bridge WebSocket server
        bleBridgeServer = BleBridgeServer().apply {
            isReuseAddr = true
            connectionLostTimeout = 5
            onCommand = { command ->
                runOnUiThread {
                    when (command) {
                        "scan" -> startBleScan()
                        "reset" -> bleDeviceManager?.resetAndRescan()
                        "stop" -> bleDeviceManager?.stopScan()
                    }
                }
            }
            startSafe(TAG, "BLE")
        }

        // Setup BLE device manager
        bleDeviceManager = BleDeviceManager(this).apply {
            onDataReceived = { json ->
                bleBridgeServer?.broadcastData(json)
            }
        }

        // Request permissions and start scanning
        requestBlePermissions()
    }

    private fun requestBlePermissions() {
        val permissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            startBleScan()
        } else {
            blePermissionLauncher.launch(permissions)
        }
    }

    private fun startBleScan() {
        bleDeviceManager?.startScan()
    }

    private fun setupSerial() {
        fc1200BridgeServer = Fc1200BridgeServer().apply {
            isReuseAddr = true
            connectionLostTimeout = 5
            onCommand = { command ->
                runOnUiThread {
                    usbSerialManager?.handleCommand(command)
                }
            }
            startSafe(TAG, "FC-1200")
        }

        usbSerialManager = UsbSerialManager(this).apply {
            onEvent = { json ->
                fc1200BridgeServer?.broadcastData(json)
            }
            start()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // Google OAuth が WebView の UA ("wv") を拒否するため、Chrome UA に偽装
            userAgentString = userAgentString.replace("; wv", "")
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                val requestedResources = request.resources
                val androidPermissions = mutableListOf<String>()

                for (resource in requestedResources) {
                    when (resource) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                            androidPermissions.add(Manifest.permission.CAMERA)
                        }
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                            // RECORD_AUDIO is not in manifest yet
                        }
                    }
                }

                if (androidPermissions.isEmpty()) {
                    request.grant(requestedResources)
                    return
                }

                val allGranted = androidPermissions.all {
                    ContextCompat.checkSelfPermission(this@WebViewActivity, it) ==
                            PackageManager.PERMISSION_GRANTED
                }

                if (allGranted) {
                    request.grant(requestedResources)
                } else {
                    pendingPermissionRequest = request
                    cameraPermissionLauncher.launch(androidPermissions.toTypedArray())
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                fileChooserLauncher.launch("*/*")
                return true
            }
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            binding.webView.reload()
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }
}

/** WebSocket サーバーを安全に起動（ポート競合時はリトライ） */
private fun WebSocketServer.startSafe(tag: String, name: String, maxRetries: Int = 3) {
    for (i in 0 until maxRetries) {
        try {
            start()
            return
        } catch (e: Exception) {
            Log.w(tag, "$name bridge server start attempt ${i + 1} failed: ${e.message}")
            if (i < maxRetries - 1) {
                Thread.sleep(1000)
            }
        }
    }
    Log.e(tag, "$name bridge server failed to start after $maxRetries attempts")
}

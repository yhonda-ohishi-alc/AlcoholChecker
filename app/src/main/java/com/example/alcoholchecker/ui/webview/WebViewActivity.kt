package com.example.alcoholchecker.ui.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.provider.Settings
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.alcoholchecker.ble.BleBridgeServer
import com.example.alcoholchecker.ble.BleDeviceManager
import com.example.alcoholchecker.databinding.ActivityWebviewBinding
import com.example.alcoholchecker.nfc.CardType
import com.example.alcoholchecker.nfc.NfcBridgeServer
import com.example.alcoholchecker.nfc.NfcReader
import com.example.alcoholchecker.call.IncomingCallActivity
import com.example.alcoholchecker.call.RoomWatcher
import com.example.alcoholchecker.screencapture.ScreenCaptureBridgeServer
import com.example.alcoholchecker.screencapture.ScreenCaptureService
import com.example.alcoholchecker.serial.Fc1200BridgeServer
import com.example.alcoholchecker.serial.UsbSerialManager
import android.media.projection.MediaProjectionManager
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

    private var screenCaptureBridgeServer: ScreenCaptureBridgeServer? = null

    private var roomWatcher: RoomWatcher? = null

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

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "RECORD_AUDIO permission granted: $granted")
        // Proceed with screen capture regardless of mic permission
        launchScreenCaptureIntent()
    }

    private val phonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "Phone permission granted: $granted")
        if (granted) {
            val number = getPhoneNumberInternal()
            if (number.isNotEmpty()) {
                binding.webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('phone-number', { detail: '${number.replace("'", "\\'")}' }))",
                    null
                )
            }
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(intent)
            binding.webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('screen-capture-result', { detail: { success: true } }))",
                null
            )
        } else {
            binding.webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('screen-capture-result', { detail: { success: false } }))",
                null
            )
        }
    }

    companion object {
        private const val TAG = "WebViewActivity"
        private const val BASE_URL = "https://alc-app.m-tama-ramu.workers.dev"
        private const val SIGNALING_URL = "https://alc-signaling.m-tama-ramu.workers.dev"
        const val ACTION_NAVIGATE_TENKO = "com.example.alcoholchecker.NAVIGATE_TENKO"
        const val EXTRA_ROOM_ID = "extra_room_id"
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
        setupScreenCapture()

        // App Link (device-claim) で起動された場合はそのURLを開く
        val deepLinkUrl = intent?.data?.toString()
        if (deepLinkUrl != null && deepLinkUrl.contains("/device-claim")) {
            requestPhonePermissionIfNeeded()
            binding.webView.loadUrl(deepLinkUrl)
        } else {
            binding.webView.loadUrl("$BASE_URL/login")
        }
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
        try {
            screenCaptureBridgeServer?.stop(1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ScreenCapture bridge server", e)
        }
        stopScreenCapture()
        roomWatcher?.stop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // App Link で既存アクティビティに戻ってきた場合
        val deepLinkUrl = intent?.data?.toString()
        if (deepLinkUrl != null && deepLinkUrl.contains("/device-claim")) {
            requestPhonePermissionIfNeeded()
            binding.webView.loadUrl(deepLinkUrl)
            return
        }

        if (intent.action == ACTION_NAVIGATE_TENKO) {
            val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
            Log.d(TAG, "Navigating to remote tenko: roomId=$roomId")
            navigateToRemoteTenko(roomId)
            return
        }

        if (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TAG_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action
        ) {
            @Suppress("DEPRECATION")
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
            handleNfcTag(tag)
        }
    }

    private fun navigateToRemoteTenko(roomId: String?) {
        val webView = binding.webView
        val currentUrl = webView.url ?: ""

        if (currentUrl.contains(BASE_URL)) {
            // 既にアプリ内 → JS でタブ切り替え
            webView.evaluateJavascript("""
                (function() {
                    // manager タブ → remote_tenko タブをクリック
                    var tabs = document.querySelectorAll('button');
                    for (var i = 0; i < tabs.length; i++) {
                        if (tabs[i].textContent.includes('運行管理者')) {
                            tabs[i].click();
                        }
                    }
                    setTimeout(function() {
                        var tabs2 = document.querySelectorAll('button');
                        for (var j = 0; j < tabs2.length; j++) {
                            if (tabs2[j].textContent.includes('遠隔点呼')) {
                                tabs2[j].click();
                            }
                        }
                    }, 500);
                })();
            """.trimIndent(), null)
        } else {
            // まだログインページ等 → トップページに遷移
            webView.loadUrl("$BASE_URL/")
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

    private fun setupScreenCapture() {
        screenCaptureBridgeServer = ScreenCaptureBridgeServer().apply {
            isReuseAddr = true
            connectionLostTimeout = 5
            onCommand = { command ->
                if (command == "stop") {
                    runOnUiThread { stopScreenCapture() }
                }
            }
            startSafe(TAG, "ScreenCapture")
        }
        ScreenCaptureService.bridgeServer = screenCaptureBridgeServer
    }

    private fun requestScreenCapture() {
        // Ensure RECORD_AUDIO permission is granted before starting screen capture
        // so that getUserMedia({audio}) works for mic in WebRTC
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchScreenCaptureIntent()
        }
    }

    private fun launchScreenCaptureIntent() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projectionManager.createScreenCaptureIntent(
                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            projectionManager.createScreenCaptureIntent()
        }
        screenCaptureLauncher.launch(intent)
    }

    private fun stopScreenCapture() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        Log.d(TAG, "Screen capture stopped")
    }

    @SuppressLint("HardwareIds")
    private fun getPhoneNumberInternal(): String {
        return try {
            val telManager = getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager

            // Try TelephonyManager.line1Number (works with READ_PHONE_NUMBERS or READ_PHONE_STATE)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS)
                == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED) {
                @Suppress("DEPRECATION")
                return telManager.line1Number ?: ""
            }

            ""
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get phone number", e)
            ""
        }
    }

    private fun requestPhonePermissionIfNeeded() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_PHONE_NUMBERS
        } else {
            Manifest.permission.READ_PHONE_STATE
        }
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            phonePermissionLauncher.launch(permission)
        }
    }

    private fun startRoomWatcher() {
        if (roomWatcher != null) return
        roomWatcher = RoomWatcher(this, SIGNALING_URL).apply {
            onNewRoom = { roomId ->
                Log.d(TAG, "New tenko room: $roomId → showing incoming call screen")
                val intent = Intent(this@WebViewActivity, IncomingCallActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, "ドライバー点呼要求")
                    putExtra(IncomingCallActivity.EXTRA_ROOM_ID, roomId)
                }
                startActivity(intent)
            }
            start()
        }
        Log.d(TAG, "RoomWatcher started")
    }

    private fun stopRoomWatcher() {
        roomWatcher?.stop()
        roomWatcher = null
        Log.d(TAG, "RoomWatcher stopped")
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun setCallEnabled(enabled: Boolean) {
            Log.d(TAG, "setCallEnabled: $enabled")
            runOnUiThread {
                if (enabled) {
                    startRoomWatcher()
                    Toast.makeText(this@WebViewActivity, "着信通知 ON", Toast.LENGTH_SHORT).show()
                } else {
                    stopRoomWatcher()
                    Toast.makeText(this@WebViewActivity, "着信通知 OFF", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun isCallEnabled(): Boolean {
            return roomWatcher != null
        }

        @SuppressLint("HardwareIds")
        @JavascriptInterface
        fun getDeviceId(): String {
            return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        }

        @JavascriptInterface
        fun isFingerprintAvailable(): Boolean {
            val biometricManager = BiometricManager.from(this@WebViewActivity)
            return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                    BiometricManager.BIOMETRIC_SUCCESS
        }

        @JavascriptInterface
        fun requestFingerprint() {
            runOnUiThread {
                showBiometricPrompt()
            }
        }

        @JavascriptInterface
        fun setMicMuted(muted: Boolean) {
            Log.d(TAG, "setMicMuted: $muted")
            val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            audioManager.isMicrophoneMute = muted
        }

        @JavascriptInterface
        fun requestScreenCapture() {
            Log.d(TAG, "requestScreenCapture")
            runOnUiThread {
                this@WebViewActivity.requestScreenCapture()
            }
        }

        @JavascriptInterface
        fun stopScreenCapture() {
            Log.d(TAG, "stopScreenCapture")
            runOnUiThread {
                this@WebViewActivity.stopScreenCapture()
            }
        }

        @JavascriptInterface
        fun isScreenCapturing(): Boolean {
            return ScreenCaptureService.bridgeServer?.connections?.isNotEmpty() == true
        }

        @JavascriptInterface
        fun getPhoneNumber(): String {
            return getPhoneNumberInternal()
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Log.d(TAG, "Biometric auth succeeded")
                binding.webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('fingerprint-result', { detail: { success: true } }))",
                    null
                )
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Log.w(TAG, "Biometric auth error: $errorCode $errString")
                binding.webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('fingerprint-result', { detail: { success: false, error: '${errString.toString().replace("'", "\\'")}' } }))",
                    null
                )
            }

            override fun onAuthenticationFailed() {
                Log.w(TAG, "Biometric auth failed")
                // 指が一致しなかった場合。ダイアログはまだ表示中なのでイベントは送らない
            }
        }

        val prompt = BiometricPrompt(this, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("指紋認証")
            .setSubtitle("指紋で本人確認を行います")
            .setNegativeButtonText("キャンセル")
            .build()
        prompt.authenticate(promptInfo)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(true)
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

        webView.addJavascriptInterface(AndroidBridge(), "Android")

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
                            androidPermissions.add(Manifest.permission.RECORD_AUDIO)
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
                injectGetDisplayMediaOverride(view)
            }
        }
    }

    private fun injectGetDisplayMediaOverride(webView: WebView?) {
        webView?.evaluateJavascript("""
            (function() {
                if (window.__gdmOverridden) return;
                window.__gdmOverridden = true;

                navigator.mediaDevices.getDisplayMedia = function(constraints) {
                    return new Promise(function(resolve, reject) {
                        console.log('[Android] getDisplayMedia called');
                        function onResult(e) {
                            window.removeEventListener('screen-capture-result', onResult);
                            console.log('[Android] screen-capture-result:', e.detail);
                            if (!e.detail.success) {
                                reject(new DOMException('Permission denied', 'NotAllowedError'));
                                return;
                            }
                            var ws = new WebSocket('ws://127.0.0.1:8783');
                            var canvas = document.createElement('canvas');
                            var ctx = canvas.getContext('2d');
                            var stream = null;
                            var settled = false;

                            ws.binaryType = 'arraybuffer';

                            function handleFrame(arrayBuf) {
                                var blob = new Blob([arrayBuf], {type: 'image/jpeg'});
                                var url = URL.createObjectURL(blob);
                                var img = new Image();
                                img.onload = function() {
                                    URL.revokeObjectURL(url);
                                    if (canvas.width !== img.width) canvas.width = img.width;
                                    if (canvas.height !== img.height) canvas.height = img.height;
                                    ctx.drawImage(img, 0, 0);
                                    if (!settled) {
                                        stream = canvas.captureStream(15);
                                        var videoTrack = stream.getVideoTracks()[0];
                                        if (videoTrack) {
                                            var origStop = videoTrack.stop.bind(videoTrack);
                                            videoTrack.stop = function() {
                                                origStop();
                                                try { ws.send(JSON.stringify({command:'stop'})); } catch(ex) {}
                                                try { ws.close(); } catch(ex) {}
                                                if (window.Android) window.Android.stopScreenCapture();
                                            };
                                        }
                                        settled = true;
                                        console.log('[Android] screen stream ready:', canvas.width, 'x', canvas.height);
                                        resolve(stream);
                                    }
                                };
                                img.onerror = function() { URL.revokeObjectURL(url); };
                                img.src = url;
                            }

                            ws.onmessage = function(event) {
                                if (event.data instanceof ArrayBuffer) {
                                    handleFrame(event.data);
                                } else if (typeof event.data === 'string') {
                                    try {
                                        var msg = JSON.parse(event.data);
                                        console.log('[Android] ws msg:', msg.type);
                                        if (msg.type === 'stopped' && stream) {
                                            stream.getTracks().forEach(function(t) { t.stop(); });
                                        }
                                    } catch(ex) {}
                                }
                            };

                            ws.onerror = function(err) {
                                console.error('[Android] ws error', err);
                                if (!settled) reject(new DOMException('Screen capture failed', 'NotAllowedError'));
                            };

                            ws.onclose = function() {
                                console.log('[Android] ws closed, settled:', settled);
                                if (!settled) reject(new DOMException('Screen capture failed', 'NotAllowedError'));
                            };
                        }

                        window.addEventListener('screen-capture-result', onResult);
                        window.Android.requestScreenCapture();
                    });
                };

                console.log('[Android] getDisplayMedia override installed');
            })();
        """.trimIndent(), null)
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

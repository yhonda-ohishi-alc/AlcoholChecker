package com.example.alcoholchecker.ui.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.hardware.usb.UsbManager
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.example.alcoholchecker.service.WatchdogService
import android.media.projection.MediaProjectionManager
import org.java_websocket.server.WebSocketServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
        Log.i(TAG, "RECORD_AUDIO permission granted: $granted")
        // Proceed with screen capture regardless of mic permission
        launchScreenCaptureIntent()
    }

    private val phonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "Phone permission granted: $granted")
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
        private const val API_URL = "https://alc-app.m-tama-ramu.workers.dev"
        const val ACTION_NAVIGATE_TENKO = "com.example.alcoholchecker.NAVIGATE_TENKO"
        const val EXTRA_ROOM_ID = "extra_room_id"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Immersive full-screen mode
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setupWebView()
        setupSwipeRefresh()
        setupBackNavigation()
        setupNfc()
        startNfcBridgeServer()
        setupBle()
        setupSerial()
        setupScreenCapture()
        startWatchdogService()
        startHeartbeat()
        startBridgeHealthCheck()
        val isDeviceOwner = autoGrantPermissionsIfDeviceOwner()
        if (!isDeviceOwner) {
            requestOverlayPermission()
        }
        requestCameraPermissionIfNeeded()
        requestNotificationPermissionIfNeeded()

        if (isDeviceOwnerNeedingRegistration()) {
            // Device Owner 未登録: オーバーレイ表示 → リトライ → 成功後に WebView ロード
            autoRegisterDeviceOwner()
        } else {
            // 通常モード or 登録済み Device Owner: 既存フロー
            fetchDeviceSettingsAndAutoStart()

            // App Link (device-claim) で起動された場合はそのURLを開く
            val deepLinkUrl = intent?.data?.toString()
            if (deepLinkUrl != null && deepLinkUrl.contains("/device-claim")) {
                requestPhonePermissionIfNeeded()
                binding.webView.loadUrl(deepLinkUrl)
            } else {
                binding.webView.loadUrl("$BASE_URL/")
            }
        }
    }

    private fun startWatchdogService() {
        val intent = Intent(this, WatchdogService::class.java)
        startForegroundService(intent)
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("権限の許可が必要です")
                .setMessage("アプリがクラッシュした際に自動復帰するため、「他のアプリの上に重ねて表示」の権限を許可してください。")
                .setPositiveButton("設定を開く") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
                .setNegativeButton("後で", null)
                .show()
        }
    }

    private fun requestCameraPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
                )
            }
        }
    }

    private fun startHeartbeat() {
        lifecycleScope.launch {
            while (isActive) {
                WatchdogService.sendHeartbeat()
                delay(30_000L)
            }
        }
    }

    private fun startBridgeHealthCheck() {
        lifecycleScope.launch {
            delay(60_000L) // 起動安定化待ち
            while (isActive) {
                delay(30_000L)
                checkAndRestartBridges()
            }
        }
    }

    private suspend fun checkAndRestartBridges() {
        data class BridgeInfo(val name: String, val port: Int, val restart: () -> Unit)
        val bridges = listOf(
            BridgeInfo("NFC", 9876) { restartNfcBridge() },
            BridgeInfo("BLE", 9877) { restartBleBridge() },
            BridgeInfo("FC-1200", 9878) { restartFc1200Bridge() },
            BridgeInfo("ScreenCapture", 8783) { restartScreenCaptureBridge() },
        )
        for (bridge in bridges) {
            val alive = withContext(Dispatchers.IO) {
                try {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress("127.0.0.1", bridge.port), 3000)
                    }
                    true
                } catch (_: Exception) { false }
            }
            if (!alive) {
                Log.w(TAG, "${bridge.name} bridge dead, restarting")
                bridge.restart()
                binding.webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('bridge-restarted', {detail:{bridge:'${bridge.name.lowercase()}'}}))",
                    null
                )
            }
        }
    }

    private fun restartNfcBridge() {
        try { nfcBridgeServer?.stop(1000) } catch (_: Exception) {}
        startNfcBridgeServer()
    }

    private fun restartBleBridge() {
        val oldOnCommand = bleBridgeServer?.onCommand
        try { bleBridgeServer?.stop(1000) } catch (_: Exception) {}
        bleBridgeServer = BleBridgeServer().apply {
            isReuseAddr = true
            connectionLostTimeout = 5
            onCommand = oldOnCommand
            startSafe(TAG, "BLE")
        }
    }

    private fun restartFc1200Bridge() {
        val oldOnCommand = fc1200BridgeServer?.onCommand
        try { fc1200BridgeServer?.stop(1000) } catch (_: Exception) {}
        fc1200BridgeServer = Fc1200BridgeServer().apply {
            isReuseAddr = true
            connectionLostTimeout = 5
            onCommand = oldOnCommand
            startSafe(TAG, "FC-1200")
        }
    }

    private fun restartScreenCaptureBridge() {
        val oldOnCommand = screenCaptureBridgeServer?.onCommand
        try { screenCaptureBridgeServer?.stop(1000) } catch (_: Exception) {}
        screenCaptureBridgeServer = ScreenCaptureBridgeServer().apply {
            isReuseAddr = true
            connectionLostTimeout = 5
            onCommand = oldOnCommand
            startSafe(TAG, "ScreenCapture")
        }
        ScreenCaptureService.bridgeServer = screenCaptureBridgeServer
    }

    private fun setupNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Log.w(TAG, "NFC not available on this device")
            return
        }
        // config.xml=false でガイド常時表示、showNfcGuide で3秒表示→forceDisable で消す
    }

    private fun setNfcReadingPositionGuide(visible: Boolean) {
        val nfc = nfcAdapter ?: return
        try {
            val extra = jp.kyocera.nfc_extras.NfcAdapterExtras()
            Log.e(TAG, "NFC guide: setting visible=$visible (forceDisable=${!visible})")
            extra.forceDisableNfcReadingPositionGuide(nfc, !visible)
            Log.e(TAG, "NFC guide: set successfully")
        } catch (e: Throwable) {
            // KYOCERA 以外の端末では IncompatibleClassChangeError 等が発生するため無視
            Log.w(TAG, "NFC reading position guide not supported", e)
        }
    }

    private fun startNfcBridgeServer() {
        nfcBridgeServer = NfcBridgeServer().apply {
            isReuseAddr = true
            connectionLostTimeout = 5
            startSafe(TAG, "NFC")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val controller = WindowInsetsControllerCompat(window, binding.root)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        enableNfcForegroundDispatch()
        WatchdogService.sendHeartbeat()
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

        // USB デバイス接続時に再スキャン
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
            Log.i(TAG, "USB device attached, scanning for serial devices")
            usbSerialManager?.scanAndConnect()
            return
        }

        // App Link で既存アクティビティに戻ってきた場合
        val deepLinkUrl = intent?.data?.toString()
        if (deepLinkUrl != null && deepLinkUrl.contains("/device-claim")) {
            requestPhonePermissionIfNeeded()
            binding.webView.loadUrl(deepLinkUrl)
            return
        }

        if (intent.action == ACTION_NAVIGATE_TENKO) {
            val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
            Log.i(TAG, "Navigating to remote tenko: roomId=$roomId")
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
        val prefs = getSharedPreferences("device_settings", MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null)

        if (deviceId.isNullOrEmpty()) {
            // デバイス未登録 → 通常遷移
            binding.webView.loadUrl("$BASE_URL/")
            return
        }

        // デバイス設定を取得して管理者権限チェック
        lifecycleScope.launch {
            val hasManagerRole = checkDeviceManagerRole(deviceId)
            val url = if (hasManagerRole) {
                if (roomId != null) "$BASE_URL/?mode=incoming_call&room=$roomId"
                else "$BASE_URL/?mode=incoming_call"
            } else {
                "$BASE_URL/"
            }
            runOnUiThread { binding.webView.loadUrl(url) }
        }
    }

    private suspend fun checkDeviceManagerRole(deviceId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = java.net.URL("$API_URL/api/devices/settings/$deviceId")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                try {
                    if (conn.responseCode != 200) return@withContext false
                    val json = conn.inputStream.bufferedReader().readText()
                    val obj = org.json.JSONObject(json)
                    val roles = obj.optJSONArray("last_login_employee_role")
                    if (roles != null) {
                        for (i in 0 until roles.length()) {
                            val role = roles.getString(i)
                            if (role == "manager" || role == "admin") return@withContext true
                        }
                    }
                    false
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "checkDeviceManagerRole failed", e)
                false
            }
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

            Log.i(TAG, "NFC card read: type=${cardData.cardType}, id=${cardData.cardId}")

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
        Log.i(TAG, "Screen capture stopped")
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

    private fun autoGrantPermissionsIfDeviceOwner(): Boolean {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(packageName)) return false

        val componentName = ComponentName(this, com.example.alcoholchecker.admin.AppDeviceAdminReceiver::class.java)
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
        } else {
            permissions.add(Manifest.permission.READ_PHONE_STATE)
        }

        for (perm in permissions) {
            val granted = dpm.setPermissionGrantState(
                componentName, packageName, perm,
                android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
            Log.w(TAG, "Auto-grant $perm: $granted")
        }
        return true
    }

    private fun isDeviceOwnerNeedingRegistration(): Boolean {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(packageName)) return false
        val prefs = getSharedPreferences("device_settings", MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null)
        return deviceId.isNullOrEmpty()
    }

    private fun autoRegisterDeviceOwner() {
        val prefs = getSharedPreferences("device_settings", MODE_PRIVATE)

        // プロビジョニング extras から registration_code を取得
        val registrationCode = prefs.getString("registration_code", null)
        if (registrationCode.isNullOrEmpty()) {
            Log.w(TAG, "Device Owner but no registration_code — loading WebView as fallback")
            binding.webView.loadUrl("$BASE_URL/")
            fetchDeviceSettingsAndAutoStart()
            return
        }

        val deviceName = prefs.getString("device_name", null) ?: android.os.Build.MODEL

        // 登録オーバーレイ表示
        binding.registrationOverlay.visibility = android.view.View.VISIBLE
        binding.registrationStatusText.text = "デバイス登録中..."

        Log.w(TAG, "Device Owner auto-registration starting...")

        lifecycleScope.launch {
            var attempt = 0
            val maxAttempts = 10
            var delayMs = 2000L

            while (attempt < maxAttempts) {
                attempt++
                Log.w(TAG, "Device Owner auto-registration attempt $attempt/$maxAttempts")
                runOnUiThread {
                    binding.registrationStatusText.text = "デバイス登録中... ($attempt/$maxAttempts)"
                }

                try {
                    val result = withContext(Dispatchers.IO) {
                        val url = java.net.URL("$API_URL/api/devices/register/claim")
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 10_000

                        val body = org.json.JSONObject().apply {
                            put("registration_code", registrationCode)
                            put("device_name", deviceName)
                        }
                        conn.outputStream.use { it.write(body.toString().toByteArray()) }

                        try {
                            if (conn.responseCode != 200) {
                                val errorBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                                Log.w(TAG, "Auto-register attempt $attempt failed: HTTP ${conn.responseCode} $errorBody")
                                return@withContext null
                            }
                            org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                        } finally {
                            conn.disconnect()
                        }
                    }

                    if (result != null) {
                        val deviceId = result.optString("device_id", "")
                        val tenantId = result.optString("tenant_id", "")
                        if (deviceId.isNotEmpty()) {
                            // 登録成功
                            prefs.edit()
                                .putString("device_id", deviceId)
                                .remove("registration_code")
                                .apply()

                            Log.w(TAG, "Device Owner auto-registered: device_id=$deviceId, tenant_id=$tenantId")

                            runOnUiThread {
                                binding.registrationOverlay.visibility = android.view.View.GONE
                                binding.webView.loadUrl("$BASE_URL/")
                                binding.webView.evaluateJavascript(
                                    "window.__deviceOwnerActivated && window.__deviceOwnerActivated('$tenantId','$deviceId')",
                                    null
                                )
                                fetchDeviceSettingsAndAutoStart()
                            }
                            return@launch // 成功 — ループ終了
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Auto-registration attempt $attempt error: ${e.message}")
                }

                // 指数バックオフ: 2s, 4s, 8s, 16s, 30s cap
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }

            // 全リトライ失敗
            Log.e(TAG, "Device Owner auto-registration failed after $maxAttempts attempts")
            runOnUiThread {
                binding.registrationStatusText.text = "デバイス登録に失敗しました\nアプリを再起動してください"
                // 5秒後にフォールバックで WebView 読み込み
                binding.root.postDelayed({
                    binding.registrationOverlay.visibility = android.view.View.GONE
                    binding.webView.loadUrl("$BASE_URL/")
                }, 5000)
            }
        }
    }

    private fun fetchDeviceSettingsAndAutoStart() {
        val prefs = getSharedPreferences("device_settings", MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null)
        if (deviceId.isNullOrEmpty()) {
            Log.w(TAG, "No device_id — skipping auto-start")
            return
        }

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    val url = java.net.URL("$API_URL/api/devices/settings/$deviceId")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    try {
                        if (conn.responseCode != 200) {
                            Log.w(TAG, "Device settings API returned ${conn.responseCode}")
                            return@withContext null
                        }
                        conn.inputStream.bufferedReader().readText()
                    } finally {
                        conn.disconnect()
                    }
                } ?: return@launch

                val json = org.json.JSONObject(response)
                val callEnabled = json.optBoolean("call_enabled", false)
                val status = json.optString("status", "")

                // キャッシュ保存 (オフラインフォールバック用)
                prefs.edit()
                    .putBoolean("call_enabled", callEnabled)
                    .apply()

                // スケジュールを SharedPreferences に保存 (enabled を call_enabled に同期)
                val callSchedule = json.optJSONObject("call_schedule") ?: org.json.JSONObject()
                callSchedule.put("enabled", callEnabled)
                getSharedPreferences("call_settings", MODE_PRIVATE)
                    .edit()
                    .putString("schedule", callSchedule.toString())
                    .apply()

                if (status == "active") {
                    // 常時接続 (着信ON/OFFはサーバー側 shouldNotify() で制御、テスト着信は常に通る)
                    runOnUiThread { startRoomWatcher() }
                    Log.i(TAG, "Auto-started RoomWatcher (call_enabled=$callEnabled, filtering is server-side)")
                    // FCM トークン登録
                    registerFcmTokenIfNeeded(deviceId)
                    // バージョン報告
                    reportVersionToBackend(deviceId)
                } else {
                    Log.i(TAG, "status=$status — not starting RoomWatcher")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch device settings: ${e.message}")
                // オフラインフォールバック: 常に接続
                runOnUiThread { startRoomWatcher() }
                Log.i(TAG, "Auto-started RoomWatcher from fallback")
            }
        }
    }

    private fun registerFcmTokenIfNeeded(deviceId: String) {
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                val prefs = getSharedPreferences("device_settings", MODE_PRIVATE)
                val registeredToken = prefs.getString("fcm_token_registered", null)
                if (token != registeredToken) {
                    Log.w(TAG, "Registering FCM token (token changed or not yet registered)")
                    com.example.alcoholchecker.fcm.MyFirebaseMessagingService.TokenRegistrar
                        .register(this, deviceId, token)
                } else {
                    Log.w(TAG, "FCM token already registered — skip")
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to get FCM token: ${e.message}")
            }
    }

    private fun reportVersionToBackend(deviceId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val info = packageManager.getPackageInfo(packageName, 0)
                val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    info.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode
                }
                val versionName = info.versionName ?: "unknown"

                val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                val isDeviceOwner = dpm.isDeviceOwnerApp(packageName)
                val isDevDevice = getSharedPreferences("device_settings", MODE_PRIVATE)
                    .getBoolean("is_dev_device", false)

                val url = java.net.URL("$API_URL/api/devices/report-version")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "PUT"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000

                val body = """{"device_id":"$deviceId","version_code":$versionCode,"version_name":"$versionName","is_device_owner":$isDeviceOwner,"is_dev_device":$isDevDevice}"""
                conn.outputStream.use { it.write(body.toByteArray()) }

                val code = conn.responseCode
                Log.i(TAG, "Version reported: HTTP $code (v$versionName/$versionCode, owner=$isDeviceOwner, dev=$isDevDevice)")
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to report version: ${e.message}")
            }
        }
    }

    private fun startRoomWatcher() {
        if (roomWatcher != null) return
        roomWatcher = RoomWatcher(this, SIGNALING_URL).apply {
            onNewRoom = { roomId ->
                Log.i(TAG, "New tenko room: $roomId → showing incoming call screen")
                val intent = Intent(this@WebViewActivity, IncomingCallActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, "ドライバー点呼要求")
                    putExtra(IncomingCallActivity.EXTRA_ROOM_ID, roomId)
                }
                startActivity(intent)
            }
            onRoomAnswered = { roomId ->
                Log.i(TAG, "Room answered: $roomId → dismissing incoming call if showing")
                IncomingCallActivity.dismissForRoom(roomId)
            }
            onConnectionStateChanged = { connected ->
                runOnUiThread {
                    binding.webView.evaluateJavascript(
                        "window.dispatchEvent(new CustomEvent('ws-connection-changed', { detail: { connected: $connected } }))",
                        null
                    )
                }
            }
            start()
        }
        Log.i(TAG, "RoomWatcher started")
    }

    private fun stopRoomWatcher() {
        roomWatcher?.stop()
        roomWatcher = null
        Log.i(TAG, "RoomWatcher stopped")
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun setCallEnabled(enabled: Boolean) {
            Log.i(TAG, "setCallEnabled: $enabled")
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

        @JavascriptInterface
        fun isCallConnected(): Boolean {
            return roomWatcher?.isConnected == true
        }

        @SuppressLint("HardwareIds")
        @JavascriptInterface
        fun getAndroidId(): String {
            return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        }

        @JavascriptInterface
        fun setDeviceId(deviceId: String) {
            Log.i(TAG, "setDeviceId: $deviceId")
            getSharedPreferences("device_settings", MODE_PRIVATE)
                .edit()
                .putString("device_id", deviceId)
                .apply()
            // デバイス登録直後に着信設定を取得してRoomWatcherを起動
            runOnUiThread { fetchDeviceSettingsAndAutoStart() }
        }

        @JavascriptInterface
        fun getDeviceId(): String {
            return getSharedPreferences("device_settings", MODE_PRIVATE)
                .getString("device_id", "") ?: ""
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
            Log.i(TAG, "setMicMuted: $muted")
            val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            audioManager.isMicrophoneMute = muted
        }

        @JavascriptInterface
        fun requestScreenCapture() {
            Log.i(TAG, "requestScreenCapture")
            runOnUiThread {
                this@WebViewActivity.requestScreenCapture()
            }
        }

        @JavascriptInterface
        fun stopScreenCapture() {
            Log.i(TAG, "stopScreenCapture")
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

        @JavascriptInterface
        fun setCallSchedule(json: String) {
            Log.i(TAG, "setCallSchedule: $json")
            getSharedPreferences("call_settings", MODE_PRIVATE)
                .edit()
                .putString("schedule", json)
                .apply()
            // RoomWatcher が接続中ならスケジュールを即送信
            roomWatcher?.sendSchedule()
        }

        @JavascriptInterface
        fun getCallSchedule(): String {
            return getSharedPreferences("call_settings", MODE_PRIVATE)
                .getString("schedule", "") ?: ""
        }

        @JavascriptInterface
        fun getDeviceModel(): String {
            return android.os.Build.MODEL
        }

        @JavascriptInterface
        fun getAppVersion(): String {
            val info = packageManager.getPackageInfo(packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
            return """{"versionCode":$code,"versionName":"${info.versionName}"}"""
        }

        @JavascriptInterface
        fun isDeviceOwner(): Boolean {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            return dpm.isDeviceOwnerApp(packageName)
        }

        @JavascriptInterface
        fun getProvisioningInfo(): String {
            val prefs = getSharedPreferences("device_settings", MODE_PRIVATE)
            val deviceId = prefs.getString("device_id", null) ?: ""
            val isOwner = (getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager)
                .isDeviceOwnerApp(packageName)
            return """{"device_id":"$deviceId","is_device_owner":$isOwner}"""
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Log.i(TAG, "Biometric auth succeeded")
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

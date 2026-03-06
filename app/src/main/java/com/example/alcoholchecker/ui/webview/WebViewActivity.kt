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
import com.example.alcoholchecker.databinding.ActivityWebviewBinding
import com.example.alcoholchecker.nfc.CardType
import com.example.alcoholchecker.nfc.NfcBridgeServer
import com.example.alcoholchecker.nfc.NfcReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebviewBinding

    private var nfcAdapter: NfcAdapter? = null
    private val nfcReader = NfcReader()
    private var nfcBridgeServer: NfcBridgeServer? = null

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        fileChooserCallback?.onReceiveValue(uris.toTypedArray())
        fileChooserCallback = null
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
        setupBackNavigation()
        setupNfc()
        startNfcBridgeServer()

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
            start()
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
        try {
            nfcBridgeServer?.stop(1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping NFC bridge server", e)
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
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith(BASE_URL)) {
                    return false
                }
                return false
            }
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

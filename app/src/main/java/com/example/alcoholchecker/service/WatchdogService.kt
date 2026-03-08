package com.example.alcoholchecker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import com.example.alcoholchecker.call.IncomingCallActivity
import com.example.alcoholchecker.call.RoomWatcher
import com.example.alcoholchecker.ui.webview.WebViewActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

class WatchdogService : Service() {

    companion object {
        private const val TAG = "WatchdogService"
        private const val CHANNEL_ID = "watchdog"
        private const val NOTIFICATION_ID = 2002
        private const val HEARTBEAT_TIMEOUT_MS = 60_000L
        private const val CHECK_INTERVAL_MS = 15_000L
        private const val SIGNALING_URL = "https://alc-signaling.m-tama-ramu.workers.dev"
        private const val API_URL = "https://alc-app.m-tama-ramu.workers.dev"

        private val lastHeartbeat = AtomicLong(0L)

        fun sendHeartbeat() {
            lastHeartbeat.set(System.currentTimeMillis())
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private var watchJob: Job? = null
    private var roomWatcher: RoomWatcher? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sendHeartbeat()

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        if (watchJob == null) {
            watchJob = scope.launch {
                while (true) {
                    delay(CHECK_INTERVAL_MS)
                    val elapsed = System.currentTimeMillis() - lastHeartbeat.get()
                    if (elapsed > HEARTBEAT_TIMEOUT_MS) {
                        Log.e(TAG, "Heartbeat timeout (${elapsed}ms) - killing process")
                        exitProcess(1)
                    }
                }
            }
        }

        // START_STICKY再起動時 (intent == null) → アプリ復帰 + RoomWatcher着信待機
        if (intent == null) {
            Log.w(TAG, "Restarted by START_STICKY")
            relaunchActivity()
            startRoomWatcher()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        watchJob?.cancel()
        watchJob = null
        roomWatcher?.stop()
        roomWatcher = null
        super.onDestroy()
    }

    private fun relaunchActivity() {
        if (Settings.canDrawOverlays(this)) {
            Log.d(TAG, "SYSTEM_ALERT_WINDOW granted - launching WebViewActivity directly")
            val launchIntent = Intent(this, WebViewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(launchIntent)
        } else {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted - waiting for notification tap or incoming call")
        }
    }

    private fun startRoomWatcher() {
        if (roomWatcher != null) return
        val prefs = getSharedPreferences("device_settings", MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null)
        if (deviceId.isNullOrEmpty()) {
            Log.d(TAG, "No device_id — skipping RoomWatcher")
            return
        }

        // サーバーから設定を取得して判断
        scope.launch {
            try {
                val response = java.net.URL("$API_URL/api/devices/settings/$deviceId")
                    .openConnection().let { conn ->
                        conn as java.net.HttpURLConnection
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        try {
                            if (conn.responseCode != 200) return@launch
                            conn.inputStream.bufferedReader().readText()
                        } finally {
                            conn.disconnect()
                        }
                    }

                val json = org.json.JSONObject(response)
                val callEnabled = json.optBoolean("call_enabled", false)
                val status = json.optString("status", "")

                prefs.edit().putBoolean("call_enabled", callEnabled).apply()

                // スケジュールを SharedPreferences に保存 (enabled を call_enabled に同期)
                val callSchedule = json.optJSONObject("call_schedule") ?: org.json.JSONObject()
                callSchedule.put("enabled", callEnabled)
                getSharedPreferences("call_settings", MODE_PRIVATE)
                    .edit().putString("schedule", callSchedule.toString()).apply()

                if (status != "active") {
                    Log.d(TAG, "status=$status — not starting RoomWatcher")
                    return@launch
                }

                // 常時接続 (着信ON/OFFはサーバー側 shouldNotify() で制御、テスト着信は常に通る)
                startRoomWatcherInternal()
                Log.d(TAG, "Started RoomWatcher (call_enabled=$callEnabled, filtering is server-side)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch settings, fallback: ${e.message}")
                startRoomWatcherInternal()
            }
        }
    }

    private fun startRoomWatcherInternal() {
        if (roomWatcher != null) return
        roomWatcher = RoomWatcher(this, SIGNALING_URL).apply {
            onNewRoom = { roomId ->
                Log.d(TAG, "Incoming tenko call: $roomId → launching IncomingCallActivity")
                val callIntent = Intent(this@WatchdogService, IncomingCallActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, "ドライバー点呼要求")
                    putExtra(IncomingCallActivity.EXTRA_ROOM_ID, roomId)
                }
                startActivity(callIntent)
            }
            start()
        }
        Log.d(TAG, "RoomWatcher started from WatchdogService")
    }

    /** WebViewActivityが起動したらService側のRoomWatcherは不要になる */
    fun stopRoomWatcher() {
        roomWatcher?.stop()
        roomWatcher = null
        Log.d(TAG, "RoomWatcher stopped (WebViewActivity took over)")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "アプリ監視",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "アプリの稼働監視"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, WebViewActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("アルコールチェッカー稼働中")
            .setContentText("タップして開く")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}

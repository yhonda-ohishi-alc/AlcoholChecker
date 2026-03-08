package com.example.alcoholchecker.fcm

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.alcoholchecker.call.IncomingCallActivity
import com.example.alcoholchecker.call.RoomWatcher
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCMService"
        private const val API_URL = "https://alc-app.m-tama-ramu.workers.dev"
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "New FCM token: ${token.take(20)}...")
        val prefs = getSharedPreferences("device_settings", MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()

        val deviceId = prefs.getString("device_id", null)
        if (!deviceId.isNullOrEmpty()) {
            registerTokenWithBackend(deviceId, token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        Log.w(TAG, "FCM received: type=${data["type"]}")

        if (data["type"] == "test") {
            showTestNotification()
            return
        }

        if (data["type"] == "test_dismiss") {
            Log.w(TAG, "FCM test_dismiss received — dismissing test call")
            IncomingCallActivity.dismissForRoom("fcm-test")
            return
        }

        if (data["type"] != "incoming_call") return

        val roomIdsJson = data["room_ids"] ?: return
        val timestamp = data["timestamp"]?.toLongOrNull() ?: 0

        // 鮮度チェック: 60秒以上前のメッセージはスキップ
        if (System.currentTimeMillis() - timestamp > 60_000) {
            Log.d(TAG, "Skipping stale FCM notification (age=${System.currentTimeMillis() - timestamp}ms)")
            return
        }

        // room_ids を解析
        val roomIds = try {
            val arr = JSONArray(roomIdsJson)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse room_ids", e)
            return
        }

        if (roomIds.isEmpty()) return

        // 重複排除: WebSocket で既に通知済みの room はスキップ
        val watcher = RoomWatcher.activeInstance
        val newRoomIds = if (watcher?.isConnected == true) {
            roomIds.filter { !watcher.hasNotifiedRoom(it) }
        } else {
            roomIds
        }

        if (newRoomIds.isEmpty()) {
            Log.d(TAG, "All rooms already notified via WebSocket — skipping FCM")
            return
        }

        // 最初の新規 room で着信画面を表示
        val roomId = newRoomIds.first()
        Log.d(TAG, "Showing incoming call for room: $roomId (via FCM)")

        val intent = Intent(this, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, "ドライバー点呼要求")
            putExtra(IncomingCallActivity.EXTRA_ROOM_ID, roomId)
        }
        startActivity(intent)
    }

    private fun showTestNotification() {
        Log.w(TAG, "Showing test notification as incoming call")
        val intent = Intent(this, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, "FCMテスト通知")
            putExtra(IncomingCallActivity.EXTRA_ROOM_ID, "fcm-test")
            putExtra(IncomingCallActivity.EXTRA_IS_TEST, true)
        }
        startActivity(intent)
    }

    private fun registerTokenWithBackend(deviceId: String, token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = java.net.URL("$API_URL/api/devices/register-fcm-token")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "PUT"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doOutput = true

                val body = """{"device_id":"$deviceId","fcm_token":"$token"}"""
                conn.outputStream.use { it.write(body.toByteArray()) }

                val code = conn.responseCode
                Log.d(TAG, "FCM token registration: HTTP $code")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register FCM token", e)
            }
        }
    }

    /** Register FCM token from outside the service (e.g. on app startup) */
    object TokenRegistrar {
        fun register(context: Context, deviceId: String, token: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val url = java.net.URL("$API_URL/api/devices/register-fcm-token")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "PUT"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.doOutput = true

                    val body = """{"device_id":"$deviceId","fcm_token":"$token"}"""
                    conn.outputStream.use { it.write(body.toByteArray()) }

                    val code = conn.responseCode
                    Log.w("FCMTokenRegistrar", "FCM token registration: HTTP $code")
                    conn.disconnect()
                    if (code in 200..299) {
                        context.getSharedPreferences("device_settings", MODE_PRIVATE)
                            .edit()
                            .putString("fcm_token", token)
                            .putString("fcm_token_registered", token)
                            .apply()
                        Log.w("FCMTokenRegistrar", "FCM token saved to backend successfully")
                    } else {
                        Log.w("FCMTokenRegistrar", "FCM token registration failed: HTTP $code")
                    }
                } catch (e: Exception) {
                    Log.e("FCMTokenRegistrar", "Failed to register FCM token", e)
                }
            }
        }
    }
}

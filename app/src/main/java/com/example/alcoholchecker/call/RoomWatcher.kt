package com.example.alcoholchecker.call

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

class RoomWatcher(
    private val context: Context,
    private val signalingUrl: String
) {
    companion object {
        private const val TAG = "RoomWatcher"
        private const val RECONNECT_DELAY_MS = 5000L

        /** Active instance for cross-component access (e.g. IncomingCallActivity) */
        var activeInstance: RoomWatcher? = null
            private set
    }

    private var wsClient: WebSocketClient? = null
    private var knownRooms = setOf<String>()
    private val notifiedRooms = mutableSetOf<String>()
    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())

    var onNewRoom: ((roomId: String) -> Unit)? = null
    var onRoomAnswered: ((roomId: String) -> Unit)? = null
    var onConnectionStateChanged: ((connected: Boolean) -> Unit)? = null
    var isConnected = false
        private set

    fun start() {
        isRunning = true
        activeInstance = this
        connect()
    }

    fun stop() {
        isRunning = false
        if (activeInstance === this) activeInstance = null
        handler.removeCallbacksAndMessages(null)
        wsClient?.close()
        wsClient = null
    }

    private fun connect() {
        if (!isRunning) return

        val prefs = context.getSharedPreferences("device_settings", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null)
        if (deviceId.isNullOrEmpty()) {
            Log.w(TAG, "No device_id in SharedPreferences, cannot connect")
            scheduleReconnect()
            return
        }
        val wsUrl = signalingUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + "/watch-rooms?device_id=$deviceId"

        Log.d(TAG, "Connecting to $wsUrl")

        wsClient = object : WebSocketClient(URI(wsUrl)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d(TAG, "Connected to watch-rooms")
                isConnected = true
                onConnectionStateChanged?.invoke(true)
                sendSchedule()
            }

            override fun onMessage(message: String?) {
                message ?: return
                Log.d(TAG, "Received: $message")
                handleMessage(message)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "Disconnected: code=$code reason=$reason")
                isConnected = false
                onConnectionStateChanged?.invoke(false)
                scheduleReconnect()
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "WebSocket error", ex)
            }
        }.also { it.connect() }
    }

    private fun handleMessage(message: String) {
        try {
            val json = JSONObject(message)
            when (json.optString("type")) {
                "rooms_updated" -> {
                    val roomsArray = json.getJSONArray("rooms")
                    val newRooms = mutableSetOf<String>()
                    for (i in 0 until roomsArray.length()) {
                        newRooms.add(roomsArray.getString(i))
                    }

                    val addedRooms = newRooms - knownRooms
                    val removedRooms = knownRooms - newRooms
                    knownRooms = newRooms

                    // Track notified rooms for FCM dedup
                    notifiedRooms.removeAll(removedRooms)
                    for (roomId in addedRooms) {
                        notifiedRooms.add(roomId)
                        Log.d(TAG, "New room detected: $roomId")
                        onNewRoom?.invoke(roomId)
                    }
                }
                "room_answered" -> {
                    val roomId = json.optString("roomId", "")
                    if (roomId.isNotEmpty()) {
                        Log.d(TAG, "Room answered by another device: $roomId")
                        onRoomAnswered?.invoke(roomId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message", e)
        }
    }

    /** Check if a room was already notified via WebSocket (for FCM dedup) */
    fun hasNotifiedRoom(roomId: String): Boolean = roomId in notifiedRooms

    /** Notify server that this device answered a call, so other devices can dismiss */
    fun notifyCallAnswered(roomId: String) {
        try {
            val msg = JSONObject().apply {
                put("type", "call_answered")
                put("roomId", roomId)
            }
            wsClient?.send(msg.toString())
            Log.d(TAG, "Sent call_answered: $roomId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send call_answered", e)
        }
    }

    fun sendSchedule() {
        try {
            val prefs = context.getSharedPreferences("call_settings", Context.MODE_PRIVATE)
            val scheduleJson = prefs.getString("schedule", null) ?: return
            val msg = JSONObject().apply {
                put("type", "set_schedule")
                put("schedule", JSONObject(scheduleJson))
            }
            wsClient?.send(msg.toString())
            Log.d(TAG, "Sent schedule: $msg")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send schedule", e)
        }
    }

    private fun scheduleReconnect() {
        if (!isRunning) return
        Log.d(TAG, "Reconnecting in ${RECONNECT_DELAY_MS}ms...")
        handler.postDelayed({ connect() }, RECONNECT_DELAY_MS)
    }
}

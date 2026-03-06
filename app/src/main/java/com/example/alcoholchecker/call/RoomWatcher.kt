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
    }

    private var wsClient: WebSocketClient? = null
    private var knownRooms = setOf<String>()
    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())

    var onNewRoom: ((roomId: String) -> Unit)? = null

    fun start() {
        isRunning = true
        connect()
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        wsClient?.close()
        wsClient = null
    }

    private fun connect() {
        if (!isRunning) return

        val wsUrl = signalingUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + "/watch-rooms"

        Log.d(TAG, "Connecting to $wsUrl")

        wsClient = object : WebSocketClient(URI(wsUrl)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d(TAG, "Connected to watch-rooms")
            }

            override fun onMessage(message: String?) {
                message ?: return
                Log.d(TAG, "Received: $message")
                handleMessage(message)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "Disconnected: code=$code reason=$reason")
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
            if (json.optString("type") != "rooms_updated") return

            val roomsArray = json.getJSONArray("rooms")
            val newRooms = mutableSetOf<String>()
            for (i in 0 until roomsArray.length()) {
                newRooms.add(roomsArray.getString(i))
            }

            val addedRooms = newRooms - knownRooms
            knownRooms = newRooms

            for (roomId in addedRooms) {
                Log.d(TAG, "New room detected: $roomId")
                onNewRoom?.invoke(roomId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message", e)
        }
    }

    private fun scheduleReconnect() {
        if (!isRunning) return
        Log.d(TAG, "Reconnecting in ${RECONNECT_DELAY_MS}ms...")
        handler.postDelayed({ connect() }, RECONNECT_DELAY_MS)
    }
}

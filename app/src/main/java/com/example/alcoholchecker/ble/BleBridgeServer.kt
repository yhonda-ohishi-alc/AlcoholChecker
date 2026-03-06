package com.example.alcoholchecker.ble

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress

class BleBridgeServer(port: Int = 9877) : WebSocketServer(InetSocketAddress("127.0.0.1", port)) {

    companion object {
        private const val TAG = "BleBridgeServer"
    }

    var onCommand: ((String) -> Unit)? = null

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        Log.d(TAG, "Client connected: ${conn?.remoteSocketAddress}")
        conn?.send(JSONObject().apply {
            put("type", "ready")
            put("device", "Android BLE Gateway")
            put("version", "android-1.0.0")
        }.toString())
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        Log.d(TAG, "Client disconnected: $reason")
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        message ?: return
        Log.d(TAG, "Received command: $message")
        // Handle commands from web app (e.g., {"command":"reset"}, {"command":"scan"})
        try {
            val json = JSONObject(message)
            val command = json.optString("command", "")
            if (command.isNotEmpty()) {
                onCommand?.invoke(command)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Invalid command JSON", e)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.e(TAG, "WebSocket error", ex)
    }

    override fun onStart() {
        Log.d(TAG, "BLE Bridge WebSocket server started on port ${address.port}")
    }

    fun broadcastData(json: JSONObject) {
        broadcast(json.toString())
    }
}

package com.example.alcoholchecker.screencapture

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class ScreenCaptureBridgeServer(port: Int = 8783) : WebSocketServer(InetSocketAddress("127.0.0.1", port)) {

    companion object {
        private const val TAG = "ScreenCapBridge"
    }

    var onCommand: ((String) -> Unit)? = null

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        Log.d(TAG, "Client connected: ${conn?.remoteSocketAddress}")
        conn?.send(JSONObject().apply {
            put("type", "ready")
            put("device", "Android ScreenCapture")
        }.toString())
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        Log.d(TAG, "Client disconnected: $reason")
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        message ?: return
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
        Log.d(TAG, "ScreenCapture Bridge started on port ${address.port}")
    }

    fun broadcastFrame(jpegData: ByteArray) {
        val buf = ByteBuffer.wrap(jpegData)
        for (conn in connections) {
            try {
                conn.send(buf)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send frame", e)
            }
        }
    }

    fun broadcastEvent(type: String) {
        val json = JSONObject().apply { put("type", type) }
        broadcast(json.toString())
    }
}

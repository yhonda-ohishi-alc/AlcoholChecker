package com.example.alcoholchecker.serial

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress

class Fc1200BridgeServer(port: Int = 9878) : WebSocketServer(InetSocketAddress("127.0.0.1", port)) {

    companion object {
        private const val TAG = "Fc1200BridgeServer"
    }

    var onCommand: ((String) -> Unit)? = null

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        Log.d(TAG, "Client connected: ${conn?.remoteSocketAddress}")
        conn?.send(JSONObject().apply {
            put("type", "ready")
            put("device", "Android FC-1200 Serial Gateway")
            put("version", "android-1.0.0")
        }.toString())
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        Log.d(TAG, "Client disconnected: $reason")
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        message ?: return
        Log.d(TAG, "Received command: $message")
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
        Log.d(TAG, "FC-1200 Bridge WebSocket server started on port ${address.port}")
    }

    fun broadcastData(json: JSONObject) {
        broadcast(json.toString())
    }
}

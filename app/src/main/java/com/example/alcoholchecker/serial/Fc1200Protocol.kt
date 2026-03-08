package com.example.alcoholchecker.serial

import android.util.Log
import org.json.JSONObject

// ─── Line Parser (CRLF buffering for partial serial reads) ───

class LineParser {
    private val buffer = StringBuilder()

    fun feed(data: String): List<String> {
        buffer.append(data)

        if (buffer.length > MAX_BUFFER_SIZE) {
            buffer.clear()
            return emptyList()
        }

        val lines = mutableListOf<String>()
        while (true) {
            val idx = buffer.indexOf("\r\n")
            if (idx < 0) break
            val line = buffer.substring(0, idx)
            buffer.delete(0, idx + 2)
            if (line.isNotEmpty()) {
                lines.add(line)
            }
        }
        return lines
    }

    fun clear() {
        buffer.clear()
    }

    companion object {
        private const val MAX_BUFFER_SIZE = 1024
    }
}

// ─── Incoming Commands (FC-1200 → Android) ───

sealed class IncomingCommand {
    data class ConnectionRequest(val model: String, val variant: String) : IncomingCommand()
    data class UsageTime(val totalSeconds: Int, val elapsedDays: Int) : IncomingCommand()
    data object WarmingComplete : IncomingCommand()
    data object BlowWaiting : IncomingCommand()
    data object BlowTimeout : IncomingCommand()
    data object BlowingFinished : IncomingCommand()
    data class NormalResult(val alcoholValue: Int, val useCount: Int) : IncomingCommand()
    data class OverResult(val useCount: Int) : IncomingCommand()
    data class BlowError(val useCount: Int) : IncomingCommand()
    data class MemoryData(val id: String, val datetime: String, val alcoholValue: Int) : IncomingCommand()
    data object DateUpdateOk : IncomingCommand()
    data object DateUpdateNg : IncomingCommand()

    companion object {
        fun parse(line: String): IncomingCommand? {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return null

            // Simple commands
            when (trimmed) {
                "MSWM" -> return WarmingComplete
                "MSBL" -> return BlowWaiting
                "MSTO" -> return BlowTimeout
                "MSEN" -> return BlowingFinished
                "DTOK" -> return DateUpdateOk
                "DTNG" -> return DateUpdateNg
            }

            // RQCN without commas: "RQCNFC-1200B"
            if (trimmed.startsWith("RQCN") && !trimmed.startsWith("RQCN,")) {
                val rest = trimmed.removePrefix("RQCN")
                if (rest.length >= 2) {
                    val model = rest.substring(0, rest.length - 1)
                    val variant = rest.substring(rest.length - 1)
                    return ConnectionRequest(model, variant)
                }
            }

            // RSERBL without commas: "RSERBL00100"
            if (trimmed.startsWith("RSERBL") && !trimmed.contains(',')) {
                val rest = trimmed.removePrefix("RSERBL")
                if (rest.length == 5 && rest.all { it.isDigit() }) {
                    return BlowError(rest.toInt())
                }
            }

            // RSOV without commas: "RSOV00200"
            if (trimmed.startsWith("RSOV") && !trimmed.contains(',')) {
                val rest = trimmed.removePrefix("RSOV")
                if (rest.length == 5 && rest.all { it.isDigit() }) {
                    return OverResult(rest.toInt())
                }
            }

            // RS without commas: "RS02500150"
            if (trimmed.startsWith("RS") && !trimmed.startsWith("RSOV") &&
                !trimmed.startsWith("RSERBL") && !trimmed.contains(',')
            ) {
                val rest = trimmed.removePrefix("RS")
                if (rest.length == 8 && rest.all { it.isDigit() }) {
                    val alcoholValue = rest.substring(0, 3).toInt()
                    val useCount = rest.substring(3).toInt()
                    return NormalResult(alcoholValue, useCount)
                }
            }

            // UT without commas: "UT003600030"
            if (trimmed.startsWith("UT") && !trimmed.contains(',')) {
                val rest = trimmed.removePrefix("UT")
                if (rest.length == 9 && rest.all { it.isDigit() }) {
                    val totalSeconds = rest.substring(0, 6).toInt()
                    val elapsedDays = rest.substring(6).toInt()
                    return UsageTime(totalSeconds, elapsedDays)
                }
            }

            // Comma-separated commands
            val parts = trimmed.split(',')
            return when (parts[0]) {
                "RQCN" -> if (parts.size >= 3) ConnectionRequest(parts[1], parts[2]) else null
                "UT" -> if (parts.size >= 3) {
                    val ts = parts[1].toIntOrNull() ?: return null
                    val ed = parts[2].toIntOrNull() ?: return null
                    UsageTime(ts, ed)
                } else null
                "RS" -> if (parts.size >= 3) {
                    val av = parts[1].toIntOrNull() ?: return null
                    val uc = parts[2].toIntOrNull() ?: return null
                    NormalResult(av, uc)
                } else null
                "RSOV" -> if (parts.size >= 2) OverResult(parts[1].toIntOrNull() ?: return null) else null
                "RSERBL" -> if (parts.size >= 2) BlowError(parts[1].toIntOrNull() ?: return null) else null
                else -> {
                    // Memory data: "IIIIIIII,YYMMDDHHMMSS,RRR"
                    if (parts.size == 3 && parts[0].length == 8 && parts[1].length == 12) {
                        val av = parts[2].toIntOrNull() ?: return null
                        MemoryData(parts[0], parts[1], av)
                    } else null
                }
            }
        }
    }
}

// ─── Outgoing Commands (Android → FC-1200) ───

enum class OutgoingCommand(private val format: String) {
    ConnectionOk("CNOK"),
    ConnectionNg("CNNG"),
    ResultOk("RSOK"),
    ResultNg("RSNG"),
    DataReadRequest("RQDD"),
    DataReadComplete("DDOK"),
    LifetimeRequest("RQUT"),
    LifetimeOk("UTOK");

    fun toBytes(): ByteArray = "$format\r\n".toByteArray(Charsets.US_ASCII)

    companion object {
        fun dateUpdate(datetime: String): ByteArray = "DT,$datetime\r\n".toByteArray(Charsets.US_ASCII)
    }
}

// ─── Measurement State Machine ───

enum class MeasurementState(val key: String) {
    Idle("idle"),
    WaitingConnection("waiting_connection"),
    Connected("connected"),
    WarmingUp("warming_up"),
    BlowWaiting("blow_waiting"),
    Measuring("measuring"),
    ResultReceived("result_received");
}

class Fc1200Session {
    private val parser = LineParser()
    private var state = MeasurementState.Idle
    private var mode = SessionMode.Measurement
    private val responseQueue = ArrayDeque<ByteArray>()

    val currentState: MeasurementState get() = state

    var onEvent: ((JSONObject) -> Unit)? = null
    var onSendData: ((ByteArray) -> Unit)? = null

    fun feed(data: String) {
        val lines = parser.feed(data)
        for (line in lines) {
            Log.i(TAG, "RX: $line")
            val cmd = IncomingCommand.parse(line)
            if (cmd != null) {
                dispatchCommand(cmd)
                drainResponses()
            } else {
                emitEvent("error") { put("message", "Unknown command: $line") }
            }
        }
    }

    fun startMeasurement() {
        mode = SessionMode.Measurement
        transition(MeasurementState.WaitingConnection)
    }

    fun startMemoryRead() {
        mode = SessionMode.MemoryRead
        queueResponse(OutgoingCommand.DataReadRequest.toBytes())
        drainResponses()
    }

    fun updateDate(datetime: String) {
        mode = SessionMode.DateUpdate
        queueResponse(OutgoingCommand.dateUpdate(datetime))
        drainResponses()
    }

    fun checkSensorLifetime() {
        mode = SessionMode.SensorLifetime
        queueResponse(OutgoingCommand.LifetimeRequest.toBytes())
        drainResponses()
    }

    fun completeMemoryRead() {
        queueResponse(OutgoingCommand.DataReadComplete.toBytes())
        drainResponses()
    }

    fun reset() {
        parser.clear()
        responseQueue.clear()
        mode = SessionMode.Measurement
        val from = state
        state = MeasurementState.Idle
        if (from != MeasurementState.Idle) {
            emitStateChanged(from, MeasurementState.Idle)
        }
    }

    private fun dispatchCommand(cmd: IncomingCommand) {
        when (mode) {
            SessionMode.Measurement -> processMeasurement(cmd)
            SessionMode.MemoryRead -> processMemoryRead(cmd)
            SessionMode.DateUpdate -> processDateUpdate(cmd)
            SessionMode.SensorLifetime -> processSensorLifetime(cmd)
        }
    }

    private fun processMeasurement(cmd: IncomingCommand) {
        when {
            // RQCN in Idle → auto-transition
            state == MeasurementState.Idle && cmd is IncomingCommand.ConnectionRequest -> {
                emitStateChanged(MeasurementState.Idle, MeasurementState.WaitingConnection)
                state = MeasurementState.Connected
                emitEvent("connection_request") {
                    put("model", cmd.model)
                    put("variant", cmd.variant)
                }
                emitStateChanged(MeasurementState.WaitingConnection, MeasurementState.Connected)
                queueResponse(OutgoingCommand.ConnectionOk.toBytes())
            }

            // RQCN in WaitingConnection → Connected
            state == MeasurementState.WaitingConnection && cmd is IncomingCommand.ConnectionRequest -> {
                val from = state
                state = MeasurementState.Connected
                emitEvent("connection_request") {
                    put("model", cmd.model)
                    put("variant", cmd.variant)
                }
                emitStateChanged(from, state)
                queueResponse(OutgoingCommand.ConnectionOk.toBytes())
            }

            // UT in Connected → WarmingUp
            state == MeasurementState.Connected && cmd is IncomingCommand.UsageTime -> {
                val from = state
                state = MeasurementState.WarmingUp
                emitEvent("usage_time") {
                    put("total_seconds", cmd.totalSeconds)
                    put("elapsed_days", cmd.elapsedDays)
                }
                emitStateChanged(from, state)
            }

            // MSWM in WarmingUp → BlowWaiting
            state == MeasurementState.WarmingUp && cmd is IncomingCommand.WarmingComplete -> {
                val from = state
                state = MeasurementState.BlowWaiting
                emitEvent("warming_complete") {}
                emitStateChanged(from, state)
            }

            // MSBL in BlowWaiting → stay
            state == MeasurementState.BlowWaiting && cmd is IncomingCommand.BlowWaiting -> {
                emitEvent("blow_waiting") {}
            }

            // MSTO in BlowWaiting → Idle (timeout)
            state == MeasurementState.BlowWaiting && cmd is IncomingCommand.BlowTimeout -> {
                val from = state
                state = MeasurementState.Idle
                emitEvent("blow_timeout") {}
                emitStateChanged(from, state)
            }

            // MSEN in BlowWaiting → Measuring
            state == MeasurementState.BlowWaiting && cmd is IncomingCommand.BlowingFinished -> {
                val from = state
                state = MeasurementState.Measuring
                emitStateChanged(from, state)
            }

            // RS in Measuring → ResultReceived → Idle
            state == MeasurementState.Measuring && cmd is IncomingCommand.NormalResult -> {
                state = MeasurementState.Idle
                emitEvent("measurement_result") {
                    put("alcohol_value", cmd.alcoholValue / 100.0)
                    put("result_type", "normal")
                    put("use_count", cmd.useCount)
                }
                emitStateChanged(MeasurementState.Measuring, MeasurementState.ResultReceived)
                queueResponse(OutgoingCommand.ResultOk.toBytes())
            }

            // RSOV in Measuring
            state == MeasurementState.Measuring && cmd is IncomingCommand.OverResult -> {
                state = MeasurementState.Idle
                emitEvent("measurement_result") {
                    put("alcohol_value", 0.25)
                    put("result_type", "over")
                    put("use_count", cmd.useCount)
                }
                emitStateChanged(MeasurementState.Measuring, MeasurementState.ResultReceived)
                queueResponse(OutgoingCommand.ResultOk.toBytes())
            }

            // RSERBL in Measuring
            state == MeasurementState.Measuring && cmd is IncomingCommand.BlowError -> {
                state = MeasurementState.Idle
                emitEvent("measurement_result") {
                    put("alcohol_value", -1.0)
                    put("result_type", "error")
                    put("use_count", cmd.useCount)
                }
                emitStateChanged(MeasurementState.Measuring, MeasurementState.ResultReceived)
                queueResponse(OutgoingCommand.ResultOk.toBytes())
            }

            // RQCN in any other state → device restarted
            cmd is IncomingCommand.ConnectionRequest -> {
                val from = state
                state = MeasurementState.Connected
                responseQueue.clear()
                emitStateChanged(from, MeasurementState.Connected)
                emitEvent("connection_request") {
                    put("model", cmd.model)
                    put("variant", cmd.variant)
                }
                queueResponse(OutgoingCommand.ConnectionOk.toBytes())
            }

            else -> {
                emitEvent("error") {
                    put("message", "Unexpected command $cmd in state ${state.key}")
                }
            }
        }
    }

    private fun processMemoryRead(cmd: IncomingCommand) {
        when (cmd) {
            is IncomingCommand.MemoryData -> {
                emitEvent("memory_data") {
                    put("id", cmd.id)
                    put("datetime", cmd.datetime)
                    put("alcohol_value", cmd.alcoholValue / 100.0)
                }
            }
            else -> emitEvent("error") { put("message", "Unexpected in memory read: $cmd") }
        }
    }

    private fun processDateUpdate(cmd: IncomingCommand) {
        when (cmd) {
            is IncomingCommand.DateUpdateOk ->
                emitEvent("date_update_response") { put("success", true) }
            is IncomingCommand.DateUpdateNg ->
                emitEvent("date_update_response") { put("success", false) }
            else -> emitEvent("error") { put("message", "Unexpected in date update: $cmd") }
        }
    }

    private fun processSensorLifetime(cmd: IncomingCommand) {
        when (cmd) {
            is IncomingCommand.UsageTime -> {
                emitEvent("usage_time") {
                    put("total_seconds", cmd.totalSeconds)
                    put("elapsed_days", cmd.elapsedDays)
                }
                queueResponse(OutgoingCommand.LifetimeOk.toBytes())
            }
            else -> emitEvent("error") { put("message", "Unexpected in sensor lifetime: $cmd") }
        }
    }

    private fun transition(newState: MeasurementState) {
        val from = state
        state = newState
        emitStateChanged(from, newState)
    }

    private fun emitStateChanged(from: MeasurementState, to: MeasurementState) {
        emitEvent("state_changed") {
            put("from", from.key)
            put("to", to.key)
        }
    }

    private inline fun emitEvent(type: String, block: JSONObject.() -> Unit) {
        val json = JSONObject().apply {
            put("type", type)
            block()
        }
        onEvent?.invoke(json)
    }

    private fun queueResponse(data: ByteArray) {
        responseQueue.addLast(data)
    }

    private fun drainResponses() {
        while (responseQueue.isNotEmpty()) {
            val data = responseQueue.removeFirst()
            Log.i(TAG, "TX: ${String(data).trim()}")
            onSendData?.invoke(data)
        }
    }

    private enum class SessionMode {
        Measurement, MemoryRead, DateUpdate, SensorLifetime
    }

    companion object {
        private const val TAG = "Fc1200Session"
    }
}

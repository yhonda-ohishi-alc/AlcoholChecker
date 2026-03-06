package com.example.alcoholchecker.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.UUID
import kotlin.math.pow

@SuppressLint("MissingPermission")
class BleDeviceManager(private val context: Context) {

    companion object {
        private const val TAG = "BleDeviceManager"

        // BLE Standard Service UUIDs
        val HEALTH_THERMOMETER_SERVICE: UUID = UUID.fromString("00001809-0000-1000-8000-00805f9b34fb")
        val BLOOD_PRESSURE_SERVICE: UUID = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb")

        // BLE Standard Characteristic UUIDs
        val TEMPERATURE_MEASUREMENT: UUID = UUID.fromString("00002a1c-0000-1000-8000-00805f9b34fb")
        val BLOOD_PRESSURE_MEASUREMENT: UUID = UUID.fromString("00002a35-0000-1000-8000-00805f9b34fb")

        // Client Characteristic Configuration Descriptor
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // RSSI threshold
        private const val MIN_RSSI = -80

        // Device name patterns (Nipro devices)
        private val THERMOMETER_NAMES = listOf("NT-100", "Thermo")
        private val BLOOD_PRESSURE_NAMES = listOf("NBP-1", "BP", "Blood")
    }

    var onDataReceived: ((JSONObject) -> Unit)? = null

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var scanner: BluetoothLeScanner? = null
    private var thermometerGatt: BluetoothGatt? = null
    private var bloodPressureGatt: BluetoothGatt? = null
    private var isScanning = false

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName ?: ""
            val address = device.address
            val serviceUuidsPreview = result.scanRecord?.serviceUuids?.joinToString { it.uuid.toString().substring(4, 8) } ?: "none"

            if (name.isNotEmpty()) {
                Log.d(TAG, "Scan: name=$name, addr=$address, rssi=${result.rssi}, services=[$serviceUuidsPreview]")
            }

            val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()

            val isThermometer = serviceUuids.contains(HEALTH_THERMOMETER_SERVICE) ||
                THERMOMETER_NAMES.any { name.contains(it, ignoreCase = true) }

            val isBloodPressure = serviceUuids.contains(BLOOD_PRESSURE_SERVICE) ||
                BLOOD_PRESSURE_NAMES.any { name.contains(it, ignoreCase = true) }

            when {
                isThermometer && thermometerGatt == null -> {
                    emitJson("found", "device" to "thermometer")
                    thermometerGatt = device.connectGatt(context, false, thermometerGattCallback)
                    if (bloodPressureGatt != null) stopScan()
                }
                isBloodPressure && bloodPressureGatt == null -> {
                    emitJson("found", "device" to "blood_pressure")
                    bloodPressureGatt = device.connectGatt(context, false, bloodPressureGattCallback)
                    if (thermometerGatt != null) stopScan()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: errorCode=$errorCode")
            isScanning = false
            emitJson("error", "message" to "BLE scan failed (code: $errorCode)")
        }
    }

    private val thermometerGattCallback = createGattCallback(
        deviceType = "thermometer",
        serviceUuid = HEALTH_THERMOMETER_SERVICE,
        characteristicUuid = TEMPERATURE_MEASUREMENT,
        onDisconnect = { thermometerGatt = null }
    ) { data -> parseTemperature(data) }

    private val bloodPressureGattCallback = createGattCallback(
        deviceType = "blood_pressure",
        serviceUuid = BLOOD_PRESSURE_SERVICE,
        characteristicUuid = BLOOD_PRESSURE_MEASUREMENT,
        onDisconnect = { bloodPressureGatt = null }
    ) { data -> parseBloodPressure(data) }

    fun startScan() {
        if (isScanning) return
        val adapter = bluetoothAdapter ?: run {
            emitJson("error", "message" to "Bluetooth not available")
            return
        }
        if (!adapter.isEnabled) {
            emitJson("error", "message" to "Bluetooth is disabled")
            return
        }

        // Try connecting to bonded (paired) devices first
        connectBondedDevices(adapter)

        scanner = adapter.bluetoothLeScanner ?: run {
            emitJson("error", "message" to "BLE scanner not available")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Unfiltered scan to also detect devices by name (Nipro devices may not advertise standard UUIDs)
        scanner?.startScan(null, settings, scanCallback)
        isScanning = true
        emitJson("status", "message" to "BLE scan started")
        Log.d(TAG, "BLE scan started")
    }

    private fun connectBondedDevices(adapter: BluetoothAdapter) {
        val bondedDevices = adapter.bondedDevices ?: return
        for (device in bondedDevices) {
            val name = device.name ?: ""
            Log.d(TAG, "Bonded device: name=$name, addr=${device.address}")

            val isThermometer = THERMOMETER_NAMES.any { name.contains(it, ignoreCase = true) }
            val isBloodPressure = BLOOD_PRESSURE_NAMES.any { name.contains(it, ignoreCase = true) }

            if (isThermometer && thermometerGatt == null) {
                Log.d(TAG, "Connecting to bonded thermometer: $name")
                emitJson("found", "device" to "thermometer")
                thermometerGatt = device.connectGatt(context, true, thermometerGattCallback)
            }
            if (isBloodPressure && bloodPressureGatt == null) {
                Log.d(TAG, "Connecting to bonded blood_pressure: $name")
                emitJson("found", "device" to "blood_pressure")
                bloodPressureGatt = device.connectGatt(context, true, bloodPressureGattCallback)
            }
        }
    }

    fun stopScan() {
        if (!isScanning) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan", e)
        }
        isScanning = false
    }

    fun resetAndRescan() {
        stopScan()
        thermometerGatt?.close()
        thermometerGatt = null
        bloodPressureGatt?.close()
        bloodPressureGatt = null
        emitJson("reset", "message" to "Scan restarted")
        startScan()
    }

    fun destroy() {
        stopScan()
        thermometerGatt?.close()
        thermometerGatt = null
        bloodPressureGatt?.close()
        bloodPressureGatt = null
    }

    private fun createGattCallback(
        deviceType: String,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        onDisconnect: () -> Unit,
        parseData: (ByteArray) -> JSONObject?
    ): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "$deviceType connected")
                        emitJson("connected", "device" to deviceType)
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "$deviceType disconnected")
                        emitJson("disconnected", "device" to deviceType)
                        gatt.close()
                        onDisconnect()
                        // Auto-rescan after disconnect
                        startScan()
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    emitJson("error", "message" to "$deviceType service discovery failed")
                    return
                }

                val service = gatt.getService(serviceUuid)
                if (service == null) {
                    emitJson("error", "message" to "$deviceType service not found")
                    gatt.disconnect()
                    return
                }

                val characteristic = service.getCharacteristic(characteristicUuid)
                if (characteristic == null) {
                    emitJson("error", "message" to "$deviceType characteristic not found")
                    gatt.disconnect()
                    return
                }

                // Enable indication or notification
                gatt.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(CCCD)
                if (descriptor != null) {
                    val properties = characteristic.properties
                    val value = when {
                        properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        else -> null
                    }
                    if (value != null) {
                        descriptor.value = value
                        gatt.writeDescriptor(descriptor)
                        Log.d(TAG, "$deviceType registered for ${if (value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) "indication" else "notification"}")
                    }
                }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val data = characteristic.value ?: return
                val json = parseData(data)
                if (json != null) {
                    onDataReceived?.invoke(json)
                }
            }
        }
    }

    // IEEE 11073 FLOAT format parser (same as ble-medical-gateway)
    private fun parseTemperature(data: ByteArray): JSONObject? {
        if (data.size < 5) return null

        val flags = data[0].toInt() and 0xFF
        val isFahrenheit = flags and 0x01 != 0

        // IEEE 11073 FLOAT: bytes 1-4
        var mantissa = (data[1].toInt() and 0xFF) or
            ((data[2].toInt() and 0xFF) shl 8) or
            ((data[3].toInt() and 0xFF) shl 16)
        if (mantissa and 0x800000 != 0) {
            mantissa = mantissa or (0xFF shl 24) // sign extend
        }
        val exponent = data[4].toInt().toByte()

        var temperature = mantissa.toFloat() * 10.0f.pow(exponent.toInt())

        if (isFahrenheit) {
            temperature = (temperature - 32.0f) * 5.0f / 9.0f
        }

        return JSONObject().apply {
            put("type", "temperature")
            put("value", "%.1f".format(temperature).toDouble())
            put("unit", "celsius")
        }
    }

    // Blood pressure parser (same as ble-medical-gateway)
    private fun parseBloodPressure(data: ByteArray): JSONObject? {
        if (data.size < 7) return null

        val flags = data[0].toInt() and 0xFF
        val isKPa = flags and 0x01 != 0
        val hasTimestamp = flags and 0x02 != 0
        val hasPulseRate = flags and 0x04 != 0

        fun parseSFLOAT(lo: Byte, hi: Byte): Float {
            var mantissa = (lo.toInt() and 0xFF) or ((hi.toInt() and 0x0F) shl 8)
            if (mantissa and 0x0800 != 0) mantissa = mantissa or 0xF000
            var exponent = (hi.toInt() and 0xFF) shr 4
            if (exponent and 0x08 != 0) exponent = exponent or 0xF0
            return mantissa.toShort().toFloat() * 10.0f.pow(exponent.toByte().toInt())
        }

        var systolic = parseSFLOAT(data[1], data[2])
        var diastolic = parseSFLOAT(data[3], data[4])
        // bytes 5-6: Mean Arterial Pressure (skip)

        var offset = 7
        if (hasTimestamp) offset += 7

        val pulse = if (hasPulseRate && offset + 2 <= data.size) {
            parseSFLOAT(data[offset], data[offset + 1])
        } else {
            -1.0f
        }

        if (isKPa) {
            systolic *= 7.50062f
            diastolic *= 7.50062f
        }

        return JSONObject().apply {
            put("type", "blood_pressure")
            put("systolic", systolic.toInt())
            put("diastolic", diastolic.toInt())
            if (pulse > 0) put("pulse", pulse.toInt())
            put("unit", "mmHg")
        }
    }

    private fun emitJson(type: String, vararg pairs: Pair<String, Any>) {
        val json = JSONObject().apply {
            put("type", type)
            pairs.forEach { put(it.first, it.second) }
        }
        onDataReceived?.invoke(json)
    }
}

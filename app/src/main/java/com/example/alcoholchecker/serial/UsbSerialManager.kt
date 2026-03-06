package com.example.alcoholchecker.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

class UsbSerialManager(private val context: Context) {

    companion object {
        private const val TAG = "UsbSerialManager"
        private const val ACTION_USB_PERMISSION = "com.example.alcoholchecker.USB_PERMISSION"

        // FC-1200 serial settings
        private const val BAUD_RATE = 9600
        private const val DATA_BITS = 8
        private const val STOP_BITS = UsbSerialPort.STOPBITS_1
        private const val PARITY = UsbSerialPort.PARITY_NONE
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: UsbSerialPort? = null
    private var connection: UsbDeviceConnection? = null
    private var ioManager: SerialInputOutputManager? = null
    private val session = Fc1200Session()
    private var readJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    var onEvent: ((JSONObject) -> Unit)? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    connectToDevice(device)
                } else {
                    Log.w(TAG, "USB permission denied")
                    emitStatus("error", "USB permission denied")
                }
            }
        }
    }

    init {
        session.onEvent = { json -> onEvent?.invoke(json) }
        session.onSendData = { data -> writeData(data) }
    }

    fun start() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbPermissionReceiver, filter)
        }
        scanAndConnect()
    }

    fun scanAndConnect() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            Log.d(TAG, "No USB serial devices found")
            emitStatus("status", "No USB serial devices found")
            return
        }

        Log.d(TAG, "Found ${drivers.size} USB serial device(s)")
        val driver = drivers[0]
        val device = driver.device

        if (usbManager.hasPermission(device)) {
            connectToDevice(device)
        } else {
            Log.d(TAG, "Requesting USB permission for ${device.deviceName}")
            val intent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = drivers.find { it.device == device } ?: run {
            emitStatus("error", "Driver not found for device")
            return
        }
        openPort(driver)
    }

    private fun openPort(driver: UsbSerialDriver) {
        try {
            val conn = usbManager.openDevice(driver.device) ?: run {
                emitStatus("error", "Failed to open USB device")
                return
            }
            connection = conn

            val port = driver.ports[0]
            port.open(conn)
            port.setParameters(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY)
            serialPort = port

            Log.d(TAG, "Serial port opened: ${driver.device.deviceName}")
            emitStatus("connected", "FC-1200 serial connected")

            startReading(port)
            session.startMeasurement()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open serial port", e)
            emitStatus("error", "Failed to open serial port: ${e.message}")
        }
    }

    private fun startReading(port: UsbSerialPort) {
        ioManager?.stop()

        val listener = object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                val text = String(data, Charsets.US_ASCII)
                session.feed(text)
            }

            override fun onRunError(e: Exception) {
                Log.e(TAG, "Serial read error", e)
                emitStatus("disconnected", "Serial read error: ${e.message}")
                disconnect()
            }
        }

        ioManager = SerialInputOutputManager(port, listener).also {
            it.readBufferSize = 256
            scope.launch { it.run() }
        }
    }

    private fun writeData(data: ByteArray) {
        try {
            serialPort?.write(data, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Serial write error", e)
        }
    }

    fun handleCommand(command: String) {
        when (command) {
            "connect" -> scanAndConnect()
            "reset" -> {
                session.reset()
                session.startMeasurement()
            }
            "disconnect" -> disconnect()
            "memory_read" -> session.startMemoryRead()
            "memory_complete" -> session.completeMemoryRead()
            "sensor_lifetime" -> session.checkSensorLifetime()
            else -> {
                if (command.startsWith("date_update:")) {
                    val datetime = command.removePrefix("date_update:")
                    session.updateDate(datetime)
                }
            }
        }
    }

    fun disconnect() {
        ioManager?.stop()
        ioManager = null
        try {
            serialPort?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing serial port", e)
        }
        serialPort = null
        connection?.close()
        connection = null
        session.reset()
    }

    fun destroy() {
        disconnect()
        try {
            context.unregisterReceiver(usbPermissionReceiver)
        } catch (_: Exception) {}
    }

    private fun emitStatus(type: String, message: String) {
        val json = JSONObject().apply {
            put("type", type)
            put("message", message)
        }
        onEvent?.invoke(json)
    }
}

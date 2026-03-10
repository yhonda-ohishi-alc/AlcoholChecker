package com.example.alcoholchecker.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.util.Log
import java.io.File

class PolicyComplianceActivity : Activity() {
    companion object {
        private const val TAG = "DeviceAdmin"
        private const val PREFS_NAME = "device_settings"
    }

    private fun fileLog(msg: String) {
        try {
            val file = File(getExternalFilesDir(null), "provisioning.log")
            file.appendText("${System.currentTimeMillis()} [PolicyCompliance] $msg\n")
        } catch (_: Exception) {}
        Log.w(TAG, msg)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileLog("ADMIN_POLICY_COMPLIANCE called")
        fileLog("Intent action: ${intent.action}")
        fileLog("Intent extras keys: ${intent.extras?.keySet()?.toList()}")

        // admin extras bundle を読み取る
        val extras = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                PersistableBundle::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
            )
        }
        fileLog("extras bundle: $extras")

        if (extras != null) {
            val registrationCode = extras.getString("registration_code")
            val deviceName = extras.getString("device_name")
            val isDevDevice = extras.getString("is_dev_device")?.toBoolean() ?: false
            fileLog("registration_code=${registrationCode != null}, device_name=$deviceName, is_dev_device=$isDevDevice")

            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean("is_dev_device", isDevDevice)
                .apply {
                    registrationCode?.let { putString("registration_code", it) }
                    deviceName?.let { putString("device_name", it) }
                }
                .apply()
            fileLog("SharedPreferences saved")
        } else {
            fileLog("extras bundle is NULL")
        }

        // Device Owner 設定
        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(this, AppDeviceAdminReceiver::class.java)
            // USB デバッグを有効化
            dpm.setGlobalSetting(componentName, Settings.Global.ADB_ENABLED, "1")
            fileLog("USB debugging enabled via Device Owner")
            // ダブルタップで画面ON
            dpm.setGlobalSetting(componentName, "double_tap_to_wake", "1")
            fileLog("Double tap to wake enabled via Device Owner")
        } catch (e: Exception) {
            fileLog("Failed to set Device Owner settings: ${e.message}")
        }

        setResult(RESULT_OK)
        finish()
    }
}

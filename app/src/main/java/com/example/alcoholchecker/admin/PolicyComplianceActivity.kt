package com.example.alcoholchecker.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.util.Log

class PolicyComplianceActivity : Activity() {
    companion object {
        private const val TAG = "DeviceAdmin"
        private const val PREFS_NAME = "device_settings"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.w(TAG, "ADMIN_POLICY_COMPLIANCE called")
        Log.w(TAG, "Intent action: ${intent.action}")
        Log.w(TAG, "Intent extras keys: ${intent.extras?.keySet()?.toList()}")
        intent.extras?.keySet()?.forEach { key ->
            Log.w(TAG, "Intent extra [$key] = ${intent.extras?.get(key)} (type: ${intent.extras?.get(key)?.javaClass?.name})")
        }

        // admin extras bundle を読み取る
        val extras = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                PersistableBundle::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(
                android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
            )
        }
        Log.w(TAG, "PolicyCompliance extras bundle: $extras")

        if (extras != null) {
            val registrationCode = extras.getString("registration_code")
            val deviceName = extras.getString("device_name")
            val isDevDevice = extras.getString("is_dev_device")?.toBoolean() ?: false
            Log.w(TAG, "PolicyCompliance extras: registration_code=${registrationCode != null}, device_name=$deviceName, is_dev_device=$isDevDevice")

            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean("is_dev_device", isDevDevice)
                .apply {
                    registrationCode?.let { putString("registration_code", it) }
                    deviceName?.let { putString("device_name", it) }
                }
                .apply()
        }

        // USB デバッグを有効化
        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(this, AppDeviceAdminReceiver::class.java)
            dpm.setGlobalSetting(componentName, Settings.Global.ADB_ENABLED, "1")
            Log.w(TAG, "USB debugging enabled via Device Owner")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enable USB debugging: ${e.message}")
        }

        setResult(RESULT_OK)
        finish()
    }
}

package com.example.alcoholchecker.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import java.io.File

class GetProvisioningModeActivity : Activity() {
    companion object {
        private const val TAG = "DeviceAdmin"
        private const val PREFS_NAME = "device_settings"
    }

    private fun fileLog(msg: String) {
        try {
            val file = File(filesDir, "provisioning.log")
            file.appendText("${System.currentTimeMillis()} [GetProvisioningMode] $msg\n")
        } catch (_: Exception) {}
        Log.w(TAG, msg)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileLog("GET_PROVISIONING_MODE called")

        // admin extras bundle を読み取って SharedPreferences に保存
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
            fileLog("extras bundle is NULL — no registration_code to save")
        }

        val resultIntent = Intent()
        resultIntent.putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_MODE,
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
        )
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}

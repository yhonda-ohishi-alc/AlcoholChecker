package com.example.alcoholchecker.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log

class GetProvisioningModeActivity : Activity() {
    companion object {
        private const val TAG = "DeviceAdmin"
        private const val PREFS_NAME = "device_settings"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.w(TAG, "GET_PROVISIONING_MODE called")

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
        Log.w(TAG, "GetProvisioningMode extras bundle: $extras")

        if (extras != null) {
            val registrationCode = extras.getString("registration_code")
            val deviceName = extras.getString("device_name")
            val isDevDevice = extras.getString("is_dev_device")?.toBoolean() ?: false
            Log.w(TAG, "GetProvisioningMode extras: registration_code=${registrationCode != null}, device_name=$deviceName, is_dev_device=$isDevDevice")

            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean("is_dev_device", isDevDevice)
                .apply {
                    registrationCode?.let { putString("registration_code", it) }
                    deviceName?.let { putString("device_name", it) }
                }
                .apply()
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

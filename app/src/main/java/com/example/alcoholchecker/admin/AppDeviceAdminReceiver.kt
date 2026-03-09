package com.example.alcoholchecker.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AppDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        private const val TAG = "DeviceAdmin"
        private const val PREFS_NAME = "device_settings"
        private const val KEY_IS_DEV_DEVICE = "is_dev_device"
        private const val KEY_REGISTRATION_CODE = "registration_code"
        private const val KEY_DEVICE_NAME = "device_name"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Log.d(TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.d(TAG, "Device admin disabled")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Log.d(TAG, "Profile provisioning complete")

        // QR プロビジョニング時の admin extras を読み取る
        val extras = intent.getBundleExtra(
            android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
        )
        val isDevDevice = extras?.getString("is_dev_device")?.toBoolean() ?: false
        val registrationCode = extras?.getString("registration_code")
        val deviceName = extras?.getString("device_name")
        Log.d(TAG, "Provisioning extras: is_dev_device=$isDevDevice, registration_code=${registrationCode != null}, device_name=$deviceName")

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_DEV_DEVICE, isDevDevice)
            .apply {
                registrationCode?.let { putString(KEY_REGISTRATION_CODE, it) }
                deviceName?.let { putString(KEY_DEVICE_NAME, it) }
            }
            .apply()
    }
}

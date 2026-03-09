package com.example.alcoholchecker.service

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class UpdateResultReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "UpdateResult"
        const val ACTION_INSTALL_RESULT = "com.example.alcoholchecker.INSTALL_RESULT"
        private const val API_BASE = "https://alc-app.m-tama-ramu.workers.dev"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.d(TAG, "Package install succeeded")
                // インストール成功時にバージョンを報告
                val versionCode = intent.getIntExtra("version_code", 0)
                val versionName = intent.getStringExtra("version_name") ?: ""
                reportVersionToBackend(context, versionCode, versionName)
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Device Owner でない場合、ユーザーの確認が必要
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                }
            }
            else -> {
                Log.e(TAG, "Package install failed: status=$status, message=$message")
            }
        }
    }

    private fun reportVersionToBackend(context: Context, versionCode: Int, versionName: String) {
        val prefs = context.getSharedPreferences(OtaUpdateService.PREFS_NAME, Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: return

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)
        val isDevDevice = prefs.getBoolean("is_dev_device", false)

        // 新しいバージョン情報を使用 (インストール直後はまだ旧アプリが動作中の可能性)
        val actualVersionCode = if (versionCode > 0) versionCode else getCurrentVersionCode(context)
        val actualVersionName = versionName.ifEmpty { getCurrentVersionName(context) }

        thread {
            try {
                val url = URL("$API_BASE/api/devices/report-version")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000

                val body = """{"device_id":"$deviceId","version_code":$actualVersionCode,"version_name":"$actualVersionName","is_device_owner":$isDeviceOwner,"is_dev_device":$isDevDevice}"""
                conn.outputStream.use { it.write(body.toByteArray()) }

                val responseCode = conn.responseCode
                Log.d(TAG, "Version reported after update: $responseCode (v$actualVersionName/$actualVersionCode)")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to report version after update", e)
            }
        }
    }

    private fun getCurrentVersionCode(context: Context): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    private fun getCurrentVersionName(context: Context): String {
        return context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }
}

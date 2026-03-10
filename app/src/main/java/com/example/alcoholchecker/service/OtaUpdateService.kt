package com.example.alcoholchecker.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.IBinder
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.example.alcoholchecker.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class OtaUpdateService : Service() {

    companion object {
        private const val TAG = "OtaUpdate"
        private const val CHANNEL_ID = "ota_update"
        private const val NOTIFICATION_ID = 9001
        private const val DEFAULT_APK_URL =
            "https://github.com/yhonda-ohishi-alc/AlcoholChecker/releases/latest/download/app-release.apk"
        const val PREFS_NAME = "device_settings"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("アプリ更新")
            .setContentText("更新を準備中...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        val downloadUrl = intent?.getStringExtra("download_url") ?: DEFAULT_APK_URL
        val targetVersionCode = intent?.getIntExtra("version_code", 0) ?: 0
        val targetVersionName = intent?.getStringExtra("version_name") ?: ""

        // バージョンチェック
        val currentVersionCode = getCurrentVersionCode()
        if (targetVersionCode > 0 && currentVersionCode >= targetVersionCode) {
            Log.d(TAG, "Already at version $currentVersionCode, skipping update")
            stopSelf()
            return START_NOT_STICKY
        }

        thread {
            try {
                downloadAndInstall(downloadUrl, targetVersionCode, targetVersionName)
            } catch (e: Exception) {
                Log.e(TAG, "OTA update failed", e)
                showErrorNotification("更新に失敗しました: ${e.message}")
            } finally {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun downloadAndInstall(downloadUrl: String, versionCode: Int, versionName: String) {
        Log.d(TAG, "Downloading APK from $downloadUrl")
        updateNotification("APK をダウンロード中...")

        val apkFile = File(cacheDir, "update.apk")

        // GitHub Releases の latest URL はリダイレクトされるので followRedirects が必要
        val url = URL(downloadUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000

        try {
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP $responseCode")
            }

            val totalSize = connection.contentLength
            var downloaded = 0L

            connection.inputStream.use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalSize > 0) {
                            val percent = (downloaded * 100 / totalSize).toInt()
                            updateNotification("ダウンロード中... $percent%")
                        }
                    }
                }
            }

            Log.d(TAG, "APK downloaded: ${apkFile.length()} bytes")
            updateNotification("インストール中...")

            if (isDeviceOwner()) {
                installPackageSilently(apkFile, versionCode, versionName)
            } else {
                Log.d(TAG, "Not device owner, prompting user to install")
                promptUserInstall(apkFile)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun installPackageSilently(apkFile: File, versionCode: Int, versionName: String) {
        Log.d(TAG, "Installing APK silently via PackageInstaller")

        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        params.setAppPackageName(packageName)

        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)

        try {
            apkFile.inputStream().use { input ->
                session.openWrite("ota_update", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            // 結果を受け取る BroadcastReceiver 用の Intent
            val intent = Intent(this, UpdateResultReceiver::class.java).apply {
                action = UpdateResultReceiver.ACTION_INSTALL_RESULT
                putExtra("version_code", versionCode)
                putExtra("version_name", versionName)
            }
            val pi = PendingIntent.getBroadcast(
                this, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            session.commit(pi.intentSender)
            Log.d(TAG, "PackageInstaller session committed (sessionId=$sessionId)")
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            // APK ファイルをクリーンアップ
            apkFile.delete()
        }
    }

    private fun promptUserInstall(apkFile: File) {
        // cache から files ディレクトリにコピー (FileProvider で公開するため)
        val installFile = File(getExternalFilesDir(null), "update.apk")
        apkFile.copyTo(installFile, overwrite = true)
        apkFile.delete()

        val apkUri: Uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            installFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // 通知タップでインストーラーを起動
        val pi = PendingIntent.getActivity(
            this, 0, installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("アプリ更新")
            .setContentText("タップしてインストールしてください")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)

        // 直接インストーラーも起動
        try {
            startActivity(installIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start installer activity: ${e.message}")
        }
    }

    private fun isDeviceOwner(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(packageName)
    }

    private fun getCurrentVersionCode(): Int {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "アプリ更新",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "OTA アップデートの進捗表示"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("アプリ更新")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun showErrorNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("アプリ更新エラー")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(false)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }
}

package com.example.alcoholchecker.call

import android.app.KeyguardManager
import android.content.Intent
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.alcoholchecker.databinding.ActivityIncomingCallBinding
import com.example.alcoholchecker.ui.webview.WebViewActivity

class IncomingCallActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "IncomingCallActivity"
        const val EXTRA_CALLER_NAME = "extra_caller_name"
        const val EXTRA_ROOM_ID = "extra_room_id"

        private val activeInstances = mutableMapOf<String, IncomingCallActivity>()

        fun dismissForRoom(roomId: String) {
            val activity = activeInstances[roomId]
            if (activity != null) {
                Log.d(TAG, "Dismissing incoming call for room: $roomId")
                activity.runOnUiThread {
                    activity.stopRingtoneAndVibration()
                    activity.finish()
                }
            }
        }
    }

    private lateinit var binding: ActivityIncomingCallBinding
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        showOnLockScreen()

        binding = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: ""
        if (roomId.isNotEmpty()) {
            activeInstances[roomId] = this
        }

        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "ドライバー"
        binding.textCallerName.text = callerName
        binding.textCallLabel.text = "遠隔点呼"

        binding.buttonAnswer.setOnClickListener {
            answerCall()
        }

        binding.buttonReject.setOnClickListener {
            rejectCall()
        }

        startRingtoneAndVibration()
    }

    private fun showOnLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startRingtoneAndVibration() {
        try {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, ringtoneUri)?.apply {
                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                if (audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT) {
                    isLooping = true
                    play()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play ringtone", e)
        }

        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 1000, 1000)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to vibrate", e)
        }
    }

    private fun stopRingtoneAndVibration() {
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun answerCall() {
        Log.d(TAG, "Call answered")
        stopRingtoneAndVibration()
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
        // Notify server so other devices dismiss their incoming call
        if (!roomId.isNullOrEmpty()) {
            RoomWatcher.activeInstance?.notifyCallAnswered(roomId)
        }
        Log.d(TAG, "Navigating to tenko room: $roomId")

        val navIntent = Intent(this, WebViewActivity::class.java).apply {
            action = WebViewActivity.ACTION_NAVIGATE_TENKO
            putExtra(WebViewActivity.EXTRA_ROOM_ID, roomId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(navIntent)
        finish()
    }

    private fun rejectCall() {
        Log.d(TAG, "Call rejected")
        stopRingtoneAndVibration()
        finish()
    }

    override fun onDestroy() {
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: ""
        if (roomId.isNotEmpty()) {
            activeInstances.remove(roomId)
        }
        super.onDestroy()
        stopRingtoneAndVibration()
    }
}

package com.example.myrecord

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat

class CallRecordingAccessibilityService : AccessibilityService() {

    private val TAG = "MyRecordService"
    private val CHANNEL_ID = "call_recording_channel"
    private val NOTIFICATION_ID = 1001

    private var audioRecorderHelper: AudioRecorderHelper? = null
    private var lastTargetPackage: String? = null
    private var currentAppName: String = "UnknownCall"
    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        audioRecorderHelper = AudioRecorderHelper(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyRecord::CallMonitorWakeLock")

        // Required so Android treats this as a genuine foreground service and
        // lifts the background microphone restriction — WakeLock alone does not do this.
        startForegroundNotification()

        Log.d(TAG, "Accessibility Service Connected")
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Recording Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps call recording active in the background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MyRecord is active")
            .setContentText("Monitoring for calls to record")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (!isMonitoring) return

            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            val isCallActive = audioManager.mode == AudioManager.MODE_IN_COMMUNICATION ||
                    audioManager.mode == AudioManager.MODE_RINGTONE ||
                    telephonyManager.callState == TelephonyManager.CALL_STATE_OFFHOOK ||
                    telephonyManager.callState == TelephonyManager.CALL_STATE_RINGING

            if (isCallActive && audioRecorderHelper?.isRecording == false) {
                Log.d(TAG, "Call active! Starting recorder for $currentAppName...")
                audioRecorderHelper?.startRecording(currentAppName)
            } else if (!isCallActive && audioRecorderHelper?.isRecording == true) {
                Log.d(TAG, "Call ended. Stopping recorder...")
                audioRecorderHelper?.stopRecording()
            }

            handler.postDelayed(this, 1000)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        val appName = when {
            packageName.contains("whatsapp") -> "WhatsApp"
            packageName.contains("com.android.dialer") -> "Cellular"
            packageName.contains("snapchat") -> "Snapchat"
            packageName.contains("instagram") -> "Instagram"
            packageName.contains("telegram") -> "Telegram"
            packageName.contains("orca") -> "Messenger"
            else -> "Unknown"
        }

        val isTargetApp = appName != "Unknown"

        if (isTargetApp) {
            if (isMonitoring && lastTargetPackage != packageName && lastTargetPackage != null) {
                audioRecorderHelper?.stopRecording()
            }

            currentAppName = appName

            if (!isMonitoring) {
                Log.d(TAG, "Target app opened. Starting monitor and acquiring WakeLock...")
                isMonitoring = true
                wakeLock?.acquire(60 * 60 * 1000L)
                handler.post(monitorRunnable)
            }
            lastTargetPackage = packageName

        } else {
            val isSystemUI = packageName.contains("launcher") ||
                    packageName.contains("systemui") ||
                    packageName.contains("settings")

            if (!isSystemUI) {
                Log.d(TAG, "Left target app. Stopping monitor and releasing WakeLock...")
                isMonitoring = false
                handler.removeCallbacks(monitorRunnable)
                lastTargetPackage = null
                currentAppName = "UnknownCall"

                if (audioRecorderHelper?.isRecording == true) {
                    audioRecorderHelper?.stopRecording()
                }

                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service Interrupted")
        cleanup()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    private fun cleanup() {
        isMonitoring = false
        handler.removeCallbacks(monitorRunnable)
        audioRecorderHelper?.cleanup()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }
}

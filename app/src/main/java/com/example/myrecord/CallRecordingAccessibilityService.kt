package com.example.myrecord

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class CallRecordingAccessibilityService : AccessibilityService() {

    private val TAG = "MyRecordService"
    private val CHANNEL_ID = "call_recording_channel"
    private val NOTIFICATION_ID = 1001

    private var audioRecorderHelper: AudioRecorderHelper? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isMonitoring = false

    // Grace period tracking
    private var recordingStartTime = 0L
    private val GRACE_PERIOD_MS = 3000L

    override fun onServiceConnected() {
        super.onServiceConnected()
        audioRecorderHelper = AudioRecorderHelper(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyRecord::WakeLock")

        createNotificationChannel()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startForegroundService()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Recording Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MyRecord Active")
            .setContentText("Monitoring for incoming calls")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: ""

        // Check if the event is from any of our target calling apps
        val isTargetApp = packageName.contains("whatsapp") ||
                packageName.contains("telegram") ||
                packageName.contains("messenger")

        if (isTargetApp) {
            val eventText = event.text?.joinToString(" ")?.lowercase() ?: ""
            val contentDesc = event.contentDescription?.toString()?.lowercase() ?: ""
            val combinedString = "$eventText $contentDesc"

            // Look for call indicators, including the call timer (e.g., "00:01", "12:34")
            val hasTimer = combinedString.matches(Regex(".*\\d{2}:\\d{2}.*"))
            val isCallActive = combinedString.contains("ringing") ||
                    combinedString.contains("calling") ||
                    combinedString.contains("ongoing call") ||
                    combinedString.contains("voice call") ||
                    combinedString.contains("video call") ||
                    hasTimer

            if (isCallActive && !isMonitoring) {
                Log.d(TAG, "Call detected via UI text in $packageName. Starting recorder...")
                isMonitoring = true
                recordingStartTime = System.currentTimeMillis()
                if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L)

                // Name the file based on the app being used
                val appName = when {
                    packageName.contains("whatsapp") -> "WhatsApp"
                    packageName.contains("telegram") -> "Telegram"
                    packageName.contains("messenger") -> "Messenger"
                    else -> "UnknownApp"
                }

                audioRecorderHelper?.startRecording("${appName}_Call")
            } else if (isMonitoring) {
                // Check if text indicates call termination
                if (combinedString.contains("end") || combinedString.contains("ending") || combinedString.contains("call ended")) {
                    stopMonitoring(ignoreGracePeriod = false)
                }
            }
        }
    }

    private fun stopMonitoring(ignoreGracePeriod: Boolean = false) {
        if (isMonitoring) {
            val elapsedTime = System.currentTimeMillis() - recordingStartTime
            if (!ignoreGracePeriod && elapsedTime < GRACE_PERIOD_MS) {
                Log.w(TAG, "Ignored premature stop trigger! Elapsed time: ${elapsedTime}ms")
                return
            }

            Log.d(TAG, "Call ended. Stopping recorder...")
            isMonitoring = false
            audioRecorderHelper?.stopRecording()
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }

    override fun onInterrupt() {
        cleanup()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    private fun cleanup() {
        isMonitoring = false
        audioRecorderHelper?.cleanup()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        recordingStartTime = 0L
    }
}
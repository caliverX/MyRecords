package com.example.myrecord

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class CallRecordingAccessibilityService : AccessibilityService() {

    private val TAG = "MyRecordService"
    private val CHANNEL_ID = "call_recording_channel"
    private val ERROR_CHANNEL_ID = "call_recording_errors"
    private val NOTIFICATION_ID = 1001
    private val ERROR_NOTIFICATION_ID = 1002

    private var audioRecorderHelper: AudioRecorderHelper? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isMonitoring = false
    private lateinit var audioManager: AudioManager

    private var recordingStartTime = 0L
    private val GRACE_PERIOD_MS = 3000L

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioRecorderHelper = AudioRecorderHelper(this)
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyRecord::WakeLock")

            createNotificationChannels()
            // Foreground Service is intentionally NOT started here.
            // Newer Android versions require a microphone-type FGS to be
            // tied closely to actual mic usage, so it's started in
            // startMonitoring() instead, right before the mic is requested.
        } catch (e: Exception) {
            Log.e(TAG, "Error during service connection initialization: ${e.message}", e)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Call Recording Service", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(ERROR_CHANNEL_ID, "Recording Alerts", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun showStatusNotification(title: String, message: String, isError: Boolean = false) {
        val channel = if (isError) ERROR_CHANNEL_ID else CHANNEL_ID
        val notification = NotificationCompat.Builder(this, channel)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(if (isError) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(isError)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(if (isError) ERROR_NOTIFICATION_ID else NOTIFICATION_ID, notification)
    }

    private fun startForegroundSafely() {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MyRecord Active")
                .setContentText("Recording in progress...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
        }
    }

    private fun stopForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop foreground service: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: ""

        val isTargetApp = packageName.contains("whatsapp") ||
                packageName.contains("telegram") ||
                packageName.contains("messenger") ||
                packageName.contains("orca")

        if (!isTargetApp) return

        val eventText = event.text?.joinToString(" ")?.lowercase() ?: ""
        val contentDesc = event.contentDescription?.toString()?.lowercase() ?: ""
        val combinedString = "$eventText $contentDesc"

        val isCallActive = CallTextAnalyzer.isCallActive(combinedString)
        val isCallEnded = CallTextAnalyzer.isCallEndText(combinedString)

        if (isCallActive && !isMonitoring) {
            Log.d(TAG, "Call detected via UI text in $packageName. Starting recorder...")
            startMonitoring(packageName)
        } else if (isMonitoring && isCallEnded) {
            Log.d(TAG, "End call phrase detected. Stopping recorder...")
            stopMonitoring(ignoreGracePeriod = false)
        }
    }

    private fun startMonitoring(packageName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && audioManager.isMicrophoneMute) {
            Log.w(TAG, "Recording aborted: System microphone is globally muted.")
            showStatusNotification("Recording Blocked", "Please unmute your microphone in quick settings.", isError = true)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val configs = audioManager.activeRecordingConfigurations
            if (configs.isNotEmpty()) {
                Log.w(TAG, "Another app is currently using the microphone; capture may fail.")
            }
        }

        try {
            isMonitoring = true
            recordingStartTime = System.currentTimeMillis()

            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10 * 60 * 1000L)
            }

            startForegroundSafely()

            val appName = RecordingFileNaming.appNameForPackage(packageName)
            val success = audioRecorderHelper?.startRecording("${appName}_Call") == true

            if (!success) {
                Log.e(TAG, "Hardware failed to start. Stopping Foreground Service to prevent OS termination.")
                showStatusNotification("Recording Failed", "Microphone access was denied or in use.", isError = true)
                stopMonitoring(ignoreGracePeriod = true)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: Microphone access blocked by OS. ${e.message}")
            showStatusNotification("Recording Blocked", "Microphone permission is missing.", isError = true)
            stopMonitoring(ignoreGracePeriod = true)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error starting monitor: ${e.message}", e)
            stopMonitoring(ignoreGracePeriod = true)
        }
    }

    private fun stopMonitoring(ignoreGracePeriod: Boolean = false) {
        if (!isMonitoring) return

        val elapsedTime = System.currentTimeMillis() - recordingStartTime
        if (!ignoreGracePeriod && elapsedTime < GRACE_PERIOD_MS) {
            Log.w(TAG, "Ignored premature stop trigger! Elapsed time: ${elapsedTime}ms")
            return
        }

        try {
            isMonitoring = false
            audioRecorderHelper?.stopRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio recorder: ${e.message}", e)
        } finally {
            stopForegroundSafely()
            try {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing wake lock: ${e.message}")
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted by OS.")
        cleanup()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    private fun cleanup() {
        stopMonitoring(ignoreGracePeriod = true)
        recordingStartTime = 0L
        audioRecorderHelper?.cleanup()
    }
}
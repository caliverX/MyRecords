package com.example.myrecord

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import android.app.PendingIntent
import android.content.Intent

class CallRecordingAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MyRecordService"
        private const val CHANNEL_ID = "call_recording_channel"
        private const val ERROR_CHANNEL_ID = "call_recording_errors"
        private const val NOTIFICATION_ID = 1001
        private const val ERROR_NOTIFICATION_ID = 1002

        // BATTERY OPTIMIZATION: Slow poll when idle, fast poll when recording
        private const val POLL_INTERVAL_IDLE_MS = 10000L
        private const val POLL_INTERVAL_ACTIVE_MS = 1500L
        private const val EXIT_CONFIRMATIONS = 2
        private const val TEXT_FAST_STOP_GRACE_MS = 3000L
    }

    private val allowedPackages = listOf(
        "whatsapp", "telegram", "instagram",
        "messenger", "orca", "snapchat",
        "wechat", "tencent.mm" // <-- Added WeChat here
    )

    private lateinit var audioManager: AudioManager
    private var audioRecorderHelper: AudioRecorderHelper? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var isMonitoring = false
    private var recordingStartTime = 0L
    private var packageNameAtStart = ""

    private var pollerThread: HandlerThread? = null
    private var pollerHandler: Handler? = null
    private var pollerRunnable: Runnable? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var everWasInCall = false
    private var notInCallCount = 0
    @Volatile private var lastForegroundPkg = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        FileLogger.init(this)
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioRecorderHelper = AudioRecorderHelper(this)
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyRecord::WakeLock")
            createNotificationChannels()
            previousMode = audioManager.mode
            startPoller(POLL_INTERVAL_IDLE_MS) // Start in low-power mode
            Log.i(TAG, "Service connected. Battery-optimized polling active.")
        } catch (e: Exception) {
            Log.e(TAG, "Error init: ${e.message}", e)
        }
    }

    override fun onInterrupt() { cleanup() }
    override fun onDestroy() { super.onDestroy(); cleanup() }

    private fun cleanup() {
        stopMonitoring(ignoreGrace = true)
        stopPoller()
        recordingStartTime = 0L
        audioRecorderHelper?.cleanup()

        // Fix 3: WakeLock Safety Net. Guarantee it is released if OS kills the service.
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                FileLogger.log(TAG, "WakeLock forcefully released in cleanup.")
            }
        } catch (e: Exception) {
            FileLogger.log(TAG, "Failed to release WakeLock: ${e.message}", isError = true)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Call Recording Service", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(ERROR_CHANNEL_ID, "Recording Alerts", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun showStatusNotification(title: String, message: String, isError: Boolean = false) {
        val channel = if (isError) ERROR_CHANNEL_ID else CHANNEL_ID
        val notification = NotificationCompat.Builder(this, channel)
            .setContentTitle(title).setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(if (isError) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(isError).build()
        getSystemService(NotificationManager::class.java).notify(if (isError) ERROR_NOTIFICATION_ID else NOTIFICATION_ID, notification)
    }

    private fun startForegroundSafely() {
        try {
            // Make notification clickable to open RecordsActivity
            val tapIntent = Intent(this, RecordsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, tapIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MyRecord Active")
                .setContentText("Recording in progress...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent) // Attach the click action
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FGS: ${e.message}")
            FileLogger.log(TAG, "Failed to start FGS: ${e.message}", isError = true)
        }
    }

    private fun stopForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") { stopForeground(true) }
        } catch (e: Exception) { Log.e(TAG, "Failed to stop FGS: ${e.message}") }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // Ignore our own app
        if (pkg == this.packageName) return

        // FIX 5: Ignore Android System UI, Launchers, and PiP (Picture-in-Picture) windows.
        // This prevents the app from stopping a recording when the user minimizes a video call.
        if (pkg == "android" || pkg == "com.android.systemui" || pkg.contains("launcher")) {
            return
        }

        // BATTERY OPTIMIZATION: Ignore events from apps we don't care about at all
        val isAllowedApp = allowedPackages.any { pkg.contains(it) }
        if (!isAllowedApp && !isMonitoring) return

        lastForegroundPkg = pkg
        val eventText = event.text?.joinToString(" ")?.lowercase() ?: ""
        val contentDesc = event.contentDescription?.toString()?.lowercase() ?: ""
        val combined = "$eventText $contentDesc"

        if (!isMonitoring && isAllowedApp && CallTextAnalyzer.isCallActive(combined)) {
            Log.d(TAG, "Fast-start via UI text in $pkg")
            FileLogger.log(TAG, "Fast-start via UI text in $pkg")
            startMonitoring(pkg)
            return
        }

        if (isMonitoring && CallTextAnalyzer.isCallEndText(combined)) {
            Log.d(TAG, "Fast-stop via UI text")
            FileLogger.log(TAG, "Fast-stop via UI text")
            stopMonitoring(ignoreGrace = false)
        }
    }

    private fun modeName(m: Int): String = when (m) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMM"
        else -> "UNKNOWN($m)"
    }

    private fun startPoller(intervalMs: Long) {
        stopPoller()
        pollerThread = HandlerThread("AudioModePoller").also { it.start() }
        pollerHandler = Handler(pollerThread!!.looper)

        pollerRunnable = object : Runnable {
            override fun run() {
                val mode = audioManager.mode
                val prev = previousMode
                val isAllowedApp = allowedPackages.any { lastForegroundPkg.contains(it) }

                if (!isMonitoring && isAllowedApp && (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL)) {
                    // FIX: Snapchat stories trigger MODE_IN_COMMUNICATION but play audio via Media.
                    // If music/media is actively playing, it's a Story, not a call. Ignore it!
                    if (!audioManager.isMusicActive) {
                        Log.i(TAG, "MODE TRANSITION: ${modeName(prev)} -> ${modeName(mode)} | App: $lastForegroundPkg")
                        startMonitoring(lastForegroundPkg)
                    } else {
                        Log.d(TAG, "Ignored IN_COMMUNICATION because Media is playing (Snapchat Story?)")
                    }
                }

                if (isMonitoring) {
                    if (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL) {
                        everWasInCall = true
                        notInCallCount = 0
                    } else if (everWasInCall) {
                        notInCallCount++
                        if (notInCallCount >= EXIT_CONFIRMATIONS) {
                            Log.i(TAG, "Poller: call ended. Stopping.")
                            stopMonitoring(ignoreGrace = true)
                            previousMode = mode
                            return
                        }
                    }
                }

                previousMode = mode
                // BATTERY OPTIMIZATION: Dynamically change poll speed based on state
                val nextInterval = if (isMonitoring) POLL_INTERVAL_ACTIVE_MS else POLL_INTERVAL_IDLE_MS
                pollerHandler?.postDelayed(this, nextInterval)
            }
        }
        pollerHandler?.post(pollerRunnable!!)
    }

    // Helper to instantly switch to high-speed polling when a call starts
    private fun activateFastPolling() {
        pollerHandler?.removeCallbacksAndMessages(null)
        pollerRunnable?.let { pollerHandler?.post(it) }
    }

    private fun stopPoller() {
        pollerHandler?.removeCallbacksAndMessages(null)
        pollerHandler = null
        pollerThread?.quitSafely()
        pollerThread = null
        pollerRunnable = null
    }

    private fun startMonitoring(pkg: String) {
        if (isMonitoring) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && audioManager.isMicrophoneMute) {
            showStatusNotification("Blocked", "Unmute microphone.", isError = true); return
        }

        try {
            isMonitoring = true
            activateFastPolling() // Switch to 1.5s polling instantly

            recordingStartTime = System.currentTimeMillis()
            packageNameAtStart = pkg.ifBlank { "UnknownApp" }
            everWasInCall = (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION)
            notInCallCount = 0
            previousMode = audioManager.mode

            if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L)
            startForegroundSafely()

            val appName = RecordingFileNaming.appNameForPackage(packageNameAtStart)
            val success = audioRecorderHelper?.startRecording("${appName}_Call") == true

            if (!success) {
                showStatusNotification("Failed", "Mic denied or in use.", isError = true)
                stopMonitoring(ignoreGrace = true)
                return
            }
            Log.i(TAG, "Recording STARTED: ${appName}_Call")
            FileLogger.log(TAG, "Recording STARTED: ${appName}_Call in package: $packageNameAtStart")

            if (pollerThread == null || !pollerThread!!.isAlive) startPoller(POLL_INTERVAL_ACTIVE_MS)
        } catch (e: SecurityException) {
            showStatusNotification("Blocked", "Missing mic permission.", isError = true)
            stopMonitoring(ignoreGrace = true)
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}", e)
            stopMonitoring(ignoreGrace = true)
        }
    }

    private fun stopMonitoring(ignoreGrace: Boolean) {
        if (!isMonitoring) return
        val elapsed = System.currentTimeMillis() - recordingStartTime
        if (!ignoreGrace && elapsed < TEXT_FAST_STOP_GRACE_MS) return

        try {
            isMonitoring = false
            audioRecorderHelper?.stopRecording()
            Log.i(TAG, "Recording STOPPED (${elapsed}ms)")
            FileLogger.log(TAG, "Recording STOPPED (${elapsed}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping: ${e.message}", e)
        } finally {
            // Release wakelock IMMEDIATELY to save battery
            try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
            stopForegroundSafely()
        }
    }
}
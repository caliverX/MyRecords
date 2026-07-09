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

class CallRecordingAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MyRecordService"
        private const val CHANNEL_ID = "call_recording_channel"
        private const val ERROR_CHANNEL_ID = "call_recording_errors"
        private const val NOTIFICATION_ID = 1001
        private const val ERROR_NOTIFICATION_ID = 1002

        // Poller tunables
        private const val POLL_INTERVAL_MS = 1500L       // Check every 1.5 s
        private const val EXIT_CONFIRMATIONS = 2        // Need 2 consecutive "not in call" (~3 s)
        private const val TEXT_FAST_STOP_GRACE_MS = 3000L
    }

    private lateinit var audioManager: AudioManager
    private var audioRecorderHelper: AudioRecorderHelper? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // ---- Monitoring state ----
    @Volatile
    private var isMonitoring = false
    private var recordingStartTime = 0L
    private var packageNameAtStart = ""

    // ---- Poller state ----
    private var pollerThread: HandlerThread? = null
    private var pollerHandler: Handler? = null
    private var pollerRunnable: Runnable? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var everWasInCall = false
    private var notInCallCount = 0

    // ---- Foreground app tracking (for file naming) ----
    @Volatile
    private var lastForegroundPkg = ""

    // =========================================================================
    //  Lifecycle
    // =========================================================================

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioRecorderHelper = AudioRecorderHelper(this)
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyRecord::WakeLock")
            createNotificationChannels()

            previousMode = audioManager.mode

            // Start the universal poller — it runs FOREVER, detecting both
            // call START and call END for ANY VoIP app
            startPoller()

            Log.i(TAG, "Service connected. Universal audio-mode poller active (initial=${modeName(previousMode)})")
        } catch (e: Exception) {
            Log.e(TAG, "Error during service connection initialization: ${e.message}", e)
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
        stopMonitoring(ignoreGrace = true)
        stopPoller()
        recordingStartTime = 0L
        audioRecorderHelper?.cleanup()
    }

    // =========================================================================
    //  Notifications
    // =========================================================================

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

    // =========================================================================
    //  Accessibility Events
    //  - Track which app is in the foreground (for file naming)
    //  - Text-based fast path (bonus: can start/stop slightly faster than poller)
    // =========================================================================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // Don't track our own package
        if (pkg == this.packageName) return

        // Always update the last-seen foreground package
        lastForegroundPkg = pkg

        // Build combined text from all available sources
        val eventText = event.text?.joinToString(" ")?.lowercase() ?: ""
        val contentDesc = event.contentDescription?.toString()?.lowercase() ?: ""
        val combined = "$eventText $contentDesc"

        // FAST START: text can fire before audio mode settles to IN_COMMUNICATION
        if (!isMonitoring && CallTextAnalyzer.isCallActive(combined)) {
            Log.d(TAG, "Fast-start via UI text in $pkg")
            startMonitoring(pkg)
            return
        }

        // FAST STOP: text can fire before audio mode reverts to NORMAL
        if (isMonitoring && CallTextAnalyzer.isCallEndText(combined)) {
            Log.d(TAG, "Fast-stop via UI text in $pkg")
            stopMonitoring(ignoreGrace = false)
        }
    }

    // =========================================================================
    //  Universal Audio Mode Poller
    //
    //  This is the PRIMARY detection mechanism. It works with ANY VoIP app
    //  because Android sets MODE_IN_COMMUNICATION for every VoIP session.
    //
    //  - Detects transition TO   IN_COMMUNICATION  → START recording
    //  - Detects transition FROM IN_COMMUNICATION  → STOP  recording
    //
    //  Runs on its own HandlerThread so it is NEVER paused by Android
    //  even when the main thread is suspended (app in background).
    // =========================================================================

    private fun modeName(m: Int): String = when (m) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMM"
        else -> "UNKNOWN($m)"
    }

    private fun startPoller() {
        stopPoller()

        pollerThread = HandlerThread("AudioModePoller").also { it.start() }
        pollerHandler = Handler(pollerThread!!.looper)

        pollerRunnable = object : Runnable {
            override fun run() {
                val mode = audioManager.mode
                val prev = previousMode

                // ---- CALL START: transition INTO IN_COMMUNICATION ----
                if (!isMonitoring
                    && mode == AudioManager.MODE_IN_COMMUNICATION
                    && prev != AudioManager.MODE_IN_COMMUNICATION
                ) {
                    Log.i(TAG, "MODE TRANSITION: ${modeName(prev)} -> ${modeName(mode)} | VoIP call detected from $lastForegroundPkg")
                    startMonitoring(lastForegroundPkg)
                }

                // ---- CALL END: transition OUT OF IN_COMMUNICATION ----
                if (isMonitoring) {
                    if (mode == AudioManager.MODE_IN_COMMUNICATION) {
                        // Still in call — reset counter
                        everWasInCall = true
                        notInCallCount = 0
                    } else if (everWasInCall) {
                        // Mode left IN_COMMUNICATION — count confirmations
                        notInCallCount++
                        Log.d(TAG, "Poller: mode=${modeName(mode)} notInCall[$notInCallCount/$EXIT_CONFIRMATIONS]")

                        if (notInCallCount >= EXIT_CONFIRMATIONS) {
                            Log.i(TAG, "Poller: call ended confirmed (mode=${modeName(mode)}). Stopping recorder.")
                            stopMonitoring(ignoreGrace = false)
                            previousMode = mode
                            return // Don't re-post
                        }
                    }
                }

                previousMode = mode
                pollerHandler?.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

        pollerHandler?.post(pollerRunnable!!)
    }

    private fun stopPoller() {
        pollerHandler?.removeCallbacksAndMessages(null)
        pollerHandler = null
        pollerThread?.quitSafely()
        pollerThread = null
        pollerRunnable = null
    }

    // =========================================================================
    //  Monitoring control
    // =========================================================================

    private fun startMonitoring(pkg: String) {
        if (isMonitoring) return // Already recording

        // Pre-flight checks
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
            packageNameAtStart = pkg.ifBlank { "UnknownApp" }

            // Sync poller state so it doesn't false-trigger on the mode it just saw
            everWasInCall = (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION)
            notInCallCount = 0
            previousMode = audioManager.mode

            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10 * 60 * 1000L)
            }

            startForegroundSafely()

            val appName = RecordingFileNaming.appNameForPackage(packageNameAtStart)
            val success = audioRecorderHelper?.startRecording("${appName}_Call") == true

            if (!success) {
                Log.e(TAG, "Hardware failed to start. Aborting.")
                showStatusNotification("Recording Failed", "Microphone access was denied or in use.", isError = true)
                stopMonitoring(ignoreGrace = true)
                return
            }

            Log.i(TAG, "Recording STARTED: ${appName}_Call (source=pkg:$packageNameAtStart mode:${modeName(audioManager.mode)})")

            // Safety: if the poller thread died for some reason, restart it
            if (pollerThread == null || !pollerThread!!.isAlive) {
                Log.w(TAG, "Poller thread was dead — restarting")
                startPoller()
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: Microphone access blocked by OS. ${e.message}")
            showStatusNotification("Recording Blocked", "Microphone permission is missing.", isError = true)
            stopMonitoring(ignoreGrace = true)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error starting monitor: ${e.message}", e)
            stopMonitoring(ignoreGrace = true)
        }
    }

    private fun stopMonitoring(ignoreGrace: Boolean) {
        if (!isMonitoring) return

        val elapsed = System.currentTimeMillis() - recordingStartTime
        if (!ignoreGrace && elapsed < TEXT_FAST_STOP_GRACE_MS) {
            Log.w(TAG, "Ignored premature stop trigger! Elapsed: ${elapsed}ms")
            return
        }

        try {
            isMonitoring = false
            audioRecorderHelper?.stopRecording()
            Log.i(TAG, "Recording STOPPED (duration=${elapsed}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio recorder: ${e.message}", e)
        } finally {
            // DO NOT stop the poller here — it must keep running to detect
            // the NEXT call. Only cleanup() / onDestroy() stops it.
            stopForegroundSafely()
            try {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing wake lock: ${e.message}")
            }
        }
    }
}
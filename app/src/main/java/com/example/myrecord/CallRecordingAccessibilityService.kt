package com.example.myrecord

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class CallRecordingAccessibilityService : AccessibilityService() {

    private val TAG = "MyRecordService"
    private var audioRecorderHelper: AudioRecorderHelper? = null

    // Track the last known target app to manage switching between apps
    private var lastTargetPackage: String? = null

    // The human-readable name to tag the current recording with
    private var currentAppName: String = "UnknownCall"

    // Tools for the 1-second background monitor
    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        audioRecorderHelper = AudioRecorderHelper(this)
        Log.d(TAG, "Accessibility Service Connected")
    }

    // 1. THE MONITOR: This runs every 1 second ONLY while a target app is open
    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (!isMonitoring) return

            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            // Allow RINGTONE (incoming call) or IN_COMMUNICATION (active voice) or OFFHOOK (active cellular)
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

            // Loop this check again in 1 second
            handler.postDelayed(this, 1000)
        }
    }

    // 2. THE SCREEN CHECKER: This just turns the 1-second monitor ON or OFF
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // Map the package to a readable name
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
            // Split files if we switch directly from WhatsApp to Snapchat
            if (isMonitoring && lastTargetPackage != packageName && lastTargetPackage != null) {
                Log.d(TAG, "Switched directly to a different target app. Splitting files...")
                audioRecorderHelper?.stopRecording()
            }

            // Update the label BEFORE the monitor's next tick uses it
            currentAppName = appName

            // If we enter a target app, turn ON the 1-second hardware monitor
            if (!isMonitoring) {
                Log.d(TAG, "Target app opened. Starting 1-second audio monitor...")
                isMonitoring = true
                handler.post(monitorRunnable)
            }
            lastTargetPackage = packageName

        } else {
            val isSystemUI = packageName.contains("launcher") ||
                    packageName.contains("systemui") ||
                    packageName.contains("settings")

            // If we completely leave the target apps, turn OFF the monitor and stop recording
            if (!isSystemUI) {
                Log.d(TAG, "Left target app. Stopping monitor and recorder...")
                isMonitoring = false
                handler.removeCallbacks(monitorRunnable)
                lastTargetPackage = null
                currentAppName = "UnknownCall"

                if (audioRecorderHelper?.isRecording == true) {
                    audioRecorderHelper?.stopRecording()
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service Interrupted")
        isMonitoring = false
        handler.removeCallbacks(monitorRunnable)
        audioRecorderHelper?.cleanup()
    }

    override fun onDestroy() {
        super.onDestroy()
        isMonitoring = false
        handler.removeCallbacks(monitorRunnable)
        audioRecorderHelper?.cleanup()
    }
}
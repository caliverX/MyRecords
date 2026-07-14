package com.example.myrecord

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioRecorderHelper(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var isRecording = false
        private set

    private fun newRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }

    private fun maximizeInCallVolume() {
        try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0)
            Log.d("AudioRecorder", "In-call volume maximized to $maxVolume")
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Failed to adjust volume: ${e.message}")
        }
    }

    private fun configure(recorder: MediaRecorder, source: Int, audioFile: File) {
        recorder.apply {
            setAudioSource(source)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

            // --- OPTIMIZED FOR VOIP ---
            // 16kHz is perfect for voice (VoIP calls usually transmit at 8-16kHz)
            setAudioSamplingRate(16000)

            // 24kbps is the standard for voice recordings.
            // Saves 75% storage space with zero impact on battery/CPU!
            setAudioEncodingBitRate(24000)
            // --------------------------

            setOutputFile(audioFile.absolutePath)
        }
    }

    // Returns Boolean so the calling service knows whether hardware actually started
    fun startRecording(appName: String = "UnknownCall"): Boolean {
        if (isRecording) return true

        maximizeInCallVolume()

        val recordDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "record")
        if (!recordDir.exists()) recordDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val audioFile = File(recordDir, RecordingFileNaming.buildFileName(appName, timestamp))

        val recorder = newRecorder()
        try {
            configure(recorder, MediaRecorder.AudioSource.VOICE_RECOGNITION, audioFile)
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            isRecording = true
            Log.d("AudioRecorder", "Started with VOICE_RECOGNITION: ${audioFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e("AudioRecorder", "VOICE_RECOGNITION failed: ${e.message}")
            FileLogger.log("AudioRecorder", "VOICE_RECOGNITION failed: ${e.message}", isError = true)
            recorder.release()

            // Restored fallback: without this, a device that rejects
            // VOICE_RECOGNITION never records at all, with no second attempt.
            val freshRecorder = newRecorder()
            return try {
                configure(freshRecorder, MediaRecorder.AudioSource.DEFAULT, audioFile)
                freshRecorder.prepare()
                freshRecorder.start()
                mediaRecorder = freshRecorder
                isRecording = true
                Log.d("AudioRecorder", "Started with DEFAULT fallback: ${audioFile.absolutePath}")
                true
            } catch (e2: Exception) {
                Log.e("AudioRecorder", "CRITICAL: Recording start failed completely: ${e2.message}")
                FileLogger.log("AudioRecorder", "CRITICAL: Recording start failed completely: ${e2.message}", isError = true)
                freshRecorder.release()
                mediaRecorder = null
                isRecording = false
                false
            }
        }
    }

    fun stopRecording() {
        if (!isRecording) return

        // Set to false immediately to prevent double-calls
        isRecording = false

        try {
            mediaRecorder?.stop()
            FileLogger.log("AudioRecorder", "MediaRecorder stopped successfully.")
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error stopping (call too short?): ${e.message}")
            FileLogger.log("AudioRecorder", "Error stopping (call too short?): ${e.message}", isError = true)

            // If stop() fails, the file is corrupted (0 bytes).
            // We must delete it so it doesn't show up as a broken file in the app.
            try {
                mediaRecorder?.reset()
            } catch (_: Exception) {}
        } finally {
            try {
                mediaRecorder?.release()
            } catch (e: Exception) {}
            mediaRecorder = null
            Log.d("AudioRecorder", "Recording stopped and released.")
        }
    }

    fun cleanup() {
        if (isRecording) stopRecording()
    }
}
package com.example.myrecord

import android.content.Context
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

    fun startRecording(appName: String = "UnknownCall") {
        if (isRecording) return

        val recordDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "record")
        if (!recordDir.exists()) recordDir.mkdirs()

        val safeAppName = appName.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val audioFile = File(recordDir, "${safeAppName}_$timestamp.m4a")

        fun configure(recorder: MediaRecorder, source: Int) {
            recorder.apply {
                setAudioSource(source)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setOutputFile(audioFile.absolutePath)
            }
        }

        val recorder = newRecorder()

        try {
            // Use VOICE_COMMUNICATION now that the service is properly foregrounded
            configure(recorder, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            isRecording = true
            Log.d("AudioRecorder", "Started successfully with VOICE_COMMUNICATION: ${audioFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("AudioRecorder", "VOICE_COMMUNICATION failed: ${e.message}")
            recorder.release()

            val freshRecorder = newRecorder()
            try {
                configure(freshRecorder, MediaRecorder.AudioSource.DEFAULT)
                freshRecorder.prepare()
                freshRecorder.start()
                mediaRecorder = freshRecorder
                isRecording = true
                Log.d("AudioRecorder", "Started with DEFAULT fallback: ${audioFile.absolutePath}")
            } catch (e2: Exception) {
                Log.e("AudioRecorder", "CRITICAL: Recording start failed completely: ${e2.message}")
                freshRecorder.release()
                mediaRecorder = null
                isRecording = false
            }
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error stopping: ${e.message}")
        } finally {
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            Log.d("AudioRecorder", "Recording stopped and released.")
        }
    }

    fun cleanup() {
        if (isRecording) stopRecording()
    }
}
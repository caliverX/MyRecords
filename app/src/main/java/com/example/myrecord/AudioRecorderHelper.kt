package com.example.myrecord

import android.content.Context
import android.media.MediaRecorder
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

        val recorder = MediaRecorder()

        try {
            // Standard microphone input, permitted via RECORD_AUDIO
            configure(recorder, MediaRecorder.AudioSource.MIC)
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            isRecording = true
            Log.d("AudioRecorder", "Started successfully with MIC: ${audioFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("AudioRecorder", "MIC failed: ${e.message}")
            recorder.release()

            val freshRecorder = MediaRecorder()
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
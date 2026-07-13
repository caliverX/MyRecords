package com.example.myrecord

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (logFile != null) return
        // Save logs in a dedicated "logs" folder
        val dir = File(context.getExternalFilesDir(null), "logs")
        if (!dir.exists()) dir.mkdirs()
        logFile = File(dir, "myrecord_log.txt")

        // Prevent log file from growing forever (clear if > 5MB)
        if (logFile!!.length() > 5 * 1024 * 1024) {
            logFile!!.writeText("")
        }

        log("FileLogger", "Logger initialized. App started.")
    }

    fun log(tag: String, message: String, isError: Boolean = false) {
        val timestamp = dateFormat.format(Date())
        val level = if (isError) "ERROR" else "INFO"
        val logLine = "$timestamp $level [$tag]: $message\n"

        // Still print to Android Studio Logcat
        if (isError) Log.e(tag, message) else Log.i(tag, message)

        try {
            logFile?.let {
                synchronized(this) {
                    it.appendText(logLine)
                }
            }
        } catch (e: Exception) {
            Log.e("FileLogger", "Failed to write to log file: ${e.message}")
        }
    }

    fun getLogFile(): File? = logFile
}
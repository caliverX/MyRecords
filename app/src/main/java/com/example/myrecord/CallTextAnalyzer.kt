package com.example.myrecord

/**
 * Pure decision-making logic with no Android dependencies, so it can be
 * unit-tested on the JVM without an emulator.
 */
object CallTextAnalyzer {

    private val timerPattern = Regex("""\d{2}:\d{2}""")

    private val activeCallPhrases = listOf(
        "ringing", "calling", "ongoing call", "voice call",
        "video call", "connected", "in call"
    )

    // Deliberately NO bare "end" -- it matches "friend", "weekend", "attend",
    // "trend", "legend", "recommend", or any contact name containing those
    // letters, and would kill an active recording on ordinary chat text.
    // Only specific, unambiguous end-of-call phrases belong here.
    private val endCallPhrases = listOf(
        "call ended", "ending call", "declined", "disconnected", "hung up"
    )

    fun isCallActive(combinedText: String): Boolean {
        val text = combinedText.lowercase()
        return activeCallPhrases.any { text.contains(it) } || timerPattern.containsMatchIn(text)
    }

    fun isCallEndText(combinedText: String): Boolean {
        val text = combinedText.lowercase()
        return endCallPhrases.any { text.contains(it) }
    }
}

object RecordingFileNaming {

    private val unsafeCharsPattern = Regex("[^A-Za-z0-9_-]")

    fun sanitizeAppName(appName: String): String {
        val cleaned = appName.replace(unsafeCharsPattern, "_")
        return cleaned.ifBlank { "UnknownCall" }
    }

    fun buildFileName(appName: String, timestamp: String): String {
        return "${sanitizeAppName(appName)}_$timestamp.m4a"
    }

    /** Maps a detected package name to a human-readable app label used in filenames. */
    fun appNameForPackage(packageName: String): String = when {
        packageName.contains("whatsapp") -> "WhatsApp"
        packageName.contains("telegram") -> "Telegram"
        packageName.contains("messenger") || packageName.contains("orca") -> "Messenger"
        else -> "UnknownApp"
    }
}
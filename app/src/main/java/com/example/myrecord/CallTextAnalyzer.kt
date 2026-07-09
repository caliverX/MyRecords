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

    /**
     * Maps a detected package name to a human-readable app label used in filenames.
     * Has a smart fallback that extracts the app name from any unknown package.
     */
    fun appNameForPackage(packageName: String): String = when {
        packageName.contains("whatsapp") -> "WhatsApp"
        packageName.contains("telegram") -> "Telegram"
        packageName.contains("messenger") || packageName.contains("orca") || packageName.contains("facebook") -> "Messenger"
        packageName.contains("signal") -> "Signal"
        packageName.contains("viber") -> "Viber"
        packageName.contains("skype") -> "Skype"
        packageName.contains("discord") -> "Discord"
        packageName.contains("line") -> "LINE"
        packageName.contains("zoom") -> "Zoom"
        packageName.contains("duo") || packageName.contains("meet") -> "Meet"
        packageName.contains("threema") -> "Threema"
        packageName.contains("wickr") -> "Wickr"
        else -> {
            // Smart fallback: take the last segment of the package name and capitalize it
            // e.g. "com.somebrand.newapp" -> "Newapp"
            packageName.split(".")
                .lastOrNull()
                ?.takeIf { it.isNotBlank() && it.length > 1 }
                ?.replaceFirstChar { it.uppercase() }
                ?: "UnknownApp"
        }
    }
}
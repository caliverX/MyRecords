package com.example.myrecord

object CallTextAnalyzer {
    private val timerPattern = Regex("""\b\d{1,2}:\d{2}\b""")

    private val activeCallPhrases = listOf(
        "ringing", "calling", "ongoing call", "voice call",
        "video call", "connected", "in call", "tap for sound",
        "speaker", "muted", "unmuted", "end call", "camera off", "camera on"
    )

    private val endCallPhrases = listOf(
        "call ended", "ending call", "declined", "disconnected", "hung up", "missed call"
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

    fun appNameForPackage(packageName: String): String = when {
        packageName.contains("whatsapp") -> "WhatsApp"
        packageName.contains("telegram") -> "Telegram"
        packageName.contains("messenger") || packageName.contains("orca") || packageName.contains("facebook") -> "Messenger"
        packageName.contains("instagram") -> "Instagram"
        packageName.contains("snapchat") -> "Snapchat"
        packageName.contains("signal") -> "Signal"
        packageName.contains("viber") -> "Viber"
        packageName.contains("skype") -> "Skype"
        packageName.contains("discord") -> "Discord"
        packageName.contains("line") -> "LINE"
        packageName.contains("wechat") || packageName.contains("tencent.mm") -> "WeChat"
        else -> {
            packageName.split(".").lastOrNull()?.takeIf { it.isNotBlank() && it.length > 1 }
                ?.replaceFirstChar { it.uppercase() } ?: "UnknownApp"
        }
    }
}
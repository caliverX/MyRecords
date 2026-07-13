package com.example.myrecord

object CallTextAnalyzer {
    // Removed the timerPattern. Voice notes have timers (0:15) which caused false triggers!

    // Strict phrases that ONLY appear when a call is ringing or connecting
    private val activeCallPhrases = listOf(
        // English
        "ringing", "calling", "ongoing call", "voice call",
        "video call", "connected", "in call",

        // Arabic (Strict)
        "جارٍ الاتصال", "جاري الاتصال", "يجري الاتصال", // Calling
        "مكالمة جارية", "مكالمة صوتية", "مكالمة فيديو", // Ongoing/Voice/Video call
        "متصل", "في المكالمة" // Connected / In call
    )

    private val endCallPhrases = listOf(
        // English
        "call ended", "ending call", "declined", "disconnected", "hung up", "missed call",

        // Arabic
        "انتهت المكالمة", "تم إنهاء المكالمة", // Call ended
        "مرفوض", "تم رفض المكالمة", // Declined
        "غير متصل", "تم قطع الاتصال", // Disconnected
        "مكالمة فائتة" // Missed call
    )

    fun isCallActive(combinedText: String): Boolean {
        val text = combinedText.lowercase()
        // Only trigger on strict phrases, NO timers!
        return activeCallPhrases.any { text.contains(it.lowercase()) }
    }

    fun isCallEndText(combinedText: String): Boolean {
        val text = combinedText.lowercase()
        return endCallPhrases.any { text.contains(it.lowercase()) }
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
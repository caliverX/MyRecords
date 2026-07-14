🎙️ MyRecord - VoIP Call Recorder for Android

MyRecord is a lightweight, battery-optimized Android application that automatically records VoIP calls from popular messaging apps. It runs silently in the background using Android's Accessibility Services and Audio Mode polling to detect when a call starts and stops, saving high-quality but storage-friendly .m4a audio files.

✨ Features

Auto-Detection: Automatically starts and stops recording for:

WhatsApp

Telegram

Messenger

Instagram

Snapchat

WeChat

Storage Optimized: Records at 16kHz / 24kbps, optimizing for voice clarity while keeping file sizes incredibly small (approx. 10MB for a 1-hour call).

Smart Cleanup: Automatically deletes corrupted files and recordings older than 90 days to save phone storage.

Built-in Player: Play, share, and delete recordings directly within the app.

Bug Logging: Includes a built-in file logger to easily diagnose issues on different devices.

🛠️ How It Works

Instead of using outdated and blocked CAPTURE_AUDIO_OUTPUT methods, MyRecord uses an AccessibilityService to monitor screen states and text. When it detects call UI elements (like "Ringing" or a call timer) or when Android's AudioManager switches to MODE_IN_COMMUNICATION, it triggers MediaRecorder to capture the audio via the microphone.

⚙️ Tech Stack

Language: Kotlin

Audio: MediaRecorder (AAC encoding), MediaPlayer

Background Execution: AccessibilityService, HandlerThread (Dynamic Polling), ForegroundService (WakeLocks)

UI: Android Views (XML), RecyclerView, Material Components

📲 Installation

Go to the Releases page.

Download the latest .apk file.

Install it on your Android device (ensure "Install unknown apps" is permitted for your browser).

Open the app and follow the 3-step setup to grant Microphone, Accessibility, and Battery permissions.

🔒 Privacy

All recordings are saved locally on the device's internal storage (Android/data/com.example.myrecord/files/Music/record/). No audio files or data are uploaded to any server. The "Send Bug Logs" feature only shares a local text file if the user explicitly clicks the button.

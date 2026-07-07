package com.example.myrecord

import android.app.Service
import android.content.Intent
import android.os.IBinder

class MyRecordService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
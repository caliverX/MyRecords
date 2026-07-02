package com.example.myrecord

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnPermissions = findViewById<Button>(R.id.btnPermissions)
        val btnAccessibility = findViewById<Button>(R.id.btnAccessibility)
        val btnBattery = findViewById<Button>(R.id.btnBattery)
        val btnViewRecords = findViewById<Button>(R.id.btnViewRecords) // NEW BUTTON

        // Button 1: Request standard permissions
        btnPermissions.setOnClickListener {
            requestBasicPermissions()
        }

        // Button 2: Open Accessibility Settings
        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Scroll down to 'Installed Apps', find 'MyRecord' and turn it ON", Toast.LENGTH_LONG).show()
        }

        // Button 3: Open Battery Settings
        btnBattery.setOnClickListener {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Set MyRecord to 'Unrestricted'", Toast.LENGTH_LONG).show()
        }

        // Button 4: Open Records List (NEW LOGIC)
        btnViewRecords.setOnClickListener {
            val intent = Intent(this, RecordsActivity::class.java)
            startActivity(intent)
        }
    }

    private fun requestBasicPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )

        val neededPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            Toast.makeText(this, "Microphone permissions already granted!", Toast.LENGTH_SHORT).show()
        }
    }
}
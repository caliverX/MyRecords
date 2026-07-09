package com.example.myrecord

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 101

    private lateinit var textServiceStatus: TextView
    private lateinit var badgeStep1: TextView
    private lateinit var badgeStep2: TextView
    private lateinit var badgeStep3: TextView
    private lateinit var textProgressCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnPermissions = findViewById<MaterialButton>(R.id.btnPermissions)
        val btnAccessibility = findViewById<MaterialButton>(R.id.btnAccessibility)
        val btnBattery = findViewById<MaterialButton>(R.id.btnBattery)
        val btnViewRecords = findViewById<MaterialButton>(R.id.btnViewRecords)

        textServiceStatus = findViewById(R.id.textServiceStatus)
        badgeStep1 = findViewById(R.id.badgeStep1)
        badgeStep2 = findViewById(R.id.badgeStep2)
        badgeStep3 = findViewById(R.id.badgeStep3)
        textProgressCount = findViewById(R.id.textProgressCount)

        btnPermissions.setOnClickListener { requestBasicPermissions() }
        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Find 'MyRecord' and turn it ON", Toast.LENGTH_LONG).show()
        }
        btnBattery.setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            Toast.makeText(this, "Set MyRecord to 'Unrestricted'", Toast.LENGTH_LONG).show()
        }
        btnViewRecords.setOnClickListener {
            startActivity(Intent(this, RecordsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStepStatus()
    }

    private fun refreshStepStatus() {
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val phoneStateGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val accessibilityOn = isAccessibilityServiceEnabled()
        val batteryUnrestricted = isIgnoringBatteryOptimizations()

        // Hidden badges for logic preservation
        setBadgeState(badgeStep1, micGranted && phoneStateGranted, "1")
        setBadgeState(badgeStep2, accessibilityOn, "2")
        setBadgeState(badgeStep3, batteryUnrestricted, "3")

        val completed = listOf(micGranted && phoneStateGranted, accessibilityOn, batteryUnrestricted).count { it }
        textProgressCount.text = getString(R.string.progress_text, completed, 3)

        // Update UI elements for the user
        if (completed == 3) {
            textServiceStatus.text = "● ACTIVE — Listening for VoIP calls"
            textServiceStatus.setTextColor(getColor(R.color.brand_success))
        } else {
            textServiceStatus.text = "○ IDLE — Complete setup below (${completed}/3)"
            textServiceStatus.setTextColor(getColor(R.color.text_hint))
        }

        // Update button stroke colors to reflect status
        findViewById<MaterialButton>(R.id.btnPermissions).strokeColor = getColorStateList(if (micGranted && phoneStateGranted) R.color.brand_success else R.color.bg_surface_high)
        findViewById<MaterialButton>(R.id.btnAccessibility).strokeColor = getColorStateList(if (accessibilityOn) R.color.brand_success else R.color.bg_surface_high)
        findViewById<MaterialButton>(R.id.btnBattery).strokeColor = getColorStateList(if (batteryUnrestricted) R.color.brand_success else R.color.bg_surface_high)
    }

    private fun setBadgeState(badge: TextView, done: Boolean, stepNumber: String) {
        badge.text = if (done) "\u2713" else stepNumber
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBasicPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val neededPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (neededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            Toast.makeText(this, "Permissions already granted!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return
        val deniedPermissions = permissions.filterIndexed { index, _ -> grantResults.getOrNull(index) != PackageManager.PERMISSION_GRANTED }
        if (deniedPermissions.isEmpty()) {
            Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show()
            refreshStepStatus()
            return
        }
        val permanentlyDenied = deniedPermissions.any { !ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
        if (permanentlyDenied) {
            Toast.makeText(this, "Permanently denied. Enable manually in Settings.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", packageName, null) })
        }
    }
}
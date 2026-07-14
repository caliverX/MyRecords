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
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import java.io.File

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
        val btnSendLogs = findViewById<MaterialButton>(R.id.btnSendLogs)

        textServiceStatus = findViewById(R.id.textServiceStatus)
        badgeStep1 = findViewById(R.id.badgeStep1)
        badgeStep2 = findViewById(R.id.badgeStep2)
        badgeStep3 = findViewById(R.id.badgeStep3)
        textProgressCount = findViewById(R.id.textProgressCount)

        btnPermissions.setOnClickListener { requestBasicPermissions() }
        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, R.string.toast_accessibility, Toast.LENGTH_LONG).show()
        }
        btnBattery.setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            Toast.makeText(this, R.string.toast_battery, Toast.LENGTH_LONG).show()
        }
        btnViewRecords.setOnClickListener {
            startActivity(Intent(this, RecordsActivity::class.java))
        }
        btnSendLogs.setOnClickListener { sendLogs() }
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

        setBadgeState(badgeStep1, micGranted && phoneStateGranted, "1")
        setBadgeState(badgeStep2, accessibilityOn, "2")
        setBadgeState(badgeStep3, batteryUnrestricted, "3")

        val completed = listOf(micGranted && phoneStateGranted, accessibilityOn, batteryUnrestricted).count { it }
        textProgressCount.text = getString(R.string.progress_text, completed, 3)

        if (completed == 3) {
            textServiceStatus.text = getString(R.string.status_active)
            textServiceStatus.setTextColor(getColor(R.color.brand_success))
        } else {
            textServiceStatus.text = getString(R.string.status_idle, completed)
            textServiceStatus.setTextColor(getColor(R.color.text_hint))
        }

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
            Toast.makeText(this, R.string.toast_perm_granted, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return
        val deniedPermissions = permissions.filterIndexed { index, _ -> grantResults.getOrNull(index) != PackageManager.PERMISSION_GRANTED }
        if (deniedPermissions.isEmpty()) {
            Toast.makeText(this, R.string.toast_perm_granted, Toast.LENGTH_SHORT).show()
            refreshStepStatus()
            return
        }
        val permanentlyDenied = deniedPermissions.any { !ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
        if (permanentlyDenied) {
            Toast.makeText(this, R.string.toast_perm_denied, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", packageName, null) })
        }
    }

    private fun sendLogs() {
        val logFile = File(getExternalFilesDir(null), "logs/myrecord_log.txt")
        if (!logFile.exists()) {
            Toast.makeText(this, R.string.toast_no_logs, Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", logFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "MyRecord Bug Logs")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(intent, "Send logs to developer via..."))
    }
}
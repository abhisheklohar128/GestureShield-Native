package com.gestureshield

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnCamera: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusDesc: TextView
    private lateinit var tvStep1Status: TextView
    private lateinit var tvStep2Status: TextView
    private lateinit var tvStep3Status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnCamera = findViewById(R.id.btnCamera)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        tvStatusDesc = findViewById(R.id.tvStatusDesc)
        tvStep1Status = findViewById(R.id.tvStep1Status)
        tvStep2Status = findViewById(R.id.tvStep2Status)
        tvStep3Status = findViewById(R.id.tvStep3Status)

        btnCamera.setOnClickListener {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        }

        btnOverlay.setOnClickListener {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
            startActivityForResult(intent, 102)
        }

        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "GestureShield शोधा आणि Enable करा", Toast.LENGTH_LONG).show()
        }

        btnStart.setOnClickListener {
            if (allPermissionsGranted()) {
                val intent = Intent(this, GestureService::class.java)
                startForegroundService(intent)
                updateUI(true)
                Toast.makeText(this, "✅ GestureShield सुरू झाले! App minimize करा.", Toast.LENGTH_LONG).show()
            }
        }

        btnStop.setOnClickListener {
            val intent = Intent(this, GestureService::class.java)
            stopService(intent)
            updateUI(false)
        }

        updatePermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val overlayGranted = Settings.canDrawOverlays(this)
        val accessibilityGranted = isAccessibilityEnabled()

        // Camera
        if (cameraGranted) {
            tvStep1Status.text = "✅ Granted"
            tvStep1Status.setTextColor(0xFF00FF88.toInt())
            btnCamera.isEnabled = false
            btnCamera.text = "✓"
        } else {
            tvStep1Status.text = "❌ Required"
            tvStep1Status.setTextColor(0xFFFF4444.toInt())
        }

        // Overlay
        if (overlayGranted) {
            tvStep2Status.text = "✅ Granted"
            tvStep2Status.setTextColor(0xFF00FF88.toInt())
            btnOverlay.isEnabled = false
            btnOverlay.text = "✓"
        } else {
            tvStep2Status.text = "❌ Required"
            tvStep2Status.setTextColor(0xFFFF4444.toInt())
        }

        // Accessibility
        if (accessibilityGranted) {
            tvStep3Status.text = "✅ Enabled"
            tvStep3Status.setTextColor(0xFF00FF88.toInt())
            btnAccessibility.isEnabled = false
            btnAccessibility.text = "✓"
        } else {
            tvStep3Status.text = "❌ Enable करा"
            tvStep3Status.setTextColor(0xFFFF4444.toInt())
        }

        // Start button
        if (allPermissionsGranted()) {
            btnStart.isEnabled = true
            tvStatus.text = "✅ Ready!"
            tvStatusDesc.text = "सर्व permissions मिळाले. GestureShield सुरू करा."
        } else {
            btnStart.isEnabled = false
            tvStatus.text = "⚡ Setup करा"
            tvStatusDesc.text = "खालील सर्व steps complete करा"
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && Settings.canDrawOverlays(this)
                && isAccessibilityEnabled()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices?.contains(packageName) == true
    }

    private fun updateUI(running: Boolean) {
        if (running) {
            btnStart.visibility = android.view.View.GONE
            btnStop.visibility = android.view.View.VISIBLE
            tvStatus.text = "🟢 GestureShield चालू आहे"
            tvStatusDesc.text = "Background मध्ये gesture detect होत आहे. App minimize करा!"
        } else {
            btnStart.visibility = android.view.View.VISIBLE
            btnStop.visibility = android.view.View.GONE
            tvStatus.text = "⚡ Setup करा"
            tvStatusDesc.text = "खालील steps follow करा"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionStatus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        updatePermissionStatus()
    }
}

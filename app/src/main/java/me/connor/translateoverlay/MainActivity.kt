package me.connor.translateoverlay

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var overlayStatus: TextView
    private lateinit var accessibilityStatus: TextView
    private lateinit var requestOverlayBtn: Button
    private lateinit var requestAccessibilityBtn: Button
    private lateinit var overlay: FloatingOverlay

    private val REQUEST_MEDIA_PROJECTION = 42
    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        overlay = FloatingOverlay(this)
        overlayStatus = findViewById(R.id.overlayStatus)
        accessibilityStatus = findViewById(R.id.accessibilityStatus)
        requestOverlayBtn = findViewById(R.id.requestOverlayBtn)
        requestAccessibilityBtn = findViewById(R.id.requestAccessibilityBtn)

        requestOverlayBtn.setOnClickListener {
            // Launch system UI to grant Draw‑over‑other‑apps
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        requestAccessibilityBtn.setOnClickListener {
            // Open Accessibility settings so user can enable your service
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.startTranscriptionBtn).setOnClickListener {
            mediaProjectionManager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager
            startActivityForResult(
                mediaProjectionManager.createScreenCaptureIntent(),
                REQUEST_MEDIA_PROJECTION
            )
        }

        findViewById<Button>(R.id.showOverlayBtn).setOnClickListener {
            overlay.show(true)
        }
    }

    override fun onResume() {
        super.onResume()
        updateOverlayStatus()
        updateAccessibilityStatus()
    }

    private fun updateOverlayStatus() {
        val granted = Settings.canDrawOverlays(this)
        overlayStatus.text = "Overlay permission: " +
                if (granted) "ENABLED ✅" else "NOT ENABLED ❌"
    }

    private fun updateAccessibilityStatus() {
        val enabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED, 0
        ) == 1

        val services = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        val myService = "$packageName/${TextAccessibilityService::class.java.name}"
        val accEnabled = enabled && services
            .split(':')
            .any { it.equals(myService, ignoreCase = true) }

        accessibilityStatus.text = "Accessibility service: " +
                if (accEnabled) "ENABLED ✅" else "NOT ENABLED ❌"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            // Kick off the AudioCaptureService with the projection intent
            val svc = Intent(this, AudioCaptureService::class.java).apply {
                putExtra("code", resultCode)
                putExtra("data", data)
            }
            ContextCompat.startForegroundService(this, svc)
        }
    }
}
package me.connor.translateoverlay

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.AdapterView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.mlkit.nl.translate.TranslateLanguage

class MainActivity : AppCompatActivity() {
    private lateinit var overlayStatus: TextView
    private lateinit var accessibilityStatus: TextView
    private lateinit var requestOverlayBtn: MaterialButton
    private lateinit var requestAccessibilityBtn: MaterialButton
    private lateinit var overlay: FloatingOverlay
    private lateinit var sourceLanguageSpinner: MaterialAutoCompleteTextView
    private lateinit var sharedPreferences: SharedPreferences

    private val REQUEST_MEDIA_PROJECTION = 42
    private lateinit var mediaProjectionManager: MediaProjectionManager

    companion object {
        private const val PREFS_NAME = "TranslateOverlayPrefs"
        private const val PREF_SOURCE_LANG = "sourceLanguage"
        private const val DEFAULT_SOURCE_LANG = "zh"  // Chinese
        
        // Available source languages
        private val SOURCE_LANGUAGES = listOf(
            Language("zh", "Chinese (中文)"),
            Language("ko", "Korean (한국어)")
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_TranslateOverlay)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        overlay = FloatingOverlay(this)
        overlayStatus = findViewById(R.id.overlayStatus)
        accessibilityStatus = findViewById(R.id.accessibilityStatus)
        requestOverlayBtn = findViewById(R.id.requestOverlayBtn)
        requestAccessibilityBtn = findViewById(R.id.requestAccessibilityBtn)
        sourceLanguageSpinner = findViewById(R.id.sourceLanguageSpinner)

        setupLanguageSpinner()

        requestOverlayBtn.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        requestAccessibilityBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<MaterialButton>(R.id.startTranscriptionBtn).setOnClickListener {
            mediaProjectionManager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager
            startActivityForResult(
                mediaProjectionManager.createScreenCaptureIntent(),
                REQUEST_MEDIA_PROJECTION
            )
        }

        findViewById<MaterialButton>(R.id.showOverlayBtn).setOnClickListener {
            overlay.show(true)
        }
    }

    private fun setupLanguageSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            SOURCE_LANGUAGES.map { it.displayName }
        )

        sourceLanguageSpinner.setAdapter(adapter)

        // Set saved or default selection
        val savedSourceLang = sharedPreferences.getString(PREF_SOURCE_LANG, DEFAULT_SOURCE_LANG)
        val sourceIndex = SOURCE_LANGUAGES.indexOfFirst { it.code == savedSourceLang }
        if (sourceIndex >= 0) {
            sourceLanguageSpinner.setText(SOURCE_LANGUAGES[sourceIndex].displayName, false)
            sourceLanguageSpinner.listSelection = sourceIndex
        }

        // Save selection when changed
        sourceLanguageSpinner.setOnItemClickListener { parent, _, position, _ ->
            val selectedLanguage = SOURCE_LANGUAGES[position].code
            sharedPreferences.edit()
                .putString(PREF_SOURCE_LANG, selectedLanguage)
                .apply()
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
            val svc = Intent(this, AudioCaptureService::class.java).apply {
                putExtra("code", resultCode)
                putExtra("data", data)
                putExtra("sourceLanguage", sharedPreferences.getString(PREF_SOURCE_LANG, DEFAULT_SOURCE_LANG))
                putExtra("targetLanguage", "en")  // Always English
            }
            ContextCompat.startForegroundService(this, svc)
        }
    }

    private data class Language(val code: String, val displayName: String)
}
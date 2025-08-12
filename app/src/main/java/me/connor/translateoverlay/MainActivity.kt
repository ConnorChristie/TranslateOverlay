package me.connor.translateoverlay

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.AdapterView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.mlkit.nl.translate.TranslateLanguage

class MainActivity : AppCompatActivity() {
    
    
    private lateinit var requestAccessibilityBtn: MaterialButton
    private lateinit var overlay: FloatingOverlay
    private lateinit var sourceLanguageSpinner: MaterialAutoCompleteTextView
    private lateinit var targetLanguageSpinner: MaterialAutoCompleteTextView
    private lateinit var scrollView: androidx.core.widget.NestedScrollView
    private lateinit var sharedPreferences: SharedPreferences
    
    private lateinit var startTranscriptionBtn: MaterialButton
    private lateinit var statusText: TextView

    private lateinit var mediaProjectionManager: MediaProjectionManager

    // State tracking
    private var isOverlayShown = false
    private var isTranscriptionRunning = false

    // Broadcast receiver to detect when services stop
    private val serviceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                FloatingOverlay.ACTION_STOP_SERVICES -> {
                    isTranscriptionRunning = false
                    updateButtonStates()
                }
            }
        }
    }

    private val startActivityForResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val svc = Intent(this, AudioCaptureService::class.java).apply {
                putExtra("code", result.resultCode)
                putExtra("data", result.data)
                putExtra("sourceLanguage", sharedPreferences.getString(PREF_SOURCE_LANG, DEFAULT_SOURCE_LANG))
                putExtra("targetLanguage", sharedPreferences.getString(PREF_TARGET_LANG, DEFAULT_TARGET_LANG))
            }
            ContextCompat.startForegroundService(this, svc)
            isTranscriptionRunning = true
            updateButtonStates()
        }
    }

    companion object {
        private const val PREFS_NAME = "TranslateOverlayPrefs"
        private const val PREF_SOURCE_LANG = "sourceLanguage"
        private const val PREF_TARGET_LANG = "targetLanguage"
        private const val DEFAULT_SOURCE_LANG = TranslatorService.AUTO_DETECT
        private const val DEFAULT_TARGET_LANG = TranslateLanguage.ENGLISH
        
        // Available source languages - requested 5 plus Auto Detect option
        private val SOURCE_LANGUAGES = listOf(
            Language(TranslatorService.AUTO_DETECT, "Auto Detect"),
            Language(TranslateLanguage.CHINESE, "Chinese (Mandarin, 普通话)"),
            Language("yue", "Cantonese (粤语, 广东话)"),
            Language(TranslateLanguage.ENGLISH, "English"),
            Language(TranslateLanguage.JAPANESE, "Japanese (日本語)"),
            Language(TranslateLanguage.KOREAN, "Korean (한국어)")
        )

        // Available target languages - using ML Kit constants
        private val TARGET_LANGUAGES = listOf(
            Language(TranslateLanguage.ENGLISH, "English"),
            Language(TranslateLanguage.CHINESE, "Chinese (中文)"),
            Language(TranslateLanguage.KOREAN, "Korean (한국어)"),
            Language(TranslateLanguage.SPANISH, "Spanish (Español)"),
            Language(TranslateLanguage.FRENCH, "French (Français)"),
            Language(TranslateLanguage.GERMAN, "German (Deutsch)"),
            Language(TranslateLanguage.JAPANESE, "Japanese (日本語)"),
            Language(TranslateLanguage.RUSSIAN, "Russian (Русский)")
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_TranslateOverlay)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Handle system insets so content is never hidden under the navigation bar
        scrollView = findViewById(R.id.mainScrollView)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scrollView) { v, insets ->
            val bottomInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottomInset)
            insets
        }

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        

        overlay = FloatingOverlay(this)
        sourceLanguageSpinner = findViewById(R.id.sourceLanguageSpinner)
        targetLanguageSpinner = findViewById(R.id.targetLanguageSpinner)
        startTranscriptionBtn = findViewById(R.id.startTranscriptionBtn)
        statusText = findViewById(R.id.statusText)

        setupLanguageSpinners()
        

        // Streamlined: Auto-trigger overlay permission on first launch if not granted
        maybeRequestOverlayPermissionOnFirstLaunch()

        

        startTranscriptionBtn.setOnClickListener {
            if (!isTranscriptionRunning) {
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                    statusText.text = "Overlay permission required"
                    return@setOnClickListener
                }
                statusText.text = "Preparing…"
                mediaProjectionManager = getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager
                startActivityForResult.launch(
                    mediaProjectionManager.createScreenCaptureIntent()
                )
            } else {
                // Stop transcription
                val stopIntent = Intent(FloatingOverlay.ACTION_STOP_SERVICES)
                sendBroadcast(stopIntent)
                isTranscriptionRunning = false
                updateButtonStates()
            }
        }

        // 'Show Overlay' removed; overlay is managed by the service lifecycle

        // Register broadcast receiver for service status
        val filter = IntentFilter(FloatingOverlay.ACTION_STOP_SERVICES)
        ContextCompat.registerReceiver(
            this,
            serviceStatusReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun maybeRequestOverlayPermissionOnFirstLaunch() {
        val hasShownOverlayPrompt = sharedPreferences.getBoolean("hasShownOverlayPrompt", false)
        if (!Settings.canDrawOverlays(this) && !hasShownOverlayPrompt) {
            sharedPreferences.edit().putBoolean("hasShownOverlayPrompt", true).apply()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun setupLanguageSpinners() {
        // Source language spinner
        val sourceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            SOURCE_LANGUAGES.map { it.displayName }
        )
        sourceLanguageSpinner.setAdapter(sourceAdapter)

        // Set saved or default selection; fall back to English if saved is missing
        val savedSourceLang = sharedPreferences.getString(PREF_SOURCE_LANG, DEFAULT_SOURCE_LANG)
        val sourceIndex = SOURCE_LANGUAGES.indexOfFirst { it.code == savedSourceLang }
        val initialIndex = if (sourceIndex >= 0) sourceIndex else SOURCE_LANGUAGES.indexOfFirst { it.code == TranslateLanguage.ENGLISH }
        if (initialIndex >= 0) {
            sourceLanguageSpinner.setText(SOURCE_LANGUAGES[initialIndex].displayName, false)
            sourceLanguageSpinner.listSelection = initialIndex
        }

        // Save selection when changed
        sourceLanguageSpinner.setOnItemClickListener { _, _, position, _ ->
            val selectedLanguage = SOURCE_LANGUAGES[position].code
            sharedPreferences.edit()
                .putString(PREF_SOURCE_LANG, selectedLanguage)
                .apply()
        }

        // Target language spinner
        val targetAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            TARGET_LANGUAGES.map { it.displayName }
        )
        targetLanguageSpinner.setAdapter(targetAdapter)

        // Set saved or default selection
        val savedTargetLang = sharedPreferences.getString(PREF_TARGET_LANG, DEFAULT_TARGET_LANG)
        val targetIndex = TARGET_LANGUAGES.indexOfFirst { it.code == savedTargetLang }
        if (targetIndex >= 0) {
            targetLanguageSpinner.setText(TARGET_LANGUAGES[targetIndex].displayName, false)
            targetLanguageSpinner.listSelection = targetIndex
        }

        // Save selection when changed
        targetLanguageSpinner.setOnItemClickListener { _, _, position, _ ->
            val selectedLanguage = TARGET_LANGUAGES[position].code
            sharedPreferences.edit()
                .putString(PREF_TARGET_LANG, selectedLanguage)
                .apply()
        }
    }

    override fun onResume() {
        super.onResume()
        
        // Update overlay state based on actual overlay status
        isOverlayShown = overlay.isShown()
        // Check if transcription service is running
        isTranscriptionRunning = isServiceRunning(AudioCaptureService::class.java)
        updateButtonStates()
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    

    private fun updateButtonStates() {
        startTranscriptionBtn.text = if (isTranscriptionRunning) "Stop Translating" else "Start Translating"
        // Disable language pickers while running
        sourceLanguageSpinner.isEnabled = !isTranscriptionRunning
        targetLanguageSpinner.isEnabled = !isTranscriptionRunning
        statusText.text = if (isTranscriptionRunning) "Transcribing and translating…" else "Ready"
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(serviceStatusReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
    }

    private data class Language(val code: String, val displayName: String)
}
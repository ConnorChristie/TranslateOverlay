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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.mlkit.nl.translate.TranslateLanguage

class MainActivity : AppCompatActivity() {
    private lateinit var overlayStatus: TextView
    private lateinit var accessibilityStatus: TextView
    private lateinit var openAIStatus: TextView
    private lateinit var requestOverlayBtn: MaterialButton
    private lateinit var requestAccessibilityBtn: MaterialButton
    private lateinit var overlay: FloatingOverlay
    private lateinit var sourceLanguageSpinner: MaterialAutoCompleteTextView
    private lateinit var targetLanguageSpinner: MaterialAutoCompleteTextView
    private lateinit var useOpenAISwitch: SwitchMaterial
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var openAIConfigManager: OpenAIConfigManager

    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val startActivityForResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val svc = Intent(this, AudioCaptureService::class.java).apply {
                putExtra("code", result.resultCode)
                putExtra("data", result.data)
                putExtra("sourceLanguage", sharedPreferences.getString(PREF_SOURCE_LANG, DEFAULT_SOURCE_LANG))
                putExtra("targetLanguage", openAIConfigManager.getTargetLanguage())
                putExtra("useOpenAI", openAIConfigManager.getUseOpenAI())
                putExtra("openAIApiKey", openAIConfigManager.getApiKey())
                putExtra("openAIModel", openAIConfigManager.getModel())
                putExtra("openAITemperature", openAIConfigManager.getTemperature())
            }
            ContextCompat.startForegroundService(this, svc)
        }
    }

    companion object {
        private const val PREFS_NAME = "TranslateOverlayPrefs"
        private const val PREF_SOURCE_LANG = "sourceLanguage"
        private const val DEFAULT_SOURCE_LANG = "zh"  // Chinese
        
        // Available source languages
        private val SOURCE_LANGUAGES = listOf(
            Language("zh", "Chinese (中文)"),
            Language("ko", "Korean (한국어)"),
            Language("en", "English"),
            Language("es", "Spanish (Español)"),
            Language("fr", "French (Français)"),
            Language("de", "German (Deutsch)"),
            Language("ja", "Japanese (日本語)"),
            Language("ru", "Russian (Русский)")
        )

        // Available target languages
        private val TARGET_LANGUAGES = listOf(
            Language("en", "English"),
            Language("zh", "Chinese (中文)"),
            Language("ko", "Korean (한국어)"),
            Language("es", "Spanish (Español)"),
            Language("fr", "French (Français)"),
            Language("de", "German (Deutsch)"),
            Language("ja", "Japanese (日本語)"),
            Language("ru", "Russian (Русский)")
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_TranslateOverlay)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        openAIConfigManager = OpenAIConfigManager(this)

        overlay = FloatingOverlay(this)
        overlayStatus = findViewById(R.id.overlayStatus)
        accessibilityStatus = findViewById(R.id.accessibilityStatus)
        openAIStatus = findViewById(R.id.openAIStatus)
        requestOverlayBtn = findViewById(R.id.requestOverlayBtn)
        requestAccessibilityBtn = findViewById(R.id.requestAccessibilityBtn)
        sourceLanguageSpinner = findViewById(R.id.sourceLanguageSpinner)
        targetLanguageSpinner = findViewById(R.id.targetLanguageSpinner)
        useOpenAISwitch = findViewById(R.id.useOpenAISwitch)

        setupLanguageSpinners()
        setupOpenAIConfiguration()

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
            startActivityForResult.launch(
                mediaProjectionManager.createScreenCaptureIntent()
            )
        }

        findViewById<MaterialButton>(R.id.showOverlayBtn).setOnClickListener {
            overlay.show(true)
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

        // Target language spinner
        val targetAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            TARGET_LANGUAGES.map { it.displayName }
        )
        targetLanguageSpinner.setAdapter(targetAdapter)

        // Set saved or default selection
        val savedTargetLang = openAIConfigManager.getTargetLanguage()
        val targetIndex = TARGET_LANGUAGES.indexOfFirst { it.code == savedTargetLang }
        if (targetIndex >= 0) {
            targetLanguageSpinner.setText(TARGET_LANGUAGES[targetIndex].displayName, false)
            targetLanguageSpinner.listSelection = targetIndex
        }

        // Save selection when changed
        targetLanguageSpinner.setOnItemClickListener { parent, _, position, _ ->
            val selectedLanguage = TARGET_LANGUAGES[position].code
            openAIConfigManager.setTargetLanguage(selectedLanguage)
        }
    }

    private fun setupOpenAIConfiguration() {
        // Set initial switch state
        useOpenAISwitch.isChecked = openAIConfigManager.getUseOpenAI()

        // Handle switch changes
        useOpenAISwitch.setOnCheckedChangeListener { _, isChecked ->
            openAIConfigManager.setUseOpenAI(isChecked)
            updateOpenAIStatus()
        }

        updateOpenAIStatus()
    }

    private fun updateOpenAIStatus() {
        val useOpenAI = openAIConfigManager.getUseOpenAI()
        val hasValidKey = openAIConfigManager.hasValidApiKey()
        val apiKey = openAIConfigManager.getApiKey()
        
        val status = when {
            !useOpenAI -> "OpenAI API: Disabled"
            apiKey == null -> "OpenAI API: No API key configured ❌\nCreate local_config.properties file"
            apiKey == "YOUR_OPENAI_API_KEY_HERE" -> "OpenAI API: Please replace placeholder with your API key ❌"
            !hasValidKey -> "OpenAI API: Invalid API key format ❌\nKey should start with 'sk-'"
            else -> "OpenAI API: Configured ✅"
        }
        
        openAIStatus.text = status
    }

    override fun onResume() {
        super.onResume()
        updateOverlayStatus()
        updateAccessibilityStatus()
        updateOpenAIStatus()
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

    private data class Language(val code: String, val displayName: String)
}
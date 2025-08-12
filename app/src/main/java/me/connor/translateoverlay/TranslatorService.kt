package me.connor.translateoverlay

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier

class TranslatorService : Service() {

    companion object {
        private const val TAG = "TranslatorService"
        const val AUTO_DETECT = "auto"  // Special value for auto-detection

        // Use ML Kit's TranslateLanguage constants to ensure compatibility
        private val SUPPORTED_SOURCE_LANGUAGES = setOf(
            AUTO_DETECT,
            TranslateLanguage.ENGLISH,
            TranslateLanguage.CHINESE,
            TranslateLanguage.JAPANESE,
            TranslateLanguage.KOREAN,
            // ML Kit does not provide a Cantonese constant; use language tag "yue" where detected
            "yue"
        )
        private val SUPPORTED_TARGET_LANGUAGES = setOf(
            TranslateLanguage.ENGLISH,
            TranslateLanguage.SPANISH,
            TranslateLanguage.CHINESE,
            TranslateLanguage.FRENCH,
            TranslateLanguage.KOREAN,
            TranslateLanguage.GERMAN,
            TranslateLanguage.JAPANESE,
            TranslateLanguage.RUSSIAN
        )
    }

    // Binder given to clients
    private val binder = TranslationBinder()

    // ML Kit components
    private lateinit var translator: Translator
    private lateinit var languageIdentifier: LanguageIdentifier
    private var sourceLanguage: String = AUTO_DETECT
    private var targetLanguage: String = TranslateLanguage.ENGLISH  // Now dynamic
    private var lastDetectedLanguage: String? = null  // Set after first detection when using auto-detect

    // Language change receiver
    private val languageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FloatingOverlay.ACTION_LANGUAGE_CHANGED) {
                val newLang = intent.getStringExtra("language_code")
                if (newLang != null && newLang in SUPPORTED_SOURCE_LANGUAGES && newLang != sourceLanguage) {
                    updateLanguageAndTranslator(newSourceLang = newLang)
                }
            }
        }
    }

    /** Gives clients access to public methods in this service */
    inner class TranslationBinder : Binder() {
        fun getService(): TranslatorService = this@TranslatorService
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize language identifier
        languageIdentifier = LanguageIdentification.getClient()
        // Don't initialize the translator here. We wait until explicit languages are provided
        // or a language is detected when using auto-detect.

        // Register for language change broadcasts
        val filter = IntentFilter(FloatingOverlay.ACTION_LANGUAGE_CHANGED)
        ContextCompat.registerReceiver(
            this,
            languageChangeReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val newSourceLang = it.getStringExtra("sourceLanguage")
            val newTargetLang = it.getStringExtra("targetLanguage")
            
            // Log warnings for unsupported languages
            if (newSourceLang != null && newSourceLang !in SUPPORTED_SOURCE_LANGUAGES) {
                Log.w(TAG, "Unsupported source language: $newSourceLang")
            }
            if (newTargetLang != null && newTargetLang !in SUPPORTED_TARGET_LANGUAGES) {
                Log.w(TAG, "Unsupported target language: $newTargetLang")
            }
            
            updateLanguageAndTranslator(newSourceLang, newTargetLang)
        }
        return START_STICKY
    }

    private fun updateLanguageAndTranslator(
        newSourceLang: String? = null,
        newTargetLang: String? = null,
        forceInit: Boolean = false
    ) {
        var needsReinit = false
        
        // Update source language if valid
        if (newSourceLang != null && newSourceLang in SUPPORTED_SOURCE_LANGUAGES && newSourceLang != sourceLanguage) {
            sourceLanguage = newSourceLang
            needsReinit = true
        }
        
        // Update target language if valid
        if (newTargetLang != null && newTargetLang in SUPPORTED_TARGET_LANGUAGES && newTargetLang != targetLanguage) {
            targetLanguage = newTargetLang
            needsReinit = true
        }
        
        val shouldInit = forceInit || needsReinit || !::translator.isInitialized

        if (shouldInit) {
            val actualSourceLangOrNull = if (sourceLanguage == AUTO_DETECT) lastDetectedLanguage else sourceLanguage

            // If using auto-detect but we have not detected a language yet, defer initialization
            if (actualSourceLangOrNull == null) {
                Log.i(TAG, "Deferring translator init: auto-detect enabled and no language detected yet")
                return
            }

            // Close existing translator before creating new one
            if (::translator.isInitialized) {
                translator.close()
            }

            // Map Cantonese (yue) to Chinese for ML Kit translator
            val mappedSourceLang = if (actualSourceLangOrNull == "yue") TranslateLanguage.CHINESE else actualSourceLangOrNull
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(mappedSourceLang)
                .setTargetLanguage(targetLanguage)
                .build()
            translator = com.google.mlkit.nl.translate.Translation.getClient(options)

            val conditions = DownloadConditions.Builder()
                .requireWifi()
                .build()
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    Log.i(TAG, "Translation model downloaded successfully for $actualSourceLangOrNull -> $targetLanguage")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to download translation model for $actualSourceLangOrNull -> $targetLanguage", e)
                }
        }
    }

    private fun initializeTranslator() {
        updateLanguageAndTranslator(forceInit = true)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(languageChangeReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
        if (::translator.isInitialized) {
            translator.close()
        }
    }

    fun translateText(
        input: String,
        callback: (result: String?) -> Unit
    ) {
        // If configured source and target match (no translation needed)
        if (sourceLanguage != AUTO_DETECT && sourceLanguage == targetLanguage) {
            callback(input)
            return
        }

        // Identify per-segment language so we can skip translating
        // text that is already in the target language (e.g., English).
        languageIdentifier.identifyLanguage(input)
            .addOnSuccessListener { detectedLang ->
                val detected = if (detectedLang == "und") null else detectedLang

                // Short-circuit when the input is already in the target language
                if (detected != null && detected == targetLanguage) {
                    callback(input)
                    return@addOnSuccessListener
                }

                if (sourceLanguage == AUTO_DETECT) {
                    // Prepare translator for detected language
                    if (detected != null && detected in SUPPORTED_SOURCE_LANGUAGES) {
                        if (detected != lastDetectedLanguage) {
                            lastDetectedLanguage = detected
                            updateLanguageAndTranslator(forceInit = true)
                        }
                    } else {
                        Log.w(TAG, "Detected unsupported language: ${detected ?: "und"}")
                    }

                    if (!::translator.isInitialized) {
                        Log.w(TAG, "Translator not initialized yet; skipping translation this time")
                        callback(null)
                        return@addOnSuccessListener
                    }

                    translator.translate(input)
                        .addOnSuccessListener { translatedText -> callback(translatedText) }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Translation failed for ${lastDetectedLanguage ?: "unknown"} -> $targetLanguage", e)
                            callback(null)
                        }
                } else {
                    // Known fixed source language; translate unless already target
                    if (!::translator.isInitialized) {
                        updateLanguageAndTranslator(forceInit = true)
                        Log.w(TAG, "Translator not ready yet for $sourceLanguage -> $targetLanguage")
                        callback(null)
                        return@addOnSuccessListener
                    }
                    translator.translate(input)
                        .addOnSuccessListener { translatedText -> callback(translatedText) }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Translation failed for $sourceLanguage -> $targetLanguage", e)
                            callback(null)
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Language detection failed", e)
                if (::translator.isInitialized) {
                    translator.translate(input)
                        .addOnSuccessListener { translatedText -> callback(translatedText) }
                        .addOnFailureListener { err ->
                            Log.e(TAG, "Translation failed after detection error", err)
                            callback(null)
                        }
                } else {
                    callback(null)
                }
            }
    }
}

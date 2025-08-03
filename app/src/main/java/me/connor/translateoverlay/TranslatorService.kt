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
            AUTO_DETECT,  // Add auto-detect option
            TranslateLanguage.ENGLISH,
            TranslateLanguage.SPANISH,
            TranslateLanguage.CHINESE,
            TranslateLanguage.FRENCH,
            TranslateLanguage.KOREAN,
            TranslateLanguage.GERMAN,
            TranslateLanguage.JAPANESE,
            TranslateLanguage.RUSSIAN
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
    private var sourceLanguage: String = AUTO_DETECT  // Default to auto-detect
    private var targetLanguage: String = TranslateLanguage.ENGLISH  // Now dynamic
    private var lastDetectedLanguage: String = TranslateLanguage.ENGLISH  // Fallback language

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

    private fun updateLanguageAndTranslator(newSourceLang: String? = null, newTargetLang: String? = null) {
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
        
        // Only reinitialize if needed
        if (needsReinit) {
            val actualSourceLang = if (sourceLanguage == AUTO_DETECT) lastDetectedLanguage else sourceLanguage
            
            // Close existing translator before creating new one
            if (::translator.isInitialized) {
                translator.close()
            }
            
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(actualSourceLang)
                .setTargetLanguage(targetLanguage)
                .build()
            translator = com.google.mlkit.nl.translate.Translation.getClient(options)

            val conditions = DownloadConditions.Builder()
                .requireWifi()
                .build()
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    Log.i(TAG, "Translation model downloaded successfully for $actualSourceLang -> $targetLanguage")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to download translation model for $actualSourceLang -> $targetLanguage", e)
                }
        }
    }

    private fun initializeTranslator() {
        updateLanguageAndTranslator()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(languageChangeReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
        translator.close()
    }

    fun translateText(
        input: String,
        callback: (result: String?) -> Unit
    ) {
        // Don't translate if source and target are the same (except for auto-detect)
        if (sourceLanguage != AUTO_DETECT && sourceLanguage == targetLanguage) {
            callback(input)
            return
        }

        // If auto-detect is enabled, first identify the language
        if (sourceLanguage == AUTO_DETECT) {
            languageIdentifier.identifyLanguage(input)
                .addOnSuccessListener { detectedLang ->
                    if (detectedLang == targetLanguage) {
                        // If detected language is the same as target, no need to translate
                        callback(input)
                        return@addOnSuccessListener
                    }

                    // Update last detected language if it's supported
                    if (detectedLang in SUPPORTED_SOURCE_LANGUAGES) {
                        if (detectedLang != lastDetectedLanguage) {
                            lastDetectedLanguage = detectedLang
                            // Update translator with new detected language
                            updateLanguageAndTranslator()
                        }
                    } else {
                        // If detected language is not supported, use last known language
                        Log.w(TAG, "Detected unsupported language: $detectedLang, falling back to $lastDetectedLanguage")
                    }

                    // Perform translation
                    translator.translate(input)
                        .addOnSuccessListener { translatedText ->
                            callback(translatedText)
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Translation failed for $lastDetectedLanguage -> $targetLanguage", e)
                            callback(null)
                        }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Language detection failed", e)
                    // Fall back to last known language
                    translator.translate(input)
                        .addOnSuccessListener { translatedText ->
                            callback(translatedText)
                        }
                        .addOnFailureListener { e2 ->
                            Log.e(TAG, "Translation failed for $lastDetectedLanguage -> $targetLanguage", e2)
                            callback(null)
                        }
                }
        } else {
            // Regular translation with known source language
            translator.translate(input)
                .addOnSuccessListener { translatedText ->
                    callback(translatedText)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Translation failed for $sourceLanguage -> $targetLanguage", e)
                    callback(null)
                }
        }
    }
}

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

class TranslatorService : Service() {

    companion object {
        private const val TAG = "TranslatorService"
        private val SUPPORTED_SOURCE_LANGUAGES = setOf(
            "en", "es", "zh", "fr"  // Match strings.xml language_codes
        )
    }

    // Binder given to clients
    private val binder = TranslationBinder()

    // ML Kit translator
    private lateinit var translator: Translator
    private var sourceLanguage: String = "en"
    private val targetLanguage: String = "en"  // Always English

    // Language change receiver
    private val languageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FloatingOverlay.ACTION_LANGUAGE_CHANGED) {
                val newLang = intent.getStringExtra("language_code")
                if (newLang != null && newLang in SUPPORTED_SOURCE_LANGUAGES && newLang != sourceLanguage) {
                    sourceLanguage = newLang
                    translator.close()
                    initializeTranslator()
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
        initializeTranslator()
        
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
            if (newSourceLang != null && newSourceLang in SUPPORTED_SOURCE_LANGUAGES && newSourceLang != sourceLanguage) {
                sourceLanguage = newSourceLang
                translator.close()
                initializeTranslator()
            } else if (newSourceLang != null && newSourceLang !in SUPPORTED_SOURCE_LANGUAGES) {
                Log.w(TAG, "Unsupported source language: $newSourceLang")
            }
        }
        return START_STICKY
    }

    private fun initializeTranslator() {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(targetLanguage)
            .build()
        translator = com.google.mlkit.nl.translate.Translation.getClient(options)

        val conditions = DownloadConditions.Builder()
            .requireWifi()
            .build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                Log.i(TAG, "Translation model downloaded successfully")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to download translation model", e)
            }
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
        // Don't translate if source and target are the same
        if (sourceLanguage == targetLanguage) {
            callback(input)
            return
        }

        translator.translate(input)
            .addOnSuccessListener { translatedText ->
                callback(translatedText)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Translation failed", e)
                callback(null)
            }
    }
}

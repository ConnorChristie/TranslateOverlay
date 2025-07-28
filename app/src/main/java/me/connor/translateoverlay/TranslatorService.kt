package me.connor.translateoverlay

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions

class TranslatorService : Service() {

    // Binder given to clients
    private val binder = TranslationBinder()

    // ML Kit translator
    private lateinit var translator: Translator

    /** Gives clients access to public methods in this service */
    inner class TranslationBinder : Binder() {
        fun getService(): TranslatorService = this@TranslatorService
    }

    override fun onCreate() {
        super.onCreate()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.CHINESE)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
        translator = com.google.mlkit.nl.translate.Translation.getClient(options)

        val conditions = DownloadConditions.Builder()
            .requireWifi()
            .build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                // model ready
            }
            .addOnFailureListener { e ->
                // handle failure
            }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        // clean up ML Kit resources
        translator.close()
    }

    /**
     * Translate some text. Result (or null on error) is delivered via callback.
     */
    fun translateText(
        input: String,
        callback: (result: String?) -> Unit
    ) {
        translator.translate(input)
            .addOnSuccessListener { translatedText ->
                callback(translatedText)
            }
            .addOnFailureListener {
                callback(null)
            }
    }
}

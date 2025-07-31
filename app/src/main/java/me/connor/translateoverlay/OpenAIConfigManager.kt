package me.connor.translateoverlay

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class OpenAIConfigManager(context: Context) {
    companion object {
        private const val PREFS_NAME = "openai_config"
        private const val KEY_USE_OPENAI = "use_openai"
        private const val KEY_TARGET_LANGUAGE = "target_language"
        private const val KEY_MODEL = "model"
        private const val KEY_TEMPERATURE = "temperature"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val localConfigManager = LocalConfigManager(context)

    fun getApiKey(): String? {
        return localConfigManager.getOpenAIApiKey()
    }

    fun setUseOpenAI(useOpenAI: Boolean) {
        encryptedPrefs.edit().putBoolean(KEY_USE_OPENAI, useOpenAI).apply()
    }

    fun getUseOpenAI(): Boolean {
        return encryptedPrefs.getBoolean(KEY_USE_OPENAI, false)
    }

    fun setTargetLanguage(language: String) {
        encryptedPrefs.edit().putString(KEY_TARGET_LANGUAGE, language).apply()
    }

    fun getTargetLanguage(): String {
        return encryptedPrefs.getString(KEY_TARGET_LANGUAGE, "en") ?: "en"
    }

    fun setModel(model: String) {
        encryptedPrefs.edit().putString(KEY_MODEL, model).apply()
    }

    fun getModel(): String {
        return encryptedPrefs.getString(KEY_MODEL, localConfigManager.getOpenAIModel()) ?: localConfigManager.getOpenAIModel()
    }

    fun setTemperature(temperature: Float) {
        encryptedPrefs.edit().putFloat(KEY_TEMPERATURE, temperature).apply()
    }

    fun getTemperature(): Float {
        return encryptedPrefs.getFloat(KEY_TEMPERATURE, localConfigManager.getOpenAITemperature())
    }

    fun hasValidApiKey(): Boolean {
        return localConfigManager.hasValidApiKey()
    }

    fun getLocalConfigManager(): LocalConfigManager {
        return localConfigManager
    }
} 
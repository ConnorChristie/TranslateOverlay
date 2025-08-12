package me.connor.translateoverlay

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.util.Properties

class LocalConfigManager(private val context: Context) {
    companion object {
        private const val TAG = "LocalConfigManager"
        private const val CONFIG_FILE_NAME = "local_config.properties"
        private const val DEFAULT_CONFIG_FILE_NAME = "default_config.properties"
    }

    private var properties: Properties? = null

    init {
        loadConfig()
    }

    private fun loadConfig() {
        try {
            properties = Properties()

            // Try to load from assets/local_config.properties first (user's config)
            try {
                context.assets.open(CONFIG_FILE_NAME).use { inputStream ->
                    properties?.load(inputStream)
                }
                Log.d(TAG, "Loaded configuration from assets/local_config.properties")
            } catch (e: Exception) {
                // If not found, fall back to default_config.properties in assets
                try {
                    context.assets.open(DEFAULT_CONFIG_FILE_NAME).use { inputStream ->
                        properties?.load(inputStream)
                    }
                    Log.d(TAG, "Loaded configuration from assets/default_config.properties")
                } catch (ex: Exception) {
                    Log.w(TAG, "Could not load default config from assets, using empty properties", ex)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading configuration", e)
            properties = Properties()
        }
    }

    // No remote keys needed in local-only mode
} 
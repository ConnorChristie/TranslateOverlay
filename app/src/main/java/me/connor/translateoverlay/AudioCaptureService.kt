package me.connor.translateoverlay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.Executors

private const val TAG = "translate-overlay"

class AudioCaptureService : Service() {

    companion object {
        private const val MAX_BUFFER_LENGTH = 1000
        private const val TRANSLATION_INTERVAL_MS = 500L
    }

    private lateinit var overlay: FloatingOverlay
    private lateinit var recorder: AudioRecord
    private lateinit var mediaProjection: MediaProjection
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var running = true
    private var lastText = ""
    private var sourceLanguage = TranslateLanguage.CHINESE
    private var targetLanguage = TranslateLanguage.ENGLISH

    // STT engine abstraction
    private var sttEngine: me.connor.translateoverlay.stt.SpeechToTextEngine? = null
    private var useOpenAI = false
    private var openAIApiKey: String? = null
    private var openAIModel: String = "whisper-1"
    private var openAITemperature: Float = 0.0f

    private var translatorService: TranslatorService? = null
    private var isBound = false

    // Throttle for partial preview
    private var lastPreviewTimeMs: Long = 0
    private val PREVIEW_MIN_INTERVAL_MS = 900L

    // OpenAI sentence/partial buffering to mirror Sherpa flow
    private val openAIBuffer = StringBuilder()

    // Sherpa restart/backoff controls
    private var sherpaRestartAttempts = 0
    private var sherpaLastRestartMs = 0L
    private val SHERPA_RESTART_BACKOFF_MS = 2_000L
    private val SHERPA_RESTART_WINDOW_MS = 10_000L
    private val SHERPA_MAX_RESTARTS_IN_WINDOW = 3
    private var currentSampleRate: Int = 16_000

    private val stopServiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FloatingOverlay.ACTION_STOP_SERVICES) {
                stopSelf()
            }
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as TranslatorService.TranslationBinder
            translatorService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            translatorService = null
            isBound = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        overlay = FloatingOverlay(this)
        overlay.show(false)

        // Register for stop service broadcasts
        registerReceiver(
            stopServiceReceiver,
            IntentFilter(FloatingOverlay.ACTION_STOP_SERVICES),
            RECEIVER_NOT_EXPORTED
        )

        Intent(this, TranslatorService::class.java).also { intent ->
            bindService(intent, conn, BIND_AUTO_CREATE)
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())

        val resultCode = intent?.getIntExtra("code", -1) ?: return START_NOT_STICKY
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        
        // Get language settings
        sourceLanguage = intent.getStringExtra("sourceLanguage") ?: sourceLanguage
        targetLanguage = intent.getStringExtra("targetLanguage") ?: targetLanguage

        // Get OpenAI settings
        useOpenAI = intent.getBooleanExtra("useOpenAI", false)
        openAIApiKey = intent.getStringExtra("openAIApiKey")
        openAIModel = intent.getStringExtra("openAIModel") ?: "whisper-1"
        openAITemperature = intent.getFloatExtra("openAITemperature", 0.0f)

        // Pass language settings to translator service
        Intent(this, TranslatorService::class.java).also { translatorIntent ->
            translatorIntent.putExtra("sourceLanguage", sourceLanguage)
            translatorIntent.putExtra("targetLanguage", targetLanguage)
            startService(translatorIntent)
        }

        mediaProjection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(resultCode, data)!!

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val sampleRate = 16000
        currentSampleRate = sampleRate
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        recorder = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        if (useOpenAI && !openAIApiKey.isNullOrBlank()) initOpenAI(sampleRate) else initSherpa(sampleRate)
        
        return START_STICKY
    }

    private fun initOpenAI(sampleRate: Int) {
        try {
            Log.d(TAG, "Init OpenAI engine: model=$openAIModel, src=$sourceLanguage, temp=$openAITemperature")
            sttEngine = me.connor.translateoverlay.stt.OpenAISttEngine(
                apiKey = openAIApiKey!!,
                model = openAIModel,
                language = sourceLanguage,
                temperature = openAITemperature.toDouble()
            ).also { engine ->
                engine.start(object : me.connor.translateoverlay.stt.SpeechToTextEngine.Listener {
                    override fun onPartial(text: String) {
                        // Show preview based on buffered remainder + new partial
                        val combined = (openAIBuffer.toString() + " " + text).trim()
                        val (_, remainder) = TextProcessingUtils.splitCompletedSentences(combined)
                        Log.d(TAG, "OpenAI partial: combinedLen=${combined.length}, remainder='${remainder.take(40)}'")
                        if (remainder.isNotBlank()) throttledPreview(remainder)
                    }
                    override fun onFinal(text: String) {
                        // Buffer, then drain completed sentences
                        if (text.isNotBlank()) {
                            Log.d(TAG, "OpenAI final segment: '${text.take(80)}'")
                            openAIBuffer.append(text)
                            drainOpenAIBuffer()
                        }
                    }
                    override fun onError(message: String) {
                        Log.e(TAG, "OpenAI error: $message")
                    }
                    override fun onStatus(connected: Boolean) {
                        Log.i(TAG, "OpenAI connection status: $connected")
                    }
                })
            }

            recorder.startRecording()
            executor.execute { processAudioLoop(sampleRate) }
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI initialization error", e)
            initSherpa(sampleRate)
        }
    }

    private fun processAudioLoop(sampleRate: Int) {
        val frameSize = (sampleRate / 1000) * 20
        val buffer = ShortArray(if (frameSize > 0) frameSize else 320)
        while (running && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val read = try { recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) } catch (e: Exception) {
                Log.e(TAG, "AudioRecord read error", e)
                -1
            }
            if (read <= 0) continue
            val engine = sttEngine
            if (engine == null) {
                // Engine not ready yet; drop this frame to avoid backpressure
                continue
            }
            try {
                engine.acceptPcm16(buffer, read, sampleRate)
            } catch (e: Exception) {
                Log.e(TAG, "Engine acceptPcm16 error", e)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // OpenAI remainder preview (throttled)
    // ──────────────────────────────────────────────────────────────────────────────
    private fun throttledPreview(remainder: String) {
        val now = System.currentTimeMillis()
        if (now - lastPreviewTimeMs < PREVIEW_MIN_INTERVAL_MS) return
        lastPreviewTimeMs = now
        val preview = TextProcessingUtils.normalizeAndSpace(remainder).let {
            if (it.endsWith("…") || it.endsWith(".")) it else "$it …"
        }
        Log.d(TAG, "Preview: '${preview.take(80)}'")
        if (sourceLanguage != targetLanguage) {
            translatorService?.translateText(preview) { translated ->
                val safe = translated ?: preview
                overlay.updateText(TextProcessingUtils.normalizeAndSpace(safe) + " ")
            }
        } else {
            overlay.updateText(preview + " ")
        }
    }

    private fun initSherpa(sampleRate: Int) {
        val am = applicationContext.assets
        try {
            if ((am.list("sherpa") ?: emptyArray()).isEmpty()) {
                Log.e(TAG, "Missing sherpa model files in assets")
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Assets dir 'sherpa' not found")
            return
        }

        try {
            createSherpaEngine(sampleRate)
            recorder.startRecording()
            executor.execute { processAudioLoop(sampleRate) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Sherpa components", e)
        }
    }

    private fun createSherpaEngine(sampleRate: Int) {
        val am = applicationContext.assets
        sttEngine = me.connor.translateoverlay.stt.SherpaSttEngine(
            assets = am,
            defaultSampleRate = sampleRate,
            sourceLanguageCode = sourceLanguage
        ).also { engine ->
            engine.start(object : me.connor.translateoverlay.stt.SpeechToTextEngine.Listener {
                override fun onFinal(text: String) {
                    handleFinalSentence(text)
                }
                override fun onError(message: String) {
                    Log.e(TAG, "Sherpa error: $message")
                    scheduleSherpaRestart(reason = message)
                }
                override fun onStatus(connected: Boolean) {
                    Log.i(TAG, "Sherpa status: $connected")
                }
            })
        }
    }

    private fun scheduleSherpaRestart(reason: String) {
        val now = System.currentTimeMillis()
        if (now - sherpaLastRestartMs > SHERPA_RESTART_WINDOW_MS) {
            sherpaRestartAttempts = 0
        }
        if (now - sherpaLastRestartMs < SHERPA_RESTART_BACKOFF_MS) {
            Log.w(TAG, "Restart suppressed due to backoff ($reason)")
            return
        }
        if (sherpaRestartAttempts >= SHERPA_MAX_RESTARTS_IN_WINDOW) {
            Log.e(TAG, "Too many Sherpa restarts in window; cooling down")
            return
        }
        sherpaRestartAttempts += 1
        sherpaLastRestartMs = now
        executor.execute {
            try {
                Log.w(TAG, "Restarting Sherpa engine due to: $reason")
                sttEngine?.stop()
                sttEngine = null
                // Do not touch recorder or processing loop; just recreate engine
                createSherpaEngine(currentSampleRate)
            } catch (e: Exception) {
                Log.e(TAG, "Sherpa restart failed", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false

        if (isBound) {
            unbindService(conn)
            isBound = false
        }

        try {
            unregisterReceiver(stopServiceReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }

        // Remove the overlay and unregister its receivers
        overlay.remove()

        // Stop the translator service
        stopService(Intent(this, TranslatorService::class.java))
        
        // Clean up STT engine
        sttEngine?.stop()
        // Flush any remaining buffered OpenAI text
        if (openAIBuffer.isNotBlank()) {
            val remaining = openAIBuffer.toString().trim()
            if (remaining.isNotEmpty()) handleFinalSentence(remaining)
            openAIBuffer.clear()
        }
        
        // Clean up resources
        mediaProjection.stop()
        recorder.stop()
        recorder.release()
        executor.shutdown()
        scope.cancel()
    }

    override fun onBind(intent: Intent?) = null

    private fun createNotification(): Notification {
        val channelId = "sherpa_asr"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Sherpa-ASR Overlay", NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(if (useOpenAI) "OpenAI Realtime Service" else "Sherpa-ASR Service")
            .setContentText("Transcribing device audio…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    fun addSpaceAfterPunctuation(input: String): String {
        val regex = Regex("""([\p{Punct}&&[^']])(?!\s)""")
        val spaced = input.replace(regex) { match ->
            "${match.value} "
        }
        // Normalize whitespace to single spaces and trim
        return spaced.replace(Regex("\\s+"), " ").trim()
    }

    private fun handleFinalSentence(sentence: String) {
        if (sentence.isBlank()) return
        if (sourceLanguage != targetLanguage) {
            translatorService?.translateText(sentence) { translated ->
                val safeText = translated ?: sentence
                overlay.updateText(TextProcessingUtils.normalizeAndSpace(safeText) + " ")
                Log.i(TAG, "Transcription + translation: $sentence -> ${translated ?: "(fallback original)"}")
            }
        } else {
            overlay.updateText(TextProcessingUtils.normalizeAndSpace(sentence) + " ")
            Log.i(TAG, "Transcription: $sentence")
        }
    }

    private fun drainOpenAIBuffer() {
        if (openAIBuffer.isBlank()) return
        val text = openAIBuffer.toString()
        val (sentences, remainder) = TextProcessingUtils.splitCompletedSentences(text)
        for (sentence in sentences) {
            handleFinalSentence(sentence)
        }
        openAIBuffer.clear()
        openAIBuffer.append(remainder)
    }
}

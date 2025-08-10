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
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    // Sherpa components
    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null

    // OpenAI components
    private var openAIService: OpenAIRealtimeService? = null
    private var useOpenAI = false
    private var openAIApiKey: String? = null
    private var openAIModel: String = "whisper-1"
    private var openAITemperature: Float = 0.0f

    private var translatorService: TranslatorService? = null
    private var isBound = false

    // Sentence buffering for Sherpa path
    private val sherpaBuffer = StringBuilder()
    private var sherpaPrev: String = ""
    private val sentenceEndRegex = Regex("[.!?。！？]")

    // OpenAI sentence/partial display buffering (translated)
    private val openAIFinalizedDisplay = StringBuilder()

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

        if (useOpenAI && !openAIApiKey.isNullOrBlank()) {
            initOpenAI(sampleRate)
        } else {
            initSherpa(sampleRate)
        }
        
        return START_STICKY
    }

    private fun initOpenAI(sampleRate: Int) {
        try {
            openAIService = OpenAIRealtimeService()
            
            val config = OpenAIRealtimeService.TranscriptionConfig(
                apiKey = openAIApiKey!!,
                model = openAIModel,
                language = sourceLanguage,
                temperature = openAITemperature.toDouble()
            )

            scope.launch {
                openAIService!!.connectForTranscription(
                    config = config,
                    onTranscription = { transcription ->
                        if (transcription.isNotBlank()) {
                            // Buffer only up to completed sentence boundaries; keep remainder for next time.
                            // Enforce a max-latency partial emission of the remainder with ellipsis.
                            val (sentences, remainder) = TextProcessingUtils.splitCompletedSentences(transcription)

                            // Emit completed sentences immediately
                            for (sentence in sentences) {
                                if (sourceLanguage != targetLanguage) {
                                    translatorService?.translateText(sentence) { translated ->
                                        val safeText = translated ?: sentence
                                        // Ensure a trailing space to separate from next sentence
                                        overlay.updateText(TextProcessingUtils.normalizeAndSpace(safeText) + " ")
                                        Log.i(TAG, "OpenAI transcription + ML Kit translation: $sentence -> ${translated ?: "(fallback original)"}")
                                    }
                                } else {
                                    overlay.updateText(TextProcessingUtils.normalizeAndSpace(sentence) + " ")
                                    Log.i(TAG, "OpenAI transcription: $sentence")
                                }
                            }

                            // For remainder, show a throttled preview if it's getting long to avoid stalling
                            if (remainder.isNotBlank()) {
                                throttledPreview(remainder)
                            }
                        }
                    },
                    onError = { error ->
                        Log.e(TAG, "OpenAI error: $error")
                    },
                    onConnectionStatus = { connected ->
                        Log.i(TAG, "OpenAI connection status: $connected")
                    }
                )
            }

            recorder.startRecording()
            executor.execute { processAudioWithOpenAI(sampleRate) }
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI initialization error", e)
            // Fall back to Sherpa
            initSherpa(sampleRate)
        }
    }

    private fun processAudioWithOpenAI(sampleRate: Int) {
        val buffer = ShortArray(1024)
        
        while (running && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val bytesRead = recorder.read(buffer, 0, buffer.size)
            if (bytesRead > 0) {
                // Convert to byte array and send to OpenAI
                val byteBuffer = ByteBuffer.allocate(bytesRead * 2)
                byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until bytesRead) {
                    byteBuffer.putShort(buffer[i])
                }
                
                openAIService?.sendAudioChunk(byteBuffer.array())
            }
            
            Thread.sleep(20)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // OpenAI remainder preview (throttled)
    // ──────────────────────────────────────────────────────────────────────────────
    private var lastPreviewTimeMs: Long = 0
    private val PREVIEW_MIN_INTERVAL_MS = 900L
    private fun throttledPreview(remainder: String) {
        val now = System.currentTimeMillis()
        if (now - lastPreviewTimeMs < PREVIEW_MIN_INTERVAL_MS) return
        lastPreviewTimeMs = now
        val preview = TextProcessingUtils.normalizeAndSpace(remainder).let {
            if (it.endsWith("…") || it.endsWith(".")) it else "$it …"
        }
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
            // Initialize VAD
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "sherpa/silero_vad.int8.onnx",
                    threshold = 0.5F,
                    minSilenceDuration = 0.25F,
                    minSpeechDuration = 0.25F,
                    windowSize = 512,
                ),
                sampleRate = 16000,
                numThreads = 1,
                provider = "cpu",
            )
            vad = Vad(am, vadConfig)

            // Initialize offline recognizer
            val modelConfig = OfflineRecognizerConfig().apply {
                this.modelConfig = OfflineModelConfig().apply {
                    senseVoice = OfflineSenseVoiceModelConfig().apply {
                        model = "sherpa/sense-voice-model.onnx"
                        useInverseTextNormalization = true
                        language = "auto"
                    }
                    tokens = "sherpa/sense-voice-tokens.txt"
                    numThreads = 2
                    debug = false
                }
            }

            recognizer = OfflineRecognizer(am, modelConfig)
            recorder.startRecording()
            executor.execute { processAudioStreaming(sampleRate) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Sherpa components", e)
        }
    }

    private fun processAudioStreaming(sampleRate: Int) {
        val windowSize = 512 // samples, fixed window size for VAD
        val buffer = ShortArray(windowSize)

        while (running && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val bytesRead = recorder.read(buffer, 0, buffer.size)
            if (bytesRead > 0) {
                // Convert to float samples and feed to VAD
                val samples = FloatArray(bytesRead) { buffer[it] / 32768.0f }
                vad?.acceptWaveform(samples)

                // Process any complete VAD segments
                // The VAD system automatically includes some context before and after speech
                while (!vad?.empty()!!) {
                    val segment = vad?.front()
                    vad?.pop()

                    if (segment != null) {
                        val stream = recognizer?.createStream()
                        if (stream != null) {
                            stream.acceptWaveform(segment.samples, sampleRate)
                            recognizer?.decode(stream)

                            val result = recognizer?.getResult(stream)
                            var text = result?.text ?: ""

                            if (text.isNotBlank()) {
                                Log.i(TAG, "Original text: $text")
                                // Compute delta against previous to avoid repeats/rewrites
                                    val delta = extractDeltaIncremental(text)
                                if (delta.isNotBlank()) {
                                    sherpaBuffer.append(delta)
                                        drainSherpaBuffer()
                                }
                            }

                            stream.release()
                        }
                    }
                }
            }

            Thread.sleep(20)
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

        // Flush any remaining buffered text from Sherpa path
        if (sherpaBuffer.isNotBlank()) {
            val remaining = sherpaBuffer.toString().trim()
            if (remaining.isNotEmpty()) {
                translatorService?.translateText(remaining) { translated ->
                    val safeText = translated ?: remaining
                    overlay.updateText(TextProcessingUtils.normalizeAndSpace(safeText) + " ")
                }
            }
            sherpaBuffer.clear()
        }

        // Remove the overlay and unregister its receivers
        overlay.remove()

        // Stop the translator service
        stopService(Intent(this, TranslatorService::class.java))
        
        // Clean up OpenAI service
        openAIService?.cleanup()
        
        // Clean up resources
        mediaProjection.stop()
        recorder.stop()
        recorder.release()
        recognizer?.release()
        vad?.release()
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

    private fun splitIntoReadableChunks(text: String): List<String> {
        // Prefer splitting by sentence terminators; fallback to ~80 char chunks
        val sentences = text.split(Regex("""(?<=[.!?。！？])\s+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (sentences.size > 1) return sentences

        val maxLen = 80
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = (start + maxLen).coerceAtMost(text.length)
            var cut = text.lastIndexOf(' ', end - 1)
            if (cut <= start) cut = end
            chunks.add(text.substring(start, cut).trim())
            start = cut
        }
        return chunks.filter { it.isNotEmpty() }
    }

    private fun extractDeltaIncremental(now: String): String {
        if (sherpaPrev.isEmpty()) {
            sherpaPrev = now
            return now
        }
        val anchorChars = 32
        val anchor = sherpaPrev.takeLast(kotlin.math.min(anchorChars, sherpaPrev.length))
        val idx = now.lastIndexOf(anchor)
        val delta = if (idx >= 0) {
            now.substring(idx + anchor.length)
        } else {
            now
        }
        sherpaPrev = now
        return delta
    }

    private fun drainSherpaBuffer() {
        if (sherpaBuffer.isBlank()) return
        val text = sherpaBuffer.toString()
        val lastPunct = sentenceEndRegex.findAll(text).lastOrNull()
        if (lastPunct != null) {
            val (sentences, remainder) = TextProcessingUtils.splitCompletedSentences(text)
            for (sentence in sentences) {
                translatorService?.translateText(sentence) { translated ->
                    val safeText = translated ?: sentence
                    overlay.updateText(TextProcessingUtils.normalizeAndSpace(safeText) + " ")
                }
            }
            sherpaBuffer.clear()
            sherpaBuffer.append(remainder)
        }
    }
}

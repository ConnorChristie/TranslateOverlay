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
    private var recognizer: OnlineRecognizer? = null
    private var punct: OfflinePunctuation? = null

    // OpenAI components
    private var openAIService: OpenAIRealtimeService? = null
    private var useOpenAI = false
    private var openAIApiKey: String? = null
    private var openAIModel: String = "whisper-1"
    private var openAITemperature: Float = 0.0f

    private var translatorService: TranslatorService? = null
    private var isBound = false

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
                            if (sourceLanguage != targetLanguage) {
                                // Use ML Kit translation service since OpenAI transcription sessions don't support translation
                                translatorService?.translateText(transcription) { translated ->
                                    overlay.updateText(addSpaceAfterPunctuation(translated!!))
                                    Log.i(TAG, "OpenAI transcription + ML Kit translation: $transcription -> $translated")
                                }
                            } else {
                                overlay.updateText(addSpaceAfterPunctuation(transcription))
                                Log.i(TAG, "OpenAI transcription: $transcription")
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
            val config = OfflinePunctuationConfig(
                model = OfflinePunctuationModelConfig(
                    ctTransformer = "sherpa/punct-model.onnx",
                    numThreads = 1,
                    debug = false,
                    provider = "cpu",
                )
            )
            punct = OfflinePunctuation(am, config = config)

            val transducerCfg = OnlineTransducerModelConfig(
                encoder = "sherpa/encoder.onnx",
                decoder = "sherpa/decoder.onnx",
                joiner = "sherpa/joiner.onnx"
            )
            val modelCfg = OnlineModelConfig(
                transducer = transducerCfg,
                paraformer = OnlineParaformerModelConfig(),
                tokens = "sherpa/tokens.txt",
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = ""
            )
            val lmCfg = OnlineLMConfig(model = "", scale = 0.5f)
            val epRules = EndpointConfig(
                EndpointRule(false, 2.4f, 0f),
                EndpointRule(true, 1.2f, 0f),
                EndpointRule(false, 0f, 20f)
            )
            val onlineCfg = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate, 80),
                modelConfig = modelCfg,
                lmConfig = lmCfg,
                endpointConfig = epRules,
                enableEndpoint = true,
                maxActivePaths = 4,
                decodingMethod = "greedy_search",
                blankPenalty = 0f
            )

            recognizer = OnlineRecognizer(am, onlineCfg)
            recorder.startRecording()
            executor.execute { processAudioStreaming(sampleRate) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun processAudioStreaming(sampleRate: Int) {
        val stream = recognizer?.createStream() ?: return
        val intervalSecs = 1.4
        val bufferSize = (intervalSecs * sampleRate).toInt()
        val buffer = ShortArray(bufferSize)

        // ← holds "end" indexes (exclusive) of each sentence we've seen
        val sentenceBoundaries = mutableListOf<Int>()
        // ← avoid rescanning old text
        var lastProcessedIndex = 0
        // matches any punctuation char
        val sentenceEnd = Regex("[\\p{Punct}]")

        while (running && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val bytesRead = recorder.read(buffer, 0, buffer.size)
            if (bytesRead > 0) {
                // feed audio
                val samples = FloatArray(bytesRead) { buffer[it] / 32768.0f }
                stream.acceptWaveform(samples, sampleRate)
                while (recognizer?.isReady(stream) == true) recognizer?.decode(stream)

                // on endpoint, tack on a bit of silence to flush
                val isEndpoint = recognizer?.isEndpoint(stream) == true
                var rawText = recognizer?.getResult(stream)?.text ?: ""
                if (isEndpoint && recognizer?.config?.modelConfig?.paraformer?.encoder?.isNotBlank() == true) {
                    val tail = FloatArray((0.8 * sampleRate).toInt())
                    stream.acceptWaveform(tail, sampleRate)
                    while (recognizer?.isReady(stream) == true) recognizer?.decode(stream)
                    rawText = recognizer?.getResult(stream)?.text ?: ""
                }

                if (rawText.isNotBlank()) {
                    // add punctuation predictions
                    val punctuated = punct?.addPunctuation(rawText) ?: rawText

                    // scan only new chars for punctuation
                    for (i in lastProcessedIndex until punctuated.length) {
                        if (sentenceEnd.matches(punctuated[i].toString())) {
                            // record the boundary just *after* this punctuation
                            val boundaryPos = i + 1
                            sentenceBoundaries.add(boundaryPos)
                            lastProcessedIndex = boundaryPos

                            // decide where our two‑sentence window starts:
                            // if we've seen ≥3 sentences, start at boundary[n-3],
                            // else start at 0
                            val startIndex = if (sentenceBoundaries.size >= 3) {
                                sentenceBoundaries[sentenceBoundaries.size - 3]
                            } else {
                                0
                            }
                            val endIndex = boundaryPos

                            // extract exactly the previous + current sentence
                            val window = punctuated
                                .substring(startIndex, endIndex)
                                .trim()

                            if (window.isEmpty()) continue

                            // now translate or display the two-sentence block
                            // Always translate if source language is not English
                            if (sourceLanguage != targetLanguage) {
                                translatorService?.translateText(window) { translated ->
                                    overlay.updateText(addSpaceAfterPunctuation(translated!!))
                                    Log.i(TAG, "Translated text: $translated")
                                }
                            } else {
                                overlay.updateText(addSpaceAfterPunctuation(window))
                                Log.i(TAG, "Displayed text: $window")
                            }

                            // if it's a hard endpoint, reset recognizer state
                            if (isEndpoint) {
                                recognizer?.reset(stream)
                                sentenceBoundaries.clear()
                                lastProcessedIndex = 0
                            }
                        }
                    }
                }
            }

            Thread.sleep(20)
        }

        stream.release()
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
        
        // Clean up OpenAI service
        openAIService?.cleanup()
        
        // Clean up resources
        mediaProjection.stop()
        recorder.stop()
        recorder.release()
        recognizer?.release()
        punct?.release()
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
        return input.replace(regex) { match ->
            "${match.value} "
        }
    }
}

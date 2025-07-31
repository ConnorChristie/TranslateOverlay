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
    private lateinit var recognizer: OnlineRecognizer
    private lateinit var punct: OfflinePunctuation
    private val executor = Executors.newSingleThreadExecutor()
    private var running = true
    private var lastText = ""
    private var sourceLanguage = "zh"
    private var targetLanguage = "en"

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
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        
        // Get language settings
        sourceLanguage = intent.getStringExtra("sourceLanguage") ?: sourceLanguage
        targetLanguage = intent.getStringExtra("targetLanguage") ?: targetLanguage

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

        initSherpa(sampleRate)
        return START_STICKY
    }

    private fun initSherpa(sampleRate: Int) {
        val am = applicationContext.assets
        try {
            if ((am.list("sherpa") ?: emptyArray()).isEmpty()) {
                overlay.updateText("Missing sherpa model files in assets")
                return
            }
        } catch (e: Exception) {
            overlay.updateText("Assets dir 'sherpa' not found")
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
            overlay.updateText("Listening…")
            executor.execute { processAudioStreaming(sampleRate) }
        } catch (e: Exception) {
            overlay.updateText("Initialization error: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun processAudioStreaming(sampleRate: Int) {
        val stream = recognizer.createStream()
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
                while (recognizer.isReady(stream)) recognizer.decode(stream)

                // on endpoint, tack on a bit of silence to flush
                val isEndpoint = recognizer.isEndpoint(stream)
                var rawText = recognizer.getResult(stream).text
                if (isEndpoint && recognizer.config.modelConfig.paraformer.encoder.isNotBlank()) {
                    val tail = FloatArray((0.8 * sampleRate).toInt())
                    stream.acceptWaveform(tail, sampleRate)
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    rawText = recognizer.getResult(stream).text
                }

                if (rawText.isNotBlank()) {
                    // add punctuation predictions
                    val punctuated = punct.addPunctuation(rawText)

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
                            if (sourceLanguage != "en") {
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
                                recognizer.reset(stream)
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
        
        // Clean up resources
        mediaProjection.stop()
        recorder.stop()
        recorder.release()
        recognizer.release()
        punct.release()
        executor.shutdown()
    }

    override fun onBind(intent: Intent?) = null

    private fun createNotification(): Notification {
        val channelId = "sherpa_asr"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Sherpa-ASR Overlay", NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sherpa-ASR Service")
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

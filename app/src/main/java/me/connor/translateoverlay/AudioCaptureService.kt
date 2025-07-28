package me.connor.translateoverlay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
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
    private val executor = Executors.newSingleThreadExecutor()
    private var running = true
    private lateinit var translator: Translator

    private var lastText = ""
    private var lastTranslationTimeMs = 0L

    override fun onCreate() {
        super.onCreate()
        overlay = FloatingOverlay(this)
        overlay.show()

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.CHINESE)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
        translator = Translation.getClient(options)
        translator.downloadModelIfNeeded()
            .addOnSuccessListener { /* ready */ }
            .addOnFailureListener { overlay.updateText("Translator init failed") }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())

        val resultCode = intent?.getIntExtra("code", -1) ?: return START_NOT_STICKY
        val data       = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
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
            overlay.updateText("Initializing online streaming model…")

            val transducerCfg = OnlineTransducerModelConfig(
                encoder = "sherpa/encoder.onnx",
                decoder = "sherpa/decoder.onnx",
                joiner  = "sherpa/joiner.onnx"
            )
            val modelCfg = OnlineModelConfig(
                transducer = transducerCfg,
                paraformer = OnlineParaformerModelConfig(),
                tokens     = "sherpa/tokens.txt",
                numThreads = 2,
                debug      = false,
                provider   = "cpu",
                modelType  = ""
            )
            val lmCfg = OnlineLMConfig(model = "", scale = 0.5f)
            val epRules = EndpointConfig(
                EndpointRule(false, 2.4f, 0f),
                EndpointRule(true,  1.2f, 0f),
                EndpointRule(false, 0f,   20f)
            )
            val onlineCfg = OnlineRecognizerConfig(
                featConfig     = FeatureConfig(sampleRate, 80),
                modelConfig    = modelCfg,
                lmConfig       = lmCfg,
                endpointConfig = epRules,
                enableEndpoint = true,
                maxActivePaths = 4,
                decodingMethod = "greedy_search",
                blankPenalty   = 0f
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

        val interval = 0.1 // i.e., 100 ms
        val bufferSize = (interval * sampleRate).toInt() // in samples
        val buffer = ShortArray(bufferSize)

        while (running && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val bytesRead = recorder.read(buffer, 0, buffer.size)
            if (bytesRead > 0) {
                val samples = FloatArray(bytesRead) { buffer[it] / 32768.0f }
                stream.acceptWaveform(samples, sampleRate)
                while (recognizer.isReady(stream)) recognizer.decode(stream)

                val isEndpoint = recognizer.isEndpoint(stream)
                var text = recognizer.getResult(stream).text

                if (isEndpoint && recognizer.config.modelConfig.paraformer.encoder.isNotBlank()) {
                    val tail = FloatArray((0.8 * sampleRate).toInt())
                    stream.acceptWaveform(tail, sampleRate)
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    text = recognizer.getResult(stream).text
                }

                val displayText = "$lastText$text".takeLast(500)

                if (isEndpoint) {
                    recognizer.reset(stream)
                    lastText = displayText
                }

                Log.i(TAG, "Recognized: $text")
                runOnMain { overlay.updateText(displayText) }

//                if (displayText != lastDisplay) {
//                    val now = SystemClock.elapsedRealtime()
//                    if (now - lastTranslationTimeMs >= TRANSLATION_INTERVAL_MS) {
//                        lastTranslationTimeMs = now
//
//                        // Extract last sentence
//                        val parts = displayText.split(Regex("(?<=[。！？?.!])\\s*"))
//                        val prefix = parts.dropLast(1).joinToString("")
//                        val lastSentence = parts.last().trim()
//
//                        val containsChinese = lastSentence.any { ch -> ch.code in 0x4E00..0x9FFF }
//                        if (containsChinese) {
//                            translator.translate(lastSentence)
//                                .addOnSuccessListener { translated ->
//                                    runOnMain { overlay.updateText(prefix + translated) }
//                                }
//                                .addOnFailureListener {
//                                    runOnMain { overlay.updateText(displayText) }
//                                }
//                        } else {
//                            runOnMain { overlay.updateText(displayText) }
//                        }
//                    }
//                }
            }
            Thread.sleep(20)
        }
        stream.release()
    }

    private fun convertBytesToFloat(buffer: ByteArray, bytesRead: Int): FloatArray {
        val sampleCount = bytesRead / 2
        val floats = FloatArray(sampleCount)
        val bb = ByteBuffer.wrap(buffer, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until sampleCount) {
            val sample = bb.short.toInt()
            floats[i] = sample / 32768.0f
        }

        return floats
    }

    private fun runOnMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
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
}

package me.connor.translateoverlay.stt

import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.connor.translateoverlay.TextProcessingUtils

class SherpaSttEngine(
    private val assets: AssetManager,
    private val defaultSampleRate: Int = 16000,
    private val sourceLanguageCode: String = "auto"
) : SpeechToTextEngine {

    companion object { private const val TAG = "SherpaSttEngine" }

    private var listener: SpeechToTextEngine.Listener? = null
    private var vad: Vad? = null
    private var recognizer: OfflineRecognizer? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val buffer = StringBuilder()
    private var floatBuffer = FloatArray(0)

    // Self-recovery / lag handling
    private val initLock = Any()
    private var stopped = false
    private var lagDebtMs: Long = 0
    private var lastRestartMs: Long = 0
    private val lagDropThresholdMs: Long = 3_000 // If >3s behind, dump/reset pipeline
    private val restartBackoffMs: Long = 1_000   // Avoid thrashing restarts

    private fun mapToSenseVoiceLang(mlkitCode: String?): String {
        val code = (mlkitCode ?: "").lowercase()
        return when (code) {
            "en" -> "en"
            "zh" -> "zh"
            "ko" -> "ko"
            "ja" -> "ja"
            "auto", "" -> "auto"
            else -> "auto"
        }
    }

    override fun start(listener: SpeechToTextEngine.Listener) {
        this.listener = listener
        stopped = false
        try {
            initializePipeline()
            listener.onStatus(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Sherpa", e)
            listener.onError("Sherpa init: ${e.message}")
        }
    }

    private fun initializePipeline() {
        synchronized(initLock) {
            // Build VAD
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "sherpa/silero_vad.int8.onnx",
                    threshold = 0.5F,
                    minSilenceDuration = 0.25F,
                    minSpeechDuration = 0.25F,
                    windowSize = 512,
                ),
                sampleRate = defaultSampleRate,
                numThreads = 1,
                provider = "cpu",
            )
            vad?.release(); vad = null
            vad = Vad(assets, vadConfig)

            // Build recognizer
            val modelConfig = OfflineRecognizerConfig().apply {
                this.modelConfig = OfflineModelConfig().apply {
                    senseVoice = OfflineSenseVoiceModelConfig().apply {
                        model = "sherpa/sense-voice-model.onnx"
                        useInverseTextNormalization = true
                        language = mapToSenseVoiceLang(sourceLanguageCode)
                    }
                    tokens = "sherpa/sense-voice-tokens.txt"
                    numThreads = 2
                    debug = false
                }
            }
            recognizer?.release(); recognizer = null
            recognizer = OfflineRecognizer(assets, modelConfig)
            lagDebtMs = 0
        }
    }

    override fun acceptPcm16(samples: ShortArray, length: Int, sampleRate: Int) {
        if (length <= 0) return
        val localVad = vad ?: return
        val localRecognizer = recognizer ?: return

        if (floatBuffer.size < length) floatBuffer = FloatArray(length)
        for (i in 0 until length) {
            floatBuffer[i] = samples[i] / 32768.0f
        }

        val startMs = SystemClock.elapsedRealtime()
        try {
            localVad.acceptWaveform(floatBuffer)
            while (!localVad.empty()) {
                val seg = try { localVad.front() } catch (e: Exception) {
                    Log.e(TAG, "VAD front() failed", e)
                    break
                }
                localVad.pop()
                var stream: OfflineStream? = null
                try {
                    stream = localRecognizer.createStream()
                    stream.acceptWaveform(seg.samples, sampleRate)
                    localRecognizer.decode(stream)
                    val text = localRecognizer.getResult(stream).text
                    if (text.isNotBlank()) handleText(text)
                } catch (e: Exception) {
                    Log.e(TAG, "Decode error; attempting soft reset", e)
                    maybeResetPipeline(reason = "decode error: ${e.message}")
                    break // avoid using possibly released recognizer/vad in this loop
                } finally {
                    try { stream?.release() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sherpa processing error; attempting soft reset", e)
            maybeResetPipeline(reason = "processing error: ${e.message}")
        } finally {
            val elapsedMs = SystemClock.elapsedRealtime() - startMs
            val audioMs = (length * 1000L) / (if (sampleRate <= 0) defaultSampleRate else sampleRate)
            val delta = elapsedMs - audioMs
            if (delta > 0) lagDebtMs += delta else if (lagDebtMs > 0) lagDebtMs = kotlin.math.max(0, lagDebtMs + delta)

            if (lagDebtMs > lagDropThresholdMs) {
                Log.w(TAG, "Pipeline behind by ${lagDebtMs}ms; dropping state and restarting")
                maybeResetPipeline(reason = "lag=${lagDebtMs}ms")
                lagDebtMs = 0
            }
        }
    }

    private fun handleText(text: String) {
        // Append and emit completed sentences
        buffer.append(text)
        val (sentences, remainder) = TextProcessingUtils.splitCompletedSentences(buffer.toString())
        sentences.forEach { listener?.onFinal(it) }
        buffer.clear()
        buffer.append(remainder)
    }

    override fun stop() {
        stopped = true
        try {
            // Inject a short tail of silence to force the VAD to flush any pending speech
            val localVad = vad
            val localRecognizer = recognizer
            if (localVad != null && localRecognizer != null) {
                val tailSilenceMs = 400 // 0.4s trailing silence
                val silenceSamples = (defaultSampleRate * tailSilenceMs) / 1000
                if (silenceSamples > 0) {
                    val zeros = FloatArray(silenceSamples) { 0f }
                    try {
                        localVad.acceptWaveform(zeros)
                        while (!localVad.empty()) {
                            val seg = localVad.front()
                            localVad.pop()
                            val stream = localRecognizer.createStream()
                            stream.acceptWaveform(seg.samples, defaultSampleRate)
                            localRecognizer.decode(stream)
                            val text = localRecognizer.getResult(stream).text
                            stream.release()
                            if (text.isNotBlank()) handleText(text)
                        }
                    } catch (_: Exception) {
                        // best-effort drain
                    }
                }
            }
            // Flush remainder as a final line if present
            if (buffer.isNotBlank()) listener?.onFinal(buffer.toString().trim())
        } finally {
            try { recognizer?.release() } catch (_: Exception) {}
            recognizer = null
            try { vad?.release() } catch (_: Exception) {}
            vad = null
        }
        scope.cancel()
        listener = null
    }

    private fun maybeResetPipeline(reason: String) {
        if (stopped) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRestartMs < restartBackoffMs) return
        lastRestartMs = now
        Log.w(TAG, "Resetting Sherpa pipeline due to $reason")
        listener?.onStatus(false)
        // If we're too far behind, dump any partial buffered text to avoid stale output
        if (reason.startsWith("lag")) {
            buffer.clear()
        }
        try {
            initializePipeline()
            listener?.onStatus(true)
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline re-init failed", e)
            listener?.onError("Sherpa restart failed: ${e.message}")
        }
    }
}

package me.connor.translateoverlay.stt

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class SherpaSttEngine(
    private val assets: AssetManager,
    private val defaultSampleRate: Int = 16000
) : SpeechToTextEngine {

    companion object { private const val TAG = "SherpaSttEngine" }

    private var listener: SpeechToTextEngine.Listener? = null
    private var vad: Vad? = null
    private var recognizer: OfflineRecognizer? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Buffer and regex for sentence extraction
    private val sentenceEndRegex = Regex("[.!?。！？]")
    private val buffer = StringBuilder()

    override fun start(listener: SpeechToTextEngine.Listener) {
        this.listener = listener
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
                sampleRate = defaultSampleRate,
                numThreads = 1,
                provider = "cpu",
            )
            vad = Vad(assets, vadConfig)

            // Initialize recognizer
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
            recognizer = OfflineRecognizer(assets, modelConfig)
            listener.onStatus(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Sherpa", e)
            listener.onError("Sherpa init: ${e.message}")
        }
    }

    override fun acceptPcm16(samples: ShortArray, length: Int, sampleRate: Int) {
        if (length <= 0) return
        val localVad = vad ?: return
        val localRecognizer = recognizer ?: return
        // Convert to floats and feed VAD
        val floats = FloatArray(length) { samples[it] / 32768.0f }
        try {
            localVad.acceptWaveform(floats)
            while (!localVad.empty()) {
                val seg = localVad.front()
                localVad.pop()
                if (seg != null) {
                    val stream = localRecognizer.createStream()
                    stream.acceptWaveform(seg.samples, sampleRate)
                    localRecognizer.decode(stream)
                    val text = localRecognizer.getResult(stream)?.text ?: ""
                    stream.release()
                    if (text.isNotBlank()) handleText(text)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sherpa processing error", e)
        }
    }

    private fun handleText(text: String) {
        // Append and emit completed sentences
        buffer.append(text)
        val content = buffer.toString()
        val lastPunct = sentenceEndRegex.findAll(content).lastOrNull()
        if (lastPunct != null) {
            val boundary = lastPunct.range.last + 1
            val ready = content.substring(0, boundary)
            val remainder = content.substring(boundary)
            // Split into sentences and notify
            ready.split(Regex("""(?<=[\\.\\!\\?。！？])\s+""")).map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { listener?.onFinal(it) }
            buffer.clear()
            buffer.append(remainder)
        }
    }

    override fun stop() {
        // Flush remainder as a final line if present
        if (buffer.isNotBlank()) listener?.onFinal(buffer.toString().trim())
        recognizer?.release(); recognizer = null
        vad?.release(); vad = null
        scope.cancel()
        listener = null
    }
}

package me.connor.translateoverlay.stt

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.connor.translateoverlay.OpenAIRealtimeService

class OpenAISttEngine(
    private val apiKey: String,
    private val model: String,
    private val language: String?,
    private val temperature: Double
) : SpeechToTextEngine {

    private var listener: SpeechToTextEngine.Listener? = null
    private var service: OpenAIRealtimeService? = null
    private var lastLogMs: Long = 0
    var debug: Boolean = true
    private val TAG = "OpenAISttEngine"

    override fun start(listener: SpeechToTextEngine.Listener) {
        this.listener = listener
        val svc = OpenAIRealtimeService()
        service = svc
        val cfg = OpenAIRealtimeService.TranscriptionConfig(
            apiKey = apiKey,
            model = model,
            language = language,
            temperature = temperature
        )

        if (debug) Log.d(TAG, "Starting OpenAI STT (model=$model, lang=${language ?: "null"}, temp=$temperature)")
        // Connect and forward callbacks from a coroutine scope
        CoroutineScope(Dispatchers.IO).launch {
            svc.connectForTranscription(
                config = cfg,
                onTranscription = { text -> listener.onFinal(text) },
                onPartial = { partial -> if (partial.isNotBlank()) listener.onPartial(partial) },
                onError = { err -> listener.onError(err) },
                onConnectionStatus = { ok -> listener.onStatus(ok) }
            )
        }
    }

    override fun acceptPcm16(samples: ShortArray, length: Int, sampleRate: Int) {
        val svc = service ?: return
        if (length <= 0) return
        // Realtime API expects pcm16 at 24kHz mono. Resample from 16kHz → 24kHz.
        val resampled: ShortArray = if (sampleRate == 24000) {
            if (length == samples.size) samples else samples.copyOf(length)
        } else {
            resampleLinear(samples, length, sampleRate, 24000)
        }
        val byteBuffer = java.nio.ByteBuffer.allocate(resampled.size * 2)
        byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in resampled.indices) byteBuffer.putShort(resampled[i])
        svc.sendAudioChunk(byteBuffer.array())
        val now = System.currentTimeMillis()
        if (debug && now - lastLogMs > 1000) {
            Log.d(TAG, "Sent audio chunk (${length} samples @ ${sampleRate}Hz)")
            lastLogMs = now
        }
    }

    override fun stop() {
        if (debug) Log.d(TAG, "Stopping OpenAI STT")
        service?.cleanup()
        service = null
        listener = null
    }

    private fun resampleLinear(
        input: ShortArray,
        length: Int,
        inRate: Int,
        outRate: Int
    ): ShortArray {
        if (inRate == outRate) return input.copyOf(length)
        val factor = outRate.toDouble() / inRate.toDouble()
        val outLen = kotlin.math.max(1, kotlin.math.floor(length * factor).toInt())
        val out = ShortArray(outLen)
        for (j in 0 until outLen) {
            val x = j / factor
            val i0 = kotlin.math.floor(x).toInt().coerceIn(0, length - 1)
            val i1 = (i0 + 1).coerceAtMost(length - 1)
            val frac = (x - i0)
            val s0 = input[i0].toInt()
            val s1 = input[i1].toInt()
            val v = s0 + ((s1 - s0) * frac)
            val clamped = v.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
            out[j] = clamped.toInt().toShort()
        }
        return out
    }
}

package me.connor.translateoverlay.stt

interface SpeechToTextEngine {
    interface Listener {
        fun onPartial(text: String) {}
        fun onFinal(text: String) {}
        fun onError(message: String) {}
        fun onStatus(connected: Boolean) {}
    }

    fun start(listener: Listener)
    fun acceptPcm16(samples: ShortArray, length: Int, sampleRate: Int)
    fun stop()
}


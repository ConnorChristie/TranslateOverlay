package me.connor.translateoverlay

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import java.io.IOException

import java.util.concurrent.atomic.AtomicBoolean

class OpenAIRealtimeService {
    companion object {
        private const val TAG = "OpenAIRealtimeService"
        private const val OPENAI_API_URL = "https://api.openai.com/v1/realtime/transcription_sessions"
        private const val OPENAI_REALTIME_URL = "wss://api.openai.com/v1/realtime"
        private const val MAX_CONTEXT_SENTENCES = 2  // Keep last 2 sentences for context
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS) // No timeout for WebSocket
        .build()
    
    private val gson = Gson()
    private val isConnected = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var onTranscriptionReceived: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onConnectionStatusChanged: ((Boolean) -> Unit)? = null
    private var ephemeralToken: String? = null
    
    // Context management
    private val recentSentences = mutableListOf<String>()
    private val sentenceEndRegex = Regex("[.!?。！？]")
    private var currentDeltaText = StringBuilder()
    private var lastProcessedIndex = 0

    data class TranscriptionConfig(
        val apiKey: String,
        val model: String = "whisper-1",
        val language: String? = null,
        val temperature: Double = 0.0
    )

    private val webSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connection opened")
            isConnected.set(true)
            onConnectionStatusChanged?.invoke(true)

            // Send initial session configuration
            val sessionConfig = JsonObject().apply {
                addProperty("type", "transcription_session.update")
                add("session", JsonObject().apply {
                    addProperty("input_audio_format", "pcm16")
                    add("input_audio_transcription", JsonObject().apply {
                        addProperty("model", "gpt-4o-mini-transcribe")
                        addProperty("prompt", "")
                    })
                    add("turn_detection", JsonObject().apply {
                        addProperty("type", "server_vad")
                        addProperty("threshold", 0.5)
                        addProperty("prefix_padding_ms", 300)
                        addProperty("silence_duration_ms", 400)
                    })
                    add("input_audio_noise_reduction", JsonObject().apply {
                        addProperty("type", "near_field")
                    })
                })
            }
            webSocket.send(gson.toJson(sessionConfig))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Received message: $text")
            try {
                val jsonObject = gson.fromJson(text, JsonObject::class.java)
                val type = jsonObject.get("type")?.asString
                
                when (type) {
                    "transcription_session.created" -> {
                        Log.d(TAG, "Transcription session created")
                    }
                    "transcription_session.updated" -> {
                        Log.d(TAG, "Transcription session updated")
                    }
                    "conversation.item.input_audio_transcription.delta" -> {
                        val deltaText = jsonObject.get("delta")?.asString
                        if (deltaText != null && deltaText.isNotBlank()) {
                            processDeltaTranscript(deltaText)
                        }
                    }
                    "conversation.item.input_audio_transcription.completed" -> {
                        // val transcript = jsonObject.get("transcript")?.asString
                        // if (transcript != null) {
                        //     processCompletedTranscript(transcript)
                        // }
                    }
                    "input_audio_buffer.speech_started" -> {
                        Log.d(TAG, "Speech started")
                    }
                    "input_audio_buffer.speech_stopped" -> {
                        Log.d(TAG, "Speech stopped")
                        // Flush any remaining partial to reduce dropped words
                        if (currentDeltaText.isNotBlank()) {
                            processCompletedTranscript(currentDeltaText.toString())
                        }
                        // Clear all buffers for the next turn
                        currentDeltaText.clear()
                        recentSentences.clear()
                        lastProcessedIndex = 0
                    }
                    "input_audio_buffer.committed" -> {
                        Log.d(TAG, "Audio buffer committed")
                    }
                    "conversation.item.created" -> {
                        Log.d(TAG, "Conversation item created")
                    }

                    "error" -> {
                        val error = jsonObject.getAsJsonObject("error")?.get("message")?.asString
                        onError?.invoke(error ?: "Unknown error")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing WebSocket message", e)
                onError?.invoke("Failed to parse response: ${e.message}")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Log.d(TAG, "Received binary message")
            // Handle binary messages if needed
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code - $reason")
            isConnected.set(false)
            onConnectionStatusChanged?.invoke(false)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code - $reason")
            isConnected.set(false)
            onConnectionStatusChanged?.invoke(false)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)
            isConnected.set(false)
            onConnectionStatusChanged?.invoke(false)
            onError?.invoke("Connection failed: ${t.message}")
        }
    }

    private suspend fun createTranscriptionSession(config: TranscriptionConfig): String {
        val payload = JsonObject().apply {
            addProperty("input_audio_format", "pcm16")
            add("input_audio_transcription", JsonObject().apply {
                addProperty("model", "gpt-4o-mini-transcribe")
                addProperty("prompt", "")
            })
            add("turn_detection", JsonObject().apply {
                addProperty("type", "server_vad")
                addProperty("threshold", 0.5)
                addProperty("prefix_padding_ms", 300)
                addProperty("silence_duration_ms", 400)
            })
            add("input_audio_noise_reduction", JsonObject().apply {
                addProperty("type", "near_field")
            })
        }

        val request = Request.Builder()
            .url(OPENAI_API_URL)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("OpenAI-Beta", "assistants=v2")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Failed to create transcription session: ${response.code}")
                }
                val responseBody = response.body?.string() ?: throw IOException("Empty response body")
                val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
                jsonResponse.getAsJsonObject("client_secret").get("value").asString
            }
        }
    }

    suspend fun connectForTranscription(
        config: TranscriptionConfig,
        onTranscription: (String) -> Unit,
        onError: (String) -> Unit,
        onConnectionStatus: (Boolean) -> Unit
    ) {
        this.onTranscriptionReceived = onTranscription
        this.onError = onError
        this.onConnectionStatusChanged = onConnectionStatus

        try {
            // First get the ephemeral token
            ephemeralToken = createTranscriptionSession(config)

            // Then connect to the WebSocket
            val request = Request.Builder()
                .url(OPENAI_REALTIME_URL)
                .addHeader("Authorization", "Bearer $ephemeralToken")
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build()

            webSocket = client.newWebSocket(request, webSocketListener)
        } catch (e: Exception) {
            onError("Failed to connect: ${e.message}")
        }
    }

    fun sendAudioChunk(audioData: ByteArray) {
        if (!isConnected.get()) {
            Log.w(TAG, "WebSocket not connected")
            return
        }

        scope.launch {
            try {
                // Convert audio data to base64
                val base64Audio = android.util.Base64.encodeToString(audioData, android.util.Base64.NO_WRAP)
                
                // Send audio buffer append event
                val message = JsonObject().apply {
                    addProperty("type", "input_audio_buffer.append")
                    addProperty("audio", base64Audio)
                }
                
                webSocket?.send(gson.toJson(message))
            } catch (e: Exception) {
                Log.e(TAG, "Error sending audio chunk", e)
                onError?.invoke("Failed to send audio: ${e.message}")
            }
        }
    }

    fun disconnect() {
        scope.launch {
            try {
                webSocket?.close(1000, "Normal closure")
                webSocket = null
                isConnected.set(false)
                onConnectionStatusChanged?.invoke(false)
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting", e)
            }
        }
    }

    fun isConnected(): Boolean = isConnected.get()

    fun cleanup() {
        disconnect()
        scope.cancel()
    }
    
    private fun processDeltaTranscript(deltaText: String) {
        // Append new delta text
        currentDeltaText.append(deltaText)
        val fullText = currentDeltaText.toString()
        
        // Scan for sentence endings from last processed position
        for (i in lastProcessedIndex until fullText.length) {
            if (sentenceEndRegex.matches(fullText[i].toString())) {
                // Extract the sentence including the punctuation mark
                val boundaryPos = i + 1
                val newSentence = fullText.substring(0, boundaryPos).trim()
                
                if (newSentence.isNotBlank()) {
                    // Add to recent sentences
                    recentSentences.add(newSentence)
                    while (recentSentences.size > MAX_CONTEXT_SENTENCES) {
                        recentSentences.removeAt(0)
                    }
                    
                    // Emit only the newly completed sentence; callers can manage context/buffering
                    onTranscriptionReceived?.invoke(newSentence)
                    
                    // Remove processed text and update index
                    currentDeltaText.delete(0, boundaryPos)
                    lastProcessedIndex = 0
                }
            }
        }
        
        // Update last processed index for the remaining text (do not emit partials without punctuation)
        lastProcessedIndex = currentDeltaText.length
    }
    
    private fun processCompletedTranscript(transcript: String) {
        // Handle any remaining text when transcription is complete
        if (transcript.isNotBlank()) {
            currentDeltaText.append(transcript)
            val remainingText = currentDeltaText.toString().trim()
            
            if (remainingText.isNotBlank()) {
                // Add final punctuation if missing
                val finalText = if (sentenceEndRegex.find(remainingText.last().toString()) == null) {
                    "$remainingText."
                } else {
                    remainingText
                }
                onTranscriptionReceived?.invoke(finalText)
            }
            
            // Clear buffers
            currentDeltaText.clear()
            lastProcessedIndex = 0
        }
    }
} 
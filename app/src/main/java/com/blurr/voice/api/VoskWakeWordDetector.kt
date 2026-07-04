package com.blurr.voice.api

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

class VoskWakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit,
    private val onStartupFailure: () -> Unit
) : RecognitionListener {

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var isListening = false

    companion object {
        private const val TAG = "VoskWakeWordDetector"
        private const val SAMPLE_RATE = 16_000.0f
        private const val MODEL_ASSET_DIR = "vosk-model-small-en-us-0.15"
        private const val MODEL_TARGET_DIR = "vosk-model-en"
        private val WAKE_PHRASES = listOf("hey miko", "miko")
    }

    fun start() {
        if (isListening) {
            Log.d(TAG, "Already started.")
            return
        }

        StorageService.unpack(
            context,
            MODEL_ASSET_DIR,
            MODEL_TARGET_DIR,
            { unpackedModel ->
                try {
                    model = unpackedModel
                    recognizer = Recognizer(unpackedModel, SAMPLE_RATE, buildGrammar())
                    speechService = SpeechService(recognizer, SAMPLE_RATE).also {
                        it.startListening(this)
                    }
                    isListening = true
                    Log.d(TAG, "Vosk wake word detection started.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start Vosk wake word detection.", e)
                    onStartupFailure()
                }
            },
            { exception ->
                Log.e(TAG, "Vosk model could not be loaded from assets/$MODEL_ASSET_DIR.", exception)
                onStartupFailure()
            }
        )
    }

    fun stop() {
        if (!isListening && speechService == null && recognizer == null && model == null) {
            Log.d(TAG, "Already stopped.")
            return
        }

        runCatching { speechService?.stop() }
        runCatching { speechService?.shutdown() }
        runCatching { recognizer?.close() }
        runCatching { model?.close() }

        speechService = null
        recognizer = null
        model = null
        isListening = false
        Log.d(TAG, "Vosk wake word detection stopped.")
    }

    override fun onPartialResult(hypothesis: String?) {
        detectWakePhrase(hypothesis)
    }

    override fun onResult(hypothesis: String?) {
        detectWakePhrase(hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {
        detectWakePhrase(hypothesis)
    }

    override fun onError(exception: Exception?) {
        Log.e(TAG, "Vosk recognition error.", exception)
        if (isListening) onStartupFailure()
    }

    override fun onTimeout() {
        Log.d(TAG, "Vosk recognition timeout.")
    }

    private fun detectWakePhrase(hypothesis: String?) {
        val recognizedText = extractText(hypothesis).lowercase()
        if (recognizedText.isBlank()) return

        if (WAKE_PHRASES.any { recognizedText.contains(it) }) {
            Log.d(TAG, "Wake phrase detected: $recognizedText")
            onWakeWordDetected()
        }
    }

    private fun extractText(hypothesis: String?): String {
        if (hypothesis.isNullOrBlank()) return ""
        return runCatching {
            val json = JSONObject(hypothesis)
            json.optString("partial").ifBlank { json.optString("text") }
        }.getOrDefault(hypothesis)
    }

    private fun buildGrammar(): String {
        return "[\"hey miko\", \"miko\", \"[unk]\"]"
    }
}

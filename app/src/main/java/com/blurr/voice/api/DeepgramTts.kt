package com.blurr.voice.api

import android.util.Log
import com.blurr.voice.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Available voice options for Miko, powered by Deepgram Aura-2.
 *
 * [voiceName] is the Deepgram model id (e.g. "aura-2-luna-en") passed as the
 * `model` query parameter to the /v1/speak endpoint.
 */
enum class TTSVoice(val displayName: String, val voiceName: String, val description: String) {
    AURA_LUNA("Luna", "aura-2-luna-en", "Warm, friendly female voice. Miko's default."),
    AURA_THALIA("Thalia", "aura-2-thalia-en", "Bright, dynamic female voice."),
    AURA_ATHENA("Athena", "aura-2-athena-en", "Calm, articulate female voice."),
    AURA_IRIS("Iris", "aura-2-iris-en", "Gentle, conversational female voice."),
    AURA_AMALTHEA("Amalthea", "aura-2-amalthea-en", "Soft, approachable female voice."),
    AURA_ANDROMEDA("Andromeda", "aura-2-andromeda-en", "Casual, expressive female voice."),
    AURA_AURORA("Aurora", "aura-2-aurora-en", "Cheerful, youthful female voice."),
    AURA_HELENA("Helena", "aura-2-helena-en", "Clear, professional female voice."),
    AURA_HERA("Hera", "aura-2-hera-en", "Confident, mature female voice."),
    AURA_CORA("Cora", "aura-2-cora-en", "Smooth, natural female voice."),
    AURA_PHOEBE("Phoebe", "aura-2-phoebe-en", "Energetic, upbeat female voice."),
    AURA_APOLLO("Apollo", "aura-2-apollo-en", "Energetic, youthful male voice."),
    AURA_ARCAS("Arcas", "aura-2-arcas-en", "Warm, natural male voice."),
    AURA_ATLAS("Atlas", "aura-2-atlas-en", "Confident, professional male voice."),
    AURA_ORION("Orion", "aura-2-orion-en", "Deep, storytelling male voice."),
    AURA_ZEUS("Zeus", "aura-2-zeus-en", "Commanding, powerful male voice.")
}

/**
 * Handles communication with the Deepgram Text-to-Speech API (Aura-2).
 *
 * The endpoint returns raw LINEAR16 PCM audio at 24000 Hz mono when requested with
 * `encoding=linear16&sample_rate=24000&container=none`, which is fed directly into the
 * existing [com.blurr.voice.utilities.TTSManager] AudioTrack pipeline.
 */
object DeepgramTts {
    const val apiKey = BuildConfig.DEEPGRAM_API_KEY
    private const val SAMPLE_RATE = 24000
    private const val BASE_URL = "https://api.deepgram.com/v1/speak"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Default voice = Miko's warm companion voice. */
    val defaultVoice: TTSVoice get() = TTSVoice.AURA_LUNA

    /**
     * Synthesizes speech using the default Miko voice.
     * @return raw LINEAR16 PCM (24000 Hz, mono) audio bytes.
     */
    suspend fun synthesize(text: String): ByteArray = synthesize(text, defaultVoice)

    /**
     * Synthesizes speech from text using the Deepgram TTS API.
     * @param text The text to synthesize.
     * @param voice The Aura-2 voice to use.
     * @return raw LINEAR16 PCM (24000 Hz, mono) audio bytes.
     * @throws Exception if the API call fails or the response is empty.
     */
    suspend fun synthesize(text: String, voice: TTSVoice): ByteArray = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) {
            throw Exception("Deepgram API key is not configured. Add DEEPGRAM_API_KEY to local.properties.")
        }

        val url = "$BASE_URL?model=${voice.voiceName}&encoding=linear16&sample_rate=$SAMPLE_RATE&container=none"

        val jsonPayload = JSONObject().apply {
            put("text", text)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token $apiKey")
            .header("Content-Type", "application/json")
            .post(jsonPayload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                Log.e("DeepgramTts", "API Error: ${response.code} - $errorBody")
                throw Exception("Deepgram TTS API request failed with code ${response.code}")
            }

            val audioBytes = response.body?.bytes()
            if (audioBytes == null || audioBytes.isEmpty()) {
                throw Exception("Received an empty response from Deepgram TTS API.")
            }
            return@withContext audioBytes
        }
    }

    /** Get all available voice options. */
    fun getAvailableVoices(): List<TTSVoice> = TTSVoice.values().toList()
}

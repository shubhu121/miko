package com.blurr.voice.core.screenshot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.blurr.voice.core.Miko
import com.blurr.voice.core.events.EventBus
import com.blurr.voice.core.events.MikoEvent
import com.blurr.voice.utilities.ApiKeyManager
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Screenshot Understanding (MIKO.md Phase 2).
 *
 * Listens for [MikoEvent.ScreenshotTaken], reads the image, and asks a Gemini vision model
 * what it shows. The one-line understanding is stored as a memory (and therefore ingested
 * into the knowledge graph), so "this screenshot belongs to Project Alpha"-style recall
 * becomes possible. Uses a direct vision call because the shared LLM clients are text-only.
 */
object ScreenshotUnderstandingService {

    private const val TAG = "ScreenshotUnderstanding"
    private const val MODEL = "gemini-2.5-flash"
    private const val MAX_DIMEN = 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            EventBus.events.collect { event ->
                if (event is MikoEvent.ScreenshotTaken) {
                    // Give the OS a moment to finish writing the file.
                    delay(1200)
                    understand(event.path)
                }
            }
        }
        Log.d(TAG, "Screenshot understanding started.")
    }

    /** Best-effort: decode, describe with vision, and remember. */
    suspend fun understand(path: String) {
        try {
            val bitmap = decodeScaled(path) ?: run {
                Log.w(TAG, "Could not decode screenshot at $path")
                return
            }
            val apiKey = runCatching { ApiKeyManager.getNextKey() }.getOrNull()
            if (apiKey.isNullOrBlank()) {
                Log.d(TAG, "No Gemini key; skipping screenshot understanding.")
                return
            }
            val model = GenerativeModel(modelName = MODEL, apiKey = apiKey)
            val prompt = "In one sentence, describe what this screenshot shows and any app, " +
                "person, topic or task it relates to. Be concise and specific."
            val response = model.generateContent(
                content { image(bitmap); text(prompt) }
            )
            val understanding = response.text?.trim()
            if (!understanding.isNullOrBlank()) {
                runCatching { Miko.memory.addMemory("Screenshot: $understanding") }
                Log.d(TAG, "Understood screenshot: $understanding")
            }
        } catch (e: Exception) {
            Log.w(TAG, "understand failed: ${e.message}")
        }
    }

    /** Decodes the screenshot downscaled so the vision payload stays small. */
    private fun decodeScaled(path: String): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            val larger = maxOf(bounds.outWidth, bounds.outHeight)
            while (larger / sample > MAX_DIMEN) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            Log.w(TAG, "decode failed: ${e.message}")
            null
        }
    }
}

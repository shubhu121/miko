package com.blurr.voice.api

import android.util.Log
import com.blurr.voice.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cognee Cloud client for persistent, graph-based memory.
 *
 * Cognee Cloud authenticates with the `X-Api-Key` header and exposes endpoints under
 * the `/api/v1` prefix. This is used by Miko as a best-effort cloud enrichment on top of
 * the offline-first local memory store — never as the sole source of truth, per Miko's
 * local-first privacy principle.
 */
class MemoryService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val apiKey = BuildConfig.COGNEE_API_KEY
    private val baseUrl = BuildConfig.COGNEE_BASE_URL.trimEnd('/')
    private val jsonMedia = "application/json".toMediaType()

    /**
     * Adds a memory to Cognee and triggers a knowledge-graph rebuild.
     *
     * The `/api/v1/add` endpoint expects **multipart/form-data** (not JSON): a `data`
     * part (the raw text/string) and a `datasetName` part. After a successful add we
     * kick off [cognify] so the new data is woven into the graph.
     */
    suspend fun addMemory(
        instruction: String,
        userId: String = defaultUserId,
        nodeSet: String? = null,
        triggerCognify: Boolean = true
    ) {
        if (!isConfigured()) {
            Log.w(TAG, "Cognee not configured. Skipping addMemory.")
            return
        }
        withContext(Dispatchers.IO) {
            try {
                // CRITICAL: the /add endpoint expects `data` as an uploaded FILE, not a text
                // field. Sending a plain string returns HTTP 422 ("Expected UploadFile") and
                // nothing is stored — send the text as a small .txt file part instead.
                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "data", "memory.txt",
                        instruction.toRequestBody("text/plain".toMediaType())
                    )
                    .addFormDataPart("datasetName", DATASET)
                    .apply {
                        // node_set groups related data in the graph (e.g. "notifications",
                        // "apps", "tasks") so cross-app relationships can be reasoned over.
                        if (!nodeSet.isNullOrBlank()) addFormDataPart("node_set", nodeSet)
                    }
                    .build()

                // Note: do NOT set Content-Type manually — OkHttp adds the multipart
                // boundary to the header for us.
                val addRequest = Request.Builder()
                    .url("$baseUrl/api/v1/add")
                    .header("X-Api-Key", apiKey)
                    .post(multipart)
                    .build()

                client.newCall(addRequest).execute().use { response ->
                    val body = response.body?.string()
                    if (response.isSuccessful) {
                        Log.d(TAG, "Cognee add ok.")
                    } else {
                        Log.e(TAG, "Cognee add failed ${response.code}: $body")
                        return@withContext
                    }
                }
                // Build the graph over the newly added data. Batched ingestion sets this
                // false and calls cognify() once after the whole batch.
                if (triggerCognify) cognify(userId)
            } catch (e: Exception) {
                Log.e(TAG, "Error adding memory to Cognee", e)
            }
        }
    }

    /**
     * Triggers knowledge-graph construction (`/api/v1/cognify`) over the dataset.
     * Runs server-side in the background (`runInBackground`) so the call returns quickly.
     * Public so callers can force a rebuild after bulk changes. Best-effort.
     */
    suspend fun cognify(userId: String = defaultUserId) {
        if (!isConfigured()) return
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("datasets", JSONArray().put(DATASET))
                    put("runInBackground", true)
                    if (userId.isNotBlank()) put("user_id", userId)
                }
                val request = Request.Builder()
                    .url("$baseUrl/api/v1/cognify")
                    .header("X-Api-Key", apiKey)
                    .header("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Cognee cognify triggered.")
                    } else {
                        Log.w(TAG, "Cognee cognify returned ${response.code}: ${response.body?.string()}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cognee cognify error: ${e.message}")
            }
        }
    }

    /** Searches Cognee memory; returns a newline-joined list of memory strings. */
    suspend fun searchMemory(query: String, userId: String = defaultUserId): String {
        if (!isConfigured()) return NO_MEMORIES
        return withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("query", query)
                    put("datasets", JSONArray().put(DATASET))
                    put("search_type", "GRAPH_COMPLETION")
                    put("top_k", 10)
                    if (userId.isNotBlank()) put("user_id", userId)
                }
                val request = Request.Builder()
                    .url("$baseUrl/api/v1/search")
                    .header("X-Api-Key", apiKey)
                    .header("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful || body == null) {
                        Log.e(TAG, "Cognee search failed ${response.code}")
                        return@withContext NO_MEMORIES
                    }
                    val results = parseResults(body)
                    if (results.isEmpty()) return@withContext NO_MEMORIES
                    results.joinToString("\n") { "- $it" }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error searching Cognee memory", e)
                NO_MEMORIES
            }
        }
    }

    /** Returns individual memory strings (used by unified search). */
    suspend fun searchMemoryList(query: String, userId: String = defaultUserId): List<String> {
        if (!isConfigured()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("query", query)
                    put("datasets", JSONArray().put(DATASET))
                    put("search_type", "CHUNKS")
                    put("top_k", 10)
                    if (userId.isNotBlank()) put("user_id", userId)
                }
                val request = Request.Builder()
                    .url("$baseUrl/api/v1/search")
                    .header("X-Api-Key", apiKey)
                    .header("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@withContext emptyList()
                    if (!response.isSuccessful) return@withContext emptyList()
                    parseResults(body)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cognee list search error: ${e.message}")
                emptyList()
            }
        }
    }

    fun isConfigured(): Boolean = apiKey.isNotBlank() && baseUrl.isNotBlank()

    private fun parseResults(body: String): List<String> {
        return try {
            val trimmed = body.trim()
            val array = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> {
                    val json = JSONObject(trimmed)
                    json.optJSONArray("results")
                        ?: json.optJSONArray("memories")
                        ?: json.optJSONArray("data")
                        ?: JSONArray()
                }
            }
            (0 until array.length()).mapNotNull { i ->
                when (val item = array.get(i)) {
                    is String -> item.takeIf { it.isNotBlank() }
                    is JSONObject -> extractText(item).takeIf { it.isNotBlank() }
                    else -> item.toString().takeIf { it.isNotBlank() }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cognee parse error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Pulls a human-readable string out of one Cognee result object. Cognee Cloud wraps
     * each hit as `{ "search_result": ..., "dataset_id": ..., "dataset_name": ... }`, where
     * `search_result` may be a plain string (completions) or a nested object (chunks). Falls
     * back to the older `memory`/`text`/`content` shapes for compatibility.
     */
    private fun extractText(item: JSONObject): String {
        when (val sr = item.opt("search_result")) {
            is String -> if (sr.isNotBlank()) return sr
            is JSONObject -> textOf(sr).let { if (it.isNotBlank()) return it }
            is JSONArray -> {
                // CHUNKS/GRAPH results wrap hits as an array of {text, ...} objects.
                val texts = (0 until sr.length()).mapNotNull { idx ->
                    when (val el = sr.opt(idx)) {
                        is String -> el.takeIf { it.isNotBlank() }
                        is JSONObject -> textOf(el).takeIf { it.isNotBlank() }
                        else -> null
                    }
                }
                if (texts.isNotEmpty()) return texts.joinToString(" ")
            }
        }
        return textOf(item)
    }

    private fun textOf(o: JSONObject): String =
        o.optString("text")
            .ifBlank { o.optString("content") }
            .ifBlank { o.optString("memory") }

    companion object {
        private const val TAG = "MemoryService"
        private const val DATASET = "miko_memories"
        private const val NO_MEMORIES = "No relevant memories found."
        val defaultUserId: String get() = BuildConfig.COGNEE_USER_ID
    }
}

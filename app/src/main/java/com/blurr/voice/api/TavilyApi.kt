package com.blurr.voice.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tavily web-search client (Bearer auth). Powers the agent's `web_search` action so Miko can
 * look things up online mid-task and reason over the results.
 */
class TavilyApi(private val apiKey: String) {

    private val client = OkHttpClient()

    /** Low-level search: caller supplies the full Tavily payload; returns the raw JSON body. */
    suspend fun search(searchParameters: JSONObject): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.tavily.com/search")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .post(searchParameters.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    Log.e("TavilyApi", "API Error ${response.code}: ${body ?: "no body"}")
                    return@withContext "{\"error\":\"Tavily HTTP ${response.code}\"}"
                }
                body
            }
        } catch (e: Exception) {
            Log.e("TavilyApi", "Search failed", e)
            "{\"error\":\"Search failed: ${e.message}\"}"
        }
    }

    /**
     * High-level search for the agent: returns a concise answer plus the top sources as plain
     * text (or an error line). A blank API key short-circuits gracefully.
     */
    suspend fun searchText(query: String, maxResults: Int = 5): String {
        if (apiKey.isBlank()) return "Web search is not configured."
        val params = JSONObject().apply {
            put("query", query)
            put("max_results", maxResults)
            put("include_answer", true)
            put("search_depth", "basic")
        }
        return parse(search(params), query)
    }

    private fun parse(raw: String, query: String): String {
        return try {
            val json = JSONObject(raw)
            if (json.has("error")) return "Web search failed: ${json.optString("error")}"
            val answer = json.optString("answer").takeIf { it.isNotBlank() }
            val results = json.optJSONArray("results") ?: JSONArray()
            val sources = buildString {
                for (i in 0 until minOf(results.length(), 3)) {
                    val r = results.optJSONObject(i) ?: continue
                    val title = r.optString("title")
                    val content = r.optString("content").take(300)
                    if (title.isNotBlank() || content.isNotBlank()) {
                        append("- ").append(title).append(": ").append(content).append("\n")
                    }
                }
            }.trim()
            when {
                answer != null && sources.isNotBlank() -> "$answer\n\nSources:\n$sources"
                answer != null -> answer
                sources.isNotBlank() -> sources
                else -> "No web results found for \"$query\"."
            }
        } catch (e: Exception) {
            Log.w("TavilyApi", "parse failed: ${e.message}")
            "Could not parse web search results."
        }
    }
}

package com.blurr.voice.core.summary

import android.content.Context

/** Persists the most recent Miko daily summary. */
class DailySummaryStore private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(summary: String, dateKey: String) {
        prefs.edit()
            .putString(KEY_SUMMARY, summary)
            .putString(KEY_DATE, dateKey)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
    }

    fun latestSummary(): String? = prefs.getString(KEY_SUMMARY, null)

    fun latestDate(): String? = prefs.getString(KEY_DATE, null)

    fun lastUpdated(): Long = prefs.getLong(KEY_UPDATED, 0L)

    companion object {
        private const val PREFS = "miko_daily_summary"
        private const val KEY_SUMMARY = "summary"
        private const val KEY_DATE = "date"
        private const val KEY_UPDATED = "updated_at"

        @Volatile private var INSTANCE: DailySummaryStore? = null
        fun getInstance(context: Context): DailySummaryStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DailySummaryStore(context.applicationContext).also { INSTANCE = it }
            }
    }
}

package com.blurr.voice.utilities

class FreemiumManager {

    companion object {
        const val DAILY_TASK_LIMIT = Long.MAX_VALUE
    }

    suspend fun getDeveloperMessage(): String = ""

    suspend fun isUserSubscribed(): Boolean = true

    suspend fun provisionUserIfNeeded() {
        // Miko currently ships without a paywall or task quota.
    }

    suspend fun getTasksRemaining(): Long = Long.MAX_VALUE

    suspend fun canPerformTask(): Boolean = true

    suspend fun decrementTaskCount() {
        // No-op while all product features are unlocked.
    }
}

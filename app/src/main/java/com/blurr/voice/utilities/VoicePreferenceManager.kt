package com.blurr.voice.utilities

import android.content.Context
import com.blurr.voice.api.TTSVoice

object VoicePreferenceManager {
    // FIX: Changed PREFS_NAME to match SettingsActivity for consistency.
    // This ensures both read/write to the same preferences file.
    private const val PREFS_NAME = "BlurrSettings" // THIS LINE WAS CHANGED

    private const val KEY_SELECTED_VOICE = "selected_voice"

    private val DEFAULT_VOICE = TTSVoice.AURA_LUNA

    fun getSelectedVoice(context: Context): TTSVoice {
        val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val selectedVoiceName = sharedPreferences.getString(KEY_SELECTED_VOICE, DEFAULT_VOICE.name)

        // Robust against stale/legacy stored voice names (e.g. old Google Chirp voices).
        return try {
            TTSVoice.valueOf(selectedVoiceName ?: DEFAULT_VOICE.name)
        } catch (e: IllegalArgumentException) {
            DEFAULT_VOICE
        }
    }

    fun saveSelectedVoice(context: Context, voice: TTSVoice) {
        val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPreferences.edit()
            .putString(KEY_SELECTED_VOICE, voice.name)
            .apply()
    }
}
package com.blurr.voice.core.notes

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Thin wrapper around [MediaRecorder] for recording voice notes to an m4a (AAC) file.
 * Handles the API-31+ constructor change and fails safe.
 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    var isRecording: Boolean = false
        private set

    /** Starts recording into [output]. Returns true if recording actually began. */
    fun start(output: File): Boolean {
        stop() // ensure clean state
        return try {
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOutputFile(output.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            isRecording = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            runCatching { recorder?.release() }
            recorder = null
            isRecording = false
            false
        }
    }

    /** Stops and releases. Returns false if the recording was too short/failed. */
    fun stop(): Boolean {
        val rec = recorder ?: return false
        return try {
            rec.stop()
            true
        } catch (e: Exception) {
            Log.w(TAG, "stop failed: ${e.message}")
            false
        } finally {
            runCatching { rec.release() }
            recorder = null
            isRecording = false
        }
    }

    companion object {
        private const val TAG = "AudioRecorder"
    }
}

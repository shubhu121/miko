package com.blurr.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.blurr.voice.core.Miko
import com.blurr.voice.core.notes.AudioRecorder
import com.blurr.voice.core.notes.MarkdownRenderer
import com.blurr.voice.core.notes.NoteStore
import com.blurr.voice.core.ui.finishWithPop
import com.blurr.voice.core.ui.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Rich markdown notepad for a single memory: title + markdown body with a formatting toolbar
 * (bold, italic, strikethrough, highlight, inline/block code with syntax coloring, heading,
 * bullet, image), a live Preview, and an optional voice recording. Saves to the local
 * [NoteStore] (offline-safe) and syncs the text into Miko's memory / Cognee graph.
 */
class NoteEditorActivity : AppCompatActivity() {

    private lateinit var store: NoteStore
    private lateinit var recorder: AudioRecorder
    private lateinit var titleField: EditText
    private lateinit var bodyField: EditText
    private lateinit var previewScroll: ScrollView
    private lateinit var previewText: TextView
    private lateinit var previewToggle: TextView
    private lateinit var recordButton: TextView
    private lateinit var playButton: TextView
    private lateinit var audioStatus: TextView

    private var noteId: String = UUID.randomUUID().toString()
    private var audioPath: String? = null
    private var player: MediaPlayer? = null
    private var previewing = false

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) toggleRecording() else
            Toast.makeText(this, "Microphone permission is needed to record.", Toast.LENGTH_SHORT).show()
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { insertImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_editor)
        window.statusBarColor = ContextCompat.getColor(this, R.color.miko_bg)

        store = NoteStore.getInstance(this)
        recorder = AudioRecorder(this)

        titleField = findViewById(R.id.note_title)
        bodyField = findViewById(R.id.note_body)
        previewScroll = findViewById(R.id.note_preview_scroll)
        previewText = findViewById(R.id.note_preview)
        previewToggle = findViewById(R.id.note_preview_toggle)
        recordButton = findViewById(R.id.note_record)
        playButton = findViewById(R.id.note_play)
        audioStatus = findViewById(R.id.note_audio_status)

        intent.getStringExtra(EXTRA_NOTE_ID)?.let { existingId ->
            store.get(existingId)?.let { note ->
                noteId = note.id
                titleField.setText(note.title)
                bodyField.setText(note.text)
                audioPath = note.audioPath
            }
        }
        refreshAudioUi()

        findViewById<TextView>(R.id.note_back).apply { pressable(); setOnClickListener { finishWithPop() } }
        findViewById<TextView>(R.id.note_save).apply { pressable(); setOnClickListener { save() } }
        previewToggle.apply { pressable(); setOnClickListener { togglePreview() } }
        recordButton.apply { pressable(); setOnClickListener { ensureMicThenRecord() } }
        playButton.apply { pressable(); setOnClickListener { togglePlayback() } }

        setupFormatBar()
    }

    // --- Formatting toolbar -------------------------------------------------

    private fun setupFormatBar() {
        findViewById<TextView>(R.id.fmt_bold).setOnClickListener { wrapSelection("**", "**", "bold") }
        findViewById<TextView>(R.id.fmt_italic).setOnClickListener { wrapSelection("*", "*", "italic") }
        findViewById<TextView>(R.id.fmt_strike).setOnClickListener { wrapSelection("~~", "~~", "strikethrough") }
        findViewById<TextView>(R.id.fmt_highlight).setOnClickListener { wrapSelection("==", "==", "highlight") }
        findViewById<TextView>(R.id.fmt_code).setOnClickListener { wrapSelection("`", "`", "code") }
        findViewById<TextView>(R.id.fmt_codeblock).setOnClickListener { wrapSelection("\n```\n", "\n```\n", "code") }
        findViewById<TextView>(R.id.fmt_heading).setOnClickListener { prefixLine("# ") }
        findViewById<TextView>(R.id.fmt_bullet).setOnClickListener { prefixLine("- ") }
        findViewById<TextView>(R.id.fmt_image).setOnClickListener { pickImage.launch("image/*") }
    }

    /** Wraps the current selection (or a placeholder) with markdown markers. */
    private fun wrapSelection(prefix: String, suffix: String, placeholder: String) {
        if (previewing) return
        val start = bodyField.selectionStart.coerceAtLeast(0)
        val end = bodyField.selectionEnd.coerceAtLeast(0)
        val lo = minOf(start, end)
        val hi = maxOf(start, end)
        val editable = bodyField.text
        val selected = if (hi > lo) editable.subSequence(lo, hi).toString() else placeholder
        editable.replace(lo, hi, "$prefix$selected$suffix")
        // Place cursor inside the wrapped text.
        bodyField.setSelection(lo + prefix.length, lo + prefix.length + selected.length)
    }

    /** Prepends [marker] to the start of the current line. */
    private fun prefixLine(marker: String) {
        if (previewing) return
        val text = bodyField.text
        val cursor = bodyField.selectionStart.coerceAtLeast(0)
        val lineStart = text.toString().lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        text.insert(lineStart, marker)
        bodyField.setSelection((cursor + marker.length).coerceAtMost(text.length))
    }

    private fun insertImage(uri: Uri) {
        // Copy the picked image into the note's own storage so it survives.
        val dest = File(store.imageDir(noteId), "${System.nanoTime()}.jpg")
        val ok = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }.isSuccess
        if (!ok || !dest.exists()) {
            Toast.makeText(this, "Couldn't add image.", Toast.LENGTH_SHORT).show()
            return
        }
        val md = "\n![image](file://${dest.absolutePath})\n"
        val cursor = bodyField.selectionStart.coerceAtLeast(0)
        bodyField.text.insert(cursor, md)
        Toast.makeText(this, "Image added", Toast.LENGTH_SHORT).show()
    }

    // --- Preview ------------------------------------------------------------

    private fun togglePreview() {
        previewing = !previewing
        if (previewing) {
            MarkdownRenderer.render(previewText, bodyField.text.toString())
            bodyField.visibility = View.GONE
            previewScroll.visibility = View.VISIBLE
            previewToggle.text = "Edit"
        } else {
            previewScroll.visibility = View.GONE
            bodyField.visibility = View.VISIBLE
            previewToggle.text = "Preview"
        }
    }

    // --- Audio --------------------------------------------------------------

    private fun ensureMicThenRecord() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) toggleRecording() else requestMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun toggleRecording() {
        if (recorder.isRecording) {
            val ok = recorder.stop()
            recordButton.text = "🎙  Record"
            if (ok) audioStatus.text = "Voice note attached" else {
                audioPath = null; audioStatus.text = "Recording failed"
            }
            refreshAudioUi()
        } else {
            val file = store.newAudioFile(noteId)
            if (recorder.start(file)) {
                audioPath = file.absolutePath
                recordButton.text = "⏹  Stop"
                audioStatus.text = "Recording…"
                playButton.visibility = View.GONE
            } else {
                Toast.makeText(this, "Couldn't start recording.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun togglePlayback() {
        val path = audioPath ?: return
        if (player?.isPlaying == true) {
            stopPlayer(); playButton.text = "▶  Play"; return
        }
        try {
            player = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { stopPlayer(); playButton.text = "▶  Play" }
                prepare(); start()
            }
            playButton.text = "⏸  Playing"
        } catch (e: Exception) {
            Log.w(TAG, "playback failed: ${e.message}")
            Toast.makeText(this, "Couldn't play recording.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshAudioUi() {
        val has = !audioPath.isNullOrBlank()
        playButton.visibility = if (has) View.VISIBLE else View.GONE
        if (has && audioStatus.text.isNullOrBlank()) audioStatus.text = "Voice note attached"
    }

    // --- Save ---------------------------------------------------------------

    private fun save() {
        if (recorder.isRecording) recorder.stop()
        val title = titleField.text.toString().trim()
        val text = bodyField.text.toString().trim()
        if (title.isBlank() && text.isBlank() && audioPath == null) {
            finishWithPop(); return
        }
        store.save(noteId, title.ifBlank { text.take(40) }, text, audioPath)

        // Sync the note's text into Miko's memory + Cognee graph (node_set "notes").
        if (text.isNotBlank()) {
            val memoryText = if (title.isBlank()) text else "$title: $text"
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { Miko.memory.addMemory(memoryText, nodeSet = "notes") }
            }
        }
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finishWithPop()
    }

    private fun stopPlayer() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    override fun onStop() {
        super.onStop()
        stopPlayer()
        if (recorder.isRecording) recorder.stop()
    }

    companion object {
        private const val TAG = "NoteEditorActivity"
        const val EXTRA_NOTE_ID = "note_id"
    }
}

package com.blurr.voice.core.notes

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Local, file-backed store for the memory notepad. Each note is one JSON file under
 * `filesDir/miko_notes/`; audio recordings live in `.../miko_notes/audio/`. Simple, robust,
 * offline — saving a note can't fail on a network/embedding error the way the old cloud path did.
 */
class NoteStore private constructor(context: Context) {

    private val dir = File(context.filesDir, "miko_notes").apply { mkdirs() }
    private val audioDir = File(dir, "audio").apply { mkdirs() }

    fun getAll(): List<Note> =
        dir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.mapNotNull { runCatching { Note.fromJson(JSONObject(it.readText())) }.getOrNull() }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()

    fun get(id: String): Note? =
        fileFor(id).takeIf { it.exists() }
            ?.let { runCatching { Note.fromJson(JSONObject(it.readText())) }.getOrNull() }

    /** Creates or updates a note by id and returns the persisted copy. */
    fun save(id: String?, title: String, text: String, audioPath: String?): Note {
        val now = System.currentTimeMillis()
        val existing = id?.let { get(it) }
        val note = Note(
            id = id ?: UUID.randomUUID().toString(),
            title = title.trim(),
            text = text.trim(),
            audioPath = audioPath,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        runCatching { fileFor(note.id).writeText(note.toJson().toString()) }
            .onFailure { Log.e(TAG, "save failed", it) }
        return note
    }

    fun delete(id: String) {
        get(id)?.audioPath?.let { runCatching { File(it).delete() } }
        runCatching { fileFor(id).delete() }
    }

    /** A fresh file path to record audio into for [noteId]. */
    fun newAudioFile(noteId: String): File = File(audioDir, "$noteId.m4a")

    /** Per-note directory for embedded images. */
    fun imageDir(noteId: String): File =
        File(File(dir, "images"), noteId).apply { mkdirs() }

    private fun fileFor(id: String) = File(dir, "$id.json")

    companion object {
        private const val TAG = "NoteStore"

        @Volatile private var INSTANCE: NoteStore? = null
        fun getInstance(context: Context): NoteStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: NoteStore(context.applicationContext).also { INSTANCE = it }
            }
    }
}

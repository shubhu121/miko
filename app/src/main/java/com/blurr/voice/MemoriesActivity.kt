package com.blurr.voice

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blurr.voice.core.notes.Note
import com.blurr.voice.core.notes.NoteStore
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * The memory notepad. Backed by the local [NoteStore] (offline, robust) instead of the old
 * Firestore array that failed for users without an existing doc. Supports text notes and
 * voice recordings; tapping a note opens the [NoteEditorActivity].
 */
class MemoriesActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateText: TextView
    private lateinit var addFab: FloatingActionButton
    private lateinit var adapter: NotesAdapter
    private lateinit var store: NoteStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memories)
        store = NoteStore.getInstance(this)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "My Memories"

        recyclerView = findViewById(R.id.memoriesRecyclerView)
        emptyStateText = findViewById(R.id.emptyStateText)
        addFab = findViewById(R.id.addMemoryFab)

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.privacyCard)
            ?.setOnClickListener { startActivity(Intent(this, PrivacyActivity::class.java)) }

        adapter = NotesAdapter(
            notes = emptyList(),
            onClick = { note -> openEditor(note.id) },
            onLongClick = { note -> confirmDelete(note) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        val swipe = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                adapter.getNoteAt(viewHolder.adapterPosition)?.let { confirmDelete(it) }
            }
        }
        ItemTouchHelper(swipe).attachToRecyclerView(recyclerView)

        addFab.setOnClickListener { openEditor(null) }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val notes = store.getAll()
        if (notes.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyStateText.visibility = View.VISIBLE
            emptyStateText.text = "No memories yet.\nTap + to jot a note or record a voice memo."
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyStateText.visibility = View.GONE
            adapter.update(notes)
        }
    }

    private fun openEditor(noteId: String?) {
        val intent = Intent(this, NoteEditorActivity::class.java)
        if (noteId != null) intent.putExtra(NoteEditorActivity.EXTRA_NOTE_ID, noteId)
        startActivity(intent)
    }

    private fun confirmDelete(note: Note) {
        AlertDialog.Builder(this)
            .setTitle("Delete memory")
            .setMessage("Delete \"${note.title.ifBlank { note.preview() }.take(60)}\"?")
            .setPositiveButton("Delete") { _, _ ->
                store.delete(note.id)
                reload()
            }
            .setNegativeButton("Cancel") { _, _ -> reload() }
            .setCancelable(false)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_memories, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_privacy -> {
            startActivity(Intent(this, PrivacyActivity::class.java)); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}

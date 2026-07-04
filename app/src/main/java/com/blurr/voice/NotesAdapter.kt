package com.blurr.voice

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.blurr.voice.core.notes.Note
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lists local notepad [Note]s: title, preview, date, and a voice-note indicator. */
class NotesAdapter(
    private var notes: List<Note>,
    private val onClick: (Note) -> Unit,
    private val onLongClick: (Note) -> Unit
) : RecyclerView.Adapter<NotesAdapter.NoteViewHolder>() {

    private val dateFormat = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault())

    class NoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.note_item_title)
        val preview: TextView = itemView.findViewById(R.id.note_item_preview)
        val date: TextView = itemView.findViewById(R.id.note_item_date)
        val audio: TextView = itemView.findViewById(R.id.note_item_audio)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false)
        return NoteViewHolder(view)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        val note = notes[position]
        holder.title.text = note.title.ifBlank { "Untitled" }
        val preview = note.preview()
        if (preview.isBlank()) {
            holder.preview.visibility = View.GONE
        } else {
            holder.preview.visibility = View.VISIBLE
            holder.preview.text = preview
        }
        holder.date.text = dateFormat.format(Date(note.updatedAt))
        holder.audio.visibility = if (note.hasAudio) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onClick(note) }
        holder.itemView.setOnLongClickListener { onLongClick(note); true }
    }

    override fun getItemCount(): Int = notes.size

    fun getNoteAt(position: Int): Note? = notes.getOrNull(position)

    fun update(newNotes: List<Note>) {
        notes = newNotes
        notifyDataSetChanged()
    }
}

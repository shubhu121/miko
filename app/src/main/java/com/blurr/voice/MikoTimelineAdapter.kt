package com.blurr.voice

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.blurr.voice.core.search.SearchResult
import com.blurr.voice.core.timeline.TimelineEntry
import com.blurr.voice.core.ui.staggerIn

/**
 * A single row on Miko's Home feed. Both timeline entries and unified-search hits
 * are normalized into this shape so one adapter renders the whole memory-first feed.
 */
data class FeedRow(
    val icon: String,
    val title: String,
    val subtitle: String,
    val timeLabel: String
) {
    companion object {
        fun from(entry: TimelineEntry): FeedRow = FeedRow(
            icon = iconFor(entry.type),
            title = entry.title,
            subtitle = entry.subtitle,
            timeLabel = relativeTime(entry.timestamp)
        )

        fun from(result: SearchResult): FeedRow = FeedRow(
            icon = if (result.source == SearchResult.Source.MEMORY) "🧠" else "🕒",
            title = result.title,
            subtitle = result.snippet.ifBlank { result.source.label },
            timeLabel = result.timestamp?.let { relativeTime(it) } ?: result.source.label
        )

        /** Maps a timeline entry type to a small, calm glyph. */
        private fun iconFor(type: String): String = when (type) {
            "notification" -> "🔔"
            "app" -> "📱"
            "clipboard" -> "📋"
            "power" -> "🔋"
            "screenshot" -> "🖼️"
            "task" -> "✨"
            "memory" -> "🧠"
            "call" -> "📞"
            "sms" -> "💬"
            "email" -> "✉️"
            "calendar" -> "📅"
            else -> "•"
        }

        private fun relativeTime(timestamp: Long): String =
            DateUtils.getRelativeTimeSpanString(
                timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            ).toString()
    }
}

/**
 * Renders the Home feed (timeline or search results). Follows the app's existing
 * simple-adapter style (mutable list + [updateItems]).
 */
class MikoTimelineAdapter(
    private var items: List<FeedRow> = emptyList()
) : RecyclerView.Adapter<MikoTimelineAdapter.RowViewHolder>() {

    /** Highest position revealed so far — so the stagger plays once, not on every scroll. */
    private var lastAnimated = -1

    class RowViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: TextView = itemView.findViewById(R.id.feed_icon)
        val title: TextView = itemView.findViewById(R.id.feed_title)
        val subtitle: TextView = itemView.findViewById(R.id.feed_subtitle)
        val time: TextView = itemView.findViewById(R.id.feed_time)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_timeline_entry, parent, false)
        return RowViewHolder(view)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        val row = items[position]
        holder.icon.text = row.icon
        holder.title.text = row.title
        holder.time.text = row.timeLabel
        if (row.subtitle.isBlank()) {
            holder.subtitle.visibility = View.GONE
        } else {
            holder.subtitle.visibility = View.VISIBLE
            holder.subtitle.text = row.subtitle
        }

        // Reveal each row once, staggered, the first time it appears.
        if (position > lastAnimated) {
            lastAnimated = position
            holder.staggerIn(position)
        }
    }

    fun resetReveal() {
        lastAnimated = -1
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<FeedRow>) {
        items = newItems
        notifyDataSetChanged()
    }
}

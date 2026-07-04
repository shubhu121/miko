package com.blurr.voice.core.timeline

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single moment on Miko's AI memory timeline — a normalized, searchable record
 * of something that happened on the device (a notification, an app open, a task…).
 */
@Entity(tableName = "timeline_entries")
data class TimelineEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val type: String,
    val title: String,
    val subtitle: String = "",
    val packageName: String? = null
)

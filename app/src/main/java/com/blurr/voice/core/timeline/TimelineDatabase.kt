package com.blurr.voice.core.timeline

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TimelineEntry::class], version = 1, exportSchema = false)
abstract class TimelineDatabase : RoomDatabase() {

    abstract fun timelineDao(): TimelineDao

    companion object {
        @Volatile private var INSTANCE: TimelineDatabase? = null

        fun getInstance(context: Context): TimelineDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TimelineDatabase::class.java,
                    "miko_timeline_database"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}

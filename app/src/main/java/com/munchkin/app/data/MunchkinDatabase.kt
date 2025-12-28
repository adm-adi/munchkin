package com.munchkin.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room Database for Munchkin app.
 */
@Database(
    entities = [SavedGameEntity::class],
    version = 1,
    exportSchema = false
)
abstract class MunchkinDatabase : RoomDatabase() {
    abstract fun savedGameDao(): SavedGameDao
    
    companion object {
        @Volatile
        private var INSTANCE: MunchkinDatabase? = null
        
        fun getInstance(context: Context): MunchkinDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MunchkinDatabase::class.java,
                    "munchkin_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

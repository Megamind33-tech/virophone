package com.viroreach.app.data

import android.content.Context
import androidx.room.Room
import com.viroreach.core.database.ViroDatabase

object ViroDatabaseProvider {
    @Volatile
    private var instance: ViroDatabase? = null

    fun get(context: Context): ViroDatabase {
        return instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ViroDatabase::class.java,
                "viro_reach.db",
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}

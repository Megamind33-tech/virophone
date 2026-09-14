package com.viroreach.core.database

import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase

@Entity(tableName = "known_contacts")
data class KnownContactEntity(
    @PrimaryKey val userId: String,
    val localName: String,
    val phoneE164: String?,
    val viroId: String?,
    val relationshipState: String,
    val presence: String = "OFFLINE"
)

@Database(entities = [KnownContactEntity::class], version = 1, exportSchema = false)
abstract class ViroDatabase : RoomDatabase()

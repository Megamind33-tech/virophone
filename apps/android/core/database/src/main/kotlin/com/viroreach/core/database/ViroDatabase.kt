package com.viroreach.core.database

import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase

@Entity(tableName = "known_contacts")
data class KnownContactEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val phoneE164: String?,
    val deviceContactId: String? = null,
    val userId: String?,
    val viroProfilePhotoUrl: String?,
    val localPhotoUri: String?,
    val customDisplayName: String? = null,
    val customPhotoUri: String? = null,
    val isReachable: Boolean,
    val isFavorite: Boolean,
    val isBlocked: Boolean = false,
    val isSpam: Boolean = false,
    val updatedAt: Long,
)

// v5 adds isSpam. fallbackToDestructiveMigration is acceptable here because
// every row is a cache rebuilt from the address book and the server.
@Database(entities = [KnownContactEntity::class], version = 5, exportSchema = false)
abstract class ViroDatabase : RoomDatabase() {
    abstract fun knownContactDao(): KnownContactDao
}

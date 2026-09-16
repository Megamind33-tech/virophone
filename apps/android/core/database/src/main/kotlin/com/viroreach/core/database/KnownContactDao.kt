package com.viroreach.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface KnownContactDao {
    @Query("SELECT * FROM known_contacts ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<KnownContactEntity>>

    @Query("SELECT * FROM known_contacts ORDER BY displayName COLLATE NOCASE ASC")
    suspend fun getAll(): List<KnownContactEntity>

    @Query("SELECT * FROM known_contacts WHERE phoneE164 = :phone LIMIT 1")
    suspend fun findByPhone(phone: String): KnownContactEntity?

    @Query("SELECT * FROM known_contacts WHERE userId = :userId LIMIT 1")
    suspend fun findByUserId(userId: String): KnownContactEntity?

    @Query("SELECT * FROM known_contacts WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): KnownContactEntity?

    @Query("SELECT COUNT(*) FROM known_contacts")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertAll(contacts: List<KnownContactEntity>)

    @Query("DELETE FROM known_contacts")
    suspend fun clearAll()

    @Query("DELETE FROM known_contacts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM known_contacts WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

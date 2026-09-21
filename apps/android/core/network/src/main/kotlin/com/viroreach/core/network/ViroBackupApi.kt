package com.viroreach.core.network

import retrofit2.http.DELETE
import retrofit2.http.GET

/**
 * The encrypted backup of this person's own chats.
 *
 * Only the status and the delete are here: the upload and the download move
 * raw bytes and go through OkHttp directly, the way media does.
 */
interface ViroBackupApi {
    @GET("api/v1/backup")
    suspend fun status(): BackupStatusDto

    @DELETE("api/v1/backup")
    suspend fun remove(): OkResult
}

data class BackupStatusDto(
    val exists: Boolean? = null,
    val sizeBytes: Long? = null,
    val messageCount: Int? = null,
    val conversationCount: Int? = null,
    val updatedAt: String? = null,
    val version: Int? = null,
)

package com.viroreach.core.network

import retrofit2.http.*

fun momentHttpStatus(error: Throwable): Int? = (error as? retrofit2.HttpException)?.code()

data class MomentDto(
    val id: String, val creatorUserId: String, val type: String, val text: String?,
    val visibility: String, val displayName: String, val avatarUrl: String?,
    val createdAt: String, val expiresAt: String, val allowVoice: Boolean,
)
data class MomentsNowDto(val serverTime: String, val moments: List<MomentDto>)
data class CreateMomentBody(val type: String, val text: String?, val visibility: String, val durationMinutes: Int)
data class ExtendMomentBody(val minutes: Int)

interface ViroMomentsApi {
    @GET("api/v1/moments/now") suspend fun now(): MomentsNowDto
    @GET("api/v1/moments/{id}") suspend fun get(@Path("id") id: String): MomentDto
    @POST("api/v1/moments") suspend fun create(@Body body: CreateMomentBody): MomentDto
    @POST("api/v1/moments/{id}/extend") suspend fun extend(@Path("id") id: String, @Body body: ExtendMomentBody): MomentDto
    @DELETE("api/v1/moments/{id}") suspend fun end(@Path("id") id: String)
}

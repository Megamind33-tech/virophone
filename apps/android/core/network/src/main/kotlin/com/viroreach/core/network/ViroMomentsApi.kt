package com.viroreach.core.network

import retrofit2.http.*

fun momentHttpStatus(error: Throwable): Int? = (error as? retrofit2.HttpException)?.code()

data class MomentDto(
    val id: String, val creatorUserId: String, val type: String, val text: String?,
    val visibility: String, val displayName: String, val avatarUrl: String?,
    val createdAt: String, val expiresAt: String, val allowVoice: Boolean,
    val participantCount: Int? = 0,
)
data class MomentsNowDto(val serverTime: String, val moments: List<MomentDto>)
data class CreateMomentBody(val type: String, val text: String?, val visibility: String, val durationMinutes: Int)
data class ExtendMomentBody(val minutes: Int)

data class MomentParticipantDto(val userId: String, val displayName: String, val isHost: Boolean, val joinedAt: String)
data class MomentReactionDto(val emoji: String, val userIds: List<String>)
data class MomentMessageDto(
    val id: String, val momentId: String, val senderUserId: String, val senderName: String,
    val body: String, val createdAt: String, val reactions: List<MomentReactionDto> = emptyList(),
)
data class MomentRoomDto(
    val moment: MomentDto, val serverTime: String,
    val participants: List<MomentParticipantDto>, val messages: List<MomentMessageDto>,
)
data class SendMomentMessageBody(val body: String)
data class MomentReactBody(val emoji: String?)
data class KnockDto(val knockerUserId: String, val knockerName: String, val createdAt: String)
data class KnocksDto(val knocks: List<KnockDto>)
data class KnockResponseBody(val accept: Boolean)
data class InviteBody(val userId: String)
data class MomentInvitationDto(val invitationId: String, val invitedAt: String, val moment: MomentDto)
data class MomentInvitationsDto(val invitations: List<MomentInvitationDto>)

interface ViroMomentsApi {
    @GET("api/v1/moments/now") suspend fun now(): MomentsNowDto
    @GET("api/v1/moments/invitations") suspend fun invitations(): MomentInvitationsDto
    @DELETE("api/v1/moments/invitations/{id}") suspend fun declineInvitation(@Path("id") id: String)
    @GET("api/v1/moments/{id}") suspend fun get(@Path("id") id: String): MomentDto
    @POST("api/v1/moments") suspend fun create(@Body body: CreateMomentBody): MomentDto
    @POST("api/v1/moments/{id}/extend") suspend fun extend(@Path("id") id: String, @Body body: ExtendMomentBody): MomentDto
    @DELETE("api/v1/moments/{id}") suspend fun end(@Path("id") id: String)

    @POST("api/v1/moments/{id}/join") suspend fun join(@Path("id") id: String): MomentRoomDto
    @POST("api/v1/moments/{id}/leave") suspend fun leave(@Path("id") id: String)
    @GET("api/v1/moments/{id}/room") suspend fun room(@Path("id") id: String): MomentRoomDto
    @POST("api/v1/moments/{id}/messages") suspend fun sendMessage(@Path("id") id: String, @Body body: SendMomentMessageBody): MomentMessageDto
    @POST("api/v1/moments/{id}/messages/{messageId}/react") suspend fun react(@Path("id") id: String, @Path("messageId") messageId: String, @Body body: MomentReactBody)
    @POST("api/v1/moments/{id}/knock") suspend fun knock(@Path("id") id: String)
    @GET("api/v1/moments/{id}/knocks") suspend fun knocks(@Path("id") id: String): KnocksDto
    @POST("api/v1/moments/{id}/knocks/{knockerId}/respond") suspend fun respondToKnock(@Path("id") id: String, @Path("knockerId") knockerId: String, @Body body: KnockResponseBody)
    @POST("api/v1/moments/{id}/invites") suspend fun invite(@Path("id") id: String, @Body body: InviteBody)
}

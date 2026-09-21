package com.viroreach.core.network

import retrofit2.http.*

fun momentHttpStatus(error: Throwable): Int? = (error as? retrofit2.HttpException)?.code()

data class MomentDto(
    val id: String, val creatorUserId: String, val type: String, val text: String?,
    val visibility: String, val displayName: String, val avatarUrl: String?,
    val createdAt: String, val expiresAt: String, val allowVoice: Boolean,
    val participantCount: Int? = 0,
    /** Reactions to the Moment itself, most-chosen first. */
    val reactions: List<MomentCheerDto> = emptyList(),
    val reactionCount: Int = 0,
    /** What this viewer chose, so the button reads as already pressed. */
    val myReaction: String? = null,
    val visibilityChangedAt: String? = null,
    /** Why people came together — shapes the room. Derived for Moments older than intents. */
    val intent: String? = null,
)
data class MomentCheerDto(val emoji: String, val count: Int)
data class MomentsNowDto(val serverTime: String, val moments: List<MomentDto>)
data class CreateMomentBody(
    val type: String,
    val text: String?,
    val visibility: String,
    val durationMinutes: Int,
    /** What people are coming together to do: COOK, WATCH, LISTEN, STAY… */
    val intent: String? = null,
)
data class ExtendMomentBody(val minutes: Int)

data class MomentParticipantDto(val userId: String, val displayName: String, val isHost: Boolean, val joinedAt: String)
data class MomentReactionDto(val emoji: String, val userIds: List<String>)
/**
 * A message in a Moment room.
 *
 * Either [body] is readable or [sealed] is true and [envelope] holds this
 * device's own copy — never both. A sealed message is opened on arrival and
 * the result put back into [body], so nothing above this layer has to know
 * which kind it was.
 */
data class MomentMessageDto(
    val id: String, val momentId: String, val senderUserId: String, val senderName: String,
    val body: String?, val createdAt: String, val reactions: List<MomentReactionDto> = emptyList(),
    val sealed: Boolean = false,
    val senderDeviceId: String? = null,
    val envelope: MomentEnvelopeDto? = null,
)
/** One sealed copy of a room message, addressed to one device. */
data class MomentEnvelopeDto(val ciphertext: String, val type: Int = 1)
data class MomentEnvelopeBody(val deviceId: String, val ciphertext: String, val type: Int)
data class MomentRoomDto(
    val moment: MomentDto, val serverTime: String,
    val participants: List<MomentParticipantDto>, val messages: List<MomentMessageDto>,
    /** What the room is right now. Null only from a server older than the room engine. */
    val state: MomentRuntimeDto? = null,
    /** Where the room's shared player is. Null from a server without shared media. */
    val playback: MomentPlaybackDto? = null,
    /** The server's clock, epoch ms, when it answered: how a phone places itself in a film. */
    val serverNow: Long? = null,
    /** What people have shared into the room to watch or listen to. */
    val media: List<MomentMediaDto>? = null,
)

/** A video or song someone brought into the Moment from their own phone. */
data class MomentMediaDto(
    val id: String,
    val kind: String,
    val title: String,
    val durationMs: Long? = null,
    val sizeBytes: Long = 0,
    val ownerUserId: String,
    val ownerName: String,
    val createdAt: String? = null,
)

data class MomentMediaListDto(val media: List<MomentMediaDto>? = null)

/**
 * The room's one shared player: what is loaded, whether it is playing, and
 * where it was at a known instant of the server's clock. Every phone works out
 * the position now from this; positions are never streamed.
 */
data class MomentPlaybackDto(
    val momentId: String,
    val revision: Int,
    val mediaId: String? = null,
    val kind: String? = null,
    val title: String? = null,
    val durationMs: Long? = null,
    val status: String,
    val positionMs: Long,
    val anchorAt: Long,
    val rate: Double = 1.0,
    val updatedBy: String? = null,
)

data class MomentPlaybackResultDto(val playback: MomentPlaybackDto, val serverNow: Long)

/** LOAD, PLAY, PAUSE, SEEK or STOP, for everyone in the room. */
data class MomentPlaybackBody(val op: String, val mediaId: String? = null, val positionMs: Long? = null)

/** A short-lived address a player can fetch; relative to the API. */
data class MomentStreamDto(val url: String, val expiresAt: String? = null)

/**
 * What a Moment room is right now: which experience is the room, what sits
 * beside it, and its atmosphere. The server holds it; every change arrives
 * whole, with a [revision], so the newest always wins.
 */
data class MomentRuntimeDto(
    val momentId: String,
    val revision: Int,
    val intent: String,
    val primary: String,
    val secondary: List<String> = emptyList(),
    val scene: String,
    val scenePinned: Boolean = false,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
)

/** Where this Moment's live room is, and a short-lived pass into it. */
data class MomentPresenceDto(val url: String, val token: String, val roomName: String)

/** One change to what a room is. Only the field its [op] needs is read. */
data class MomentRoomChangeBody(
    val op: String,
    val intent: String? = null,
    val module: String? = null,
    val scene: String? = null,
)
/** Whichever the sender could manage: readable text, or one copy per device. */
data class SendMomentMessageBody(val body: String? = null, val envelopes: List<MomentEnvelopeBody>? = null)
data class MomentVisibilityBody(val visibility: String)
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
    @PATCH("api/v1/moments/{id}/visibility") suspend fun setVisibility(@Path("id") id: String, @Body body: MomentVisibilityBody): MomentDto
    /** A reaction to the Moment itself — no need to be in its room. */
    @POST("api/v1/moments/{id}/react") suspend fun cheer(@Path("id") id: String, @Body body: MomentReactBody): MomentDto

    @POST("api/v1/moments/{id}/join") suspend fun join(@Path("id") id: String): MomentRoomDto
    @POST("api/v1/moments/{id}/leave") suspend fun leave(@Path("id") id: String)
    @GET("api/v1/moments/{id}/room") suspend fun room(@Path("id") id: String): MomentRoomDto
    /** Shares a video or song from this phone into the Moment. */
    @Multipart
    @POST("api/v1/moments/{id}/media")
    suspend fun shareMedia(
        @Path("id") id: String,
        @Part file: okhttp3.MultipartBody.Part,
        @Part("title") title: okhttp3.RequestBody?,
        @Part("durationMs") durationMs: okhttp3.RequestBody?,
    ): MomentMediaDto

    @GET("api/v1/moments/{id}/media")
    suspend fun listMedia(@Path("id") id: String): MomentMediaListDto

    @DELETE("api/v1/moments/{id}/media/{mediaId}")
    suspend fun unshareMedia(@Path("id") id: String, @Path("mediaId") mediaId: String)

    @POST("api/v1/moments/{id}/media/{mediaId}/stream")
    suspend fun streamUrl(@Path("id") id: String, @Path("mediaId") mediaId: String): MomentStreamDto

    /** Play, pause, seek — for everyone in the room. */
    @POST("api/v1/moments/{id}/playback")
    suspend fun playback(@Path("id") id: String, @Body body: MomentPlaybackBody): MomentPlaybackResultDto

    /** Admission to the room's live faces and voices. Turns nothing on by itself. */
    @POST("api/v1/moments/{id}/presence")
    suspend fun presence(@Path("id") id: String): MomentPresenceDto

    /** Changes what the room is, for everyone in it. */
    @POST("api/v1/moments/{id}/state") suspend fun changeRoom(@Path("id") id: String, @Body body: MomentRoomChangeBody): MomentRuntimeDto
    @POST("api/v1/moments/{id}/messages") suspend fun sendMessage(@Path("id") id: String, @Body body: SendMomentMessageBody): MomentMessageDto
    @POST("api/v1/moments/{id}/messages/{messageId}/react") suspend fun react(@Path("id") id: String, @Path("messageId") messageId: String, @Body body: MomentReactBody)
    @POST("api/v1/moments/{id}/knock") suspend fun knock(@Path("id") id: String)
    @GET("api/v1/moments/{id}/knocks") suspend fun knocks(@Path("id") id: String): KnocksDto
    @POST("api/v1/moments/{id}/knocks/{knockerId}/respond") suspend fun respondToKnock(@Path("id") id: String, @Path("knockerId") knockerId: String, @Body body: KnockResponseBody)
    @POST("api/v1/moments/{id}/invites") suspend fun invite(@Path("id") id: String, @Body body: InviteBody)
}

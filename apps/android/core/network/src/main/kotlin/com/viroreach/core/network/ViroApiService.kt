package com.viroreach.core.network

import com.viroreach.core.model.ContactDiscoveryMatch
import com.viroreach.core.model.PublicProfile
import retrofit2.Response
import retrofit2.http.*

interface ViroApiService {
    @POST("api/v1/auth/otp/request")
    suspend fun requestOtp(@Body body: OtpRequestBody): OtpRequestResponse

    @POST("api/v1/auth/otp/verify")
    suspend fun verifyOtp(@Body body: OtpVerifyBody): OtpVerifyResponse

    @POST("api/v1/auth/refresh")
    suspend fun refreshToken(@Body body: RefreshBody): RefreshResponse

    @GET("api/v1/me")
    suspend fun getMe(): MeResponse

    @PATCH("api/v1/me")
    suspend fun updateMe(@Body body: UpdateMeBody): MeResponse

    @POST("api/v1/blocks")
    suspend fun blockUser(@Body body: BlockUserBody)

    @DELETE("api/v1/blocks/{userId}")
    suspend fun unblockUser(@Path("userId") userId: String)

    @POST("api/v1/connections")
    suspend fun inviteConnection(@Body body: ConnectionInviteBody): ConnectionInviteResponse

    @POST("api/v1/contacts/discover")
    suspend fun discoverContacts(@Body body: DiscoverBody): DiscoverResponse

    @GET("api/v1/directory/exact/{viroId}")
    suspend fun exactViroIdLookup(@Path("viroId") viroId: String): Response<PublicProfile>

    @POST("api/v1/calls/authorize")
    suspend fun authorizeCall(@Body body: AuthorizeCallBody): AuthorizeCallResponse

    @POST("api/v1/calls/{callId}/end")
    suspend fun endCall(@Path("callId") callId: String)

    @POST("api/v1/discovery/ephemeral")
    suspend fun registerEphemeral(@Body body: RegisterEphemeralBody): RegisterEphemeralResponse

    @POST("api/v1/discovery/ephemeral/resolve")
    suspend fun resolveEphemeral(@Body body: ResolveEphemeralBody): ResolveEphemeralResponse

    @POST("api/v1/turn/credentials")
    suspend fun getTurnCredentials(): TurnCredentialsResponse

    @GET("api/v1/offline-trust/material")
    suspend fun getOfflineTrustMaterial(): OfflineTrustMaterialResponse

    @GET("api/v1/offline-trust/call-tickets")
    suspend fun getOfflineCallTickets(): OfflineCallTicketsResponse

    // --- Push notifications ---
    @POST("api/v1/push/tokens")
    suspend fun registerPushToken(@Body body: RegisterPushTokenBody)

    @HTTP(method = "DELETE", path = "api/v1/push/tokens", hasBody = true)
    suspend fun removePushToken(@Body body: RemovePushTokenBody)

    // --- Call history / telemetry ---
    @GET("api/v1/calls/history")
    suspend fun getCallHistory(): List<CallHistoryEntry>

    @POST("api/v1/calls/{callId}/events")
    suspend fun postCallQuality(@Path("callId") callId: String, @Body body: CallQualityBody)

    // --- Messaging ---
    @POST("api/v1/messages")
    suspend fun sendMessage(@Body body: SendMessageBody): SendMessageResponse

    @GET("api/v1/messages/conversations")
    suspend fun listConversations(): List<ConversationSummary>

    @GET("api/v1/messages/conversations/{id}")
    suspend fun conversationHistory(@Path("id") id: String): List<MessageDto>

    @POST("api/v1/messages/conversations/{id}/read")
    suspend fun markConversationRead(@Path("id") id: String)

    // --- Conferences (group calls) ---
    @POST("api/v1/conferences")
    suspend fun createConference(@Body body: CreateConferenceBody): CreateConferenceResponse

    @GET("api/v1/conferences/{id}/participants")
    suspend fun conferenceParticipants(@Path("id") id: String): List<ConferenceParticipantDto>
}

data class OtpRequestBody(val phoneE164: String)
data class OtpRequestResponse(val challengeId: String, val expiresAt: String)
data class OtpVerifyBody(val challengeId: String, val code: String, val devicePublicKey: String, val platform: String, val appVersion: String)
data class OtpVerifyResponse(val accessToken: String, val refreshToken: String, val expiresIn: Int, val userId: String, val deviceId: String, val isNewUser: Boolean)
data class RefreshBody(val refreshToken: String)
data class RefreshResponse(val accessToken: String, val refreshToken: String, val expiresIn: Int)
data class MeResponse(val userId: String, val phoneE164: String, val displayName: String, val avatarUrl: String?, val viroId: String?, val allowCallsFromViroId: String)
data class UpdateMeBody(
    val displayName: String? = null,
    val avatarUrl: String? = null,
)
data class BlockUserBody(val blockedUserId: String)
data class ConnectionInviteBody(val targetUserId: String)
data class ConnectionInviteResponse(val id: String, val status: String)
data class DiscoverBody(val phonesE164: List<String>, val defaultRegion: String? = "ZM")
data class DiscoverResponse(val matches: List<ContactDiscoveryMatch>)
data class AuthorizeCallBody(
    val targetUserId: String,
    val preferredRoute: String? = null,
    val offlineTicket: String? = null,
)
data class AuthorizeCallResponse(val callId: String, val authorized: Boolean, val expiresAt: String, val routeType: String, val sessionMaterial: Map<String, String>?)
data class RegisterEphemeralBody(val ephemeralId: String)
data class RegisterEphemeralResponse(val expiresAt: String)
data class ResolveEphemeralBody(val ephemeralId: String, val authorizedUserIds: List<String>)
data class ResolveEphemeralResponse(val authorized: Boolean, val userId: String? = null)
data class TurnCredentialsResponse(val urls: List<String>, val username: String, val credential: String, val ttlSeconds: Int)
data class OfflineTrustMaterialResponse(val material: List<OfflineTrustEntry>, val syncedAt: String)
data class OfflineTrustEntry(
    val peerUserId: String,
    val trustToken: String,
    val epoch: Int,
    val expiresAt: String,
    val protocolVersion: Int = 1,
    val deviceId: String = "",
)
data class OfflineCallTicketsResponse(val tickets: List<OfflineCallTicket>, val syncedAt: String)
data class OfflineCallTicket(val ticket: String, val peerUserId: String, val expiresAt: String)

// Push
data class RegisterPushTokenBody(val token: String, val provider: String = "fcm")
data class RemovePushTokenBody(val token: String)

// Call history / telemetry
data class CallHistoryEntry(
    val id: String,
    val callerUserId: String,
    val calleeUserId: String,
    val status: String,
    val routeType: String?,
    val startedAt: String?,
    val answeredAt: String?,
    val endedAt: String?,
)
data class CallQualityBody(
    val latency: Double? = null,
    val jitter: Double? = null,
    val packetLoss: Double? = null,
    val bitrate: Double? = null,
    val codec: String? = null,
    val route: String? = null,
    val relayed: Boolean? = null,
)

// Messaging
data class SendMessageBody(
    val toUserId: String? = null,
    val conversationId: String? = null,
    val body: String,
    val clientMsgId: String? = null,
)
data class MessageDto(
    val id: String,
    val conversationId: String,
    val senderUserId: String,
    val body: String?,
    val type: String,
    val clientMsgId: String?,
    val createdAt: String,
)
data class SendMessageResponse(val conversationId: String, val message: MessageDto)
data class ConversationSummary(
    val id: String,
    val isGroup: Boolean,
    val title: String?,
    val participants: List<String>,
    val lastMessage: MessageDto?,
    val unread: Int,
    val updatedAt: String,
)

// Conferences
data class CreateConferenceBody(
    val inviteeUserIds: List<String> = emptyList(),
    val title: String? = null,
)
data class CreateConferenceResponse(val roomId: String, val allowed: List<String>)
data class ConferenceParticipantDto(val userId: String, val deviceId: String)

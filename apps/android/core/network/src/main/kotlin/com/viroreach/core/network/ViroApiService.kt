package com.viroreach.core.network

import com.viroreach.core.model.ContactDiscoveryMatch
import com.viroreach.core.model.PublicProfile
import retrofit2.Response
import retrofit2.http.*

interface ViroApiService {
    @POST("api/v1/auth/otp/request")
    suspend fun requestOtp(@Body body: OtpRequestBody): OtpRequestResponse

    // --- Preferences that follow the account to a new device ---
    @GET("api/v1/preferences")
    suspend fun getPreferences(): PreferencesResponse

    @PUT("api/v1/preferences/contacts")
    suspend fun putContactPreferences(@Body body: ContactPreferencesBody)

    @PUT("api/v1/preferences/appearance")
    suspend fun putAppearancePreferences(@Body body: AppearancePreferencesBody)

    // Connects a phone number to the signed-in account, so an account made
    // with email becomes findable by people who only know the number.
    // Numbers and emails on this account, for the profile page.
    @GET("api/v1/auth/identities")
    suspend fun listIdentities(): IdentitiesResponse

    @HTTP(method = "DELETE", path = "api/v1/auth/identities/{kind}/{id}")
    suspend fun removeIdentity(
        @Path("kind") kind: String,
        @Path("id") id: String,
    ): IdentitiesResponse

    @POST("api/v1/auth/link/email/request")
    suspend fun requestEmailLink(@Body body: EmailLinkRequestBody): PhoneLinkRequestResponse

    @POST("api/v1/auth/link/email/verify")
    suspend fun verifyEmailLink(@Body body: EmailLinkVerifyBody): IdentitiesResponse

    @POST("api/v1/auth/link/phone/request")
    suspend fun requestPhoneLink(@Body body: PhoneLinkRequestBody): PhoneLinkRequestResponse

    @POST("api/v1/auth/link/phone/verify")
    suspend fun verifyPhoneLink(@Body body: PhoneLinkVerifyBody): PhoneLinkVerifyResponse

    @POST("api/v1/auth/firebase/signin")
    suspend fun firebaseSignIn(@Body body: FirebaseSignInBody): FirebaseSignInResponse

    @POST("api/v1/auth/otp/verify")
    suspend fun verifyOtp(@Body body: OtpVerifyBody): OtpVerifyResponse

    @POST("api/v1/auth/refresh")
    suspend fun refreshToken(@Body body: RefreshBody): RefreshResponse

    @GET("api/v1/me")
    suspend fun getMe(): MeResponse

    @PATCH("api/v1/me")
    suspend fun updateMe(@Body body: UpdateMeBody): MeResponse

    @GET("api/v1/me/export")
    suspend fun exportAccount(): AccountExport

    @DELETE("api/v1/me")
    suspend fun deleteAccount(): DeleteAccountResponse

    @GET("api/v1/blocks")
    suspend fun listBlocks(): List<BlockedUser>

    @POST("api/v1/blocks")
    suspend fun blockUser(@Body body: BlockUserBody)

    @DELETE("api/v1/blocks/{userId}")
    suspend fun unblockUser(@Path("userId") userId: String)

    @POST("api/v1/connections")
    suspend fun inviteConnection(@Body body: ConnectionInviteBody): ConnectionInviteResponse

    @GET("api/v1/connections")
    suspend fun listConnections(): List<ConnectionDto>

    @POST("api/v1/connections/{id}/accept")
    suspend fun acceptConnection(@Path("id") id: String): ConnectionDto

    @POST("api/v1/connections/{id}/reject")
    suspend fun rejectConnection(@Path("id") id: String): ConnectionDto

    @DELETE("api/v1/connections/{id}")
    suspend fun revokeConnection(@Path("id") id: String)

    @GET("api/v1/devices")
    suspend fun listDevices(): List<DeviceSummary>

    @DELETE("api/v1/devices/{id}")
    suspend fun revokeDevice(@Path("id") id: String)

    @GET("api/v1/plans")
    suspend fun listPlans(): List<PlanDto>

    @GET("api/v1/me/subscription")
    suspend fun getMySubscription(): SubscriptionDto

    @POST("api/v1/me/subscription")
    suspend fun selectSubscription(@Body body: SelectPlanBody): SubscriptionDto

    @POST("api/v1/contacts/discover")
    suspend fun discoverContacts(@Body body: DiscoverBody): DiscoverResponse

    @GET("api/v1/directory/exact/{viroId}")
    suspend fun exactViroIdLookup(@Path("viroId") viroId: String): Response<PublicProfile>

    @POST("api/v1/calls/authorize")
    suspend fun authorizeCall(@Body body: AuthorizeCallBody): AuthorizeCallResponse

    @POST("api/v1/calls/{callId}/end")
    suspend fun endCall(@Path("callId") callId: String)

    @POST("api/v1/calls/{callId}/invite")
    suspend fun inviteToCall(
        @Path("callId") callId: String,
        @Body body: InviteToCallBody,
    ): InviteToCallResponse

    @POST("api/v1/calls/{callId}/livekit-token")
    suspend fun getLiveKitToken(@Path("callId") callId: String): LiveKitTokenResponse

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

    @POST("api/v1/conferences/{id}/livekit-token")
    suspend fun getConferenceLiveKitToken(@Path("id") id: String): LiveKitTokenResponse

    // --- Presence ---
    @POST("api/v1/presence")
    suspend fun updatePresence(@Body body: UpdatePresenceBody)

    @GET("api/v1/presence/{userId}")
    suspend fun getPresence(@Path("userId") userId: String): PresenceResponse
}

data class OtpRequestBody(val phoneE164: String)
data class OtpRequestResponse(val challengeId: String, val expiresAt: String)
data class OtpVerifyBody(val challengeId: String, val code: String, val devicePublicKey: String, val platform: String, val appVersion: String)
data class OtpVerifyResponse(val accessToken: String, val refreshToken: String, val expiresIn: Int, val userId: String, val deviceId: String, val isNewUser: Boolean)
data class ContactPreferenceDto(
    val phoneE164: String,
    val isFavorite: Boolean = false,
    val customDisplayName: String? = null,
    val isHidden: Boolean = false,
    /** Block/spam for contacts with no Viro account — see migration 009. */
    val isBlocked: Boolean = false,
    val isSpam: Boolean = false,
)
data class ContactPreferencesBody(val contacts: List<ContactPreferenceDto>)
data class AppearancePreferencesDto(
    val themeMode: String = "SYSTEM",
    val fontSize: String = "STANDARD",
    val density: String = "COMFORTABLE",
)
data class AppearancePreferencesBody(
    val themeMode: String? = null,
    val fontSize: String? = null,
    val density: String? = null,
)
data class PreferencesResponse(
    val contacts: List<ContactPreferenceDto> = emptyList(),
    val appearance: AppearancePreferencesDto = AppearancePreferencesDto(),
)

data class PhoneIdentityDto(val id: String, val phoneE164: String, val verified: Boolean)
data class EmailIdentityDto(val id: String, val email: String, val verified: Boolean)
data class IdentitiesResponse(
    val phones: List<PhoneIdentityDto> = emptyList(),
    val emails: List<EmailIdentityDto> = emptyList(),
)
data class EmailLinkRequestBody(val email: String)
data class EmailLinkVerifyBody(val challengeId: String, val code: String)

data class PhoneLinkRequestBody(val phoneE164: String)
data class PhoneLinkRequestResponse(val challengeId: String, val expiresAt: String)
data class PhoneLinkVerifyBody(val challengeId: String, val code: String)

/**
 * outcome "linked": the number was free and is now on this account.
 * outcome "adopted": the number already had an account and this (empty) one was
 * folded into it — accessToken/refreshToken are then present and the client MUST
 * switch to them, because the user id it was holding no longer exists.
 */
data class PhoneLinkVerifyResponse(
    val outcome: String,
    val phoneE164: String,
    val userId: String,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresIn: Int? = null,
)

data class FirebaseSignInBody(
    val idToken: String,
    val devicePublicKey: String,
    val platform: String,
    val appVersion: String,
)
data class FirebaseSignInResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int,
    val userId: String,
    val deviceId: String,
    val email: String,
    val isNewUser: Boolean,
)

data class RefreshBody(val refreshToken: String)
data class RefreshResponse(val accessToken: String, val refreshToken: String, val expiresIn: Int)
data class MeResponse(val userId: String, val phoneE164: String, val displayName: String, val avatarUrl: String?, val viroId: String?, val allowCallsFromViroId: String)
data class DeleteAccountResponse(val deleted: Boolean)
data class AccountExport(
    val userId: String,
    val exportedAt: String,
    val profile: MeResponse? = null,
    val calls: List<CallHistoryEntry> = emptyList(),
    val connections: List<ConnectionInviteResponse> = emptyList(),
    val blocks: List<BlockedUser> = emptyList(),
    val devices: List<DeviceSummary> = emptyList(),
    val messages: List<ExportedMessage> = emptyList(),
)
data class ExportedMessage(
    val id: String,
    val conversationId: String,
    val body: String?,
    val createdAt: String?,
)
data class UpdateMeBody(
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val viroId: String? = null,
    val allowCallsFromViroId: String? = null,
)
data class BlockUserBody(val blockedUserId: String)
data class BlockedUser(val blockedUserId: String)
data class ConnectionInviteBody(val targetUserId: String)
data class ConnectionInviteResponse(val id: String, val status: String)
data class ConnectionDto(
    val id: String,
    val requesterUserId: String,
    val recipientUserId: String,
    val status: String,
    val direction: String,
)
data class DeviceSummary(
    val id: String,
    val platform: String,
    val appVersion: String,
    val createdAt: String,
    val lastSeenAt: String,
)
data class PlanDto(
    val id: String,
    val name: String,
    val description: String?,
    val isActive: Boolean = true,
)
data class SubscriptionDto(
    val planId: String?,
    val planName: String,
    val description: String?,
    val status: String,
    val expiresAt: String?,
    val isDefault: Boolean = false,
)
data class SelectPlanBody(val planId: String)
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
data class LiveKitTokenResponse(val url: String, val token: String, val roomName: String)

data class InviteToCallBody(val userId: String)

data class InviteToCallResponse(val invited: Boolean, val deviceIds: List<String> = emptyList())
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
    val peerUserId: String? = null,
    val peerDisplayName: String? = null,
    val peerPhoneE164: String? = null,
    val direction: String? = null,
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

// Presence
data class UpdatePresenceBody(val state: String)
data class PresenceResponse(val state: String)

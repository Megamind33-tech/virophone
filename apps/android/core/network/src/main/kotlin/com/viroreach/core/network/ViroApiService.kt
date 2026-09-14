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
}

data class OtpRequestBody(val phoneE164: String)
data class OtpRequestResponse(val challengeId: String, val expiresAt: String)
data class OtpVerifyBody(val challengeId: String, val code: String, val devicePublicKey: String, val platform: String, val appVersion: String)
data class OtpVerifyResponse(val accessToken: String, val refreshToken: String, val expiresIn: Int, val userId: String, val deviceId: String, val isNewUser: Boolean)
data class RefreshBody(val refreshToken: String)
data class RefreshResponse(val accessToken: String, val refreshToken: String, val expiresIn: Int)
data class MeResponse(val userId: String, val phoneE164: String, val displayName: String, val avatarUrl: String?, val viroId: String?, val allowCallsFromViroId: String)
data class DiscoverBody(val phonesE164: List<String>, val defaultRegion: String? = "ZM")
data class DiscoverResponse(val matches: List<ContactDiscoveryMatch>)
data class AuthorizeCallBody(val targetUserId: String, val preferredRoute: String? = null)
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

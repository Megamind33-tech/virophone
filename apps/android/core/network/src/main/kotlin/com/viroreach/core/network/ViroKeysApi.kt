package com.viroreach.core.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The key directory: public keys only.
 *
 * This phone publishes what others need in order to start an encrypted session
 * with it, and collects the same for the people it writes to. No private key
 * ever appears in any of these bodies — they never leave the device.
 */
interface ViroKeysApi {
    /** Publish this device's identity, its signed and Kyber prekeys, and a first batch. */
    @POST("api/v1/keys")
    suspend fun publish(@Body body: KeyBundleBody): PrekeyStatusDto

    /** How many one-time prekeys are left on the server for this device. */
    @GET("api/v1/keys/status")
    suspend fun status(): PrekeyStatusDto

    /** Top up when running low. */
    @POST("api/v1/keys/prekeys")
    suspend fun topUp(@Body body: PrekeyBatchBody): PrekeyStatusDto

    /** Which devices this person can be reached on. Asking costs no prekey. */
    @GET("api/v1/keys/{userId}/devices")
    suspend fun devices(@Path("userId") userId: String): UserDevicesDto

    /**
     * What is needed to start talking to the named devices. Every answer spends
     * one of that device's one-time prekeys, so ask only about devices this
     * phone has no session with yet.
     */
    @GET("api/v1/keys/{userId}")
    suspend fun bundles(
        @Path("userId") userId: String,
        @Query("devices") deviceIds: String,
    ): UserBundlesDto
}

data class PublicKeyBody(val keyId: Int, val publicKey: String, val signature: String)
data class OneTimePrekeyBody(val keyId: Int, val publicKey: String)

data class KeyBundleBody(
    val registrationId: Int,
    val identityKey: String,
    val signedPreKey: PublicKeyBody,
    val kyberPreKey: PublicKeyBody,
    val oneTimePreKeys: List<OneTimePrekeyBody>? = null,
)

data class PrekeyBatchBody(val oneTimePreKeys: List<OneTimePrekeyBody>)

data class PrekeyStatusDto(
    val available: Int? = null,
    /** Top up below this. */
    val lowWater: Int? = null,
    val max: Int? = null,
)

data class UserDevicesDto(val userId: String? = null, val deviceIds: List<String>? = null)

data class UserBundlesDto(val userId: String? = null, val devices: List<DeviceBundleDto>? = null)

/** One device of the person being written to. */
data class DeviceBundleDto(
    val deviceId: String,
    val registrationId: Int,
    val identityKey: String,
    val signedPreKey: PublicKeyBody,
    val kyberPreKey: PublicKeyBody,
    /** Null when that device has run out; the session still starts. */
    val preKey: OneTimePrekeyBody? = null,
)

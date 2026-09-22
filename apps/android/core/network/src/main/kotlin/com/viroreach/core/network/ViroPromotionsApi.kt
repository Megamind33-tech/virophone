package com.viroreach.core.network

import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * One thing a campaign is saying right now.
 *
 * [action] is a Viro route rather than a web address — a promotion that could
 * send somebody anywhere would be a phishing surface with an admin login in
 * front of it.
 */
data class PromotionDto(
    val id: String,
    val campaignId: String,
    val campaign: String,
    val title: String,
    val body: String? = null,
    val action: String? = null,
)

data class PromotionsDto(val promotions: List<PromotionDto> = emptyList())

/**
 * What the platform is saying, and how to make it stop saying it.
 *
 * The server does the deciding: drafts, archived campaigns, anything outside
 * its period, anything for a different audience and anything this person has
 * already waved away never arrive here. The app draws what it is given.
 */
interface ViroPromotionsApi {
    @GET("api/v1/promotions/live")
    suspend fun live(): PromotionsDto

    /** For good, and on every device this person signs in to. */
    @POST("api/v1/promotions/{id}/dismiss")
    suspend fun dismiss(@Path("id") id: String)
}

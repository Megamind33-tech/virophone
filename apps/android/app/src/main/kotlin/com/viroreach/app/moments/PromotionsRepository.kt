package com.viroreach.app.moments

import com.viroreach.core.network.PromotionDto
import com.viroreach.core.network.ViroPromotionsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the platform is saying, if anything.
 *
 * Almost always nothing, which is the point: this holds an empty list far more
 * often than not, and a screen showing it must look untouched when it is
 * empty rather than leaving a gap where a promotion would go.
 *
 * Dismissal is the server's business, so waving one away here reaches every
 * device this person signs in to and survives a reinstall. It is also removed
 * from the list immediately rather than waiting for the answer — tapping the
 * cross should feel like it worked, and a failed request only means it comes
 * back on the next refresh rather than never going away.
 */
class PromotionsRepository(
    private val api: ViroPromotionsApi,
    private val userId: () -> String?,
) {
    private val _promotions = MutableStateFlow<List<PromotionDto>>(emptyList())
    val promotions = _promotions.asStateFlow()
    private var owner: String? = null

    /** Another account signed in: nothing of the last one's stays on screen. */
    fun clear() {
        owner = null
        _promotions.value = emptyList()
    }

    suspend fun refresh() {
        val account = userId() ?: return clear()
        if (owner != account) {
            _promotions.value = emptyList()
            owner = account
        }
        try {
            val fresh = api.live().promotions
            if (account != userId()) return clear()
            _promotions.value = fresh
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing is said about a failure here. A promotion that could not
            // be fetched is a promotion nobody misses.
        }
    }

    suspend fun dismiss(id: String) {
        _promotions.value = _promotions.value.filterNot { it.id == id }
        try {
            api.dismiss(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // It reappears on the next refresh, which is the right failure:
            // silently keeping it hidden would be a promise this cannot make.
        }
    }
}

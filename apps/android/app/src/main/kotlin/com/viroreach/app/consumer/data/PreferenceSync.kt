package com.viroreach.app.consumer.data

import android.util.Log
import com.viroreach.app.consumer.CachedContactsRepository
import com.viroreach.core.network.AppearancePreferencesBody
import com.viroreach.core.network.ContactPreferenceDto
import com.viroreach.core.network.ContactPreferencesBody
import com.viroreach.core.network.ViroApiService

/**
 * Carries the preferences that used to live only on the handset — favourites,
 * custom contact names, hidden contacts and appearance — to and from the
 * server, so a replacement phone restores them on sign-in.
 *
 * Calls, messages, contacts and connections already followed the account
 * because they are server rows; these four did not, and were lost with the
 * device. This is deliberately not a backup/restore mechanism: there is no
 * snapshot to go stale, just preferences that belong to the account.
 *
 * Keyed on E.164 phone number, which is the only contact identifier that means
 * the same thing on a different handset.
 */
class PreferenceSync(
    private val api: ViroApiService,
    private val contacts: CachedContactsRepository,
) {

    /**
     * Pulls server preferences and applies them locally. Runs after sign-in,
     * once contacts have been loaded, so there are rows to apply them to.
     */
    suspend fun pull(): Result<Int> = runCatching {
        val remote = api.getPreferences()
        var applied = 0
        if (remote.contacts.isNotEmpty()) {
            applied = contacts.applyRemotePreferences(
                remote.contacts.associateBy { it.phoneE164 },
            )
        }
        Log.i(TAG, "PREFERENCES_PULLED contacts=${remote.contacts.size} applied=$applied")
        applied
    }.onFailure { Log.w(TAG, "PREFERENCES_PULL_FAILED ${it.message}") }

    /**
     * Pushes every locally-set preference. Whole rows rather than deltas, so an
     * interrupted sync cannot leave one half-applied; the server drops rows that
     * carry no preference at all.
     */
    suspend fun push(): Result<Int> = runCatching {
        val local = contacts.localPreferencesForSync()
        if (local.isEmpty()) return@runCatching 0
        api.putContactPreferences(ContactPreferencesBody(local))
        Log.i(TAG, "PREFERENCES_PUSHED count=${local.size}")
        local.size
    }.onFailure { Log.w(TAG, "PREFERENCES_PUSH_FAILED ${it.message}") }

    /** Fire-and-forget after a single toggle or rename. */
    suspend fun pushOne(
        phoneE164: String?,
        isFavorite: Boolean,
        customDisplayName: String?,
        isHidden: Boolean,
        isBlocked: Boolean = false,
        isSpam: Boolean = false,
    ) {
        val phone = phoneE164?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            api.putContactPreferences(
                ContactPreferencesBody(
                    listOf(
                        ContactPreferenceDto(
                            phoneE164 = phone,
                            isFavorite = isFavorite,
                            customDisplayName = customDisplayName,
                            isHidden = isHidden,
                            isBlocked = isBlocked,
                            isSpam = isSpam,
                        ),
                    ),
                ),
            )
        }.onFailure { Log.w(TAG, "PREFERENCE_PUSH_ONE_FAILED ${it.message}") }
    }

    suspend fun pushAppearance(themeMode: String, fontSize: String, density: String) {
        runCatching {
            api.putAppearancePreferences(
                AppearancePreferencesBody(themeMode, fontSize, density),
            )
        }.onFailure { Log.w(TAG, "APPEARANCE_PUSH_FAILED ${it.message}") }
    }

    private companion object {
        const val TAG = "ViroPrefSync"
    }
}

package com.viroreach.app.personalization

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.viroreach.core.network.ProfilePhotoUploader
import com.viroreach.core.network.UpdateMeBody
import com.viroreach.core.network.ViroApiService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.profileDataStore by preferencesDataStore("viro_profile")

data class UserProfile(
    val userId: String? = null,
    val displayName: String = "",
    val phoneE164: String? = null,
    val serverAvatarUrl: String? = null,
    val localPhotoUri: String? = null,
    val viroId: String? = null,
    val allowCallsFromViroId: String = "CONNECTIONS_ONLY",
) {
    val effectivePhotoUrl: String? = serverAvatarUrl?.takeIf { it.isNotBlank() }
        ?: localPhotoUri?.takeIf { it.isNotBlank() }
}

class ProfileRepository(
    context: Context,
    private val api: ViroApiService,
    private val photoUploader: ProfilePhotoUploader,
) {
    private val appContext = context.applicationContext
    private val store = appContext.profileDataStore

    val profile: Flow<UserProfile> = store.data.map { prefs ->
        UserProfile(
            userId = prefs[KEY_USER_ID],
            displayName = prefs[KEY_DISPLAY_NAME] ?: "",
            phoneE164 = prefs[KEY_PHONE],
            serverAvatarUrl = prefs[KEY_SERVER_AVATAR]?.ifBlank { null },
            localPhotoUri = prefs[KEY_LOCAL_PHOTO]?.ifBlank { null },
            viroId = prefs[KEY_VIRO_ID]?.ifBlank { null },
            allowCallsFromViroId = prefs[KEY_ALLOW_CALLS] ?: "CONNECTIONS_ONLY",
        )
    }

    suspend fun refreshFromServer(): Result<UserProfile> = runCatching {
        applyMeResponse(api.getMe())
        profile.first()
    }

    suspend fun uploadPhoto(uri: Uri): Result<UserProfile> = runCatching {
        ProfilePhotoCapture.takePersistableReadPermission(appContext, uri)
        store.edit { prefs -> prefs[KEY_LOCAL_PHOTO] = uri.toString() }
        val me = photoUploader.upload(uri)
        applyMeResponse(me)
        store.edit { prefs -> prefs.remove(KEY_LOCAL_PHOTO) }
        profile.first()
    }

    suspend fun removePhoto(): Result<UserProfile> = runCatching {
        store.edit { prefs -> prefs.remove(KEY_LOCAL_PHOTO) }
        applyMeResponse(api.updateMe(UpdateMeBody(avatarUrl = null)))
        profile.first()
    }

    suspend fun saveProfile(displayName: String): Result<UserProfile> = runCatching {
        val trimmed = displayName.trim()
        applyMeResponse(api.updateMe(UpdateMeBody(displayName = trimmed)))
        profile.first()
    }

    suspend fun setAllowCallsFromViroId(value: String): Result<UserProfile> = runCatching {
        applyMeResponse(api.updateMe(UpdateMeBody(allowCallsFromViroId = value)))
        profile.first()
    }

    private suspend fun applyMeResponse(me: com.viroreach.core.network.MeResponse) {
        store.edit { prefs ->
            prefs[KEY_USER_ID] = me.userId
            prefs[KEY_DISPLAY_NAME] = me.displayName
            prefs[KEY_PHONE] = me.phoneE164
            prefs[KEY_SERVER_AVATAR] = me.avatarUrl.orEmpty()
            prefs[KEY_VIRO_ID] = me.viroId.orEmpty()
            prefs[KEY_ALLOW_CALLS] = me.allowCallsFromViroId.ifBlank { "CONNECTIONS_ONLY" }
        }
    }

    companion object {
        private val KEY_USER_ID = stringPreferencesKey("user_id")
        private val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
        private val KEY_PHONE = stringPreferencesKey("phone_e164")
        private val KEY_SERVER_AVATAR = stringPreferencesKey("server_avatar")
        private val KEY_LOCAL_PHOTO = stringPreferencesKey("local_photo_uri")
        private val KEY_VIRO_ID = stringPreferencesKey("viro_id")
        private val KEY_ALLOW_CALLS = stringPreferencesKey("allow_calls_from_viro_id")
    }
}

package com.viroreach.app.consumer

import com.viroreach.app.personalization.AvatarRequest
import com.viroreach.app.personalization.AvatarResolver

fun ContactListItem.resolveAvatarUrl(): String? {
    customPhotoUri?.takeIf { it.isNotBlank() }?.let { return it }
    val resolved = AvatarResolver.resolve(
        AvatarRequest(
            displayName = effectiveDisplayName,
            viroProfilePhotoUrl = viroProfilePhotoUrl,
            viroProfilePhotoVersion = viroProfilePhotoVersion,
            localContactPhotoUri = localPhotoUri,
        ),
    )
    return resolved.imageUrl
}

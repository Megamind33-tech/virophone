package com.viroreach.app.personalization

/**
 * Strict avatar resolution order:
 * 1. Viro profile photo URL
 * 2. Local Android contact photo URI (device-local only)
 * 3. Initials fallback
 *
 * No web scraping, no social inference from phone numbers.
 */
data class AvatarRequest(
    val displayName: String,
    val viroProfilePhotoUrl: String? = null,
    val viroProfilePhotoVersion: String? = null,
    val localContactPhotoUri: String? = null,
)

enum class AvatarSourceKind {
    VIRO_PROFILE,
    LOCAL_CONTACT,
    INITIALS,
}

data class ResolvedAvatar(
    val kind: AvatarSourceKind,
    val imageUrl: String? = null,
    val initials: String,
)

object AvatarResolver {
    fun resolve(request: AvatarRequest): ResolvedAvatar {
        val initials = initialsFor(request.displayName)
        if (!request.viroProfilePhotoUrl.isNullOrBlank()) {
            return ResolvedAvatar(
                kind = AvatarSourceKind.VIRO_PROFILE,
                imageUrl = request.viroProfilePhotoUrl,
                initials = initials,
            )
        }
        if (!request.localContactPhotoUri.isNullOrBlank()) {
            return ResolvedAvatar(
                kind = AvatarSourceKind.LOCAL_CONTACT,
                imageUrl = request.localContactPhotoUri,
                initials = initials,
            )
        }
        return ResolvedAvatar(kind = AvatarSourceKind.INITIALS, initials = initials)
    }

    fun initialsFor(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val letterParts = parts.map { part -> part.filter { it.isLetter() } }.filter { it.isNotBlank() }
        return when {
            letterParts.isEmpty() -> "?"
            letterParts.size == 1 -> letterParts[0].take(2).uppercase()
            else -> "${letterParts.first().first()}${letterParts.last().first()}".uppercase()
        }
    }
}

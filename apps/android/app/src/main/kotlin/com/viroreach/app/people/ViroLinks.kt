package com.viroreach.app.people

/**
 * Shared "Add me on Viro" links. Two shapes reach the app:
 *   viro://u/<viro-id>                         — tapped in a chat, opens Viro directly
 *   https://<host>/api/v1/invite/<viro-id>     — the public page's "Open in Viro" button
 */
object ViroLinks {
    /** The Viro ID in a link, without the @, or null when the link isn't one of ours. */
    fun viroIdFromUrl(url: String?): String? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val withoutScheme = raw.substringAfter("://", missingDelimiterValue = "")
        if (withoutScheme.isEmpty()) return null
        val scheme = raw.substringBefore("://").lowercase()
        // Strip query and fragment, then split the path.
        val path = withoutScheme.substringBefore('?').substringBefore('#')
        val parts = path.split('/').filter { it.isNotBlank() }
        val id = when {
            // viro://u/<id> — the host is "u"
            scheme == "viro" && parts.firstOrNull()?.lowercase() == "u" -> parts.getOrNull(1)
            // …/invite/<id>
            parts.size >= 2 && parts[parts.size - 2].lowercase() == "invite" -> parts.last()
            else -> null
        }
        return id?.removePrefix("@")?.trim()?.takeIf { it.isNotEmpty() && it.none { c -> c == '@' || c == ' ' } }
    }
}

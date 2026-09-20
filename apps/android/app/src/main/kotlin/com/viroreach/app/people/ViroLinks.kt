package com.viroreach.app.people

/**
 * Shared "Add me on Viro" links. Two shapes reach the app:
 *   viro://u/<viro-id>                         — tapped in a chat, opens Viro directly
 *   https://<host>/api/v1/invite/<viro-id>     — the public page's "Open in Viro" button
 */
object ViroLinks {
    /** The group invite code in a link, or null when it isn't a group link. */
    fun groupCodeFromUrl(url: String?): String? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val scheme = raw.substringBefore("://").lowercase()
        val rest = raw.substringAfter("://", missingDelimiterValue = "")
        if (rest.isEmpty()) return null
        val path = rest.substringBefore('?').substringBefore('#')
        val parts = path.split('/').filter { it.isNotBlank() }
        val code = when {
            // viro://g/<code>
            scheme == "viro" && parts.firstOrNull()?.lowercase() == "g" -> parts.getOrNull(1)
            // …/invite/g/<code>
            parts.size >= 3 && parts[parts.size - 3].lowercase() == "invite" && parts[parts.size - 2].lowercase() == "g" -> parts.last()
            else -> null
        }
        return code?.takeIf { it.isNotBlank() && it.length in 6..32 && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
    }

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

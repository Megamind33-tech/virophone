package com.viroreach.feature.calling

import com.viroreach.core.network.DiscoverBody
import com.viroreach.core.network.ViroApiService
import com.viroreach.feature.contacts.PhoneNormalizer

data class ResolvedCallTarget(
    val inputIdentity: String,
    val userId: String,
    val phoneE164: String?,
    val displayName: String?,
    val relationshipState: String?,
    val resolvedAt: Long = System.currentTimeMillis(),
)

class CallTargetException(val code: String, message: String) : Exception(message)

class CallTargetResolver(private val api: ViroApiService) {
    suspend fun resolveTarget(input: String): ResolvedCallTarget {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            throw CallTargetException("INVALID_TARGET", "Target is required")
        }
        if (USER_UUID.matches(trimmed)) {
            return ResolvedCallTarget(
                inputIdentity = trimmed,
                userId = trimmed,
                phoneE164 = null,
                displayName = null,
                relationshipState = null,
            )
        }
        val e164 = PhoneNormalizer.normalizeToE164(trimmed)
            ?: throw CallTargetException("INVALID_TARGET", "Invalid phone number format")
        val response = api.discoverContacts(DiscoverBody(listOf(e164)))
        val match = response.matches.firstOrNull()
            ?: throw CallTargetException(
                "TARGET_NOT_FOUND",
                "This number is not on Viro yet. They need to sign up first.",
            )
        return ResolvedCallTarget(
            inputIdentity = trimmed,
            userId = match.userId,
            phoneE164 = e164,
            displayName = match.displayName,
            relationshipState = match.relationshipState.toString(),
        )
    }

    companion object {
        private val USER_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE,
        )
    }
}

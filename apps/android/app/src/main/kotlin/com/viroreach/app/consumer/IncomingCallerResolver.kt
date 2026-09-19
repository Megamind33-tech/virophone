package com.viroreach.app.consumer

import com.viroreach.app.consumer.call.CallPresentation
import com.viroreach.app.session.SessionManager
import com.viroreach.feature.calling.IncomingCallInfo
import com.viroreach.feature.contacts.PhoneNumberFormatter
import com.viroreach.app.consumer.PeerNameResolver

suspend fun resolveIncomingCallerPresentation(
    session: SessionManager,
    info: IncomingCallInfo,
): CallPresentation = runCatching {
    val phone = info.callerPhoneE164
    val cachedByPhone = phone?.let { session.contactsRepository.findByPhone(it) }
    val cachedByUser = info.callerUserId?.let { session.contactsRepository.findByUserId(it) }
    val cached = cachedByPhone ?: cachedByUser
    val serverName = info.callerDisplayName?.trim()?.takeIf { it.isNotEmpty() }
    val displayName = cached?.effectiveDisplayName
        ?: serverName
        ?: phone?.let { PhoneNumberFormatter.formatE164International(it) }
        // Deliberately no id fallback: "Viro user a5b4413b" is not a name, and
        // it is what replaced saved contacts' names on the incoming-call screen.
        ?: PeerNameResolver.UNKNOWN
    CallPresentation(
        displayName = displayName,
        avatarUrl = cached?.resolveAvatarUrl(),
        phoneE164 = phone ?: cached?.phoneE164,
    )
}.getOrElse {
    CallPresentation(
        displayName = info.callerDisplayName?.takeIf { it.isNotBlank() } ?: "Incoming call",
        phoneE164 = info.callerPhoneE164,
    )
}

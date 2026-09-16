package com.viroreach.feature.calling

data class IncomingCallInfo(
    val callId: String,
    val callerUserId: String?,
    val callerDeviceId: String?,
    val callerPhoneE164: String?,
    val callerDisplayName: String? = null,
)

package com.viroreach.feature.calling

data class ChatSignalingEvent(
    val conversationId: String,
    val fromUserId: String?,
    val body: String,
)

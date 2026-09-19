package com.viroreach.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a notification tap asked to go. Held until the signed-in UI is ready
 * to take it, then consumed, so a tap during startup is not lost.
 */
object AppNavigation {
    data class Target(
        val screen: String,
        val peerUserId: String? = null,
        val conversationId: String? = null,
        /** A Viro ID or email to search for, from a shared link. */
        val query: String? = null,
    )

    private val _pending = MutableStateFlow<Target?>(null)
    val pending: StateFlow<Target?> = _pending.asStateFlow()

    fun request(target: Target) {
        _pending.value = target
    }

    fun consume(): Target? = _pending.value.also { _pending.value = null }
}

package com.viroreach.app.root

enum class AppStartupState {
    RestoringSession,
    Unauthenticated,
    Authenticated,
}

fun resolveStartupState(
    restoring: Boolean,
    isAuthenticated: Boolean,
): AppStartupState = when {
    restoring -> AppStartupState.RestoringSession
    isAuthenticated -> AppStartupState.Authenticated
    else -> AppStartupState.Unauthenticated
}

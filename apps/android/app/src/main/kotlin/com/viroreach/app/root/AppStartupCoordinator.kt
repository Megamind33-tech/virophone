package com.viroreach.app.root

enum class AppStartupState {
    RestoringSession,
    Unauthenticated,
    /** Signed in, loading contacts and history before the app is shown. */
    Preparing,
    /** Signed in, but the one-time name + Viro ID step hasn't been done. */
    ProfileSetup,
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

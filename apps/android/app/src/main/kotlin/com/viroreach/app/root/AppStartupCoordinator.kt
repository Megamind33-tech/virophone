package com.viroreach.app.root

enum class AppStartupState {
    RestoringSession,
    Unauthenticated,
    /** Signed in, loading contacts and history before the app is shown. */
    Preparing,
    /** Signed in, but the one-time name + Viro ID step hasn't been done. */
    ProfileSetup,
    /**
     * Straight after ProfileSetup, and only then: the chance to say who you
     * are. Everyone else reaches the same questions from You, so nobody who
     * already has an account is stopped at the door to answer them.
     */
    AboutYou,
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

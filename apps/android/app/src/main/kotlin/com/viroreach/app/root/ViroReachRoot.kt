package com.viroreach.app.root

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.viroreach.app.auth.AuthFlow
import com.viroreach.app.consumer.ConsumerNav
import com.viroreach.app.developer.DeveloperAccess
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroSpacing
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.viroreach.core.designsystem.components.ViroLogoMark

@Composable
fun ViroReachRoot() {
    val context = LocalContext.current
    val session = remember { SessionManager.get(context) }
    var startupState by remember { mutableStateOf(AppStartupState.RestoringSession) }

    LaunchedEffect(session) {
        startupState = runCatching {
            if (session.restoreSessionIfAuthenticated()) {
                AppStartupState.Preparing
            } else {
                AppStartupState.Unauthenticated
            }
        }.getOrElse {
            if (session.isAuthenticated) AppStartupState.Preparing
            else AppStartupState.Unauthenticated
        }
    }

    LaunchedEffect(startupState) { com.viroreach.app.diagnostics.Breadcrumbs.startup(startupState.name) }

    // Both entry paths — a fresh sign-in and a restored session — pass through
    // Preparing, so neither one can show a half-loaded app.
    LaunchedEffect(startupState) {
        if (startupState == AppStartupState.Preparing) {
            runCatching { session.warmUpForSession() }
            // Ask for a name and Viro ID once. Offline, or on an older server,
            // this is false and the app opens as before.
            val needsName = runCatching { session.people.needsProfileSetup() }.getOrDefault(false)
            startupState = if (needsName) AppStartupState.ProfileSetup else AppStartupState.Authenticated
        }
    }

    when (startupState) {
        // One branch for both, so the opening screen is the same screen
        // throughout and its arrival plays once. Two branches would be two
        // screens as far as Compose is concerned, and the logo would arrive,
        // vanish and arrive again the moment restoring turned into preparing.
        AppStartupState.RestoringSession, AppStartupState.Preparing -> OpeningScreen(
            message = if (startupState == AppStartupState.Preparing) {
                "Getting your contacts and calls ready…"
            } else {
                "Restoring secure session…"
            },
        )
        AppStartupState.ProfileSetup -> com.viroreach.app.people.ProfileSetupScreen(
            session = session,
            onDone = { startupState = AppStartupState.AboutYou },
        )
        AppStartupState.AboutYou -> com.viroreach.app.people.AboutYouSetupScreen(
            session = session,
            onDone = { startupState = AppStartupState.Authenticated },
        )
        AppStartupState.Unauthenticated -> AuthFlow(
            session = session,
            onAuthenticated = { startupState = AppStartupState.Preparing },
        )
        AppStartupState.Authenticated -> CallEventCoordinator(session = session) {
            ConsumerNav(
                session = session,
                showDeveloperEntry = DeveloperAccess.isHarnessAvailable(),
                onLogout = { startupState = AppStartupState.Unauthenticated },
            )
        }
    }
}


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
            startupState = AppStartupState.Authenticated
        }
    }

    when (startupState) {
        AppStartupState.RestoringSession -> StartupLoadingScreen()
        AppStartupState.Preparing -> StartupLoadingScreen(
            message = "Getting your contacts and calls ready…",
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

@Composable
private fun StartupLoadingScreen(message: String = "Restoring secure session…") {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ViroSpacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The logo alone: no wordmark, no spinner, no second mark. The
        // message still reaches screen readers.
        ViroLogoMark(modifier = Modifier.semantics { contentDescription = message })
    }
}

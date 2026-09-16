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
import com.viroreach.core.designsystem.components.ViroLoadingIndicator

@Composable
fun ViroReachRoot() {
    val context = LocalContext.current
    val session = remember { SessionManager.get(context) }
    var startupState by remember { mutableStateOf(AppStartupState.RestoringSession) }

    LaunchedEffect(session) {
        startupState = runCatching {
            if (session.restoreSessionIfAuthenticated()) {
                AppStartupState.Authenticated
            } else {
                AppStartupState.Unauthenticated
            }
        }.getOrElse {
            if (session.isAuthenticated) AppStartupState.Authenticated
            else AppStartupState.Unauthenticated
        }
    }

    when (startupState) {
        AppStartupState.RestoringSession -> StartupLoadingScreen()
        AppStartupState.Unauthenticated -> AuthFlow(
            session = session,
            onAuthenticated = { startupState = AppStartupState.Authenticated },
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
private fun StartupLoadingScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ViroSpacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Viro Call", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(ViroSpacing.lg))
        ViroLoadingIndicator(message = "Restoring secure session…")
    }
}

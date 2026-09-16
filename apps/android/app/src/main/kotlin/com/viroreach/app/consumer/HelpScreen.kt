package com.viroreach.app.consumer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.ViroBackButton
import com.viroreach.core.designsystem.components.ViroSafeScreen
import com.viroreach.core.designsystem.components.ViroScreenBackground

@Composable
fun HelpScreen(onBack: () -> Unit) {
    ViroScreenBackground {
        ViroSafeScreen {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(ViroSpacing.md),
                verticalArrangement = Arrangement.spacedBy(ViroSpacing.md),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ViroBackButton(onClick = onBack)
                    Spacer(Modifier.width(ViroSpacing.sm))
                    Text(
                        "Help",
                        style = MaterialTheme.typography.titleLarge,
                        color = ViroColors.textPrimary,
                    )
                }
                HelpCard(
                    title = "Calling",
                    body = "Tap a contact and Call. Viro uses your data connection " +
                        "(not your mobile minutes). Stay on the call screen until you hear the other person.",
                )
                HelpCard(
                    title = "Group calls",
                    body = "On Contacts, tap Select, choose up to several people, then start a group call. " +
                        "Everyone who is on Viro is invited. Each person connects directly (mesh) for small groups.",
                )
                HelpCard(
                    title = "Messages",
                    body = "Open a contact and tap Message. Texts send through Viro’s servers so they arrive " +
                        "even if the other person is offline, then sync when they reconnect.",
                )
                HelpCard(
                    title = "Blocked contacts",
                    body = "You → Privacy → Blocked contacts. Unblock someone there. " +
                        "Blocked people cannot call or message you.",
                )
                HelpCard(
                    title = "Sign in",
                    body = "Choose your country, enter the phone number on this device, then enter the code " +
                        "we send. Email sign-in is available when your account has an email identity.",
                )
                HelpCard(
                    title = "Delete account",
                    body = "You → Delete account permanently closes your Viro identity, signs out every device, " +
                        "and removes your profile from discovery. This cannot be undone.",
                )
            }
        }
    }
}

@Composable
private fun HelpCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = ViroColors.surface)) {
        Column(Modifier.padding(ViroSpacing.md), verticalArrangement = Arrangement.spacedBy(ViroSpacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = ViroColors.textPrimary)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = ViroColors.textSecondary)
        }
    }
}

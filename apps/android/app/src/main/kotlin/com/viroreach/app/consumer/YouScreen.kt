package com.viroreach.app.consumer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.*
import com.viroreach.feature.contacts.PhoneNumberFormatter

@Composable
fun YouScreen(
    session: SessionManager,
    showDeveloperEntry: Boolean,
    onDeveloper: () -> Unit,
    onAppearance: () -> Unit,
    onEditProfile: () -> Unit,
    onBlockedContacts: () -> Unit,
    onLogout: () -> Unit,
) {
    val viewModel = remember(session, showDeveloperEntry) {
        YouViewModel(session, showDeveloperEntry)
    }
    val profile by session.profileRepository.profile.collectAsState(
        initial = com.viroreach.app.personalization.UserProfile(),
    )
    LaunchedEffect(Unit) {
        viewModel.refresh()
        session.profileRepository.refreshFromServer()
    }
    val state = viewModel.uiState
    val displayName = profile.displayName.ifBlank { "You" }

    ViroScreenBackground {
        ViroSafeScreen {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(ViroSpacing.md),
                verticalArrangement = Arrangement.spacedBy(ViroSpacing.md),
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ViroAvatar(
                        imageUrl = profile.effectivePhotoUrl,
                        size = ViroAvatarSize.Hero,
                        kind = ViroAvatarKind.USER_PROFILE_IMAGE,
                    )
                    Spacer(Modifier.height(ViroSpacing.sm))
                    Text(displayName, style = MaterialTheme.typography.headlineMedium, color = ViroColors.textPrimary)
                    Text(
                        state.phoneE164?.let { PhoneNumberFormatter.formatE164International(it) } ?: "—",
                        style = MaterialTheme.typography.bodyLarge,
                        color = ViroColors.textSecondary,
                    )
                    Text(
                        "Edit profile",
                        color = ViroColors.accent,
                        modifier = Modifier
                            .clickable(onClick = onEditProfile)
                            .padding(vertical = ViroSpacing.sm),
                    )
                }
                SettingsSection(title = "Account") {
                    SettingsNavRow("Profile", onEditProfile)
                    SettingsRow(
                        label = "Phone",
                        value = state.phoneE164?.let { PhoneNumberFormatter.formatE164International(it) } ?: "—",
                    )
                }
                SettingsSection(title = "Preferences") {
                    SettingsNavRow("Appearance", onAppearance)
                    SettingsRow(label = "Calling", value = "Default")
                    SettingsRow(label = "Notifications", value = "Coming soon")
                }
                SettingsSection(title = "Privacy & Security") {
                    SettingsNavRow("Blocked contacts", onBlockedContacts)
                }
                SettingsSection(title = "Support") {
                    SettingsRow(label = "Help", value = "Coming soon")
                    SettingsRow(label = "About Viro", value = state.versionName)
                }
                if (state.showDeveloperEntry) {
                    SettingsSection(title = "Internal") {
                        SettingsNavRow("Developer", onDeveloper)
                    }
                }
                ViroSecondaryButton(
                    text = "Log out",
                    onClick = {
                        viewModel.logout()
                        onLogout()
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ViroSpacing.xs)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = ViroColors.textMuted)
        Card(colors = CardDefaults.cardColors(containerColor = ViroColors.surface)) {
            Column(
                Modifier.padding(ViroSpacing.md),
                verticalArrangement = Arrangement.spacedBy(ViroSpacing.sm),
                content = content,
            )
        }
    }
}

@Composable
private fun SettingsNavRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = ViroColors.accent,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = ViroSpacing.sm),
    )
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = ViroColors.textPrimary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = ViroColors.textSecondary)
    }
}

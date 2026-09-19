package com.viroreach.app.personalization

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.network.IdentitiesResponse
import com.viroreach.feature.contacts.PhoneNumberFormatter
import kotlinx.coroutines.launch

/**
 * The numbers and emails attached to this account, on the profile page.
 *
 * An account can hold several of each — someone with two SIMs should be
 * reachable on both — and every one has to be verified by a code before it is
 * attached. Typing a number is not proof of holding it, and without that step
 * anyone could claim anyone else's number.
 *
 * A number belongs to exactly one account. That is enforced by a UNIQUE
 * constraint on phone_e164 in the database, not by this screen, so a client that
 * tried to attach the same number to fifteen accounts would simply be refused.
 */
@Composable
fun ProfileIdentitiesSection(
    session: SessionManager,
    onAddPhone: () -> Unit,
    onAddEmail: () -> Unit,
) {
    var identities by remember { mutableStateOf<IdentitiesResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loading = true
        runCatching { session.api.listIdentities() }
            .onSuccess { identities = it; message = null }
            .onFailure { message = "Couldn't load your numbers and emails" }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxWidth()) {
        Text(
            "Numbers and emails",
            style = MaterialTheme.typography.titleMedium,
            color = ViroColors.textPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "People can find you on Viro with any verified number here.",
            style = MaterialTheme.typography.bodySmall,
            color = ViroColors.textSecondary,
        )
        Spacer(Modifier.height(ViroSpacing.sm))

        message?.let {
            Text(it, color = ViroColors.consumerWarning, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(ViroSpacing.sm))
        }

        if (loading && identities == null) {
            CircularProgressIndicator(color = ViroColors.accent, modifier = Modifier.size(24.dp))
        } else {
            val data = identities
            val total = (data?.phones?.size ?: 0) + (data?.emails?.size ?: 0)

            data?.phones?.forEach { phone ->
                IdentityRow(
                    label = PhoneNumberFormatter.formatE164International(phone.phoneE164),
                    verified = phone.verified,
                    // The last remaining sign-in method is not removable — the
                    // server refuses it, and offering the button anyway would
                    // just produce an error the user cannot act on.
                    removable = total > 1,
                    onRemove = {
                        scope.launch {
                            runCatching { session.api.removeIdentity("phone", phone.id) }
                                .onFailure { message = "Couldn't remove that number" }
                            reload()
                        }
                    },
                )
            }
            data?.emails?.forEach { email ->
                IdentityRow(
                    label = email.email,
                    verified = email.verified,
                    removable = total > 1,
                    onRemove = {
                        scope.launch {
                            runCatching { session.api.removeIdentity("email", email.id) }
                                .onFailure { message = "Couldn't remove that email" }
                            reload()
                        }
                    },
                )
            }

            if (total == 0) {
                Text(
                    "Nothing linked yet.",
                    color = ViroColors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(ViroSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(ViroSpacing.sm)) {
                TextButton(onClick = onAddPhone) {
                    Text("Add phone number", color = ViroColors.accent)
                }
                TextButton(onClick = onAddEmail) {
                    Text("Add email", color = ViroColors.accent)
                }
            }
        }
    }
}

@Composable
private fun IdentityRow(
    label: String,
    verified: Boolean,
    removable: Boolean,
    onRemove: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ViroColors.surface),
        modifier = Modifier.fillMaxWidth().padding(bottom = ViroSpacing.sm),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(ViroSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = ViroColors.textPrimary, style = MaterialTheme.typography.bodyLarge)
                Text(
                    // Unverified entries cannot be used to find or sign in to the
                    // account, so the state is stated plainly rather than implied.
                    if (verified) "Verified" else "Not verified",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (verified) ViroColors.success else ViroColors.consumerWarning,
                )
            }
            if (removable) {
                TextButton(onClick = onRemove) {
                    Text("Remove", color = ViroColors.consumerError)
                }
            }
        }
    }
}

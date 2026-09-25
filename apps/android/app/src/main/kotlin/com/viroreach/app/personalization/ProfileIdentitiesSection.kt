package com.viroreach.app.personalization

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroColors
import com.viroreach.core.designsystem.components.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
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

    val data = identities
    val total = (data?.phones?.size ?: 0) + (data?.emails?.size ?: 0)
    ViroSection(
        title = "Numbers and emails",
        footer = message ?: "People can find you on Viro with any verified number here.",
    ) {
        if (loading && identities == null) {
            ViroLoadingState()
        } else {
            data?.phones?.forEach { phone ->
                IdentityRow(
                    label = PhoneNumberFormatter.formatE164International(phone.phoneE164),
                    icon = Icons.Outlined.Phone,
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
                    icon = Icons.Outlined.Email,
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
            if (total > 0) ViroRowDivider(inset = false)
            ViroListRow("Add phone number", icon = Icons.Outlined.AddIcCall, onClick = onAddPhone)
            ViroListRow("Add email", icon = Icons.Outlined.AlternateEmail, onClick = onAddEmail)
        }
    }
}

@Composable
private fun IdentityRow(
    label: String,
    icon: ImageVector,
    verified: Boolean,
    removable: Boolean,
    onRemove: () -> Unit,
) {
    ViroListRow(
        title = label,
        icon = icon,
        // Unverified entries cannot be used to find or sign in to the account,
        // so the state is stated plainly rather than implied.
        subtitle = if (verified) "Verified" else "Not verified",
        trailing = if (removable) {
            { TextButton(onClick = onRemove) { Text("Remove", color = ViroColors.consumerError) } }
        } else null,
    )
}

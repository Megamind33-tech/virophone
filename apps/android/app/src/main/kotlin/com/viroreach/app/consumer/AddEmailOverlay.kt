package com.viroreach.app.consumer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.*
import com.viroreach.core.network.EmailLinkRequestBody
import com.viroreach.core.network.EmailLinkVerifyBody
import kotlinx.coroutines.launch

/**
 * Adds an email address to an account, confirmed by a code.
 *
 * Verification is the point of the flow, not a formality: an unverified address
 * would let anyone attach someone else's email to their own account, and the
 * server will not attach one without a matching code. An address belongs to a
 * single account — enforced by a UNIQUE constraint, so the refusal comes from
 * the database rather than from a check this screen could be tricked past.
 */
@Composable
fun AddEmailOverlay(
    session: SessionManager,
    onDone: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var challengeId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun message(t: Throwable?): String {
        val raw = t?.message.orEmpty()
        return when {
            raw.contains("already on your account", true) ->
                "That email is already on your account."
            raw.contains("another Viro account", true) ->
                "That email is already used by another Viro account."
            raw.contains("expired", true) -> "That code expired — request a new one."
            raw.contains("Too many", true) -> "Too many attempts. Request a new code."
            raw.contains("Invalid OTP", true) -> "That code is not correct."
            raw.contains("Invalid email", true) -> "Enter a valid email address."
            else -> "Couldn't add that email. Please try again."
        }
    }

    ViroScreenBackground {
        ViroSafeScreen(applyImePadding = true, applyNavigationBarsPadding = true) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ViroSpacing.lg),
            ) {
                Spacer(Modifier.height(ViroSpacing.xl))
                Text(
                    if (challengeId == null) "Add an email address" else "Confirm your email",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (challengeId == null) {
                        "You'll be able to sign in with this address as well as your number."
                    } else {
                        "Enter the 6-digit code sent to $email."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(ViroSpacing.lg))
                if (challengeId == null) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it.trim(); error = null },
                        label = { Text("Email address") },
                        singleLine = true,
                        enabled = !loading,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { if (it.length <= 6) code = it.filter { c -> c.isDigit() }; error = null },
                        label = { Text("6-digit code") },
                        singleLine = true,
                        enabled = !loading,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    ViroErrorMessage(it)
                }

                Spacer(Modifier.height(ViroSpacing.md))
                ViroContinueButton(
                    text = when {
                        loading && challengeId == null -> "Sending code…"
                        loading -> "Confirming…"
                        challengeId == null -> "Send code"
                        else -> "Confirm"
                    },
                    enabled = !loading &&
                        if (challengeId == null) email.contains("@") else code.length == 6,
                    onClick = {
                        scope.launch {
                            loading = true
                            error = null
                            val current = challengeId
                            if (current == null) {
                                runCatching { session.api.requestEmailLink(EmailLinkRequestBody(email)) }
                                    .onSuccess { challengeId = it.challengeId }
                                    .onFailure { error = message(it) }
                            } else {
                                runCatching {
                                    session.api.verifyEmailLink(EmailLinkVerifyBody(current, code))
                                }
                                    .onSuccess { onDone() }
                                    .onFailure { error = message(it) }
                            }
                            loading = false
                        }
                    },
                )

                Spacer(Modifier.height(ViroSpacing.sm))
                TextButton(onClick = onDone, enabled = !loading) { Text("Cancel") }
                Spacer(Modifier.height(ViroSpacing.lg))
            }
        }
    }
}

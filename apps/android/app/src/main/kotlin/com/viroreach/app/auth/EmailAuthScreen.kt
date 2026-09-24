package com.viroreach.app.auth

import com.viroreach.core.designsystem.ViroColors
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.*

/**
 * Email and password sign-in, offered alongside phone-OTP rather than instead
 * of it. The screen scrolls: with the keyboard open on a short device the
 * password field and the button would otherwise sit below the fold with no way
 * to reach them.
 */
@Composable
fun EmailAuthScreen(
    email: String,
    password: String,
    registerMode: Boolean,
    loading: Boolean,
    errorMessage: String?,
    infoMessage: String?,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onToggleMode: () -> Unit,
    onSubmit: () -> Unit,
    onForgotPassword: () -> Unit,
    onUsePhone: () -> Unit,
) {
    ViroScreenBackground {
        ViroSafeScreen(applyImePadding = true, applyNavigationBarsPadding = true) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ViroSpacing.lg),
            ) {
                Spacer(Modifier.height(ViroSpacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ViroCallBrand()
                }
                Spacer(Modifier.height(ViroSpacing.xl))
                Text(
                    if (registerMode) "Create an account" else "Sign in with email",
                    style = MaterialTheme.typography.headlineMedium,
                    color = ViroColors.textPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (registerMode) {
                        "Use an email address and password instead of a phone number."
                    } else {
                        "Enter the email address and password for your Viro account."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(ViroSpacing.lg))
                OutlinedTextField(
                    value = email,
                    onValueChange = onEmailChange,
                    label = { Text("Email address") },
                    singleLine = true,
                    enabled = !loading,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !loading,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (registerMode) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "At least 6 characters.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Said plainly at the point of choice rather than discovered
                // later: an email account is not reachable by phone number, so
                // contacts who only know the number cannot find it.
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ⓘ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "People who have your phone number won't find this account in " +
                            "their contacts. They can reach you by the Viro ID you'll " +
                            "choose next, or by this email address.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!registerMode) {
                    TextButton(onClick = onForgotPassword, enabled = !loading) {
                        Text("Forgot password?")
                    }
                }

                infoMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                errorMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    ViroErrorMessage(it)
                }

                Spacer(Modifier.height(ViroSpacing.md))
                ViroContinueButton(
                    text = when {
                        loading && registerMode -> "Creating account…"
                        loading -> "Signing in…"
                        registerMode -> "Create account"
                        else -> "Sign in"
                    },
                    onClick = onSubmit,
                    enabled = !loading && email.isNotBlank() && password.isNotBlank(),
                )

                Spacer(Modifier.height(ViroSpacing.sm))
                TextButton(onClick = onToggleMode, enabled = !loading) {
                    Text(
                        if (registerMode) {
                            "Already have an account? Sign in"
                        } else {
                            "New to Viro? Create an account"
                        },
                    )
                }
                TextButton(onClick = onUsePhone, enabled = !loading) {
                    Text("Use a phone number instead")
                }
                Spacer(Modifier.height(ViroSpacing.lg))
            }
        }
    }
}

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.viroreach.app.session.SessionManager
import com.viroreach.core.designsystem.ViroSpacing
import com.viroreach.core.designsystem.components.*
import com.viroreach.core.network.ApiDiagnostics
import com.viroreach.core.network.FirebaseLinkEmailBody
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Adds an email address to an account, proved by a verification link.
 *
 * Verification is the point of the flow, not a formality: an unverified address
 * would let anyone attach someone else's email to their own account.
 *
 * Firebase does the emailing. The user picks a password, which creates a
 * Firebase email login; Firebase sends its verification link; once it has been
 * opened, the ID token (now marked email_verified) goes to the server, which
 * attaches the address. This used to wait for a 6-digit code the Viro server
 * was meant to email — but the server has no mail service, so the code never
 * arrived. As a bonus the user ends up able to sign in with the email and
 * password, and to reset that password from the sign-in screen.
 */
@Composable
fun AddEmailOverlay(
    session: SessionManager,
    onDone: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var linkSent by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val auth = remember { FirebaseAuth.getInstance() }

    suspend fun sendLink() {
        val existingLogin = auth.currentUser?.takeIf { it.email.equals(email, ignoreCase = true) }
        val user = existingLogin ?: try {
            auth.createUserWithEmailAndPassword(email, password).await().user
        } catch (e: FirebaseAuthUserCollisionException) {
            // The address already has a Firebase login (an earlier attempt that
            // was never finished, or a past email sign-up). The password proves
            // it is theirs.
            auth.signInWithEmailAndPassword(email, password).await().user
        } ?: throw IllegalStateException("Firebase returned no user")
        if (!user.isEmailVerified) user.sendEmailVerification().await()
        linkSent = true
        info = if (user.isEmailVerified) null else "Link sent to $email."
    }

    suspend fun confirm() {
        val user = auth.currentUser ?: throw IllegalStateException("Start again: the email login was lost")
        user.reload().await()
        if (!user.isEmailVerified) {
            errorText = "Not confirmed yet. Open the link in the email from Viro, then tap Continue."
            return
        }
        val token = user.getIdToken(true).await().token ?: throw IllegalStateException("Firebase returned no token")
        session.api.linkEmailWithFirebase(FirebaseLinkEmailBody(token))
        // The Viro session is the one that matters; the Firebase login was only
        // the proof of ownership and is not needed on this device any more.
        auth.signOut()
        onDone()
    }

    fun message(t: Throwable): String = when (t) {
        is FirebaseAuthWeakPasswordException -> "Choose a password of at least 6 characters."
        is FirebaseAuthInvalidCredentialsException ->
            if (linkSent) "Couldn't confirm. Try again." else
                "That email already has a Viro login, and this isn't its password."
        else -> {
            val server = ApiDiagnostics.parseFailure("POST", "/api/v1/auth/link/email/firebase", t)
            when {
                server.message?.contains("another Viro account", true) == true ->
                    "That email is already used by another Viro account."
                server.httpStatus == -1 && t is java.io.IOException ->
                    "No connection. Check your data and try again."
                else -> server.message ?: t.message ?: "Couldn't add that email. Please try again."
            }
        }
    }

    fun run(block: suspend () -> Unit) {
        scope.launch {
            loading = true
            errorText = null
            runCatching { block() }.onFailure { errorText = message(it) }
            loading = false
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
                    if (!linkSent) "Add an email address" else "Check your email",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (!linkSent) {
                        "You'll be able to sign in with this email and password as well as your number."
                    } else {
                        "We sent a link to $email. Open it, then come back and tap Continue. " +
                            "Check spam if it hasn't arrived."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(ViroSpacing.lg))
                if (!linkSent) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it.trim(); errorText = null },
                        label = { Text("Email address") },
                        singleLine = true,
                        enabled = !loading,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(ViroSpacing.sm))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; errorText = null },
                        label = { Text("Password (6+ characters)") },
                        singleLine = true,
                        enabled = !loading,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                info?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                errorText?.let {
                    Spacer(Modifier.height(8.dp))
                    ViroErrorMessage(it)
                }

                Spacer(Modifier.height(ViroSpacing.md))
                ViroContinueButton(
                    text = when {
                        loading && !linkSent -> "Sending link…"
                        loading -> "Checking…"
                        !linkSent -> "Send verification link"
                        else -> "Continue"
                    },
                    enabled = !loading &&
                        (linkSent || (email.contains("@") && password.length >= 6)),
                    onClick = { run { if (!linkSent) sendLink() else confirm() } },
                )

                if (linkSent) {
                    Spacer(Modifier.height(ViroSpacing.sm))
                    TextButton(
                        onClick = {
                            run {
                                auth.currentUser?.sendEmailVerification()?.await()
                                info = "Sent again to $email."
                            }
                        },
                        enabled = !loading,
                    ) { Text("Resend link") }
                }

                Spacer(Modifier.height(ViroSpacing.sm))
                TextButton(
                    onClick = {
                        auth.signOut()
                        onDone()
                    },
                    enabled = !loading,
                ) { Text("Cancel") }
                Spacer(Modifier.height(ViroSpacing.lg))
            }
        }
    }
}

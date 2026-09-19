package com.viroreach.app.consumer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.viroreach.app.auth.AuthRepository
import com.viroreach.app.auth.AuthStep
import com.viroreach.app.auth.AuthViewModel
import com.viroreach.app.auth.LinkPhoneOtpScreen
import com.viroreach.app.auth.LinkPhoneScreen
import com.viroreach.app.session.SessionManager

/**
 * Adds a phone number to an account that signed in with email, reached from
 * Settings rather than only from the sign-up flow.
 *
 * Anyone who tapped "Not now" during sign-up previously had no route back short
 * of signing out, which left their account permanently unreachable by anyone who
 * has their number.
 *
 * Reuses the same screens and view model as the sign-up step so the two cannot
 * drift apart — including the session swap that the "adopted" outcome requires.
 */
@Composable
fun AddPhoneOverlay(
    session: SessionManager,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(session) { AuthRepository(session, context) }
    val viewModel = remember(repository) { AuthViewModel(repository) }
    val state = viewModel.uiState

    // Enter at the phone step: this is not a sign-in, the user is already here.
    LaunchedEffect(Unit) {
        if (state.step != AuthStep.LinkPhone && state.step != AuthStep.LinkPhoneOtp) {
            viewModel.showLinkPhone()
        }
    }

    when (state.step) {
        AuthStep.LinkPhoneOtp -> LinkPhoneOtpScreen(
            phoneE164 = state.linkPhoneE164.orEmpty(),
            otp = state.linkOtp,
            loading = state.loading,
            errorMessage = state.errorMessage,
            onOtpChange = viewModel::updateLinkOtp,
            onVerify = { viewModel.verifyLinkOtp(onDone) },
            onChangeNumber = viewModel::changeLinkNumber,
            onSkip = onDone,
        )
        else -> LinkPhoneScreen(
            phoneDigits = state.linkDigits,
            country = state.country,
            loading = state.loading,
            errorMessage = state.errorMessage,
            onPhoneDigitsChange = viewModel::updateLinkDigits,
            onCountryChange = viewModel::selectCountry,
            onContinue = viewModel::requestLinkOtp,
            onSkip = onDone,
        )
    }
}

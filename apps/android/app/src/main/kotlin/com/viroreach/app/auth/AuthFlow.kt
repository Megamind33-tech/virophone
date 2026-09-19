package com.viroreach.app.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.viroreach.app.session.SessionManager
import com.viroreach.feature.contacts.CountryCatalog
import com.viroreach.feature.contacts.PhoneNumberFormatter

@Composable
fun AuthFlow(
    session: SessionManager,
    onAuthenticated: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(session) { AuthRepository(session, context) }
    val returningInstall = remember(session) { session.isReturningInstall }
    val savedPhone = remember(session) {
        session.authenticatedPhoneE164 ?: session.testIdentityStore.getPhoneE164()
    }
    val initialDigits = remember(savedPhone) {
        savedPhone?.let { PhoneNumberFormatter.nationalDigitsFromE164(it) }.orEmpty()
    }
    val initialCountry = remember(savedPhone) {
        savedPhone?.let { CountryCatalog.fromE164(it) } ?: CountryCatalog.default()
    }
    val viewModel = remember(returningInstall, initialDigits, initialCountry) {
        AuthViewModel(repository, returningInstall, initialDigits, initialCountry)
    }
    val state = viewModel.uiState

    when (state.step) {
        AuthStep.Phone -> LoginScreen(
            phoneDigits = state.phoneDigits,
            country = state.country,
            loading = state.loading,
            errorMessage = state.errorMessage,
            returningUser = returningInstall,
            onPhoneDigitsChange = viewModel::updatePhoneDigits,
            onCountryChange = viewModel::selectCountry,
            onContinue = viewModel::requestOtp,
            onUseEmail = viewModel::showEmailAuth,
        )
        AuthStep.Email -> EmailAuthScreen(
            email = state.email,
            password = state.password,
            registerMode = state.registerMode,
            loading = state.loading,
            errorMessage = state.errorMessage,
            infoMessage = state.infoMessage,
            onEmailChange = viewModel::updateEmail,
            onPasswordChange = viewModel::updatePassword,
            onToggleMode = viewModel::toggleEmailRegisterMode,
            onSubmit = { viewModel.submitEmailAuth(onAuthenticated) },
            onForgotPassword = viewModel::sendPasswordReset,
            onUsePhone = viewModel::showPhoneAuth,
        )
        AuthStep.LinkPhone -> LinkPhoneScreen(
            phoneDigits = state.linkDigits,
            country = state.country,
            loading = state.loading,
            errorMessage = state.errorMessage,
            onPhoneDigitsChange = viewModel::updateLinkDigits,
            onCountryChange = viewModel::selectCountry,
            onContinue = viewModel::requestLinkOtp,
            onSkip = onAuthenticated,
        )
        AuthStep.LinkPhoneOtp -> LinkPhoneOtpScreen(
            phoneE164 = state.linkPhoneE164.orEmpty(),
            otp = state.linkOtp,
            loading = state.loading,
            errorMessage = state.errorMessage,
            onOtpChange = viewModel::updateLinkOtp,
            onVerify = { viewModel.verifyLinkOtp(onAuthenticated) },
            onChangeNumber = viewModel::changeLinkNumber,
            onSkip = onAuthenticated,
        )
        AuthStep.Otp -> {
            val phone = state.normalizedPhone
                ?: PhoneNumberFormatter.formatE164International(
                    PhoneNumberFormatter.canonicalE164(state.phoneDigits, state.country.iso2)
                        ?: state.phoneDigits,
                )
            OtpScreen(
                phoneE164 = phone,
                otp = state.otpInput,
                loading = state.loading,
                errorMessage = state.errorMessage,
                hardwareTestMode = viewModel.isHardwareTestMode,
                onOtpChange = viewModel::updateOtp,
                onVerify = { viewModel.verifyOtp(onAuthenticated) },
                onResend = viewModel::resendOtp,
                onChangeNumber = viewModel::changeNumber,
            )
        }
    }
}

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

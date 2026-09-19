package com.viroreach.app.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viroreach.app.BuildConfig
import com.viroreach.feature.contacts.CountryCatalog
import com.viroreach.feature.contacts.CountryOption
import com.viroreach.feature.contacts.PhoneNumberFormatter
import kotlinx.coroutines.launch

enum class AuthStep { Phone, Otp, Email, LinkPhone, LinkPhoneOtp }

data class AuthUiState(
    val step: AuthStep = AuthStep.Phone,
    /** Raw digits only — display formatting is derived in UI. */
    val phoneDigits: String = "",
    val country: CountryOption = CountryCatalog.default(),
    val otpInput: String = "",
    val challengeId: String? = null,
    val normalizedPhone: String? = null,
    val loading: Boolean = false,
    val errorMessage: String? = null,
    /** Neutral confirmation, e.g. "reset link sent" — shown instead of an error. */
    val infoMessage: String? = null,
    val returningInstall: Boolean = false,
    val email: String = "",
    val password: String = "",
    /** Create-account vs sign-in on the email screen. */
    val registerMode: Boolean = false,
    /** Phone being linked to an already signed-in account. */
    val linkDigits: String = "",
    val linkChallengeId: String? = null,
    val linkPhoneE164: String? = null,
    val linkOtp: String = "",
)

class AuthViewModel(
    private val repository: AuthRepository,
    returningInstall: Boolean = false,
    initialPhoneDigits: String = "",
    initialCountry: CountryOption = CountryCatalog.default(),
) : ViewModel() {
    var uiState by mutableStateOf(
        AuthUiState(
            phoneDigits = initialPhoneDigits,
            country = initialCountry,
            returningInstall = returningInstall,
        ),
    )
        private set

    val isHardwareTestMode: Boolean = BuildConfig.DEBUG

    // --- Email / password (Firebase) ---

    fun updateEmail(value: String) {
        uiState = uiState.copy(email = value.trim(), errorMessage = null, infoMessage = null)
    }

    /**
     * Emails a password-reset link. Firebase sends it and hosts the page where
     * the new password is chosen, so nothing here handles the password.
     *
     * The confirmation is the same whether or not an account exists for the
     * address; saying "no such account" would let anyone test which emails
     * are registered.
     */
    fun sendPasswordReset() {
        val email = uiState.email.trim()
        if (!email.contains("@") || !email.contains(".")) {
            uiState = uiState.copy(errorMessage = "Enter your email address above, then tap Forgot password.")
            return
        }
        uiState = uiState.copy(loading = true, errorMessage = null, infoMessage = null)
        viewModelScope.launch {
            val result = repository.sendPasswordReset(email)
            uiState = if (result.isSuccess || !isNetworkFailure(result.exceptionOrNull())) {
                uiState.copy(
                    loading = false,
                    infoMessage = "If $email has a Viro account, a reset link is on its way. Check spam too.",
                )
            } else {
                uiState.copy(
                    loading = false,
                    errorMessage = "Couldn't reach the server. Check your connection and try again.",
                )
            }
        }
    }

    private fun isNetworkFailure(error: Throwable?): Boolean {
        val raw = error?.message.orEmpty()
        return error is java.io.IOException || raw.contains("NETWORK", true) || raw.contains("timeout", true)
    }

    fun updatePassword(value: String) {
        uiState = uiState.copy(password = value, errorMessage = null)
    }

    fun showEmailAuth() {
        uiState = uiState.copy(step = AuthStep.Email, errorMessage = null)
    }

    fun showPhoneAuth() {
        uiState = uiState.copy(step = AuthStep.Phone, errorMessage = null, password = "")
    }

    fun toggleEmailRegisterMode() {
        uiState = uiState.copy(registerMode = !uiState.registerMode, errorMessage = null, infoMessage = null)
    }

    // --- Linking a phone number after email sign-in ------------------------

    /** Entry point when reached from Settings rather than after sign-up. */
    fun showLinkPhone() {
        uiState = uiState.copy(step = AuthStep.LinkPhone, errorMessage = null)
    }

    fun updateLinkDigits(value: String) {
        uiState = uiState.copy(
            linkDigits = PhoneNumberFormatter.sanitizeAuthDigits(value),
            errorMessage = null,
        )
    }

    fun updateLinkOtp(value: String) {
        uiState = uiState.copy(linkOtp = value, errorMessage = null)
    }

    fun changeLinkNumber() {
        uiState = uiState.copy(
            step = AuthStep.LinkPhone,
            linkChallengeId = null,
            linkOtp = "",
            errorMessage = null,
        )
    }

    fun requestLinkOtp() {
        val canonical = PhoneNumberFormatter.canonicalE164(uiState.linkDigits, uiState.country.iso2)
        if (canonical == null) {
            uiState = uiState.copy(errorMessage = "Enter a valid phone number")
            return
        }
        uiState = uiState.copy(loading = true, errorMessage = null)
        viewModelScope.launch {
            val result = repository.requestPhoneLink(canonical)
            uiState = result.fold(
                onSuccess = {
                    uiState.copy(
                        loading = false,
                        step = AuthStep.LinkPhoneOtp,
                        linkChallengeId = it,
                        linkPhoneE164 = canonical,
                        linkOtp = "",
                    )
                },
                onFailure = {
                    uiState.copy(loading = false, errorMessage = linkMessage(it))
                },
            )
        }
    }

    fun verifyLinkOtp(onDone: () -> Unit) {
        val challengeId = uiState.linkChallengeId ?: return
        uiState = uiState.copy(loading = true, errorMessage = null)
        viewModelScope.launch {
            val result = repository.verifyPhoneLink(challengeId, uiState.linkOtp.trim())
            result.fold(
                onSuccess = {
                    uiState = uiState.copy(loading = false)
                    onDone()
                },
                onFailure = {
                    uiState = uiState.copy(loading = false, errorMessage = linkMessage(it))
                },
            )
        }
    }

    /**
     * The server's refusal message when a number belongs to an account with
     * history is written for the user and is more useful than anything this
     * layer could invent, so it is passed through rather than replaced.
     */
    private fun linkMessage(error: Throwable?): String {
        val raw = error?.message.orEmpty()
        return when {
            raw.contains("already on your account", true) ->
                "That number is already on your account."
            raw.contains("call or message history", true) ->
                "That number belongs to another Viro account with history. " +
                    "Sign in with that number instead."
            raw.contains("different email", true) ->
                "That number belongs to an account using a different email address."
            raw.contains("expired", true) -> "That code expired — request a new one."
            raw.contains("Too many", true) -> "Too many attempts. Request a new code."
            raw.contains("Invalid OTP", true) -> "That code is not correct."
            raw.contains("valid phone", true) -> "Enter a valid phone number."
            else -> "Couldn't confirm that number. Please try again."
        }
    }

    fun submitEmailAuth(onAuthenticated: () -> Unit) {
        val email = uiState.email.trim()
        val password = uiState.password
        if (!email.contains("@") || !email.contains(".")) {
            uiState = uiState.copy(errorMessage = "Enter a valid email address")
            return
        }
        if (uiState.registerMode && password.length < 6) {
            uiState = uiState.copy(errorMessage = "Password must be at least 6 characters")
            return
        }
        uiState = uiState.copy(loading = true, errorMessage = null)
        viewModelScope.launch {
            val result = repository.signInWithEmail(email, password, uiState.registerMode)
            uiState = if (result.isSuccess) {
                // Straight to the phone-link step rather than into the app: an
                // email-only account cannot be found by anyone who has the
                // user's number, and the moment after sign-up is when they are
                // most willing to add it.
                uiState.copy(loading = false, password = "", step = AuthStep.LinkPhone)
            } else {
                uiState.copy(
                    loading = false,
                    password = "",
                    errorMessage = emailAuthMessage(result.exceptionOrNull()),
                )
            }
            // onAuthenticated is deliberately NOT called here — the link step
            // calls it, whether the user adds a number or skips.
        }
    }

    /**
     * Firebase's own messages leak implementation detail ("The supplied auth
     * credential is incorrect, malformed or has expired") and name which of the
     * two fields was wrong, which is an account-enumeration hint. These say
     * what to do instead.
     */
    private fun emailAuthMessage(error: Throwable?): String {
        val raw = error?.message.orEmpty()
        return when {
            raw.contains("EMAIL_EXISTS", true) ||
                raw.contains("already in use", true) ->
                "That email already has an account. Sign in instead."
            raw.contains("WEAK_PASSWORD", true) ->
                "Choose a longer password — at least 6 characters."
            raw.contains("INVALID_EMAIL", true) ->
                "That doesn't look like a valid email address."
            raw.contains("NETWORK", true) || raw.contains("timeout", true) ->
                "Couldn't reach the server. Check your connection and try again."
            raw.contains("PASSWORD", true) || raw.contains("credential", true) ||
                raw.contains("no user record", true) || raw.contains("USER_NOT_FOUND", true) ->
                "Email or password is incorrect."
            else -> "Couldn't sign in. Please try again."
        }
    }

    fun updatePhoneDigits(value: String) {
        uiState = uiState.copy(
            phoneDigits = PhoneNumberFormatter.sanitizeAuthDigits(value),
            errorMessage = null,
        )
    }

    fun selectCountry(country: CountryOption) {
        uiState = uiState.copy(country = country, errorMessage = null)
    }

    fun updateOtp(value: String) {
        uiState = uiState.copy(otpInput = value, errorMessage = null)
    }

    fun requestOtp() {
        viewModelScope.launch {
            uiState = uiState.copy(loading = true, errorMessage = null)
            repository.requestOtp(uiState.phoneDigits, uiState.country.iso2)
                .onSuccess { challenge ->
                    uiState = uiState.copy(
                        loading = false,
                        step = AuthStep.Otp,
                        challengeId = challenge.challengeId,
                        normalizedPhone = challenge.phoneE164,
                        otpInput = "",
                    )
                }
                .onFailure { e ->
                    uiState = uiState.copy(
                        loading = false,
                        errorMessage = ConsumerAuthErrorMapper.fromThrowable(e),
                    )
                }
        }
    }

    fun verifyOtp(onSuccess: () -> Unit) {
        val challengeId = uiState.challengeId ?: return
        val phone = uiState.normalizedPhone ?: return
        viewModelScope.launch {
            uiState = uiState.copy(loading = true, errorMessage = null)
            repository.verifyOtp(challengeId, uiState.otpInput.trim(), phone)
                .onSuccess {
                    uiState = uiState.copy(loading = false)
                    onSuccess()
                }
                .onFailure { e ->
                    uiState = uiState.copy(
                        loading = false,
                        errorMessage = ConsumerAuthErrorMapper.fromThrowable(e),
                    )
                }
        }
    }

    fun changeNumber() {
        uiState = uiState.copy(
            step = AuthStep.Phone,
            otpInput = "",
            challengeId = null,
            errorMessage = null,
        )
    }

    fun resendOtp() {
        requestOtp()
    }
}

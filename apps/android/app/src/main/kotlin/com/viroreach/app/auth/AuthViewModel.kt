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

enum class AuthStep { Phone, Otp }

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
    val returningInstall: Boolean = false,
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

package com.viroreach.app.consumer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.viroreach.app.BuildConfig
import com.viroreach.app.session.SessionManager

data class YouUiState(
    val phoneE164: String? = null,
    val email: String? = null,
    val versionName: String = BuildConfig.VERSION_NAME,
    val showDeveloperEntry: Boolean = false,
)

class YouViewModel(
    private val session: SessionManager,
    showDeveloperEntry: Boolean,
) : ViewModel() {
    var uiState by mutableStateOf(
        YouUiState(
            phoneE164 = session.authenticatedPhoneE164,
            email = session.authenticatedEmail,
            showDeveloperEntry = showDeveloperEntry,
        ),
    )
        private set

    fun refresh() {
        uiState = uiState.copy(
            phoneE164 = session.authenticatedPhoneE164,
            email = session.authenticatedEmail,
        )
    }

    fun logout() {
        session.logout()
    }
}

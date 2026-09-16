package com.viroreach.app.consumer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

enum class ContactsLoadState { Idle, Loading, Ready, Error }

data class ContactsUiState(
    val loadState: ContactsLoadState = ContactsLoadState.Idle,
    val contacts: List<ContactListItem> = emptyList(),
    val errorMessage: String? = null,
    val refreshFailed: Boolean = false,
    val isRefreshing: Boolean = false,
)

/**
 * Session-scoped contacts state so tab switches never recreate an empty list.
 */
class ContactsCoordinator(
    private val repository: CachedContactsRepository,
    private val scope: CoroutineScope,
) {
    var uiState by mutableStateOf(ContactsUiState())
        private set

    private var hasEverLoaded = false

    init {
        repository.observeContacts()
            .onEach { cached ->
                if (cached.isNotEmpty()) {
                    hasEverLoaded = true
                    uiState = uiState.copy(
                        contacts = cached,
                        loadState = ContactsLoadState.Ready,
                    )
                }
            }
            .launchIn(scope)

        scope.launch {
            val cached = repository.loadCachedContacts()
            if (cached.isNotEmpty()) {
                hasEverLoaded = true
                uiState = uiState.copy(
                    contacts = cached,
                    loadState = ContactsLoadState.Ready,
                )
            } else if (!hasEverLoaded) {
                uiState = uiState.copy(loadState = ContactsLoadState.Loading)
            }
            refresh(silent = cached.isNotEmpty())
        }
    }

    fun refresh(silent: Boolean = uiState.contacts.isNotEmpty() || hasEverLoaded) {
        scope.launch {
            val hadCache = repository.cachedCount() > 0 || uiState.contacts.isNotEmpty()
            if (!silent && !hadCache) {
                uiState = uiState.copy(
                    loadState = ContactsLoadState.Loading,
                    isRefreshing = true,
                    refreshFailed = false,
                    errorMessage = null,
                )
            } else {
                uiState = uiState.copy(
                    isRefreshing = true,
                    refreshFailed = false,
                    errorMessage = null,
                )
            }
            runCatching { repository.loadContacts() }
                .onSuccess { items ->
                    hasEverLoaded = true
                    uiState = uiState.copy(
                        loadState = ContactsLoadState.Ready,
                        contacts = items.ifEmpty { uiState.contacts },
                        isRefreshing = false,
                        refreshFailed = false,
                    )
                }
                .onFailure {
                    uiState = uiState.copy(
                        loadState = if (uiState.contacts.isEmpty() && !hadCache) {
                            ContactsLoadState.Error
                        } else {
                            ContactsLoadState.Ready
                        },
                        isRefreshing = false,
                        refreshFailed = uiState.contacts.isNotEmpty() || hadCache,
                        errorMessage = if (uiState.contacts.isEmpty() && !hadCache) {
                            "Couldn't load contacts."
                        } else {
                            null
                        },
                    )
                }
        }
    }

    fun onContactsPermissionGranted() {
        refresh(silent = uiState.contacts.isNotEmpty() || hasEverLoaded)
    }
}

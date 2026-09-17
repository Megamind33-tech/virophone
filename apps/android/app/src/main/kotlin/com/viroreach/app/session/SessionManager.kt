package com.viroreach.app.session

import android.content.Context
import com.viroreach.app.consumer.CachedContactsRepository
import com.viroreach.app.consumer.ContactsCoordinator
import com.viroreach.app.consumer.data.CallHistoryStore
import com.viroreach.app.consumer.data.ConferenceManager
import com.viroreach.app.consumer.data.MessagesStore
import com.viroreach.app.consumer.data.ServerMessagesRepository
import com.viroreach.app.personalization.ProfileRepository
import com.viroreach.app.personalization.ViroAppearanceManager
import com.viroreach.core.model.CallStateMachineState
import com.viroreach.core.network.NetworkMonitor
import com.viroreach.core.network.ProfilePhotoUploader
import com.viroreach.core.network.SessionTokenManager
import com.viroreach.core.network.TestIdentityStore
import com.viroreach.core.network.TokenStore
import com.viroreach.core.network.ViroApiClient
import com.viroreach.core.network.ViroApiService
import com.viroreach.feature.calling.CallManager
import com.viroreach.feature.calling.SignalingConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SessionManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    val tokenStore: TokenStore = TokenStore(appContext)
    val testIdentityStore: TestIdentityStore = TestIdentityStore(appContext)
    val sessionTokenManager: SessionTokenManager = SessionTokenManager(tokenStore)
    private val viroApiClient: ViroApiClient = ViroApiClient(sessionTokenManager)
    val api: ViroApiService = viroApiClient.api
    val callManager: CallManager = CallManager(
        appContext,
        tokenStore,
        sessionTokenManager,
        api,
        forceRelay = false,
    )
    val networkMonitor: NetworkMonitor = NetworkMonitor(appContext)
    val callHistoryStore: CallHistoryStore = CallHistoryStore(appContext)
    val messagesStore: MessagesStore = MessagesStore()
    val serverMessagesRepository: ServerMessagesRepository = ServerMessagesRepository(api)
    val conferenceManager: ConferenceManager = ConferenceManager(this, appContext)
    val appearanceManager: ViroAppearanceManager = ViroAppearanceManager(appContext)
    val profileRepository: ProfileRepository = ProfileRepository(
        appContext,
        api,
        ProfilePhotoUploader(appContext, sessionTokenManager),
    )
    val incomingCallNotifier: com.viroreach.app.call.IncomingCallNotifier =
        com.viroreach.app.call.IncomingCallNotifier(appContext)
    val incomingCallRinger: com.viroreach.app.call.IncomingCallRinger =
        com.viroreach.app.call.IncomingCallRinger(appContext)
    val contactsRepository: CachedContactsRepository = CachedContactsRepository(this, appContext)

    var lastTargetInput: String = ""
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val contactsCoordinator: ContactsCoordinator = ContactsCoordinator(contactsRepository, scope)
    private var callActiveSinceMs: Long? = null

    val isAuthenticated: Boolean
        get() = sessionTokenManager.isAuthenticated()

    val authenticatedPhoneE164: String?
        get() = tokenStore.getAuthenticatedPhoneE164()
            ?: testIdentityStore.getPhoneE164()

    init {
        scope.launch {
            networkMonitor.hasInternet.collect { available ->
                if (!available || !isAuthenticated) return@collect
                val wss = callManager.wssConnectionState.value
                if (wss == SignalingConnectionState.DISCONNECTED ||
                    wss == SignalingConnectionState.FAILED ||
                    wss == SignalingConnectionState.RECONNECTING
                ) {
                    callManager.connectSignaling(force = wss == SignalingConnectionState.FAILED)
                }
            }
        }
        scope.launch {
            networkMonitor.networkGeneration.collect {
                if (it == 0) return@collect
                // A network-selector tick (e.g. a transient VALIDATED flag flip on the
                // same Wi-Fi) does not mean the socket is actually broken. Only force a
                // teardown/reconnect when it's already unhealthy — forcing one on a live
                // socket can drop an in-flight call.accept/call.answer mid-handshake,
                // leaving the caller stuck on "Calling…" forever (delivery is
                // fire-and-forget, not queued for a reconnecting device).
                if (isAuthenticated && callManager.wssConnectionState.value != SignalingConnectionState.CONNECTED) {
                    callManager.connectSignaling(force = true)
                }
                callManager.recoverFromNetworkTransition()
            }
        }
        observeCallLifecycle()
    }

    private fun observeCallLifecycle() {
        var previous = CallStateMachineState.IDLE
        scope.launch {
            callManager.state.collect { state ->
                if (state == CallStateMachineState.ACTIVE && previous != CallStateMachineState.ACTIVE) {
                    callActiveSinceMs = System.currentTimeMillis()
                    callHistoryStore.onCallAnswered()
                }
                if (callHistoryStore.hasActiveCall() && isCallTerminalState(state)) {
                    val skippedIdleToFailure = previous == CallStateMachineState.IDLE &&
                        state in CALL_FAILURE_STATES
                    val leftActiveCall = previous !in CALL_INACTIVE_STATES || skippedIdleToFailure
                    if (leftActiveCall) {
                        val effectiveState = when {
                            state in CALL_FAILURE_STATES -> state
                            state in CALL_INACTIVE_STATES && previous in CALL_FAILURE_STATES -> previous
                            else -> state
                        }
                        val duration = callActiveSinceMs?.let {
                            ((System.currentTimeMillis() - it) / 1000).toInt()
                        } ?: 0
                        callHistoryStore.onCallEnded(effectiveState, duration)
                        callActiveSinceMs = null
                    }
                }
                previous = state
            }
        }
    }

    suspend fun restoreSessionIfAuthenticated(): Boolean {
        if (!sessionTokenManager.restorePersistedSession()) return false
        runCatching { connectSignalingAuto() }
        runCatching { profileRepository.refreshFromServer() }
        startBackgroundSignaling()
        return true
    }

    val isReturningInstall: Boolean
        get() = testIdentityStore.hasRegisteredBefore() ||
            !tokenStore.getAuthenticatedPhoneE164().isNullOrBlank()

    fun updateLastTargetInput(input: String) {
        lastTargetInput = input
    }

    suspend fun onAuthenticationSuccess(normalizedPhoneE164: String) {
        testIdentityStore.savePhoneE164(normalizedPhoneE164)
        connectSignalingAuto()
        profileRepository.refreshFromServer()
        startBackgroundSignaling()
    }

    suspend fun connectSignalingAuto() {
        if (isAuthenticated) {
            callManager.ensureSignalingReady()
        }
    }

    /**
     * Consumer outgoing call — auto-connects signaling and uses cached Viro user id when available.
     * [phoneE164] may be null when calling back a known Viro user id with no phone on file
     * (e.g. a server-synced call-history entry) — a phone number is only required when
     * [userId] isn't known, since resolving by raw number needs one.
     */
    suspend fun placeOutgoingCall(
        phoneE164: String?,
        displayName: String,
        userId: String? = null,
    ) {
        if (!userId.isNullOrBlank()) {
            callManager.startCallToRegisteredUser(userId, phoneE164, displayName)
        } else if (!phoneE164.isNullOrBlank()) {
            callManager.resolveTarget(phoneE164)
            callManager.startCall(phoneE164)
        }
    }

    fun startBackgroundSignaling() {
        if (!isAuthenticated) return
        com.viroreach.app.call.ViroSignalingForegroundService.start(appContext)
    }

    fun stopBackgroundSignaling() {
        com.viroreach.app.call.ViroSignalingForegroundService.stop(appContext)
    }

    fun logout() {
        stopBackgroundSignaling()
        callManager.disconnectSignaling()
        callManager.clearSessionCallContext()
        tokenStore.clearSession()
    }

    fun resetTestSession() {
        stopBackgroundSignaling()
        callManager.disconnectSignaling()
        callManager.clearSessionCallContext()
        tokenStore.clearSession()
        testIdentityStore.clear()
        lastTargetInput = ""
    }

    fun wssButtonLabel(): String = when (callManager.wssConnectionState.value) {
        SignalingConnectionState.DISCONNECTED -> "CONNECT WSS"
        SignalingConnectionState.CONNECTING, SignalingConnectionState.AUTHENTICATING ->
            "CONNECTING…"
        SignalingConnectionState.CONNECTED -> "DISCONNECT WSS"
        SignalingConnectionState.RECONNECTING -> "RECONNECTING…"
        SignalingConnectionState.FAILED -> "RETRY WSS"
        SignalingConnectionState.STOPPED, SignalingConnectionState.LISTENING -> "CONNECT WSS"
    }

    companion object {
        private val CALL_INACTIVE_STATES = setOf(
            CallStateMachineState.IDLE,
            CallStateMachineState.ENDED,
        )
        private val CALL_FAILURE_STATES = setOf(
            CallStateMachineState.FAILED,
            CallStateMachineState.UNAUTHORIZED,
            CallStateMachineState.UNREACHABLE,
            CallStateMachineState.NETWORK_FAILED,
            CallStateMachineState.PEER_REJECTED,
            CallStateMachineState.BUSY,
            CallStateMachineState.TIMEOUT,
            CallStateMachineState.MEDIA_FAILED,
            CallStateMachineState.SERVER_FAILED,
        )

        private fun isCallTerminalState(state: CallStateMachineState): Boolean =
            state in CALL_INACTIVE_STATES || state in CALL_FAILURE_STATES

        @Volatile
        private var instance: SessionManager? = null

        fun get(context: Context): SessionManager {
            return instance ?: synchronized(this) {
                instance ?: SessionManager(context.applicationContext).also { instance = it }
            }
        }

        internal fun peekInstance(): SessionManager? = instance
    }
}

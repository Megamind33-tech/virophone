package com.viroreach.app.session

import android.content.Context
import com.viroreach.app.consumer.CachedContactsRepository
import com.viroreach.app.consumer.ContactsCoordinator
import com.viroreach.app.consumer.data.PreferenceSync
import com.viroreach.app.consumer.data.CallHistoryStore
import com.viroreach.app.consumer.data.syncFromServer
import com.viroreach.app.consumer.data.ConferenceManager
import com.viroreach.app.messaging.MessageNotifier
import com.viroreach.app.messaging.MessagingRepository
import com.viroreach.app.messaging.VoicePlayer
import com.viroreach.app.relationships.RelationshipRepository
import com.viroreach.app.relationships.ReminderScheduler
import com.viroreach.core.network.ViroMessagingApi
import com.viroreach.app.personalization.ProfileRepository
import com.viroreach.app.personalization.ViroAppearanceManager
import com.viroreach.core.model.CallStateMachineState
import kotlinx.coroutines.tasks.await
import com.viroreach.core.network.NetworkMonitor
import com.viroreach.core.network.looksLikeE164
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.viroreach.core.network.UpdatePresenceBody

class SessionManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    val tokenStore: TokenStore = TokenStore(appContext)

    /**
     * Messages and encryption keys are kept per account on this phone. Before
     * anything opens them: the files an earlier build kept for everyone go to
     * the account that owned them, and the account in use is selected.
     */
    private val storageAccount: String? = (tokenStore.getUserId() ?: tokenStore.getLastUserId()).also { owner ->
        if (owner != null) {
            runCatching { com.viroreach.core.database.MessagingDatabase.adoptLegacy(appContext, owner) }
            runCatching { com.viroreach.core.e2ee.E2eeDatabase.adoptLegacy(appContext, owner) }
        }
        com.viroreach.core.database.MessagingDatabase.useAccount(owner)
        com.viroreach.core.e2ee.E2eeDatabase.useAccount(owner)
    }
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
    val messagingApi: ViroMessagingApi = viroApiClient.messaging
    /** Messages, kept in Room and synced — see MessagingRepository. */
    val messaging: MessagingRepository = MessagingRepository(
        appContext,
        messagingApi,
        viroApiClient.httpClient,
        viroApiClient.baseUrl,
        tokenStore,
        callManager,
        viroApiClient.keys,
    )
    val relationships: RelationshipRepository = RelationshipRepository(appContext, messagingApi)
    val moments = com.viroreach.app.moments.MomentsRepository(viroApiClient.moments) { tokenStore.getUserId() }
    /** What the platform is saying, which is usually nothing. */
    val promotions = com.viroreach.app.moments.PromotionsRepository(viroApiClient.promotions) { tokenStore.getUserId() }

    /**
     * The room's end of encryption: the same engine ordinary chats use, so a
     * session started to say something in a Moment is the session a later
     * private message rides on, and the safety number means one thing.
     */
    private val roomCrypto = object : com.viroreach.app.moments.RoomCrypto {
        override suspend fun canSealFor(userIds: List<String>): Boolean =
            messaging.e2ee.isRegistered() && messaging.e2ee.everyoneCanReceive(userIds)

        override suspend fun seal(
            recipients: List<String>,
            plaintext: String,
        ): List<com.viroreach.core.network.MomentEnvelopeBody> {
            val me = tokenStore.getUserId() ?: return emptyList()
            return messaging.e2ee.seal(me, recipients, plaintext)
                .map { com.viroreach.core.network.MomentEnvelopeBody(it.deviceId, it.ciphertext, it.type) }
        }

        override suspend fun open(
            senderUserId: String,
            senderDeviceId: String,
            ciphertext: String,
            type: Int,
        ): String? = messaging.e2ee.open(senderUserId, senderDeviceId, ciphertext, type)
    }

    /** State for one open Moment Room; short-lived like the room itself. */
    fun openMomentRoom(momentId: String): com.viroreach.app.moments.MomentRoomState =
        com.viroreach.app.moments.MomentRoomState(viroApiClient.moments, momentId, roomCrypto) {
            tokenStore.getUserId()
        }

    /** Sharing from this phone into one open Moment Room. */
    fun openMomentMedia(
        context: android.content.Context,
        room: com.viroreach.app.moments.MomentRoomState,
        playback: com.viroreach.app.moments.engine.SharedPlayback,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = com.viroreach.app.moments.engine.MomentMediaShare(context, viroApiClient.moments, room, playback, scope)

    /** An address the server gave relative to itself, made absolute for a player. */
    fun apiUrl(path: String): String = viroApiClient.baseUrl + path.removePrefix("/")

    /** The live faces and voices of one open Moment Room, over [media]. */
    fun openMomentLive(momentId: String, media: com.viroreach.voice.webrtc.MomentMediaTransport) =
        com.viroreach.app.moments.engine.MomentLive(viroApiClient.moments, momentId, media)

    /**
     * The encrypted backup of this phone's chats. Without it, an encrypted
     * conversation exists only on the phone that opened it.
     */
    val backup: com.viroreach.app.messaging.BackupManager = com.viroreach.app.messaging.BackupManager(
        appContext,
        viroApiClient.backup,
        viroApiClient.httpClient,
        viroApiClient.baseUrl,
    )
    /** What downloads by itself, and what Viro is keeping on this phone. */
    val mediaSettings: com.viroreach.app.messaging.MediaSettings = com.viroreach.app.messaging.MediaSettings(appContext)
    val mediaStorage: com.viroreach.app.messaging.MediaStorage by lazy {
        com.viroreach.app.messaging.MediaStorage(appContext, messaging.media)
    }

    /** My profile + reaching people without a phone number (Viro ID / email, connections). */
    val people: com.viroreach.app.people.PeopleRepository = com.viroreach.app.people.PeopleRepository(appContext) { api }
    val messageNotifier: MessageNotifier = MessageNotifier(appContext)
    /** One voice note plays at a time, across every chat. */
    val voicePlayer: VoicePlayer = VoicePlayer()
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
    val callerRingbackPlayer: com.viroreach.app.call.CallerRingbackPlayer =
        com.viroreach.app.call.CallerRingbackPlayer()
    val contactsRepository: CachedContactsRepository = CachedContactsRepository(this, appContext)

    var lastTargetInput: String = ""
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val contactsCoordinator: ContactsCoordinator = ContactsCoordinator(contactsRepository, scope)

    /** Carries favourites, custom names and hidden contacts across devices. */
    val preferenceSync: PreferenceSync = PreferenceSync(api, contactsRepository)
    private var callActiveSinceMs: Long? = null

    val isAuthenticated: Boolean
        get() = sessionTokenManager.isAuthenticated()

    val authenticatedPhoneE164: String?
        get() = tokenStore.getAuthenticatedPhoneE164()
            // Same legacy guard as TokenStore: an email-only account signed in
            // on an older build left its address in this slot too.
            ?: testIdentityStore.getPhoneE164()?.takeIf { looksLikeE164(it) }

    /** Set only for accounts that signed in with an email; null for phone accounts. */
    val authenticatedEmail: String?
        get() = tokenStore.getAuthenticatedEmail()

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
        startPresenceHeartbeat()
        startWssWatchdog()
        startMessaging()
        scope.launch {
            callManager.messagingFrames.collect { (type, payload) ->
                if (type.startsWith("moment.")) moments.onFrame(type, jsonToMap(payload))
            }
        }
        scope.launch {
            callManager.wssConnectionState.collect { state ->
                if (state == SignalingConnectionState.CONNECTED && isAuthenticated) {
                    moments.refresh(); moments.refreshInvitations()
                }
            }
        }
    }

    /** Socket payloads arrive as org.json objects (stubbed on the unit-test
     *  classpath); repositories take plain maps so they stay testable. */
    private fun jsonToMap(json: org.json.JSONObject): Map<String, Any?> =
        json.keys().asSequence().associateWith { key ->
            when (val value = json.get(key)) {
                is org.json.JSONObject -> jsonToMap(value)
                is org.json.JSONArray -> (0 until value.length()).mapNotNull { i ->
                    val item = value.get(i)
                    when (item) {
                        is org.json.JSONObject -> jsonToMap(item)
                        is org.json.JSONArray -> null
                        else -> item
                    }
                }
                else -> value
            }
        }

    /**
     * Messaging runs for the life of the process, not of a screen: frames are
     * applied and notifications raised whether or not a chat is open.
     */
    private var messagingStarted = false

    private fun startMessaging() {
        // Called again at sign-in, because the process-start call had no user
        // to start a session for. messaging.start() knows to redo only the
        // session-scoped half; the notification collector below must not be
        // duplicated, or every message would arrive twice.
        messaging.start()
        if (messagingStarted) return
        messagingStarted = true
        runCatching { ReminderScheduler.start(appContext) }
        // A backup nobody remembers to run is not a backup.
        runCatching { com.viroreach.app.messaging.BackupWorker.schedule(appContext) }
        scope.launch {
            messaging.incoming.collect { (conversationId, message) ->
                val conv = runCatching { messaging.conversation(conversationId).first() }.getOrNull()
                val name = runCatching { contactsRepository.displayNameForUserId(message.senderUserId) }.getOrNull()
                    ?: "Viro"
                messageNotifier.show(conv, name, message)
            }
        }
    }

    /**
     * Some networks (restrictive home/public Wi-Fi routers especially) NAT-time
     * out an "idle" WebSocket without ever telling Android the network itself
     * is down — hasInternet and networkGeneration both rely on a real network
     * change to fire, so neither one notices. The socket then sits dead until
     * OkHttp's own ping eventually times out (up to ~60s), and nothing was
     * actively watching afterward to reconnect it — a call signaled during
     * that dead window is simply lost, leaving the caller stuck on
     * "Calling…" forever. This actively checks and self-heals regardless of
     * whether Android ever reports a network change at all.
     */
    private fun startWssWatchdog() {
        scope.launch {
            while (true) {
                delay(8_000)
                if (!isAuthenticated) continue
                val wss = callManager.wssConnectionState.value
                if (wss != SignalingConnectionState.CONNECTED &&
                    wss != SignalingConnectionState.CONNECTING &&
                    wss != SignalingConnectionState.AUTHENTICATING
                ) {
                    callManager.connectSignaling(force = true)
                }
            }
        }
    }

    /**
     * Reports this device's presence to the server every 60s while connected —
     * comfortably inside PresenceService's 120s TTL, so a killed app or a lost
     * connection naturally reads as OFFLINE within two missed beats, with no
     * explicit disconnect hook needed.
     */
    private fun startPresenceHeartbeat() {
        scope.launch {
            while (true) {
                if (isAuthenticated && callManager.wssConnectionState.value == SignalingConnectionState.CONNECTED) {
                    val state = if (callManager.state.value == CallStateMachineState.ACTIVE) "BUSY" else "ONLINE"
                    runCatching { api.updatePresence(UpdatePresenceBody(state)) }
                }
                delay(60_000)
            }
        }
    }

    private fun observeCallLifecycle() {
        var previous = CallStateMachineState.IDLE
        scope.launch {
            callManager.state.collect { state ->
                // Guarded on callActiveSinceMs, not just the state transition —
                // a RECONNECTING dip mid-call also transitions back through
                // ACTIVE, and treating that as a fresh "answered" event reset
                // the recorded call duration back to zero on every reconnect.
                if (state == CallStateMachineState.ACTIVE &&
                    previous != CallStateMachineState.ACTIVE &&
                    callActiveSinceMs == null
                ) {
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
        // Records this account as the device's owner without clearing, so a
        // user who upgrades while signed in keeps their favourites.
        tokenStore.getUserId()?.let {
            runCatching { enforceAccountBoundary(it, isSessionRestore = true) }
        }
        runCatching { connectSignalingAuto() }
        // Profile, contacts, preferences and history are loaded by
        // warmUpForSession() before the UI is shown — see ViroReachRoot.
        startBackgroundSignaling()
        return true
    }

    val isReturningInstall: Boolean
        get() = testIdentityStore.hasRegisteredBefore() ||
            !tokenStore.getAuthenticatedPhoneE164().isNullOrBlank() ||
            !tokenStore.getAuthenticatedEmail().isNullOrBlank()

    fun updateLastTargetInput(input: String) {
        lastTargetInput = input
    }

    /**
     * [normalizedPhoneE164] is null for an email-only account — it has no
     * number to remember, and storing anything else here would surface as a
     * phone number elsewhere in the app.
     */
    suspend fun onAuthenticationSuccess(normalizedPhoneE164: String?) {
        normalizedPhoneE164?.let { testIdentityStore.savePhoneE164(it) }
        // Messaging was started when the process was — which, for anyone
        // signing in rather than being restored, was before there was a user
        // to start it for. Its session-scoped work (the first sync, the
        // feature flags, and registering this device's encryption keys with
        // the directory) has to run now, or this device would publish no keys
        // at all and nobody could send it an encrypted message until the app
        // was killed and reopened. The collectors inside it start only once.
        startMessaging()
        connectSignalingAuto()
        // The heavy loading happens in warmUpForSession(), behind the preparing
        // screen, so nothing here races the first paint.
        startBackgroundSignaling()
    }


    /**
     * Registers this device's FCM token with the API so the server can wake it
     * for an incoming call. Safe to call repeatedly — the server upserts by
     * token. Silently does nothing when Firebase isn't configured in this
     * build (no google-services.json), so debug builds without it still run.
     */
    suspend fun registerPushToken(token: String? = null): Boolean {
        if (!isAuthenticated) return false
        val fcmToken = token ?: runCatching {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
        }.getOrElse { return false }
        if (fcmToken.isBlank()) return false
        return runCatching {
            api.registerPushToken(
                com.viroreach.core.network.RegisterPushTokenBody(token = fcmToken),
            )
            true
        }.getOrDefault(false)
    }

    /**
     * Drops cached data belonging to a different account before this one is
     * allowed to see it.
     *
     * Signing out is not the only way accounts change hands: the app can be
     * killed mid-session, or a second account can sign in without the first
     * ever tapping logout. Keying the wipe on "the stored userId differs from
     * the one signing in" catches every one of those, which clearing only on
     * logout did not.
     */
    suspend fun enforceAccountBoundary(
        newUserId: String,
        isSessionRestore: Boolean = false,
    ) {
        val previous = tokenStore.getLastUserId()
        // Messages and keys follow the account: its own files, untouched by
        // anyone else signing in on this phone and waiting for it to return.
        if (previous != newUserId) {
            com.viroreach.core.database.MessagingDatabase.useAccount(newUserId)
            com.viroreach.core.e2ee.E2eeDatabase.useAccount(newUserId)
            runCatching { messaging.onAccountChanged() }
        }
        when {
            // Same account signing back in: keep everything. Favourites and
            // custom names exist nowhere else, so clearing here loses them.
            previous == newUserId -> Unit

            // A different account: wipe before it can read anything.
            previous != null -> clearLocalUserData("account-changed")

            // No recorded owner, but this session was already signed in when
            // the app started — it is the owner. Adopt it without clearing,
            // so upgrading the app does not cost a logged-in user their data.
            isSessionRestore -> Unit

            // No recorded owner and a fresh sign-in. Any cached data here
            // cannot be attributed to this account — it predates the device
            // ever recording an owner, which is exactly the state left by a
            // build older than this check. Unattributable data next to a new
            // account is the case that leaked favourites, so clear it.
            else -> clearLocalUserData("unknown-owner")
        }
        tokenStore.setLastUserId(newUserId)
    }

    /**
     * Wipes every local store that belongs to one account.
     *
     * Each store clears itself rather than this method reaching into its
     * DataStore: two `preferencesDataStore` delegates over the same file in one
     * process throws at runtime, so the owner has to be the one to clear it.
     *
     * Appearance preferences are deliberately left alone — theme, font size and
     * density describe the device, not the person, and resetting them on every
     * account switch protects nothing.
     */
    private suspend fun clearLocalUserData(reason: String) {
        moments.clear()
        android.util.Log.i("ViroSession", "CLEARING_LOCAL_USER_DATA reason=$reason")
        // Messages and encryption keys are not cleared here any more: each
        // account keeps its own, and the other account's are not the ones open.
        runCatching { promotions.clear() }
        runCatching { contactsRepository.clearCache() }
        runCatching { callHistoryStore.clearAll() }
        runCatching { profileRepository.clearCachedProfile() }
    }


    /**
     * Loads everything the first screen needs BEFORE the app is shown.
     *
     * Without this the UI painted immediately and filled in afterwards: the call
     * log appeared with phone numbers or placeholders, then silently rewrote
     * itself into real names once contacts finished syncing seconds later. Even
     * when each value was eventually correct, watching the app correct itself
     * reads as broken.
     *
     * Order matters and is the whole point:
     *  1. contacts first — every name shown anywhere resolves against them
     *  2. preferences second — favourites and custom names need contact rows to
     *     land on
     *  3. call history last — its names are resolved through the contacts loaded
     *     in step 1, so it renders correct the first time
     *
     * Bounded by a timeout: a slow or missing network must delay the app, never
     * strand the user on a loading screen. Whatever is ready by then is shown,
     * and the rest continues in the background.
     */
    suspend fun warmUpForSession(): Boolean {
        // The work runs in the SESSION's own scope, and the timeout applies only
        // to how long the UI waits for it. The previous version wrapped the work
        // itself in withTimeoutOrNull, which CANCELLED it part-done — and
        // loadContacts() deletes duplicate rows before a slow network step, so a
        // cancellation there wiped contacts out of the cache without rewriting
        // them. Waiting less is fine; interrupting a half-written cache is not.
        val job = scope.launch {
            runCatching { profileRepository.refreshFromServer() }
            runCatching { contactsRepository.loadContacts() }
            runCatching { people.refreshConnections() }
            runCatching { preferenceSync.pull() }
            runCatching {
                callHistoryStore.syncFromServer(api, tokenStore.getUserId()) { peerUserId ->
                    contactsRepository.displayNameForUserId(peerUserId)
                }
            }
            runCatching { registerPushToken() }
            runCatching { messaging.syncNow() }
            runCatching { relationships.refresh(force = true) }
            // A live location survives the app being closed: pick it back up.
            runCatching {
                if (messaging.myLiveLocations().isNotEmpty()) {
                    com.viroreach.app.messaging.location.LiveLocationService.start(appContext)
                }
            }
        }
        // A cold first sync on a phone with hundreds of contacts genuinely takes
        // longer than this. When that happens the app opens on whatever is
        // cached and the sync finishes behind it, rather than holding the user
        // on a spinner — the contacts list is observed from Room, so it fills in
        // as soon as the write lands.
        val finished = withTimeoutOrNull(WARMUP_TIMEOUT_MS) { job.join() } != null
        if (!finished) {
            android.util.Log.i(
                "ViroSession",
                "WARMUP_STILL_RUNNING after ${WARMUP_TIMEOUT_MS}ms — continuing in background",
            )
        }
        return finished
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
        moments.clear()
        stopBackgroundSignaling()
        callManager.disconnectSignaling()
        callManager.clearSessionCallContext()
        tokenStore.clearSession()
        // Deliberately NOT wiping cached data here. Signing out and back in as
        // the SAME account must not cost the user anything, and favourites and
        // custom contact names live only on this device — no server copy exists
        // to re-sync them from, so clearing on logout destroys them for good.
        //
        // The wipe belongs at sign-in instead (see enforceAccountBoundary): the
        // data is only unsafe once a DIFFERENT account is about to read it, and
        // nothing is readable between logout and the next sign-in anyway.
        voicePlayer.stop()
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
        /** Long enough for a slow connection, short enough not to feel stuck. */
        private const val WARMUP_TIMEOUT_MS = 12_000L

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

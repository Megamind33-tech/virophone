package com.viroreach.feature.calling

import android.content.Context
import com.viroreach.core.model.CallStateMachineState
import com.viroreach.core.network.ApiDiagnostics
import com.viroreach.core.network.SessionTokenManager
import com.viroreach.core.network.TokenStore
import com.viroreach.core.network.ViroApiService
import retrofit2.HttpException
import com.viroreach.feature.discovery.AuthorizedPeerResolver
import com.viroreach.feature.discovery.EphemeralIdGenerator
import com.viroreach.feature.discovery.LocalNetworkDiscoveryService
import com.viroreach.feature.discovery.NsdLanDiscovery
import com.viroreach.voice.api.CallStatistics
import com.viroreach.voice.webrtc.LiveKitCallEngine
import com.viroreach.voice.webrtc.WebRtcVoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.UUID

/**
 * Orchestrates authorize → signaling transport → WebRTC.
 * Signaling is abstracted — WSS (remote) or LAN (local, offline-trust authenticated).
 */
class CallManager(
    context: Context,
    private val tokenStore: TokenStore,
    private val sessionTokenManager: SessionTokenManager,
    private val api: ViroApiService,
    forceRelay: Boolean = false,
) {
    enum class RelayMode { AUTO, TURN_UDP, TURN_TCP }

    var relayMode: RelayMode = if (forceRelay) RelayMode.TURN_UDP else RelayMode.AUTO

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // WebRTC direct P2P remains for LOCAL_LAN calls only (LiveKit needs a
    // reachable server, so it cannot serve true offline/no-WAN calling).
    // Every WSS (internet) call's media now goes through LiveKit instead.
    private val voiceEngine: WebRtcVoiceEngine = WebRtcVoiceEngine(appContext)
    private val liveKitEngine = LiveKitCallEngine(appContext)
    private val offlineTrust = OfflineTrustStore(appContext)
    private val ephemeralGen = EphemeralIdGenerator()
    private val peerRegistry = LocalPeerRegistry()
    private val authenticator = LocalPeerAuthenticator(offlineTrust, tokenStore, ephemeralGen)
    private val wssTransport = RemoteWssSignalingTransport(scope)
    private val localTransport = LocalLanSignalingTransport(scope, authenticator)
    private val routeEngine = SignalingRouteEngine(appContext, peerRegistry)
    private val targetResolver = CallTargetResolver(api)

    private var activeTransport: CallSignalingTransport = wssTransport

    private val _state = MutableStateFlow(CallStateMachineState.IDLE)
    val state: StateFlow<CallStateMachineState> = _state.asStateFlow()

    private val _routeLabel = MutableStateFlow("NOT AVAILABLE")
    val routeLabel: StateFlow<String> = _routeLabel.asStateFlow()

    private val _signalingRoute = MutableStateFlow(SignalingRoute.NONE)
    val signalingRoute: StateFlow<SignalingRoute> = _signalingRoute.asStateFlow()

    private val _iceSummary = MutableStateFlow("NOT AVAILABLE")
    val iceSummary: StateFlow<String> = _iceSummary.asStateFlow()

    private val _callQuality = MutableStateFlow<CallStatistics?>(null)
    val callQuality: StateFlow<CallStatistics?> = _callQuality.asStateFlow()

    /** True while the *other* party's connection is reported poor/lost on an internet call. */
    val remotePeerUnstable: StateFlow<Boolean> = liveKitEngine.remotePeerUnstable

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    // Hold has to remember whatever the mute state was *before* it muted for
    // the hold itself, so resuming restores it exactly rather than always
    // unmuting — someone who had muted themselves before putting the call on
    // hold should still be muted afterward.
    private var mutedBeforeHold = false
    private val _isOnHold = MutableStateFlow(false)
    val isOnHold: StateFlow<Boolean> = _isOnHold.asStateFlow()

    /** True while the *peer* has put the call on hold (told to us via call.hold/call.unhold). */
    private val _peerOnHold = MutableStateFlow(false)
    val peerOnHold: StateFlow<Boolean> = _peerOnHold.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    val wssConnectionState = wssTransport.connectionState
    val wssStatusDetail = wssTransport.statusDetail
    val wssCloseCode = wssTransport.lastCloseCode
    val wssGeneration = wssTransport.connectionGeneration
    val localSignalingState = localTransport.connectionState
    val localPeerEndpoints = peerRegistry.endpoints

    private val _webRtcReady = MutableStateFlow(false)
    val webRtcReady: StateFlow<Boolean> = _webRtcReady.asStateFlow()

    private val _resolvedTarget = MutableStateFlow<ResolvedCallTarget?>(null)
    val resolvedTarget: StateFlow<ResolvedCallTarget?> = _resolvedTarget.asStateFlow()

    private val _incomingCall = MutableStateFlow<IncomingCallInfo?>(null)
    val incomingCall: StateFlow<IncomingCallInfo?> = _incomingCall.asStateFlow()

    private val _isCallerRole = MutableStateFlow(false)
    val isCallerRole: StateFlow<Boolean> = _isCallerRole.asStateFlow()

    private val _chatEvents = MutableSharedFlow<ChatSignalingEvent>(extraBufferCapacity = 32)
    val chatEvents: SharedFlow<ChatSignalingEvent> = _chatEvents.asSharedFlow()

    private val _conferenceEvents = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 32)
    val conferenceEvents: SharedFlow<SignalingMessage> = _conferenceEvents.asSharedFlow()

    private var activeCallId: String? = null
    private var calleeDeviceId: String? = null
    private var remotePeerDeviceId: String? = null
    private var callerUserId: String? = null
    private var isCaller = false
    private var pendingRemoteOffer: SessionDescription? = null
    private var calleeAcceptPending = false

    private val discoveryService: LocalNetworkDiscoveryService

    init {
        val trustResolver = object : AuthorizedPeerResolver {
            override suspend fun resolve(ephemeralId: String, bindingTag: String?) =
                resolveAuthorizedContact(ephemeralId, bindingTag)
        }
        lateinit var service: LocalNetworkDiscoveryService
        val nsd = NsdLanDiscovery(
            appContext,
            ephemeralGen,
            bindingTagProvider = { bindingTagForAdvertisement() },
        ) { eid, transport, btag, host, port ->
            peerRegistry.upsertAnonymous(eid, host, port, btag)
            service.onPeerDiscovered(eid, transport, btag, host, port)
            btag?.let { tag ->
                offlineTrust.resolvePeerByBindingTag(eid, tag, tokenStore.getDeviceId())?.let { uid ->
                    peerRegistry.authorize(eid, uid)
                }
            }
        }
        service = LocalNetworkDiscoveryService(ephemeralGen, trustResolver, nsd, null)
        discoveryService = service

        scope.launch {
            voiceEngine.initialize()
            _webRtcReady.value = true
        }
        localTransport.startListening()
        discoveryService.startLanDiscovery()

        wssTransport.incoming.onEach { handleSignalingMessage(it, SignalingRoute.WSS) }.launchIn(scope)
        localTransport.incoming.onEach { handleSignalingMessage(it, SignalingRoute.LOCAL_LAN) }.launchIn(scope)

        voiceEngine.onLocalIceCandidate = lambda@{ candidate ->
            val id = activeCallId ?: return@lambda
            activeTransport.send(
                "call.ice",
                id,
                remotePeerDeviceId(),
                JSONObject()
                    .put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex),
            )
        }
        voiceEngine.onIceRestartOffer = lambda@{ sdp ->
            val id = activeCallId ?: return@lambda
            sendIceRestartSdp(id, sdp)
        }
        voiceEngine.liveStatistics.onEach { stats ->
            _callQuality.value = stats
        }.launchIn(scope)
        voiceEngine.callState.onEach { engineState ->
            when (engineState) {
                CallStateMachineState.ACTIVE -> {
                    if (_state.value in CALLEE_ACTIVE_PROMOTE_STATES) {
                        _state.value = CallStateMachineState.ACTIVE
                        refreshIceSummary()
                    }
                }
                CallStateMachineState.FAILED -> {
                    if (_state.value in CALLEE_ACTIVE_PROMOTE_STATES) {
                        _state.value = CallStateMachineState.MEDIA_FAILED
                    }
                }
                else -> Unit
            }
        }.launchIn(scope)
        // Same bridging pattern for the LiveKit engine (WSS/internet calls) —
        // ACTIVE only once LiveKit reports a real remote-track subscription,
        // never merely on room-connected.
        liveKitEngine.callState.onEach { engineState ->
            when (engineState) {
                CallStateMachineState.ACTIVE -> {
                    if (_state.value in CALLEE_ACTIVE_PROMOTE_STATES) {
                        _state.value = CallStateMachineState.ACTIVE
                    }
                }
                CallStateMachineState.RECONNECTING -> {
                    if (_state.value == CallStateMachineState.ACTIVE) {
                        _state.value = CallStateMachineState.RECONNECTING
                    }
                }
                CallStateMachineState.MEDIA_FAILED -> {
                    if (_state.value in CALLEE_ACTIVE_PROMOTE_STATES) {
                        _state.value = CallStateMachineState.MEDIA_FAILED
                    }
                }
                else -> Unit
            }
        }.launchIn(scope)
    }


    /**
     * Everyone currently in this call's LiveKit room besides us. A 1:1 call
     * has one entry; adding people (see [addParticipant]) grows it, which is
     * what lets the in-call UI show a roster instead of a single name.
     */
    val callParticipants: StateFlow<List<com.viroreach.voice.webrtc.LiveKitParticipant>> =
        liveKitEngine.participants

    /**
     * Adds another person to the call already in progress. They ring as a
     * normal incoming call and join this same room on accept, so nobody's
     * audio is interrupted — see CallsService.inviteToCall.
     */
    suspend fun addParticipant(targetUserId: String): Result<Unit> {
        val callId = activeCallId
            ?: return Result.failure(IllegalStateException("No active call"))
        if (_signalingRoute.value == SignalingRoute.LOCAL_LAN) {
            return Result.failure(
                IllegalStateException("Adding people isn't available on a LAN call"),
            )
        }
        return runCatching {
            api.inviteToCall(
                callId,
                com.viroreach.core.network.InviteToCallBody(targetUserId),
            )
            log("CALL_INVITE_SENT callId=$callId target=$targetUserId")
            Unit
        }.onFailure { e ->
            _lastError.value = "Couldn't add them to the call: ${e.message}"
        }
    }
    private fun sendIceRestartSdp(callId: String, sdp: SessionDescription) {
        activeTransport.send(
            "call.iceRestart",
            callId,
            remotePeerDeviceId(),
            JSONObject()
                .put("sdp", sdp.description)
                .put("type", sdp.type.canonicalForm()),
        )
    }

    private fun remotePeerDeviceId(): String? = remotePeerDeviceId

    private fun bindingTagForAdvertisement(): String? {
        val eid = ephemeralGen.getCurrentId()
        val entry = offlineTrust.getMaterial().firstOrNull() ?: return null
        return offlineTrust.computeBindingTag(entry.trustToken, eid)
    }

    private suspend fun resolveAuthorizedContact(ephemeralId: String, bindingTag: String?): com.viroreach.core.model.AuthorizedNearbyContact? {
        if (bindingTag == null) return null
        return offlineTrust.resolvePeerByBindingTag(ephemeralId, bindingTag, tokenStore.getDeviceId())
            ?.let { peerUserId ->
                peerRegistry.authorize(ephemeralId, peerUserId)
                com.viroreach.core.model.AuthorizedNearbyContact(
                    contact = com.viroreach.core.model.KnownContact(
                        userId = peerUserId,
                        localName = peerUserId.take(8),
                        phoneE164 = null,
                        viroId = null,
                        relationshipState = com.viroreach.core.model.ContactRelationshipState.UNKNOWN,
                    ),
                    ephemeralId = ephemeralId,
                    transportType = com.viroreach.core.model.CallRouteType.LAN,
                )
            }
    }

    private fun lanIceMaterial(callId: String): Map<String, String> = mapOf(
        "callId" to callId,
        "iceServers" to "stun:stun.l.google.com:19302",
    )

    suspend fun connectSignaling(force: Boolean = false): Boolean {
        val token = sessionTokenManager.getAccessTokenForRequest()
        if (token == null) {
            _lastError.value = "WSS FAILED — NOT AUTHENTICATED"
            return false
        }
        activeTransport = wssTransport
        _signalingRoute.value = SignalingRoute.WSS
        wssTransport.connect(
            SignalingConnectConfig(
                wssUrl = com.viroreach.core.network.BuildConfig.WSS_URL,
                accessToken = token,
                forceReconnect = force,
            ),
        )
        return true
    }

    fun disconnectSignaling() {
        wssTransport.disconnect()
        _signalingRoute.value = SignalingRoute.NONE
    }

    fun internetCallPrerequisites(): List<String> {
        val missing = mutableListOf<String>()
        if (!sessionTokenManager.isAuthenticated()) missing.add("AUTH")
        if (wssConnectionState.value != SignalingConnectionState.CONNECTED) missing.add("WSS")
        if (!_webRtcReady.value) missing.add("WEBRTC")
        return missing
    }

    /**
     * Connects WSS if needed and waits until the call stack is ready.
     * Consumer flows call this automatically — users never configure signaling.
     */
    suspend fun ensureSignalingReady(timeoutMs: Long = 15_000L): Boolean {
        if (!sessionTokenManager.isAuthenticated()) {
            _lastError.value = "Sign in to place Viro calls"
            return false
        }
        if (wssConnectionState.value == SignalingConnectionState.CONNECTED && _webRtcReady.value) {
            return true
        }
        connectSignaling(force = wssConnectionState.value == SignalingConnectionState.FAILED)
        voiceEngine.ensureInitialized()
        val ready = withTimeoutOrNull(timeoutMs) {
            while (true) {
                if (wssConnectionState.value == SignalingConnectionState.CONNECTED && _webRtcReady.value) {
                    return@withTimeoutOrNull true
                }
                if (wssConnectionState.value == SignalingConnectionState.FAILED) {
                    connectSignaling(force = true)
                }
                delay(250)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false
        if (!ready) {
            _lastError.value = when (routeEngine.isInternetAvailable()) {
                false -> "No network connection. Try mobile data or Wi-Fi."
                else -> "Connecting to Viro Call… please wait a moment and try again"
            }
        }
        return ready
    }

    fun primeResolvedTarget(target: ResolvedCallTarget) {
        _resolvedTarget.value = target
        _lastError.value = null
    }

    /** Skip discover API when the contact is already known to be on Viro Call. */
    suspend fun startCallToRegisteredUser(
        userId: String,
        phoneE164: String?,
        displayName: String?,
    ) {
        val phone = phoneE164?.trim().orEmpty()
        primeResolvedTarget(
            ResolvedCallTarget(
                inputIdentity = phone.ifBlank { userId },
                userId = userId,
                phoneE164 = phoneE164,
                displayName = displayName,
                relationshipState = "REGISTERED",
            ),
        )
        startCall(phone.ifBlank { userId })
    }

    suspend fun resolveTarget(input: String): ResolvedCallTarget? {
        val trimmed = input.trim()
        return try {
            targetResolver.resolveTarget(trimmed).also {
                _resolvedTarget.value = it
                _lastError.value = null
            }
        } catch (e: CallTargetException) {
            _lastError.value = "${e.code}: ${e.message}"
            if (_resolvedTarget.value?.inputIdentity != trimmed) {
                _resolvedTarget.value = null
            }
            null
        } catch (e: HttpException) {
            val fail = ApiDiagnostics.parseFailure("POST", "/api/v1/contacts/discover", e)
            _lastError.value = fail.summary()
            if (_resolvedTarget.value?.inputIdentity != trimmed) {
                _resolvedTarget.value = null
            }
            null
        }
    }

    fun clearResolvedTarget() {
        _resolvedTarget.value = null
    }

    suspend fun startCall(targetInput: String) {
        _lastError.value = null
        if (targetInput.isBlank()) {
            _lastError.value = "Enter a phone number to call"
            _state.value = CallStateMachineState.FAILED
            return
        }
        // Set before any suspending work: the call screen opens synchronously the
        // moment the user taps Call, but isCaller previously wasn't set true until
        // deep inside startRemoteCall/startLocalCall. During that gap (ensureSignalingReady
        // alone can take up to 15s) the UI read state=IDLE + isCaller=false, which the
        // label function renders as "Incoming…" on a call the user just placed themselves.
        isCaller = true
        _isCallerRole.value = true
        if (!ensureSignalingReady()) {
            _state.value = CallStateMachineState.FAILED
            return
        }
        try {
            voiceEngine.ensureInitialized().getOrElse { e ->
                throw IllegalStateException("WebRTC init failed: ${e.message}")
            }

            val trimmed = targetInput.trim()
            val resolved = _resolvedTarget.value?.takeIf {
                it.inputIdentity == trimmed || it.phoneE164 == trimmed || it.userId == trimmed
            } ?: targetResolver.resolveTarget(trimmed).also { _resolvedTarget.value = it }
            val targetUserId = resolved.userId

            val route = routeEngine.selectRoute(targetUserId)
            _signalingRoute.value = route
            when (route) {
                SignalingRoute.LOCAL_LAN -> startLocalCall(targetUserId)
                SignalingRoute.WSS -> startRemoteCall(targetUserId)
                else -> {
                    _lastError.value = "No signaling route available (offline / no local peer)"
                    _state.value = CallStateMachineState.FAILED
                }
            }
        } catch (e: CallTargetException) {
            _state.value = CallStateMachineState.FAILED
            _lastError.value = "${e.code}: ${e.message}"
        } catch (e: Exception) {
            _state.value = CallStateMachineState.FAILED
            _lastError.value = e.message ?: e.javaClass.simpleName
        }
        // Resolved target and WSS are session infrastructure — never cleared on call failure.
    }

    private suspend fun startRemoteCall(targetUserId: String) {
        activeTransport = wssTransport
        _state.value = CallStateMachineState.AUTHORIZING
        val preferred = when (relayMode) {
            RelayMode.AUTO -> "INTERNET_P2P"
            RelayMode.TURN_UDP, RelayMode.TURN_TCP -> "TURN_RELAY"
        }
        val auth = try {
            api.authorizeCall(
                com.viroreach.core.network.AuthorizeCallBody(targetUserId, preferred),
            )
        } catch (e: HttpException) {
            val fail = ApiDiagnostics.parseFailure("POST", "/api/v1/calls/authorize", e)
            _state.value = CallStateMachineState.FAILED
            _lastError.value = "CALL AUTHORIZE FAILED ${fail.summary()}"
            return
        }
        if (!auth.authorized) {
            _state.value = CallStateMachineState.UNAUTHORIZED
            _lastError.value = "Call not authorized for target user"
            return
        }
        activeCallId = auth.callId
        calleeDeviceId = auth.sessionMaterial?.get("calleeDeviceId")
        remotePeerDeviceId = calleeDeviceId
        isCaller = true
        _isCallerRole.value = true
        _incomingCall.value = null

        // No SDP/ICE to negotiate over Viro's own signaling anymore — LiveKit
        // handles that internally once both sides join the room after Accept.
        // call.invite still rings the callee's device(s) exactly as before.
        _state.value = CallStateMachineState.INVITING
        activeTransport.send("call.invite", auth.callId, remotePeerDeviceId(), JSONObject())
        _routeLabel.value = "Viro Call"
    }

    private suspend fun startLocalCall(targetUserId: String) {
        val peer = peerRegistry.findAuthorizedPeer(targetUserId)
            ?: throw IllegalStateException("No authorized local peer endpoint for $targetUserId")
        activeTransport = localTransport
        localTransport.connect(
            SignalingConnectConfig(
                peerHost = peer.hostAddress,
                peerPort = peer.signalingPort,
                peerUserId = targetUserId,
                peerEphemeralId = peer.ephemeralId,
                peerBindingTag = peer.bindingTag,
            ),
        )
        val callId = UUID.randomUUID().toString()
        activeCallId = callId
        isCaller = true
        _isCallerRole.value = true
        _state.value = CallStateMachineState.SIGNALING

        val material = lanIceMaterial(callId)
        voiceEngine.startCall(targetUserId, material)
        voiceEngine.applySessionMaterial(material)

        _state.value = CallStateMachineState.INVITING
        voiceEngine.createOffer { sdp ->
            activeTransport.send(
                "call.invite",
                callId,
                null,
                JSONObject().put("sdp", sdp.description).put("type", sdp.type.canonicalForm()),
            )
            activeTransport.send("call.offer", callId, null, JSONObject().put("sdp", sdp.description))
        }
        _routeLabel.value = "LAN (local signaling)"
    }

    suspend fun acceptCall(callId: String) {
        try {
            activeCallId = callId
            isCaller = false
            _isCallerRole.value = false
            _state.value = CallStateMachineState.CONNECTING
            val target = remotePeerDeviceId()
            if (_signalingRoute.value == SignalingRoute.LOCAL_LAN) {
                // Offline/no-WAN calling can't reach a LiveKit server — keep the
                // existing direct WebRTC P2P path for LAN calls unchanged.
                calleeAcceptPending = true
                voiceEngine.ensureInitialized().getOrElse { e ->
                    throw IllegalStateException("Voice engine unavailable: ${e.message}")
                }
                voiceEngine.applySessionMaterial(lanIceMaterial(callId))
                voiceEngine.acceptCall(callId)
                completeCalleeHandshake(callId)
                _routeLabel.value = "LAN (local signaling)"
            } else {
                activeTransport.send("call.accept", callId, target)
                _incomingCall.value = null
                connectLiveKitMedia(callId)
                _routeLabel.value = "Viro Call"
            }
        } catch (e: Exception) {
            _lastError.value = e.message ?: "Could not answer call"
            _state.value = CallStateMachineState.MEDIA_FAILED
            clearIncomingState()
        }
    }

    /**
     * Fetches a room-scoped LiveKit token for [callId] and joins — used by
     * both sides once the call is accepted (see acceptCall and the
     * call.accept branch in handleSignalingMessage).
     */
    private suspend fun connectLiveKitMedia(callId: String) {
        try {
            // Neither the token fetch nor Room.connect() had a bound here —
            // a stalled network call (no response, not even a failure) left
            // the call sitting at CONNECTING forever with nothing on screen
            // to explain why or any way to retry.
            val connected = withTimeoutOrNull(LIVEKIT_CONNECT_TIMEOUT_MS) {
                val creds = api.getLiveKitToken(callId)
                log("LIVEKIT_TOKEN_RECEIVED callId=$callId")
                liveKitEngine.connect(creds.url, creds.token, forceRelay = relayMode != RelayMode.AUTO)
                true
            }
            if (connected == null) {
                log("LIVEKIT_CONNECT_TIMEOUT callId=$callId")
                _lastError.value = "Couldn't connect the call — check your connection and try again."
                _state.value = CallStateMachineState.MEDIA_FAILED
            }
        } catch (e: Exception) {
            _lastError.value = "Media connect failed: ${e.message}"
            _state.value = CallStateMachineState.MEDIA_FAILED
        }
    }

    private fun log(event: String) {
        android.util.Log.i("ViroCall", event)
    }

    private fun completeCalleeHandshake(callId: String) {
        if (!calleeAcceptPending) return
        val offer = pendingRemoteOffer ?: return
        pendingRemoteOffer = null
        calleeAcceptPending = false
        voiceEngine.setRemoteDescription(offer) {
            voiceEngine.createAnswer { sdp ->
                val target = remotePeerDeviceId()
                activeTransport.send(
                    "call.accept",
                    callId,
                    target,
                )
                activeTransport.send(
                    "call.answer",
                    callId,
                    target,
                    JSONObject()
                        .put("sdp", sdp.description)
                        .put("type", sdp.type.canonicalForm()),
                )
                // Signaling is done, but media hasn't connected yet — stay in
                // CONNECTING until the ICE observer reports a real connection
                // (see voiceEngine.callState.onEach below). Marking ACTIVE here
                // lies to the UI and causes an immediate ACTIVE->RECONNECTING
                // flip the moment ICE state is actually observed.
                _state.value = CallStateMachineState.CONNECTING
                _incomingCall.value = null
                refreshIceSummary()
            }
        }
    }

    private fun bufferRemoteOffer(sdpText: String) {
        pendingRemoteOffer = SessionDescription(SessionDescription.Type.OFFER, sdpText)
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        if (_signalingRoute.value == SignalingRoute.LOCAL_LAN) voiceEngine.setMuted(muted)
        else liveKitEngine.setMuted(muted)
    }

    /**
     * Puts the call on hold: mutes local audio and tells the peer, so their
     * screen shows they've been put on hold rather than just going silent
     * with no explanation.
     */
    fun holdCall() {
        if (_state.value != CallStateMachineState.ACTIVE || _isOnHold.value) return
        mutedBeforeHold = _isMuted.value
        _isOnHold.value = true
        setMuted(true)
        val id = activeCallId ?: return
        activeTransport.send("call.hold", id, remotePeerDeviceId())
    }

    fun resumeCall() {
        if (!_isOnHold.value) return
        _isOnHold.value = false
        setMuted(mutedBeforeHold)
        val id = activeCallId ?: return
        activeTransport.send("call.unhold", id, remotePeerDeviceId())
    }

    fun setSpeaker(on: Boolean) {
        if (_signalingRoute.value == SignalingRoute.LOCAL_LAN) voiceEngine.setSpeaker(on)
        else liveKitEngine.setSpeaker(on)
    }

    fun sendSignalingEvent(
        type: String,
        callId: String,
        targetDeviceId: String? = null,
        payload: JSONObject? = null,
    ) {
        activeTransport.send(type, callId, targetDeviceId, payload)
    }

    fun sendConferenceEvent(
        type: String,
        roomId: String,
        targetDeviceId: String? = null,
        payload: JSONObject? = null,
    ) {
        wssTransport.sendConference(type, roomId, targetDeviceId, payload)
    }

    fun sendChatMessage(conversationId: String, targetUserId: String?, body: String) {
        sendSignalingEvent(
            type = "chat.message",
            callId = conversationId,
            payload = JSONObject()
                .put("body", body)
                .put("targetUserId", targetUserId ?: JSONObject.NULL),
        )
    }

    suspend fun rejectCall() {
        // The UI's global call-overlay watcher re-shows the call screen for any
        // non-terminal state, so ENDED must be reached even if teardown throws —
        // otherwise the call state gets stuck and later navigation (e.g. opening
        // chat) can suddenly snap back to the call screen.
        try {
            runCatching {
                activeCallId?.let { id ->
                    activeTransport.send("call.reject", id, remotePeerDeviceId())
                }
            }
            runCatching { endActiveMedia() }
        } finally {
            _state.value = CallStateMachineState.ENDED
            clearIncomingState()
        }
    }

    suspend fun hangUp() {
        // See rejectCall — teardown steps must not prevent reaching ENDED.
        try {
            activeCallId?.let { id ->
                runCatching { activeTransport.send("call.end", id, remotePeerDeviceId()) }
                runCatching { endActiveMedia() }
                if (_signalingRoute.value == SignalingRoute.WSS) {
                    runCatching { api.endCall(id) }
                }
            }
        } finally {
            _state.value = CallStateMachineState.ENDED
            clearIncomingState()
        }
    }

    /**
     * Caller-side auto-cancel after ringing too long with no answer — same
     * teardown as hangUp(), but lands on TIMEOUT ("No answer") instead of a
     * generic ENDED, and is what stops the caller's screen sitting on
     * "Calling…" forever when the other side never picks up.
     */
    suspend fun cancelUnanswered() {
        try {
            activeCallId?.let { id ->
                runCatching { activeTransport.send("call.end", id, remotePeerDeviceId()) }
                runCatching { endActiveMedia() }
                if (_signalingRoute.value == SignalingRoute.WSS) {
                    runCatching { api.endCall(id) }
                }
            }
        } finally {
            _state.value = CallStateMachineState.TIMEOUT
            clearIncomingState()
        }
    }

    /** Tears down whichever media engine this call actually used. */
    private suspend fun endActiveMedia() {
        val id = activeCallId
        if (_signalingRoute.value == SignalingRoute.LOCAL_LAN) {
            id?.let { voiceEngine.endCall(it) }
        } else {
            liveKitEngine.disconnect()
        }
    }

    private fun onIncomingCall(msg: SignalingMessage, viaRoute: SignalingRoute) {
        if (activeCallId == msg.callId &&
            _state.value != CallStateMachineState.IDLE &&
            _state.value != CallStateMachineState.ENDED
        ) {
            msg.payload?.optString("sdp")?.takeIf { it.isNotBlank() }?.let(::bufferRemoteOffer)
            if (calleeAcceptPending) {
                completeCalleeHandshake(msg.callId)
            }
            return
        }
        isCaller = false
        _isCallerRole.value = false
        activeCallId = msg.callId
        callerUserId = msg.fromUserId
        remotePeerDeviceId = msg.fromDeviceId
        calleeDeviceId = msg.fromDeviceId
        val callerPhone = msg.payload?.optString("callerPhoneE164", null)
        val callerName = msg.payload?.optString("callerDisplayName", null)
        _incomingCall.value = IncomingCallInfo(
            callId = msg.callId,
            callerUserId = msg.fromUserId,
            callerDeviceId = msg.fromDeviceId,
            callerPhoneE164 = callerPhone,
            callerDisplayName = callerName,
        )
        msg.payload?.optString("sdp")?.takeIf { it.isNotBlank() }?.let(::bufferRemoteOffer)
        activeTransport = if (viaRoute == SignalingRoute.LOCAL_LAN) localTransport else wssTransport
        _signalingRoute.value = viaRoute
        _state.value = CallStateMachineState.RINGING
        _lastError.value = null
        msg.fromDeviceId?.let { callerDevice ->
            activeTransport.send("call.ringing", msg.callId, callerDevice)
        }
        if (calleeAcceptPending) {
            completeCalleeHandshake(msg.callId)
        }
    }

    private fun handleSignalingMessage(msg: SignalingMessage, viaRoute: SignalingRoute) {
        if (msg.type.startsWith("conf.")) {
            scope.launch { _conferenceEvents.emit(msg) }
            return
        }
        when (msg.type) {
            "call.incoming", "call.invite" -> onIncomingCall(msg, viaRoute)
            "call.ringing" -> {
                if (isCaller) {
                    activeCallId = msg.callId
                    _state.value = CallStateMachineState.RINGING
                }
            }
            "call.accept" -> {
                if (isCaller && _state.value in CALLER_PREACTIVE_STATES) {
                    _state.value = CallStateMachineState.CONNECTING
                    if (_signalingRoute.value == SignalingRoute.WSS) {
                        // LAN calls still complete their media handshake via the
                        // call.offer/call.answer/call.ice exchange below.
                        scope.launch { connectLiveKitMedia(msg.callId) }
                    }
                }
            }
            // These four only ever fire for LOCAL_LAN calls now — WSS/internet
            // calls stopped sending them once LiveKit took over their media
            // (see startRemoteCall/acceptCall). Guarded so the two media
            // engines can never both act on the same call.
            "call.offer" -> {
                if (_signalingRoute.value != SignalingRoute.LOCAL_LAN) return
                val sdp = msg.payload?.getString("sdp") ?: return
                if (!isCaller) {
                    // Ignore duplicate/late offers once the call is already up —
                    // the caller sends the SDP via both call.invite and
                    // call.offer, and re-applying it would disrupt live media.
                    if (_state.value == CallStateMachineState.ACTIVE) return
                    bufferRemoteOffer(sdp)
                    when (_state.value) {
                        CallStateMachineState.RINGING -> return
                        CallStateMachineState.CONNECTING -> {
                            completeCalleeHandshake(msg.callId)
                            return
                        }
                        else -> Unit
                    }
                }
                val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
                voiceEngine.setRemoteDescription(offer) {
                    scope.launch {
                        if (!isCaller) {
                            voiceEngine.createAnswer { answer ->
                                activeTransport.send(
                                    "call.answer",
                                    msg.callId,
                                    remotePeerDeviceId(),
                                    JSONObject()
                                        .put("sdp", answer.description)
                                        .put("type", answer.type.canonicalForm()),
                                )
                                // See completeCalleeHandshake: wait for the real ICE
                                // connection before claiming ACTIVE.
                                _state.value = CallStateMachineState.CONNECTING
                            }
                        }
                    }
                }
            }
            "call.answer" -> {
                if (_signalingRoute.value != SignalingRoute.LOCAL_LAN) return
                val sdp = msg.payload?.getString("sdp") ?: return
                voiceEngine.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp)) {
                    // Media negotiation starts here, but ICE hasn't connected yet —
                    // let the ICE observer promote to ACTIVE for real.
                    _state.value = CallStateMachineState.CONNECTING
                    refreshIceSummary()
                }
            }
            "call.ice" -> {
                if (_signalingRoute.value != SignalingRoute.LOCAL_LAN) return
                val payload = msg.payload ?: return
                val candidate = IceCandidate(
                    payload.optString("sdpMid"),
                    payload.optInt("sdpMLineIndex"),
                    payload.getString("candidate"),
                )
                voiceEngine.addIceCandidate(candidate)
            }
            "call.iceRestart" -> {
                if (_signalingRoute.value == SignalingRoute.LOCAL_LAN) handleIceRestart(msg)
            }
            "call.hold" -> _peerOnHold.value = true
            "call.unhold" -> _peerOnHold.value = false
            // Distinct terminal states so the UI can show the right reason
            // ("Call declined" / "Line busy") instead of a generic end — the
            // labels already existed in consumerFailureMessage but nothing
            // was ever setting these states to trigger them.
            // The side that presses hang-up tears its own media down explicitly
            // (see hangUp()) — but the OTHER side only ever learns about it
            // through this signaling message, and until now this just flipped
            // the state flag without ever disconnecting LiveKit/WebRTC. Their
            // audio engine kept running — media never actually stopped on
            // that side, even though the screen said the call had ended.
            "call.reject" -> {
                _state.value = CallStateMachineState.PEER_REJECTED
                // endActiveMedia() must run (and read activeCallId) before
                // clearIncomingState() nulls it out — both belong in the same
                // coroutine so that ordering is guaranteed.
                scope.launch {
                    runCatching { endActiveMedia() }
                    clearIncomingState()
                }
            }
            "call.busy" -> {
                _state.value = CallStateMachineState.BUSY
                scope.launch {
                    runCatching { endActiveMedia() }
                    clearIncomingState()
                }
            }
            "call.end" -> {
                _state.value = CallStateMachineState.ENDED
                scope.launch {
                    runCatching { endActiveMedia() }
                    clearIncomingState()
                }
            }
            "call.error" -> {
                val code = msg.payload?.optString("code", msg.payload?.optString("reason"))
                _lastError.value = code?.let { "Signaling error: $it" } ?: "Signaling error"
                if (_state.value != CallStateMachineState.ACTIVE) {
                    _state.value = CallStateMachineState.FAILED
                }
            }
            "chat.message" -> {
                val body = msg.payload?.optString("body") ?: return
                scope.launch {
                    _chatEvents.emit(
                        ChatSignalingEvent(
                            conversationId = msg.callId,
                            fromUserId = msg.fromUserId,
                            body = body,
                        ),
                    )
                }
            }
            "message.new" -> {
                // Server-delivered persisted message (Phase B messaging).
                val payload = msg.payload ?: return
                val messageObj = payload.optJSONObject("message") ?: return
                val body = messageObj.optString("body")
                if (body.isNullOrBlank()) return
                val senderUserId = messageObj.optString("senderUserId", msg.fromUserId ?: "")
                val conversationId = payload.optString("conversationId", msg.callId)
                scope.launch {
                    _chatEvents.emit(
                        ChatSignalingEvent(
                            conversationId = conversationId,
                            fromUserId = senderUserId.ifBlank { msg.fromUserId },
                            body = body,
                        ),
                    )
                }
            }
        }
    }

    fun clearSessionCallContext() {
        clearResolvedTarget()
        _state.value = CallStateMachineState.IDLE
        _lastError.value = null
        clearIncomingState()
    }

    private fun clearIncomingState() {
        activeCallId = null
        callerUserId = null
        calleeDeviceId = null
        remotePeerDeviceId = null
        pendingRemoteOffer = null
        calleeAcceptPending = false
        isCaller = false
        _isCallerRole.value = false
        _incomingCall.value = null
        _callQuality.value = null
        _isMuted.value = false
        _isOnHold.value = false
        _peerOnHold.value = false
        mutedBeforeHold = false
    }

    companion object {
        private const val LIVEKIT_CONNECT_TIMEOUT_MS = 20_000L
        private val CALLER_PREACTIVE_STATES = setOf(
            CallStateMachineState.INVITING,
            CallStateMachineState.RINGING,
            CallStateMachineState.SIGNALING,
            CallStateMachineState.AUTHORIZING,
            CallStateMachineState.CONNECTING,
        )
        private val CALLEE_ACTIVE_PROMOTE_STATES = setOf(
            CallStateMachineState.CONNECTING,
            CallStateMachineState.RINGING,
            CallStateMachineState.SIGNALING,
            CallStateMachineState.INVITING,
            CallStateMachineState.RECONNECTING,
        )
    }

    fun pendingCallId(): String? = activeCallId

    fun refreshIceSummary() {
        voiceEngine.collectIceSummary { summary -> _iceSummary.value = summary }
    }

    /**
     * Phase 1B — recover media path after Wi-Fi/mobile handoff or brief connectivity loss.
     */
    suspend fun recoverFromNetworkTransition() {
        // LiveKit (WSS/internet calls) reconnects on network changes on its own —
        // see the RoomEvent.Reconnecting/Reconnected bridging in init{}, which
        // already keeps _state in sync. There's no equivalent recovery for a
        // direct LAN P2P call (no relay to fail over to), so nothing to do there.
    }

    private fun handleIceRestart(msg: SignalingMessage) {
        val payload = msg.payload ?: return
        val sdpText = payload.optString("sdp", null) ?: return
        val typeLabel = payload.optString("type", "offer")
        val type = when (typeLabel.lowercase()) {
            "answer" -> SessionDescription.Type.ANSWER
            "pranswer" -> SessionDescription.Type.PRANSWER
            else -> SessionDescription.Type.OFFER
        }
        val sdp = SessionDescription(type, sdpText)
        if (type == SessionDescription.Type.OFFER) {
            voiceEngine.setRemoteDescription(sdp) {
                voiceEngine.createAnswer { answer ->
                    activeTransport.send(
                        "call.iceRestart",
                        msg.callId,
                        msg.fromDeviceId,
                        JSONObject()
                            .put("sdp", answer.description)
                            .put("type", answer.type.canonicalForm()),
                    )
                }
            }
        } else {
            voiceEngine.setRemoteDescription(sdp) {
                _state.value = CallStateMachineState.ACTIVE
                refreshIceSummary()
            }
        }
    }

    fun shutdown() {
        discoveryService.stopLanDiscovery()
        wssTransport.disconnect()
        localTransport.disconnect()
        scope.launch {
            voiceEngine.shutdown()
            liveKitEngine.disconnect()
        }
    }
}

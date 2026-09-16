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
    private val voiceEngine: WebRtcVoiceEngine = WebRtcVoiceEngine(appContext)
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

    private fun iceSessionMaterial(callId: String, turn: com.viroreach.core.network.TurnCredentialsResponse): Map<String, String> {
        val urls = when (relayMode) {
            RelayMode.AUTO -> turn.urls
            RelayMode.TURN_UDP -> turn.urls.filter { it.contains("transport=udp") || it.startsWith("stun:") }
            RelayMode.TURN_TCP -> turn.urls.filter { it.contains("transport=tcp") }
        }
        voiceEngine.iceTransportPolicyRelay = relayMode != RelayMode.AUTO
        return mapOf(
            "callId" to callId,
            "iceServers" to urls.joinToString(","),
            "iceUsername" to turn.username,
            "iceCredential" to turn.credential,
        )
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
        _state.value = CallStateMachineState.SIGNALING

        val turn = api.getTurnCredentials()
        val material = iceSessionMaterial(auth.callId, turn)
        voiceEngine.startCall(targetUserId, material)
        voiceEngine.applySessionMaterial(material)

        _state.value = CallStateMachineState.INVITING
        voiceEngine.createOffer { sdp ->
            activeTransport.send(
                "call.invite",
                auth.callId,
                remotePeerDeviceId(),
                JSONObject().put("sdp", sdp.description).put("type", sdp.type.canonicalForm()),
            )
            activeTransport.send("call.offer", auth.callId, remotePeerDeviceId(), JSONObject().put("sdp", sdp.description))
        }
        _routeLabel.value = when (relayMode) {
            RelayMode.AUTO -> "P2P/TURN (auto)"
            RelayMode.TURN_UDP -> "TURN UDP (forced)"
            RelayMode.TURN_TCP -> "TURN TCP (forced)"
        }
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
            calleeAcceptPending = true
            val material = if (_signalingRoute.value == SignalingRoute.LOCAL_LAN) {
                lanIceMaterial(callId)
            } else {
                val turn = api.getTurnCredentials()
                iceSessionMaterial(callId, turn)
            }
            voiceEngine.ensureInitialized().getOrElse { e ->
                throw IllegalStateException("Voice engine unavailable: ${e.message}")
            }
            voiceEngine.applySessionMaterial(material)
            voiceEngine.acceptCall(callId)
            completeCalleeHandshake(callId)
            _routeLabel.value = when (_signalingRoute.value) {
                SignalingRoute.LOCAL_LAN -> "LAN (local signaling)"
                else -> when (relayMode) {
                    RelayMode.AUTO -> "P2P/TURN (auto)"
                    RelayMode.TURN_UDP -> "TURN UDP (forced)"
                    RelayMode.TURN_TCP -> "TURN TCP (forced)"
                }
            }
        } catch (e: Exception) {
            _lastError.value = e.message ?: "Could not answer call"
            _state.value = CallStateMachineState.MEDIA_FAILED
            clearIncomingState()
        }
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
                _state.value = CallStateMachineState.ACTIVE
                _incomingCall.value = null
                refreshIceSummary()
            }
        }
    }

    private fun bufferRemoteOffer(sdpText: String) {
        pendingRemoteOffer = SessionDescription(SessionDescription.Type.OFFER, sdpText)
    }

    fun setMuted(muted: Boolean) = voiceEngine.setMuted(muted)
    fun setSpeaker(on: Boolean) = voiceEngine.setSpeaker(on)

    fun sendSignalingEvent(
        type: String,
        callId: String,
        targetDeviceId: String? = null,
        payload: JSONObject? = null,
    ) {
        activeTransport.send(type, callId, targetDeviceId, payload)
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
        runCatching {
            activeCallId?.let { id ->
                activeTransport.send("call.reject", id, remotePeerDeviceId())
            }
        }
        activeCallId?.let { voiceEngine.endCall(it) }
        _state.value = CallStateMachineState.ENDED
        clearIncomingState()
    }

    suspend fun hangUp() {
        activeCallId?.let { id ->
            runCatching { activeTransport.send("call.end", id, remotePeerDeviceId()) }
            voiceEngine.endCall(id)
            if (_signalingRoute.value == SignalingRoute.WSS) {
                try { api.endCall(id) } catch (_: Exception) {}
            }
        }
        _state.value = CallStateMachineState.ENDED
        clearIncomingState()
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
                }
            }
            "call.offer" -> {
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
                                _state.value = CallStateMachineState.ACTIVE
                            }
                        }
                    }
                }
            }
            "call.answer" -> {
                val sdp = msg.payload?.getString("sdp") ?: return
                voiceEngine.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp)) {
                    _state.value = CallStateMachineState.ACTIVE
                    refreshIceSummary()
                }
            }
            "call.ice" -> {
                val payload = msg.payload ?: return
                val candidate = IceCandidate(
                    payload.optString("sdpMid"),
                    payload.optInt("sdpMLineIndex"),
                    payload.getString("candidate"),
                )
                voiceEngine.addIceCandidate(candidate)
            }
            "call.iceRestart" -> handleIceRestart(msg)
            "call.end", "call.reject" -> {
                _state.value = CallStateMachineState.ENDED
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
            "conference.invite", "conference.join", "conference.leave" -> {
                // Conference frames are handled by ConferenceManager via chatEvents extension if needed.
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
    }

    companion object {
        private val CALL_RECOVERABLE_STATES = setOf(
            CallStateMachineState.ACTIVE,
            CallStateMachineState.RECONNECTING,
            CallStateMachineState.SIGNALING,
            CallStateMachineState.RINGING,
        )
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
        val callId = activeCallId ?: return
        if (_state.value !in CALL_RECOVERABLE_STATES) return
        if (_signalingRoute.value != SignalingRoute.WSS) return
        _state.value = CallStateMachineState.RECONNECTING
        try {
            val turn = api.getTurnCredentials()
            val material = iceSessionMaterial(callId, turn)
            voiceEngine.updateIceServers(material)
            if (isCaller) {
                voiceEngine.triggerIceRestart { sdp -> sendIceRestartSdp(callId, sdp) }
            }
        } catch (e: Exception) {
            _lastError.value = "Network recovery failed: ${e.message}"
        }
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
        scope.launch { voiceEngine.shutdown() }
    }
}

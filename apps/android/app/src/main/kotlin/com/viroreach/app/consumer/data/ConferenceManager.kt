package com.viroreach.app.consumer.data

import android.content.Context
import com.viroreach.app.consumer.ContactListItem
import com.viroreach.app.session.SessionManager
import com.viroreach.core.network.CreateConferenceBody
import com.viroreach.feature.calling.SignalingMessage
import com.viroreach.voice.webrtc.MeshOfferPolicy
import com.viroreach.voice.webrtc.MeshVoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class IncomingConferenceInvite(
    val roomId: String,
    val fromUserId: String?,
    val title: String,
)

data class ConferenceParticipant(
    val id: String,
    val userId: String? = null,
    val name: String,
    val muted: Boolean = false,
    val speaking: Boolean = false,
    val isActiveSpeaker: Boolean = false,
    val connected: Boolean = false,
)

data class ConferenceState(
    val conferenceId: String? = null,
    val title: String = "Group Call",
    val elapsedSeconds: Int = 0,
    val participants: List<ConferenceParticipant> = emptyList(),
    val isActive: Boolean = false,
    val speakerOn: Boolean = true,
    val localMuted: Boolean = false,
    val incomingInvite: IncomingConferenceInvite? = null,
    val error: String? = null,
)

/**
 * Multi-party mesh conference: REST create/join + WSS conf.* signaling +
 * one WebRTC PeerConnection per remote device.
 */
class ConferenceManager(
    private val session: SessionManager,
    context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mesh = MeshVoiceEngine(context)
    private val _state = MutableStateFlow(ConferenceState())
    val state: StateFlow<ConferenceState> = _state.asStateFlow()

    private val pendingNames = mutableMapOf<String, String>()

    init {
        session.callManager.conferenceEvents
            .onEach { handleFrame(it) }
            .launchIn(scope)
        mesh.onLocalIceCandidate = { remoteDeviceId, candidate ->
            val roomId = _state.value.conferenceId ?: return@onLocalIceCandidate
            session.callManager.sendConferenceEvent(
                type = "conf.ice",
                roomId = roomId,
                targetDeviceId = remoteDeviceId,
                payload = JSONObject()
                    .put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex),
            )
        }
        mesh.onPeerConnected = { deviceId ->
            updateParticipant(deviceId) { it.copy(connected = true) }
        }
        mesh.onPeerDisconnected = { deviceId ->
            updateParticipant(deviceId) { it.copy(connected = false, speaking = false) }
        }
    }

    fun startConference(contacts: List<ContactListItem>) {
        scope.launch {
            val inviteeIds = contacts.mapNotNull { it.userId }.distinct()
            contacts.forEach { contact ->
                contact.userId?.let { pendingNames[it] = contact.effectiveDisplayName }
            }
            val created = runCatching {
                session.callManager.ensureSignalingReady()
                session.api.createConference(
                    CreateConferenceBody(inviteeUserIds = inviteeIds, title = "Group Call"),
                )
            }.getOrElse { err ->
                _state.value = ConferenceState(error = err.message ?: "Couldn't start group call")
                return@launch
            }
            val local = localParticipant()
            _state.value = ConferenceState(
                conferenceId = created.roomId,
                title = "Group Call",
                participants = listOf(local) + contacts.map { contact ->
                    ConferenceParticipant(
                        id = contact.userId ?: contact.id,
                        userId = contact.userId,
                        name = contact.effectiveDisplayName,
                    )
                },
                isActive = true,
            )
            prepareMedia(created.roomId)
            session.callManager.sendConferenceEvent("conf.join", created.roomId)
            session.callManager.sendConferenceEvent("conf.invite", created.roomId)
        }
    }

    fun acceptIncoming() {
        val invite = _state.value.incomingInvite ?: return
        scope.launch {
            runCatching { session.callManager.ensureSignalingReady() }
            val local = localParticipant()
            _state.value = _state.value.copy(
                conferenceId = invite.roomId,
                title = invite.title,
                isActive = true,
                incomingInvite = null,
                participants = listOf(local),
            )
            prepareMedia(invite.roomId)
            session.callManager.sendConferenceEvent("conf.join", invite.roomId)
        }
    }

    fun declineIncoming() {
        _state.value = ConferenceState()
    }

    fun toggleMute(participantId: String) {
        _state.value = _state.value.copy(
            participants = _state.value.participants.map { p ->
                if (p.id == participantId) p.copy(muted = !p.muted) else p
            },
        )
        if (participantId == LOCAL_ID) {
            val muted = _state.value.participants.firstOrNull { it.id == LOCAL_ID }?.muted == true
            mesh.setMuted(muted)
            _state.value = _state.value.copy(localMuted = muted)
        }
    }

    fun toggleSpeaker() {
        val next = !_state.value.speakerOn
        mesh.setSpeaker(next)
        _state.value = _state.value.copy(speakerOn = next)
    }

    fun setActiveSpeaker(participantId: String) {
        _state.value = _state.value.copy(
            participants = _state.value.participants.map { p ->
                p.copy(isActiveSpeaker = p.id == participantId, speaking = p.id == participantId)
            },
        )
    }

    fun tickElapsed() {
        if (!_state.value.isActive) return
        _state.value = _state.value.copy(elapsedSeconds = _state.value.elapsedSeconds + 1)
    }

    fun endConference() {
        _state.value.conferenceId?.let { id ->
            session.callManager.sendConferenceEvent("conf.leave", id)
        }
        mesh.shutdown()
        pendingNames.clear()
        _state.value = ConferenceState()
    }

    private suspend fun prepareMedia(roomId: String) {
        val turn = runCatching { session.api.getTurnCredentials() }.getOrNull()
        val material = if (turn != null) {
            mapOf(
                "callId" to roomId,
                "iceServers" to turn.urls.joinToString(","),
                "iceUsername" to turn.username,
                "iceCredential" to turn.credential,
            )
        } else {
            mapOf(
                "callId" to roomId,
                "iceServers" to "stun:stun.l.google.com:19302",
            )
        }
        mesh.initialize()
        mesh.applyIceServers(material)
        mesh.setSpeaker(_state.value.speakerOn)
        mesh.setMuted(_state.value.localMuted)
    }

    private fun handleFrame(msg: SignalingMessage) {
        when (msg.type) {
            "conf.invite" -> {
                if (_state.value.isActive) return
                val roomId = msg.roomId ?: msg.callId
                if (roomId.isBlank()) return
                _state.value = _state.value.copy(
                    incomingInvite = IncomingConferenceInvite(
                        roomId = roomId,
                        fromUserId = msg.fromUserId,
                        title = msg.payload?.optString("title")?.ifBlank { null } ?: "Group Call",
                    ),
                )
            }
            "conf.joined" -> {
                val others = parseParticipants(msg.payload)
                others.forEach { connectTo(it.userId, it.deviceId) }
            }
            "conf.peer-joined" -> {
                val deviceId = msg.fromDeviceId ?: return
                connectTo(msg.fromUserId, deviceId)
            }
            "conf.peer-left" -> {
                val deviceId = msg.fromDeviceId ?: return
                mesh.removePeer(deviceId)
                _state.value = _state.value.copy(
                    participants = _state.value.participants.filter { it.id != deviceId },
                )
            }
            "conf.offer" -> {
                val from = msg.fromDeviceId ?: return
                val sdp = msg.payload?.optString("sdp") ?: return
                mesh.setRemoteSdp(from, "offer", sdp) {
                    mesh.createAnswer(from) { answer, type ->
                        val roomId = _state.value.conferenceId ?: return@createAnswer
                        session.callManager.sendConferenceEvent(
                            "conf.answer",
                            roomId,
                            from,
                            JSONObject().put("sdp", answer).put("type", type),
                        )
                    }
                }
            }
            "conf.answer" -> {
                val from = msg.fromDeviceId ?: return
                val sdp = msg.payload?.optString("sdp") ?: return
                mesh.setRemoteSdp(from, "answer", sdp)
            }
            "conf.ice" -> {
                val from = msg.fromDeviceId ?: return
                val payload = msg.payload ?: return
                mesh.addRemoteIce(
                    from,
                    payload.optString("sdpMid"),
                    payload.optInt("sdpMLineIndex"),
                    payload.optString("candidate"),
                )
            }
        }
    }

    private fun connectTo(userId: String?, deviceId: String) {
        val localDeviceId = session.tokenStore.getDeviceId() ?: return
        if (deviceId == localDeviceId) return
        upsertRemote(userId, deviceId)
        if (!_state.value.isActive) return
        if (MeshOfferPolicy.localCreatesOffer(localDeviceId, deviceId)) {
            mesh.createOffer(deviceId) { offer, type ->
                val roomId = _state.value.conferenceId ?: return@createOffer
                session.callManager.sendConferenceEvent(
                    "conf.offer",
                    roomId,
                    deviceId,
                    JSONObject().put("sdp", offer).put("type", type),
                )
            }
        } else {
            mesh.ensurePeer(deviceId)
        }
    }

    private fun upsertRemote(userId: String?, deviceId: String) {
        val name = userId?.let { pendingNames[it] }
            ?: _state.value.participants.firstOrNull { it.userId == userId }?.name
            ?: userId?.take(8)
            ?: "Participant"
        val existing = _state.value.participants
        if (existing.any { it.id == deviceId }) return
        _state.value = _state.value.copy(
            participants = existing + ConferenceParticipant(
                id = deviceId,
                userId = userId,
                name = name,
            ),
        )
    }

    private fun updateParticipant(
        id: String,
        transform: (ConferenceParticipant) -> ConferenceParticipant,
    ) {
        _state.value = _state.value.copy(
            participants = _state.value.participants.map { if (it.id == id) transform(it) else it },
        )
    }

    private fun localParticipant(): ConferenceParticipant {
        val name = session.authenticatedPhoneE164 ?: "You"
        return ConferenceParticipant(
            id = LOCAL_ID,
            userId = session.tokenStore.getUserId(),
            name = name,
            speaking = true,
            isActiveSpeaker = true,
            connected = true,
        )
    }

    private data class RemoteMember(val userId: String?, val deviceId: String)

    private fun parseParticipants(payload: JSONObject?): List<RemoteMember> {
        val arr: JSONArray = payload?.optJSONArray("participants") ?: return emptyList()
        val localDeviceId = session.tokenStore.getDeviceId()
        val out = mutableListOf<RemoteMember>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val deviceId = obj.optString("deviceId")
            if (deviceId.isBlank() || deviceId == localDeviceId) continue
            out += RemoteMember(
                userId = obj.optString("userId").ifBlank { null },
                deviceId = deviceId,
            )
        }
        return out
    }

    companion object {
        const val LOCAL_ID = "local"
    }
}

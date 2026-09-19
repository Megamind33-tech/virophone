package com.viroreach.app.consumer.data

import android.content.Context
import com.viroreach.app.consumer.ContactListItem
import com.viroreach.app.session.SessionManager
import com.viroreach.core.network.CreateConferenceBody
import com.viroreach.feature.calling.SignalingMessage
import com.viroreach.voice.webrtc.LiveKitCallEngine
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
 * Multi-party group call: REST create/join + WSS conf.* signaling for the
 * invite/roster (who's in the room, by name), with LiveKit carrying the
 * actual audio — one Room per conference, every participant connects to the
 * same room instead of meshing a WebRTC connection to each other peer.
 */
class ConferenceManager(
    private val session: SessionManager,
    context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val liveKit = LiveKitCallEngine(context)
    private val _state = MutableStateFlow(ConferenceState())
    val state: StateFlow<ConferenceState> = _state.asStateFlow()

    private val pendingNames = mutableMapOf<String, String>()

    init {
        session.callManager.conferenceEvents
            .onEach { handleFrame(it) }
            .launchIn(scope)
        // LiveKit's roster is keyed by identity == userId (see
        // LiveKitService.generateTokenForRoom) — merge it onto the
        // WSS-populated roster (which has display names) by userId.
        liveKit.participants
            .onEach { livekitRoster ->
                val connectedIds = livekitRoster.associateBy { it.identity }
                _state.value = _state.value.copy(
                    participants = _state.value.participants.map { p ->
                        val live = p.userId?.let { connectedIds[it] }
                        p.copy(connected = live != null, speaking = live?.isSpeaking == true)
                    },
                )
            }
            .launchIn(scope)
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
            liveKit.setMuted(muted)
            _state.value = _state.value.copy(localMuted = muted)
        }
    }

    fun toggleSpeaker() {
        val next = !_state.value.speakerOn
        liveKit.setSpeaker(next)
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
        scope.launch { liveKit.disconnect() }
        pendingNames.clear()
        _state.value = ConferenceState()
    }

    private suspend fun prepareMedia(roomId: String) {
        val creds = runCatching { session.api.getConferenceLiveKitToken(roomId) }
            .getOrElse { err ->
                _state.value = _state.value.copy(error = "Couldn't connect audio: ${err.message}")
                return
            }
        runCatching { liveKit.connect(creds.url, creds.token) }
            .onFailure { err ->
                _state.value = _state.value.copy(error = "Couldn't connect audio: ${err.message}")
            }
        liveKit.setSpeaker(_state.value.speakerOn)
        liveKit.setMuted(_state.value.localMuted)
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
                parseParticipants(msg.payload).forEach { upsertRemote(it.userId, it.deviceId) }
            }
            "conf.peer-joined" -> {
                upsertRemote(msg.fromUserId, msg.fromDeviceId ?: return)
            }
            "conf.peer-left" -> {
                val userId = msg.fromUserId
                val deviceId = msg.fromDeviceId ?: return
                _state.value = _state.value.copy(
                    participants = _state.value.participants.filter {
                        it.id != (userId ?: deviceId)
                    },
                )
            }
            // conf.offer / conf.answer / conf.ice no longer apply — LiveKit
            // handles all SDP/ICE internally once everyone's in the room.
        }
    }

    private fun upsertRemote(userId: String?, deviceId: String) {
        val localDeviceId = session.tokenStore.getDeviceId()
        if (deviceId == localDeviceId) return
        val id = userId ?: deviceId
        val name = userId?.let { pendingNames[it] }
            ?: _state.value.participants.firstOrNull { it.userId == userId }?.name
            // No id fallback — an unnamed participant is "Participant", not a
            // UUID fragment nobody can match to a person.
            ?: "Participant"
        if (_state.value.participants.any { it.id == id }) return
        _state.value = _state.value.copy(
            participants = _state.value.participants + ConferenceParticipant(
                id = id,
                userId = userId,
                name = name,
            ),
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

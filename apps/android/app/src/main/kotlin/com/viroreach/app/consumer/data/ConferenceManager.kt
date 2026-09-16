package com.viroreach.app.consumer.data

import com.viroreach.app.session.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

data class ConferenceParticipant(
    val id: String,
    val name: String,
    val muted: Boolean = false,
    val speaking: Boolean = false,
    val isActiveSpeaker: Boolean = false,
)

data class ConferenceState(
    val conferenceId: String? = null,
    val title: String = "Group Call",
    val elapsedSeconds: Int = 0,
    val participants: List<ConferenceParticipant> = emptyList(),
    val isActive: Boolean = false,
)

/**
 * Multi-party conference orchestration over existing WSS signaling.
 */
class ConferenceManager(private val session: SessionManager) {
    private val _state = MutableStateFlow(ConferenceState())
    val state: StateFlow<ConferenceState> = _state.asStateFlow()

    fun startConference(participantNames: List<String>) {
        val conferenceId = UUID.randomUUID().toString()
        val localName = session.authenticatedPhoneE164 ?: "You"
        val participants = listOf(
            ConferenceParticipant("local", localName, speaking = true, isActiveSpeaker = true),
        ) + participantNames.mapIndexed { index, name ->
            ConferenceParticipant("p$index", name)
        }
        _state.value = ConferenceState(
            conferenceId = conferenceId,
            title = "Group Call",
            participants = participants,
            isActive = true,
        )
        session.callManager.sendSignalingEvent(
            type = "conference.invite",
            callId = conferenceId,
            payload = JSONObject().put("participants", participantNames.size),
        )
    }

    fun toggleMute(participantId: String) {
        _state.value = _state.value.copy(
            participants = _state.value.participants.map { p ->
                if (p.id == participantId) p.copy(muted = !p.muted) else p
            },
        )
        if (participantId == "local") {
            val muted = _state.value.participants.firstOrNull { it.id == "local" }?.muted == true
            session.callManager.setMuted(muted)
        }
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
            session.callManager.sendSignalingEvent("conference.end", id)
        }
        _state.value = ConferenceState()
    }
}

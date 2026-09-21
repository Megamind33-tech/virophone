package com.viroreach.app.moments

import com.viroreach.core.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

fun MomentDto.endsAt(): Long = runCatching { Instant.parse(expiresAt).toEpochMilli() }.getOrDefault(0)
fun MomentDto.activity(): String = text?.takeIf { it.isNotBlank() } ?: when (type) {
    "FREE" -> "Free for a quick call"
    "BREAK" -> "Taking a break"
    "LISTENING" -> "Listening"
    "WATCHING" -> "Watching"
    "GAMING" -> "Gaming"
    "WORKING" -> "Working"
    else -> "A Moment"
}
fun remainingMinutes(end: Long, now: Long): Int = ((end - now).coerceAtLeast(0) + 59_999L).div(60_000L).toInt()

/** A socket frame flattened to plain values so repositories stay JVM-testable
 *  (org.json is a stub on the unit-test classpath). */
typealias MomentFrame = Pair<String, Map<String, Any?>>

private fun str(map: Map<String, Any?>?, key: String): String? = (map?.get(key) as? String)

/**
 * The encryption a Moment room needs, kept to three calls so the room can be
 * tested on the JVM without libsignal or an Android context behind it.
 *
 * The implementation is the same engine ordinary chats use — a room has no
 * group key of its own, because it lives for at most two hours and people
 * arrive and leave while it is running, so every message is sealed once per
 * device exactly as a one-to-one message is.
 */
interface RoomCrypto {
    /** Whether every one of these people has at least one device with keys. */
    suspend fun canSealFor(userIds: List<String>): Boolean
    /** One sealed copy per device of [recipients], plus my own other devices. */
    suspend fun seal(recipients: List<String>, plaintext: String): List<MomentEnvelopeBody>
    /** Opens one copy. Null when this device cannot: opened already, or never addressed. */
    suspend fun open(senderUserId: String, senderDeviceId: String, ciphertext: String, type: Int): String?
}

/** Process/session cache: tab switches never blank it; activity is not left on
 * disk after logout. The existing websocket only invalidates; HTTP authorizes. */
class MomentsRepository(private val api: ViroMomentsApi, private val userId: () -> String?) {
    private val mutex = Mutex()
    private var owner: String? = null
    private var offset = 0L
    private val _moments = MutableStateFlow<List<MomentDto>>(emptyList())
    val moments = _moments.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded = _loaded.asStateFlow()
    private val _invitations = MutableStateFlow<List<MomentInvitationDto>>(emptyList())
    val invitations = _invitations.asStateFlow()

    /** Everything moment.* the socket delivers; open rooms and the Now screen
     *  subscribe rather than each owning a socket. */
    private val _frames = MutableSharedFlow<MomentFrame>(extraBufferCapacity = 256)
    val frames = _frames.asSharedFlow()

    fun now() = System.currentTimeMillis() + offset
    fun clear() { owner = null; _moments.value = emptyList(); _invitations.value = emptyList(); _loaded.value = false; _error.value = null; offset = 0 }
    fun prune() {
        if (owner != userId()) clear()
        _moments.value = _moments.value.filter { it.endsAt() > now() }
    }
    suspend fun refresh() = mutex.withLock {
        val account = userId() ?: return@withLock clear()
        if (owner != account) { clear(); owner = account }
        try {
            val result = api.now()
            if (account != userId()) return@withLock clear()
            offset = Instant.parse(result.serverTime).toEpochMilli() - System.currentTimeMillis()
            _moments.value = result.moments.filter { it.endsAt() > now() }
            _loaded.value = true
            _error.value = null
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { prune(); _error.value = "Couldn't refresh Moments. Check your connection." }
    }
    suspend fun refreshInvitations() {
        if (userId() == null) return
        try { _invitations.value = api.invitations().invitations }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { /* The section simply stays absent until it loads. */ }
    }
    suspend fun declineInvitation(id: String): Result<Unit> = runCatching { api.declineInvitation(id) }
        .onFailure { if (it is CancellationException) throw it }
        .also { refreshInvitations() }

    /** Called by the session for every moment.* socket frame. List-affecting
     *  events re-fetch the authorized list; room events are forwarded to any
     *  open room through [frames]. */
    suspend fun onFrame(type: String, payload: Map<String, Any?>) {
        _frames.emit(type to payload)
        when (type) {
            "moment.invited" -> { refresh(); refreshInvitations() }
            "moment.created", "moment.updated", "moment.ended", "moment.expired" -> {
                refresh()
                if (type != "moment.created" && type != "moment.updated") refreshInvitations()
            }
            "moment.joined", "moment.left", "moment.cheer", "moment.audience" -> refresh()
            else -> {}
        }
    }

    private fun cache(moment: MomentDto) {
        _moments.value = listOf(moment) + _moments.value.filter { it.id != moment.id }
        _loaded.value = true
    }
    suspend fun create(body: CreateMomentBody): Result<MomentDto> = action(onSuccess = ::cache) { api.create(body) }
    suspend fun extend(id: String): Result<MomentDto> = action(onSuccess = ::cache) { api.extend(id, ExtendMomentBody(15)) }
    suspend fun end(id: String): Result<Unit> = action(onSuccess = {
        _moments.value = _moments.value.filter { it.id != id }
    }) { api.end(id) }
    suspend fun verify(id: String): Result<MomentDto> = action { api.get(id) }
    /** A reaction to the Moment itself; null clears the one this person left. */
    suspend fun cheer(id: String, emoji: String?): Result<MomentDto> =
        action(onSuccess = ::cache) { api.cheer(id, MomentReactBody(emoji)) }
    /** The host changing who can see a Moment that is already running. */
    suspend fun setVisibility(id: String, visibility: String): Result<MomentDto> =
        action(onSuccess = ::cache) { api.setVisibility(id, MomentVisibilityBody(visibility)) }
    suspend fun knock(id: String): Result<Unit> = action { api.knock(id) }
    suspend fun invite(momentId: String, userId: String): Result<Unit> =
        runCatching { api.invite(momentId, InviteBody(userId)) }
            .onFailure { if (it is CancellationException) throw it }
    suspend fun respondToKnock(momentId: String, knockerId: String, accept: Boolean): Result<Unit> =
        runCatching { api.respondToKnock(momentId, knockerId, KnockResponseBody(accept)) }
            .onFailure { if (it is CancellationException) throw it }
    private suspend fun <T> action(onSuccess: (T) -> Unit = {}, block: suspend () -> T): Result<T> {
        val account = userId() ?: return Result.failure(IllegalStateException("Sign in to use Moments."))
        return try {
            val result = block()
            if (account != userId()) return Result.failure(IllegalStateException("Your account changed. Please try again."))
            if (owner != account) { clear(); owner = account }
            // The mutation response is already authoritative. A failed follow-up
            // GET must not hide a newly created Moment or resurrect one just ended.
            onSuccess(result)
            refresh()
            Result.success(result)
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            refresh()
            Result.failure(IllegalStateException(when (momentHttpStatus(e)) {
                404 -> "This Moment is no longer available."
                409 -> "You already have an active Moment. Open it to manage it."
                else -> "Couldn't update the Moment. Please try again."
            }))
        }
    }
}

/** One open Moment Room. Entering joins (idempotent for the host); leaving the
 *  screen leaves the room for guests. Frames from the socket are applied to
 *  the open room; nothing here persists past the session. */
class MomentRoomState(
    private val api: ViroMomentsApi,
    val momentId: String,
    private val crypto: RoomCrypto? = null,
    private val userId: () -> String?,
) {
    private val mutex = Mutex()
    val moment = MutableStateFlow<MomentDto?>(null)
    val participants = MutableStateFlow<List<MomentParticipantDto>>(emptyList())
    val messages = MutableStateFlow<List<MomentMessageDto>>(emptyList())
    val closed = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    /** True once anything in this room has gone out sealed, for the room's badge. */
    val encrypted = MutableStateFlow(false)
    /**
     * What each sealed message said, once it has been opened.
     *
     * A sealed copy opens exactly once — the ratchet moves on — so the plain
     * text has to be kept here. Nothing is written to disk: the room and
     * everything said in it end with the Moment.
     */
    private val opened = mutableMapOf<String, String>()

    suspend fun enter(): Result<Unit> = mutex.withLock {
        try {
            val room = api.join(momentId)
            apply(room)
            Result.success(Unit)
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (momentHttpStatus(e) == 404) closed.value = true
            error.value = when (momentHttpStatus(e)) {
                404 -> "This Moment is no longer available."
                403 -> "Couldn't enter. Try joining again."
                else -> "Couldn't open this room. Check your connection."
            }
            Result.failure(e)
        }
    }

    suspend fun refresh() = mutex.withLock {
        if (closed.value) return@withLock
        try { apply(api.room(momentId)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (momentHttpStatus(e) == 404) closed.value = true }
    }

    /** The room state machine for socket frames: chat and reactions apply
     *  inline; membership and lifecycle cause an authoritative refetch. */
    suspend fun onFrame(type: String, payload: Map<String, Any?>) {
        val frameMoment = str(payload, "momentId") ?: return
        if (frameMoment != momentId) return
        when (type) {
            "moment.message" -> {
                val m = messageOf(payload["message"])
                if (m != null && m.momentId == momentId && messages.value.none { it.id == m.id }) {
                    messages.value = messages.value + readable(m)
                }
            }
            "moment.audience", "moment.cheer" -> refresh()
            "moment.reaction" -> {
                val mid = str(payload, "messageId")
                if (mid != null) refresh()
            }
            "moment.joined", "moment.left" -> refresh()
            "moment.ended", "moment.expired" -> closed.value = true
            else -> {}
        }
    }

    /**
     * Says something in the room, sealed when everyone in it can read sealed
     * messages.
     *
     * Everyone, not most people: a room where one person's phone has no keys
     * yet sends in the clear rather than leaving that person out of the
     * conversation. That is the same rule ordinary chats follow, and it is
     * what stops the promise being true only some of the time.
     */
    suspend fun send(body: String): Result<MomentMessageDto> = mutex.withLock {
        val text = body.trim().take(500)
        if (text.isEmpty()) return@withLock Result.failure(IllegalStateException("Type a message first."))
        try {
            val envelopes = sealFor(text)
            val sent = api.sendMessage(momentId, if (envelopes == null) {
                SendMomentMessageBody(body = text)
            } else {
                SendMomentMessageBody(envelopes = envelopes)
            })
            if (envelopes != null) encrypted.value = true
            // The server answers a sealed message with no body — it has none.
            // This device knows what it just said, so it shows that.
            val shown = if (sent.body == null) sent.copy(body = text) else sent
            opened[sent.id] = text
            if (messages.value.none { it.id == sent.id }) messages.value = messages.value + shown
            Result.success(shown)
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (momentHttpStatus(e) == 404) closed.value = true
            Result.failure(IllegalStateException(when (momentHttpStatus(e)) {
                404 -> "This Moment has ended."
                403 -> "You're no longer in this room."
                else -> "Couldn't send. Check your connection."
            }))
        }
    }

    suspend fun react(messageId: String, emoji: String?): Result<Unit> = try {
        api.react(momentId, messageId, MomentReactBody(emoji))
        refresh()
        Result.success(Unit)
    }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(IllegalStateException("Couldn't react. Try again.")) }

    /** Guests leaving closes their view server-side; the host ends the Moment
     *  instead (the sheet says so — this is not their exit). */
    suspend fun leave() {
        val me = userId()
        val isHost = moment.value?.creatorUserId == me
        if (me != null && !isHost) runCatching { api.leave(momentId) }
    }

    /**
     * One sealed copy per device in the room, or null to send in the clear.
     *
     * Null covers every reason sealing cannot happen — no encryption on this
     * build, this device not yet in the directory, somebody here with no keys,
     * or the key lookup failing — and each of them means the same thing to the
     * caller: send it the ordinary way rather than not at all.
     */
    private suspend fun sealFor(text: String): List<MomentEnvelopeBody>? {
        val engine = crypto ?: return null
        val me = userId() ?: return null
        val others = participants.value.map { it.userId }.filter { it != me }.distinct()
        if (others.isEmpty()) return null
        if (!engine.canSealFor(others)) return null
        val envelopes = runCatching { engine.seal(others, text) }.getOrDefault(emptyList())
        return envelopes.takeIf { it.isNotEmpty() }
    }

    /**
     * Turns a sealed message into a readable one, once.
     *
     * A message that cannot be opened keeps its place in the room rather than
     * vanishing: something was said, and pretending otherwise would be a
     * stranger kind of wrong than saying so.
     */
    private suspend fun readable(m: MomentMessageDto): MomentMessageDto {
        if (!m.sealed) return m
        encrypted.value = true
        opened[m.id]?.let { return m.copy(body = it) }
        val engine = crypto ?: return m.copy(body = SEALED_UNREADABLE)
        val env = m.envelope ?: return m.copy(body = SEALED_UNREADABLE)
        val from = m.senderDeviceId ?: return m.copy(body = SEALED_UNREADABLE)
        val plain = runCatching { engine.open(m.senderUserId, from, env.ciphertext, env.type) }.getOrNull()
            ?: return m.copy(body = SEALED_UNREADABLE)
        opened[m.id] = plain
        return m.copy(body = plain)
    }

    private suspend fun apply(room: MomentRoomDto) {
        moment.value = room.moment
        participants.value = room.participants
        // Authoritative replace: reconnects can only reorder, never duplicate.
        messages.value = room.messages.distinctBy { it.id }.map { readable(it) }
        error.value = null
    }

    @Suppress("UNCHECKED_CAST")
    private fun messageOf(raw: Any?): MomentMessageDto? {
        val map = raw as? Map<String, Any?> ?: return null
        val reactions = (map["reactions"] as? List<*>)?.mapNotNull { r ->
            val rm = r as? Map<String, Any?> ?: return@mapNotNull null
            val userIds = (rm["userIds"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            val emoji = rm["emoji"] as? String ?: return@mapNotNull null
            MomentReactionDto(emoji, userIds)
        } ?: emptyList()
        return MomentMessageDto(
            id = map["id"] as? String ?: return null,
            momentId = map["momentId"] as? String ?: momentId,
            senderUserId = map["senderUserId"] as? String ?: return null,
            senderName = map["senderName"] as? String ?: "Viro user",
            body = map["body"] as? String,
            createdAt = map["createdAt"] as? String ?: "",
            reactions = reactions,
            sealed = map["sealed"] == true,
            senderDeviceId = map["senderDeviceId"] as? String,
            envelope = (map["envelope"] as? Map<String, Any?>)?.let { e ->
                val ct = e["ciphertext"] as? String ?: return@let null
                MomentEnvelopeDto(ct, (e["type"] as? Number)?.toInt() ?: 1)
            },
        ).takeIf { it.body != null || it.sealed }
    }
}

/**
 * Shown in place of a sealed message this device cannot open — one sent
 * before this phone was in the room, or one already read on another of the
 * person's devices.
 */
const val SEALED_UNREADABLE = "🔒 Sent before you joined"

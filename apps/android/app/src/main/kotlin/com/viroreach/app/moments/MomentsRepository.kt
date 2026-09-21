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
fun MomentDto.activity(): String = text?.takeIf { it.isNotBlank() }
    // What they are doing together, in the words the room uses: "Cooking".
    ?: com.viroreach.app.moments.engine.MomentIntent.of(intent)?.activity
    ?: when (type) {
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
    /**
     * The ending of a Moment that has finished: how long, with whom, and what
     * it could leave behind.
     *
     * Null rather than an error when there is nothing to answer — a Moment
     * spent alone, or one whose offers have already expired. An ending with
     * nothing in it is not a failure, it is simply over.
     */
    suspend fun ending(id: String): MomentEndingDto? =
        try { api.ending(id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { null }

    /** Keeps what was chosen. An empty list is a real answer: keep nothing. */
    suspend fun keep(id: String, offerIds: List<String>): Result<Unit> =
        runCatching { if (offerIds.isNotEmpty()) api.keep(id, KeepBody(offerIds)) }
            .onFailure { if (it is CancellationException) throw it }

    /** What this person has kept, newest first. */
    suspend fun keepsakes(): List<MomentKeepsakeDto> =
        try { api.keepsakes().keepsakes }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { emptyList() }

    suspend fun forgetKeepsake(keepsakeId: String): Result<Unit> =
        runCatching { api.forgetKeepsake(keepsakeId) }
            .onFailure { if (it is CancellationException) throw it }

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

/** Someone reaching this phone without words. */
data class MomentTouch(val from: String, val fromName: String, val kind: String, val to: String?)

/** One open Moment Room. Entering joins (idempotent for the host); leaving the
 *  screen leaves the room for guests. Frames from the socket are applied to
 *  the open room; nothing here persists past the session. */
class MomentRoomState(
    private val api: ViroMomentsApi,
    val momentId: String,
    private val crypto: RoomCrypto? = null,
    /** This phone's clock; replaced in tests. */
    private val clock: () -> Long = System::currentTimeMillis,
    private val userId: () -> String?,
) {
    private val mutex = Mutex()
    val moment = MutableStateFlow<MomentDto?>(null)
    val participants = MutableStateFlow<List<MomentParticipantDto>>(emptyList())
    val messages = MutableStateFlow<List<MomentMessageDto>>(emptyList())
    val closed = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    /**
     * What the room is right now — the experience that fills it, what sits
     * beside it, its atmosphere. Only ever replaced by a newer revision, so a
     * late or repeated frame cannot move the room backwards.
     */
    val runtime = MutableStateFlow<MomentRuntimeDto?>(null)
    /** The room's shared player, newest revision only. */
    val playback = MutableStateFlow<MomentPlaybackDto?>(null)
    /** What people have brought to watch or listen to. */
    val media = MutableStateFlow<List<MomentMediaDto>>(emptyList())
    /** The room's kitchen timer, newest revision only. */
    val timer = MutableStateFlow<MomentTimerDto?>(null)
    /** The question the room is answering, newest revision only. */
    val choice = MutableStateFlow<MomentChoiceDto?>(null)
    /** Touches arriving for this phone: felt once, never kept. */
    val touches = kotlinx.coroutines.flow.MutableSharedFlow<MomentTouch>(extraBufferCapacity = 8)
    /**
     * How far the server's clock is from this phone's, in ms. Taken from
     * request round-trips (the midpoint), never from pushed frames, whose
     * delay is unknown.
     */
    @Volatile var serverOffsetMs: Long = 0L
        private set
    /** The server's time now, as well as this phone can tell. */
    fun serverNow(): Long = clock() + serverOffsetMs
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
            val sentAt = clock()
            val room = api.join(momentId)
            observeClock(room.serverNow, sentAt)
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
        try {
            val sentAt = clock()
            val room = api.room(momentId)
            observeClock(room.serverNow, sentAt)
            apply(room)
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // 403: no longer in this room — left on another phone, or taken
            // out of it. Either way this phone's room is over too.
            if (momentHttpStatus(e) == 404 || momentHttpStatus(e) == 403) closed.value = true
        }
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
            "moment.state" -> runtimeOf(payload["state"])?.let { adopt(it) }
            "moment.playback" -> playbackOf(payload["playback"])?.let { adoptPlayback(it) }
            "moment.media" -> refreshMedia()
            "moment.timer" -> timerOf(payload["timer"])?.let { adoptTimer(it) }
            "moment.choice" -> choiceOf(payload["choice"])?.let { adoptChoice(it) }
            "moment.touch" -> {
                val from = str(payload, "from")
                val kind = str(payload, "kind")
                if (from != null && kind != null && from != userId()) {
                    touches.tryEmit(MomentTouch(from, str(payload, "fromName") ?: "Someone", kind, str(payload, "to")))
                }
            }
            "moment.audience", "moment.cheer" -> refresh()
            "moment.reaction" -> {
                val mid = str(payload, "messageId")
                if (mid != null) refresh()
            }
            "moment.left" -> if (str(payload, "userId") == userId()) closed.value = true else refresh()
            "moment.joined" -> refresh()
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

    /**
     * Changes what the room is — for everyone in it. The answer is applied
     * straight away; the frame that follows it is the same revision and is
     * ignored.
     */
    suspend fun change(body: MomentRoomChangeBody): Result<Unit> = try {
        adopt(api.changeRoom(momentId, body))
        Result.success(Unit)
    }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        if (momentHttpStatus(e) == 404) closed.value = true
        Result.failure(IllegalStateException(when (momentHttpStatus(e)) {
            404 -> "This Moment has ended."
            403 -> "You're no longer in this room."
            400 -> "That can't be done in this room."
            else -> "Couldn't change the room. Check your connection."
        }))
    }

    /** The host ended it here; the ending shows without waiting for the frame. */
    fun markClosed() {
        closed.value = true
    }

    /**
     * Play, pause, seek, load or stop — for everyone here. The answer is the
     * new shared state and is applied at once; its frame is then ignored.
     */
    suspend fun playback(body: MomentPlaybackBody): Result<Unit> = try {
        val sentAt = clock()
        val result = api.playback(momentId, body)
        observeClock(result.serverNow, sentAt)
        adoptPlayback(result.playback)
        Result.success(Unit)
    }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        if (momentHttpStatus(e) == 404) closed.value = true
        Result.failure(IllegalStateException(when (momentHttpStatus(e)) {
            404 -> "This Moment has ended."
            403 -> "You're no longer in this room."
            400 -> "That isn't here to play any more."
            else -> "Couldn't reach the room. Check your connection."
        }))
    }

    /** A fresh address for the player; relative to the API. */
    suspend fun streamUrl(mediaId: String): Result<String> = try {
        Result.success(api.streamUrl(momentId, mediaId).url)
    }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(IllegalStateException("Couldn't open this on your phone.")) }

    suspend fun refreshMedia() {
        runCatching { api.listMedia(momentId).media }.getOrNull()?.let { media.value = it }
    }

    /** Takes something this person shared back out of the room. */
    suspend fun unshare(mediaId: String): Result<Unit> = try {
        api.unshareMedia(momentId, mediaId)
        media.value = media.value.filter { it.id != mediaId }
        Result.success(Unit)
    }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(IllegalStateException("Couldn't remove it. Try again.")) }

    /** Something this phone just shared: shown at once, before its frame. */
    fun added(item: MomentMediaDto) {
        if (media.value.none { it.id == item.id }) media.value = media.value + item
    }

    /** The kitchen timer, for everyone here. */
    suspend fun timer(body: MomentTimerBody): Result<Unit> = tool {
        val sentAt = clock()
        val result = api.timer(momentId, body)
        observeClock(result.serverNow, sentAt)
        adoptTimer(result.timer)
    }

    /** Ask, answer, decide or clear the room's question. */
    suspend fun choice(body: MomentChoiceBody): Result<Unit> = tool { adoptChoice(api.choice(momentId, body).choice) }

    /** A heart, a hug, a wave or a tap. */
    suspend fun touch(kind: String, to: String? = null): Result<Unit> = tool { api.touch(momentId, MomentTouchBody(kind, to)) }

    private suspend fun tool(work: suspend () -> Unit): Result<Unit> = try {
        work()
        Result.success(Unit)
    }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        if (momentHttpStatus(e) == 404) closed.value = true
        Result.failure(IllegalStateException(when (momentHttpStatus(e)) {
            404 -> "This Moment has ended."
            403 -> "You're no longer in this room."
            429 -> "Slow down a little."
            400 -> "That can't be done right now."
            else -> "Couldn't reach the room. Check your connection."
        }))
    }

    internal fun adoptTimer(t: MomentTimerDto, authoritative: Boolean = false) {
        if (t.momentId != momentId) return
        val current = timer.value
        if (current == null || t.revision > current.revision || (authoritative && t.revision >= current.revision)) timer.value = t
    }

    internal fun adoptChoice(c: MomentChoiceDto, authoritative: Boolean = false) {
        if (c.momentId != momentId) return
        val current = choice.value
        if (current == null || c.revision > current.revision || (authoritative && c.revision >= current.revision)) choice.value = c
    }

    internal fun adoptPlayback(p: MomentPlaybackDto, authoritative: Boolean = false) {
        if (p.momentId != momentId) return
        val current = playback.value
        if (current == null || p.revision > current.revision || (authoritative && p.revision >= current.revision)) {
            playback.value = p
        }
    }

    private fun observeClock(serverNow: Long?, sentAt: Long) {
        if (serverNow == null) return
        val receivedAt = clock()
        serverOffsetMs = serverNow - (sentAt + receivedAt) / 2
    }

    /**
     * Takes a room shape if it is newer than the one held. A room read passes
     * [authoritative], because a reconnect can land on the same revision the
     * phone already has and should still take it.
     */
    internal fun adopt(state: MomentRuntimeDto, authoritative: Boolean = false) {
        if (state.momentId != momentId) return
        val current = runtime.value
        if (current == null || state.revision > current.revision || (authoritative && state.revision >= current.revision)) {
            runtime.value = state
        }
    }

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
        // A room read is the truth: after a reconnect this is what the room
        // became while this phone was away.
        room.state?.let { adopt(it, authoritative = true) }
        room.playback?.let { adoptPlayback(it, authoritative = true) }
        room.media?.let { media.value = it }
        room.timer?.let { adoptTimer(it, authoritative = true) }
        room.choice?.let { adoptChoice(it, authoritative = true) }
        // Authoritative replace: reconnects can only reorder, never duplicate.
        messages.value = room.messages.distinctBy { it.id }.map { readable(it) }
        error.value = null
    }

    @Suppress("UNCHECKED_CAST")
    private fun timerOf(raw: Any?): MomentTimerDto? {
        val m = raw as? Map<String, Any?> ?: return null
        return MomentTimerDto(
            momentId = m["momentId"] as? String ?: return null,
            revision = (m["revision"] as? Number)?.toInt() ?: return null,
            status = m["status"] as? String ?: return null,
            label = m["label"] as? String,
            endsAt = (m["endsAt"] as? Number)?.toLong(),
            remainingMs = (m["remainingMs"] as? Number)?.toLong(),
            durationMs = (m["durationMs"] as? Number)?.toLong(),
            updatedBy = m["updatedBy"] as? String,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun choiceOf(raw: Any?): MomentChoiceDto? {
        val m = raw as? Map<String, Any?> ?: return null
        return MomentChoiceDto(
            momentId = m["momentId"] as? String ?: return null,
            revision = (m["revision"] as? Number)?.toInt() ?: return null,
            status = m["status"] as? String ?: return null,
            question = m["question"] as? String,
            options = (m["options"] as? List<*>)?.mapNotNull { o ->
                val om = o as? Map<String, Any?> ?: return@mapNotNull null
                MomentChoiceOptionDto(om["id"] as? String ?: return@mapNotNull null, om["text"] as? String ?: "")
            },
            picks = (m["picks"] as? Map<String, Any?>)?.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }?.toMap(),
            askedBy = m["askedBy"] as? String,
            decided = m["decided"] as? String,
            updatedBy = m["updatedBy"] as? String,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun playbackOf(raw: Any?): MomentPlaybackDto? {
        val m = raw as? Map<String, Any?> ?: return null
        return MomentPlaybackDto(
            momentId = m["momentId"] as? String ?: return null,
            revision = (m["revision"] as? Number)?.toInt() ?: return null,
            mediaId = m["mediaId"] as? String,
            kind = m["kind"] as? String,
            title = m["title"] as? String,
            durationMs = (m["durationMs"] as? Number)?.toLong(),
            status = m["status"] as? String ?: return null,
            positionMs = (m["positionMs"] as? Number)?.toLong() ?: 0L,
            anchorAt = (m["anchorAt"] as? Number)?.toLong() ?: return null,
            rate = (m["rate"] as? Number)?.toDouble() ?: 1.0,
            updatedBy = m["updatedBy"] as? String,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun runtimeOf(raw: Any?): MomentRuntimeDto? {
        val m = raw as? Map<String, Any?> ?: return null
        return MomentRuntimeDto(
            momentId = m["momentId"] as? String ?: return null,
            revision = (m["revision"] as? Number)?.toInt() ?: return null,
            intent = m["intent"] as? String ?: "BE",
            primary = m["primary"] as? String ?: return null,
            secondary = (m["secondary"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            scene = m["scene"] as? String ?: "NEUTRAL",
            scenePinned = m["scenePinned"] == true,
            updatedAt = m["updatedAt"] as? String,
            updatedBy = m["updatedBy"] as? String,
        )
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

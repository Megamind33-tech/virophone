package com.viroreach.app.messaging

import android.content.Context
import android.util.Log
import com.viroreach.core.e2ee.DecryptFailure
import com.viroreach.core.e2ee.E2eeEngine
import com.viroreach.core.e2ee.OpenResult
import com.viroreach.core.e2ee.MediaCrypto
import com.viroreach.core.database.ConversationEntity
import com.viroreach.core.database.KvEntity
import com.viroreach.core.database.MessageEntity
import com.viroreach.core.database.MessagingDatabase
import com.viroreach.core.database.PendingDecryptionEntity
import com.viroreach.core.network.ApiDiagnostics
import com.viroreach.core.network.EnvelopeBody
import com.viroreach.core.network.ViroKeysApi
import com.viroreach.core.network.ConvDto
import com.viroreach.core.network.ContactCardBody
import com.viroreach.core.network.GroupInviteDto
import com.viroreach.core.network.GroupInvitePreviewDto
import com.viroreach.core.network.LocationBody
import com.viroreach.core.network.LocationUpdateBody
import com.viroreach.core.network.ConvSettingsBody
import com.viroreach.core.network.EditBody
import com.viroreach.core.network.MediaDto
import com.viroreach.core.network.MsgDto
import com.viroreach.core.network.PrivateBody
import com.viroreach.core.network.ReactBody
import com.viroreach.core.network.ReactionDto
import com.viroreach.core.network.ReplyDto
import com.viroreach.core.network.SendBody
import com.viroreach.core.network.TokenStore
import com.viroreach.core.network.ViroMessagingApi
import com.viroreach.core.network.FeaturesDto
import com.viroreach.core.network.GifPageDto
import com.viroreach.core.network.GifSendDto
import com.viroreach.core.network.GroupBody
import com.viroreach.core.network.GroupPatchBody
import com.viroreach.core.network.LinkPreviewDto
import com.viroreach.core.network.LoopAnswerBody
import com.viroreach.core.network.LoopAnswerDto
import com.viroreach.core.network.MemberDto
import com.viroreach.core.network.MembersBody
import com.viroreach.core.network.PollBody
import com.viroreach.core.network.PollDto
import com.viroreach.core.network.PollOptionDto
import com.viroreach.core.network.RoleBody
import com.viroreach.core.network.StickerRef
import com.viroreach.core.network.VoteBody
import com.viroreach.feature.calling.CallManager
import com.viroreach.feature.calling.SignalingConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * The single owner of messaging state on this phone.
 *
 * Messages live in Room and every screen observes Room, so what a screen
 * shows no longer depends on a live frame happening to arrive while it is
 * open. Three things keep Room right:
 *
 *  1. Live frames, applied as they arrive.
 *  2. A sync cursor: whenever the socket (re)connects, a push arrives, or a
 *     chat is open, everything changed since the cursor is fetched. A frame
 *     missed during a connection blip is picked up by the next sync — that
 *     gap was why a message sometimes only appeared once the other person
 *     replied.
 *  3. An outbox: a message is written locally first (so it shows instantly,
 *     marked "sending") and retried until the server has it. The server
 *     de-duplicates on clientMsgId, so a retry can never double-send.
 *
 * Conversations are keyed by the SERVER id. A chat opened before any message
 * exists uses "peer:<userId>" until the first send creates the real one; the
 * old in-memory store invented random local ids, and messages landed in a
 * copy of the chat that was not on screen.
 */
class MessagingRepository(
    context: Context,
    private val api: ViroMessagingApi,
    val http: OkHttpClient,
    val baseUrl: String,
    private val tokenStore: TokenStore,
    private val callManager: CallManager,
    keysApi: ViroKeysApi,
) {
    private val appContext = context.applicationContext
    /**
     * The signed-in account's own message store, looked up each time: each
     * account on this phone keeps its own, and which one is open changes when
     * somebody else signs in — without anything being deleted.
     */
    private val dao get() = MessagingDatabase.get(appContext).dao()

    /** End-to-end encryption: this phone's keys, sessions, and sealing. */
    val e2ee = E2eeEngine(appContext, keysApi)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val outboxMutex = Mutex()
    /**
     * One message at a time through read → open → write.
     *
     * The same message reaches this phone more than once — on the socket, in
     * the next sync, in the open chat's backstop poll, echoed back to its
     * sender — and those used to run side by side. Two of them could read "not
     * here yet" together; the first opened it and moved the ratchet on, the
     * second failed to open it again and wrote the sealed copy over the
     * readable one. Held for the whole of each batch, so that cannot happen.
     */
    private val ingestMutex = Mutex()
    /** When waiting re-send requests were last collected from the server. */
    @Volatile private var lastResendSweep = 0L
    /** One retry pass at a time; a timer tick skips one already running, an event waits for it. */
    private val retryMutex = Mutex()
    /** The one-off repair of sealed rows kept from before the retry queue existed. */
    private val repaired = java.util.concurrent.atomic.AtomicBoolean(false)
    val media = MediaFiles(appContext, http, baseUrl)

    private val _typing = MutableStateFlow<Map<String, TypingState>>(emptyMap())
    val typing: StateFlow<Map<String, TypingState>> = _typing.asStateFlow()

    /** Conversation → when the other person last said they have it open ("together now"). */
    private val _present = MutableStateFlow<Map<String, Long>>(emptyMap())
    val present: StateFlow<Map<String, Long>> = _present.asStateFlow()

    /** Loop state changed in a conversation — Loop cards refresh on this. */
    private val _loopChanges = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val loopChanges: SharedFlow<String> = _loopChanges.asSharedFlow()

    /** A placeholder conversation turned into a real one: (from, to). */
    private val _conversationMoved = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)
    val conversationMoved: SharedFlow<Pair<String, String>> = _conversationMoved.asSharedFlow()

    /** A conversation vanished (erased, expired private session). */
    private val _conversationGone = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val conversationGone: SharedFlow<String> = _conversationGone.asSharedFlow()

    /** Incoming message worth a notification: (conversationId, message). */
    private val _incoming = MutableSharedFlow<Pair<String, ChatMessage>>(extraBufferCapacity = 32)
    val incoming: SharedFlow<Pair<String, ChatMessage>> = _incoming.asSharedFlow()

    /** What the server has switched on (GIF search, transcripts). */
    private val _features = MutableStateFlow(FeaturesDto(gifs = false, gifProvider = null, transcripts = false))
    val features: StateFlow<FeaturesDto> = _features.asStateFlow()

    private val previewCache = mutableMapOf<String, LinkPreviewDto?>()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** The chat currently on screen; its messages count as read on arrival. */
    @Volatile var openConversationId: String? = null
        private set
    private var openPoller: Job? = null

    private var started = false

    fun myUserId(): String? = tokenStore.getUserId()

    // ------------------------------------------------------------ lifecycle

    fun start() {
        // The per-session work runs on every call, including the one after a
        // different account signs in: clearLocal() has just wiped this phone's
        // encryption keys, and without a fresh registration the new account
        // would hold no keys at all and quietly send everything in the clear
        // until the app was next killed and reopened.
        scope.launch { syncNow() }
        scope.launch { refreshFeatures() }
        scope.launch { setUpEncryption() }
        // The collectors and timers below belong to the process, not to the
        // session, so they are started once.
        if (started) return
        started = true
        scope.launch {
            callManager.messagingFrames.collect { (type, payload) ->
                runCatching { onFrame(type, payload) }
                    .onFailure { Log.w(TAG, "FRAME_FAILED type=$type ${it.message}") }
            }
        }
        // Every (re)connection is a chance something was missed.
        scope.launch {
            var wasConnected = false
            callManager.wssConnectionState.collect { state ->
                val connected = state == SignalingConnectionState.CONNECTED
                if (connected && !wasConnected) {
                    syncNow()
                    flushOutbox()
                }
                wasConnected = connected
            }
        }
        // Disappearing messages vanish on this phone on time, not at next sync.
        scope.launch {
            while (true) {
                runCatching { dao.deleteExpired(System.currentTimeMillis()) }
                // Anything in the retry queue whose next attempt is due. A
                // single indexed query when there is nothing to do.
                runCatching {
                    val due = dao.nextPendingAt()
                    if (due != null && due <= System.currentTimeMillis()) retryPendingDecryption("timer")
                }
                val now = System.currentTimeMillis()
                _typing.value = _typing.value.filterValues { now - it.at < TYPING_TTL_MS }
                _present.value = _present.value.filterValues { now - it < PRESENT_TTL_MS }
                delay(5_000)
            }
        }
    }

    /**
     * Makes sure this phone has encryption keys and that the server holds
     * their public half. Cheap and idempotent: after the first run it only
     * tops up one-time prekeys when the server is running low.
     */
    private suspend fun setUpEncryption() {
        val userId = tokenStore.getUserId() ?: return
        val deviceId = tokenStore.getDeviceId() ?: return
        if (e2ee.ensureRegistered(userId, deviceId)) {
            // Anything that arrived before this phone had keys can open now.
            retryPendingDecryption("registered", everything = true)
            repairUnrecoveredSealed()
        }
    }

    suspend fun refreshFeatures() {
        runCatching { api.features() }.onSuccess { _features.value = it }
    }

    /**
     * Another account is now the one signed in. Its messages are in its own
     * store, which [dao] now opens; what is held in memory about the previous
     * one is let go, and the one-off repairs run again for this account.
     */
    fun onAccountChanged() {
        openConversationId = null
        _typing.value = emptyMap()
        _present.value = emptyMap()
        repaired.set(false)
        lastResendSweep = 0L
        e2ee.onAccountChanged()
    }

    /** Wipes this account's local messages and keys: the account itself is gone. */
    suspend fun clearLocal() {
        dao.wipeAll()
        media.wipe()
        // Encryption keys belong to the person who signed in, not the phone.
        // Leaving them would let the next account inherit their sessions.
        runCatching { e2ee.wipe() }
    }

    // --------------------------------------------------------------- reads

    // Screens collect these straight into Compose, where an exception is an
    // app crash. A local read failing must show an empty list, not kill Viro.
    fun conversations(): Flow<List<ConversationItem>> =
        dao.observeConversations().map { rows -> rows.map { it.toItem(myUserId()) } }
            .catch { e -> Log.e(TAG, "INBOX_READ_FAILED", e); emit(emptyList()) }

    fun messages(conversationId: String): Flow<List<ChatMessage>> =
        dao.observeMessages(conversationId).map { rows ->
            val now = System.currentTimeMillis()
            rows
                // A sealed reaction is kept as a row, because a message opens
                // once — but it belongs on another message, not in the chat.
                .filter { it.type != TYPE_REACTION }
                .filter { it.expiresAt == null || it.expiresAt!! > now }
                .map { it.toChat(myUserId()) }
        }.catch { e -> Log.e(TAG, "CHAT_READ_FAILED", e); emit(emptyList()) }

    fun conversation(conversationId: String): Flow<ConversationEntity?> = dao.observeConversation(conversationId)
        .catch { e -> Log.e(TAG, "CONVERSATION_READ_FAILED", e); emit(null) }

    suspend fun dmWith(peerUserId: String): ConversationEntity? = dao.dmWith(peerUserId)

    suspend fun message(id: String): ChatMessage? = dao.message(id)?.toChat(myUserId())

    fun starred(): Flow<List<ChatMessage>> = dao.observeStarred().map { l -> l.map { it.toChat(myUserId()) } }

    /** The conversation id to show for a person: the real DM if it exists, else a placeholder. */
    suspend fun conversationIdFor(peerUserId: String): String =
        dao.dmWith(peerUserId)?.id ?: placeholder(peerUserId)

    // ---------------------------------------------------------------- sync

    fun syncSoon() {
        scope.launch { syncNow() }
    }

    suspend fun syncNow(): Boolean = syncMutex.withLock {
        val account = tokenStore.getUserId()?.takeIf { it.isNotBlank() } ?: return@withLock false
        try {
            var cursor = dao.kv(KEY_CURSOR)?.value
            val initial = cursor == null
            var pages = 0
            while (true) {
                val res = api.sync(cursor)
                if (tokenStore.getUserId() != account) return@withLock false
                // An absent snapshot is not an authoritative empty inbox.
                val snapshot = requireNotNull(res.conversations) { "Incomplete conversation sync" }
                val messages = requireNotNull(res.messages) { "Incomplete message sync" }
                apply(snapshot, messages, pruneConversations = true)
                cursor = res.serverTime
                dao.putKv(KvEntity(KEY_CURSOR, cursor))
                pages++
                if (res.hasMore != true || pages > 20) break
            }
            if (initial) Log.i(TAG, "SYNC_INITIAL_DONE")
            _lastError.value = null
            scope.launch { retryPendingDecryption("sync") }
            scope.launch { answerWaitingResendRequests() }
            true
        } catch (e: Exception) {
            Log.w(TAG, "SYNC_FAILED ${e.message}")
            false
        }
    }

    private suspend fun apply(convs: List<ConvDto>, msgs: List<MsgDto>, pruneConversations: Boolean) {
        val me = myUserId()
        if (pruneConversations) {
            val live = convs.map { it.id }.toSet()
            val gone = dao.allConversations().map { it.id }.filter {
                it !in live && !it.startsWith(PLACEHOLDER)
            }
            if (gone.isNotEmpty()) {
                // Pending sends belong to this phone until acknowledged.
                gone.forEach { dao.deleteMessagesUpTo(it, Long.MAX_VALUE) }
                dao.deleteConversations(gone)
                gone.forEach { _conversationGone.tryEmit(it) }
            }
        }
        val entities = convs.map { dto -> dto.toEntity(me, dao.conversation(dto.id)) }
        if (entities.isNotEmpty()) dao.upsertConversations(entities)
        for (c in entities) {
            // "Delete chat" and "Reset" also remove what this phone already holds.
            c.clearedAt?.let { dao.deleteMessagesUpTo(c.id, it) }
            c.resetAt?.let { resetAt ->
                val stale = dao.observeMessagesOnce(c.id).filter { it.createdAt < resetAt && it.type != "SYSTEM" && it.status == null }
                if (stale.isNotEmpty()) dao.deleteMessages(stale.map { it.id })
            }
            // A chat opened before its first message now has a real id.
            c.peerUserId?.let { peer ->
                if (c.kind == "DM") {
                    val ph = placeholder(peer)
                    if (dao.observeMessagesOnce(ph).isNotEmpty()) {
                        dao.moveMessages(ph, c.id)
                        _conversationMoved.tryEmit(ph to c.id)
                    }
                }
            }
        }
        upsertMessages(msgs, "sync")
    }

    /**
     * The one way a message from the server becomes a row on this phone —
     * live frames, sync, history pages, search results and retries alike.
     *
     * Deduplicated on the server's id (or the outbox's clientMsgId), opened
     * at most once, and written with the state it is really in.
     */
    private suspend fun upsertMessages(msgs: List<MsgDto>, source: String = "api") {
        if (msgs.isEmpty()) return
        val settled = mutableListOf<String>()
        val handovers = mutableListOf<String>()
        val newSessions = mutableSetOf<String>()
        val resends = mutableListOf<String>()
        var written: List<MessageEntity> = emptyList()
        ingestMutex.withLock {
            val rows = mutableListOf<MessageEntity>()
            // In the server's order, so that within one batch the message that
            // starts a session is opened before those that depend on it.
            for (dto in msgs.distinctBy { it.id }.sortedBy { parseIso(it.createdAt) ?: 0L }) {
                val existing = dao.message(dto.id)
                    ?: dto.clientMsgId?.let { dao.byClientMsgId(it) }
                // A retry for a message that has since gone (deleted, cleared,
                // expired) must not bring it back.
                if (source.startsWith(RETRY) && existing == null) {
                    dao.deletePending(listOf(dto.id))
                    continue
                }
                // The outbox copy is replaced by the server's.
                // REPLACE on the unique clientMsgId swaps the outbox row atomically
                // when upsert succeeds. Never delete it before decoding its reply.
                rows += ingest(dto, existing, source, settled, handovers, newSessions, resends)
            }
            dao.upsertMessages(rows)
            written = rows
            // An encrypted reaction arrives as a message; what it means is a change
            // to another one. Applied after the rows are in, so the reaction is
            // never lost if the app stops between the two.
            rows.filter { it.type == TYPE_REACTION }.forEach { applySealedReaction(it) }
        }
        // Only now that the chat holds the readable copy: the handover rows
        // in the key store and the queue entries can go.
        if (settled.isNotEmpty()) dao.deletePending(settled)
        handovers.forEach { e2ee.forgetOpened(it) }
        if (source.startsWith(RETRY)) announceLateOpenings(written)
        if (resends.isNotEmpty()) scope.launch { resends.forEach { askForResend(it) } }
        // A new session with somebody's device is exactly what anything of
        // theirs still waiting was waiting for.
        if (newSessions.isNotEmpty()) {
            scope.launch {
                newSessions.forEach { retryPendingDecryption("session", fromDevice = it) }
                e2ee.topUpSoon()
            }
        }
    }

    /**
     * Puts a sealed reaction where it belongs.
     *
     * The reaction itself stays in the store — opened once is all anyone gets,
     * so its own row is the record that it was applied — but nothing shows it
     * as a message.
     */
    private suspend fun applySealedReaction(row: MessageEntity) {
        val meta = ChatJson.map(row.metadataJson)
        val targetId = meta["targetId"] as? String ?: return
        val emoji = meta["emoji"] as? String
        val target = dao.message(targetId) ?: return
        val others = ChatJson.reactions(target.reactionsJson).filter { it.userId != row.senderUserId }
        val next = if (emoji.isNullOrBlank()) others else others + ReactionDto(row.senderUserId, emoji)
        dao.upsertMessages(listOf(target.copy(reactionsJson = ChatJson.toJson(next))))
    }

    /**
     * Messages the retry queue has just opened, told about the way they would
     * have been on arrival — their notification was held back while they
     * were still sealed. Old ones stay quiet: a message from yesterday
     * opening now is not news.
     */
    private fun announceLateOpenings(rows: List<MessageEntity>) {
        val me = myUserId()
        val now = System.currentTimeMillis()
        for (row in rows) {
            if (row.type == TYPE_ENCRYPTED || row.type == TYPE_REACTION || row.type == "SYSTEM") continue
            if (row.senderUserId == me || row.deletedAt != null) continue
            if (now - row.createdAt > LATE_NOTIFY_MS) continue
            if (openConversationId == row.conversationId) continue
            _incoming.tryEmit(row.conversationId to row.toChat(me))
        }
    }

    /**
     * One message, from the server's copy to the row this phone keeps.
     *
     * A sealed message is opened here, once. What it cannot open yet it does
     * not lose: the ciphertext goes to the retry queue and the row waits in
     * its place in the chat. What it can never open is said plainly.
     */
    private suspend fun ingest(
        dto: MsgDto,
        existing: MessageEntity?,
        source: String,
        settled: MutableList<String>,
        handovers: MutableList<String>,
        newSessions: MutableSet<String>,
        resends: MutableList<String>,
    ): MessageEntity {
        if (dto.type != TYPE_ENCRYPTED) {
            if (existing?.type == TYPE_ENCRYPTED) settled += dto.id
            return dto.toEntity(existing)
        }
        val me = myUserId()
        val direction = if (dto.senderUserId == me) "out" else "in"
        // Already opened once. Everything but the votes is kept as it was;
        // those keep arriving, because the server counts them without being
        // able to read the poll.
        if (existing != null && existing.type != TYPE_ENCRYPTED) {
            if (source.startsWith(RETRY)) settled += dto.id
            // Unless it has been edited since: an edit is new words sealed
            // again, and the only way to read them is to open the new copy.
            // If that fails, the words already here stay rather than being
            // swapped for a placeholder.
            val editedAt = parseIso(dto.editedAt)
            if (dto.senderUserId != me && dto.deletedAt == null && editedAt != null && editedAt > (existing.editedAt ?: 0L)) {
                val mine = e2ee.myDeviceId()
                val copy = mine?.let { id -> dto.envelopes?.firstOrNull { it.deviceId == id } }
                val from = dto.senderDeviceId
                if (copy != null && from != null) {
                    val key = dto.id + "@" + editedAt
                    val edit = e2ee.openMessage(key, dto.senderUserId, from, copy.ciphertext, copy.type ?: 1)
                    if (edit is OpenResult.Opened) {
                        handovers += key
                        CryptoTrace.log(dto.id, dto.conversationId, from, direction, source, "DECRYPTED", "EDIT")
                        return withPayload(dto, edit.plaintext).toEntity(existing)
                    }
                    CryptoTrace.log(dto.id, dto.conversationId, from, direction, source, "EDIT_NOT_OPENED", (edit as OpenResult.Failed).failure.name)
                }
            }
            return asOpenedBefore(dto, existing).toEntity(existing)
        }
        // Deleted for everyone: nothing left to open, and nothing to wait for.
        if (dto.deletedAt != null) {
            settled += dto.id
            return dto.toEntity(existing)
        }
        dto.senderDeviceId?.let { e2ee.noteSenderDevice(dto.senderUserId, it) }
        val myDeviceId = e2ee.myDeviceId()
        val senderDeviceId = dto.senderDeviceId
        val envelope = myDeviceId?.let { mine -> dto.envelopes?.firstOrNull { it.deviceId == mine } }

        // Whoever sealed it can seal it again for this phone — unless that
        // was this very phone, which is the one place it cannot come from.
        val canAsk = myDeviceId != null && senderDeviceId != null &&
            !(dto.senderUserId == me && senderDeviceId == myDeviceId)
        if (myDeviceId != null && (envelope == null || senderDeviceId == null)) {
            // Sealed for other devices only: before this phone was signed in,
            // or before the sender knew about it. Nothing here can open it, but
            // the author's phone can seal it again for this one.
            if (canAsk) return holdForResend(dto, existing, myDeviceId, source, direction, "NOT_ADDRESSED_TO_THIS_DEVICE", settled, resends)
            settled += dto.id
            CryptoTrace.log(dto.id, dto.conversationId, senderDeviceId, direction, source, CRYPTO_UNAVAILABLE, "NOT_ADDRESSED_TO_THIS_DEVICE")
            return dto.toEntity(existing).copy(cryptoState = CRYPTO_UNAVAILABLE)
        }
        val result = if (myDeviceId == null) {
            OpenResult.Failed(DecryptFailure.NOT_REGISTERED)
        } else {
            e2ee.openMessage(dto.id, dto.senderUserId, senderDeviceId!!, envelope!!.ciphertext, envelope.type ?: 1)
        }
        return when (result) {
            is OpenResult.Opened -> {
                settled += dto.id
                handovers += dto.id
                if (result.newSession) newSessions += senderDeviceId!!
                CryptoTrace.log(dto.id, dto.conversationId, senderDeviceId, direction, source, "DECRYPTED")
                withPayload(dto, result.plaintext).toEntity(existing)
            }
            is OpenResult.Failed -> {
                if (!result.failure.retryable) {
                    // A spent key or a damaged copy cannot be opened here again
                    // — but the words are still on the author's phone.
                    if (canAsk && result.failure != DecryptFailure.UNSUPPORTED_VERSION) {
                        return holdForResend(dto, existing, myDeviceId, source, direction, result.failure.name, settled, resends)
                    }
                    settled += dto.id
                    CryptoTrace.log(dto.id, dto.conversationId, senderDeviceId, direction, source, CRYPTO_UNAVAILABLE, result.failure.name)
                    return dto.toEntity(existing).copy(cryptoState = CRYPTO_UNAVAILABLE)
                }
                val now = System.currentTimeMillis()
                val before = dao.pending(dto.id)
                val attempts = (before?.attempts ?: 0) + 1
                val firstSeenAt = before?.firstSeenAt ?: now
                dao.savePending(
                    PendingDecryptionEntity(
                        messageId = dto.id,
                        conversationId = dto.conversationId,
                        senderUserId = dto.senderUserId,
                        senderDeviceId = senderDeviceId,
                        messageJson = ChatJson.gson.toJson(PendingDecryption.forQueue(dto, myDeviceId)),
                        reason = result.failure.name,
                        attempts = attempts,
                        nextRetryAt = PendingDecryption.nextRetryAt(attempts, now),
                        gaveUp = PendingDecryption.givesUp(
                            attempts, firstSeenAt, now,
                            alwaysRecoverable = result.failure == DecryptFailure.NOT_REGISTERED,
                        ),
                        firstSeenAt = firstSeenAt,
                    ),
                )
                CryptoTrace.log(dto.id, dto.conversationId, senderDeviceId, direction, source, "PENDING_DECRYPTION", result.failure.name, attempts)
                // A session that keeps refusing a message has drifted, and
                // waiting will not bring it back; a fresh one from the author
                // will. Asked once retrying has had a fair go.
                if (canAsk && result.failure != DecryptFailure.NOT_REGISTERED && attempts >= RESEND_AFTER_ATTEMPTS) resends += dto.id
                dto.toEntity(existing).copy(cryptoState = CRYPTO_PENDING)
            }
        }
    }

    /**
     * A message this phone cannot open itself, waiting for its author's
     * phone to seal it again for this device.
     *
     * Asked for straight away and again at most hourly, for two days: the
     * author's phone only answers while it is online, and nothing is kept on
     * the server in between. Past that it is said plainly to be unavailable.
     */
    private suspend fun holdForResend(
        dto: MsgDto,
        existing: MessageEntity?,
        myDeviceId: String?,
        source: String,
        direction: String,
        reason: String,
        settled: MutableList<String>,
        resends: MutableList<String>,
    ): MessageEntity {
        val now = System.currentTimeMillis()
        val before = dao.pending(dto.id)
        val firstSeenAt = before?.firstSeenAt ?: now
        if (now - firstSeenAt > RESEND_WINDOW_MS) {
            settled += dto.id
            CryptoTrace.log(dto.id, dto.conversationId, dto.senderDeviceId, direction, source, CRYPTO_UNAVAILABLE, "RESEND_NEVER_CAME:$reason")
            return dto.toEntity(existing).copy(cryptoState = CRYPTO_UNAVAILABLE)
        }
        val attempts = (before?.attempts ?: 0) + 1
        dao.savePending(
            PendingDecryptionEntity(
                messageId = dto.id,
                conversationId = dto.conversationId,
                senderUserId = dto.senderUserId,
                senderDeviceId = dto.senderDeviceId,
                messageJson = ChatJson.gson.toJson(PendingDecryption.forQueue(dto, myDeviceId)),
                reason = "$RESEND_REASON$reason",
                attempts = attempts,
                nextRetryAt = now + RESEND_EVERY_MS,
                gaveUp = false,
                firstSeenAt = firstSeenAt,
            ),
        )
        resends += dto.id
        CryptoTrace.log(dto.id, dto.conversationId, dto.senderDeviceId, direction, source, "RESEND_REQUESTED", reason, attempts)
        return dto.toEntity(existing).copy(cryptoState = CRYPTO_RESENDING)
    }

    /** Asks the author's phone to seal a message again for this one — at most hourly per message. */
    private suspend fun askForResend(messageId: String) {
        val key = "$RESEND_ASKED$messageId"
        val last = dao.kv(key)?.value?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        if (now - last < RESEND_EVERY_MS - 60_000L) return
        runCatching { api.requestResend(messageId) }
            .onSuccess { dao.putKv(KvEntity(key, now.toString())) }
            .onFailure { Log.w(TAG, "RESEND_REQUEST_FAILED ${it.javaClass.simpleName}") }
    }

    /**
     * Another device cannot open one of my messages and has asked for it
     * again. This phone still holds what it said, so it seals exactly that —
     * the same payload the message first carried — for that device alone.
     */
    private suspend fun answerResendRequest(p: JSONObject) {
        val messageId = p.optString("messageId").takeIf { it.isNotBlank() } ?: return
        val userId = p.optString("userId").takeIf { it.isNotBlank() } ?: return
        val deviceId = p.optString("deviceId").takeIf { it.isNotBlank() } ?: return
        answerResend(messageId, userId, deviceId)
    }

    /**
     * Requests that waited on the server while this account was signed out —
     * on a shared phone, while the very account asking was the one signed in.
     * Collected after a sync, at most every few minutes.
     */
    private suspend fun answerWaitingResendRequests() {
        val now = System.currentTimeMillis()
        if (now - lastResendSweep < RESEND_SWEEP_MS) return
        lastResendSweep = now
        val waiting = runCatching { api.pendingResendRequests().requests.orEmpty() }.getOrElse { return }
        for (r in waiting) answerResend(r.messageId, r.userId, r.deviceId)
    }

    private suspend fun answerResend(messageId: String, userId: String, deviceId: String) {
        val row = dao.message(messageId) ?: return
        // Only my own words, and only ones this phone can actually read.
        if (row.senderUserId != myUserId() || row.type == TYPE_ENCRYPTED || row.deletedAt != null) return
        val key = "$RESENT$messageId:$deviceId"
        val now = System.currentTimeMillis()
        val last = dao.kv(key)?.value?.toLongOrNull() ?: 0L
        if (now - last < RESEND_ANSWER_EVERY_MS) return
        val meta = ChatJson.map(row.metadataJson).filterKeys { it != "outbox" && it != "mentions" }.toMutableMap()
        row.pollJson?.let { runCatching { ChatJson.gson.fromJson(it, PollDto::class.java) }.getOrNull() }?.let { poll ->
            meta["poll"] = mapOf(
                "question" to poll.question,
                "options" to poll.options.orEmpty().map { it.text },
                "multi" to (poll.multi == true),
            )
        }
        val plaintext = SealedMessage.pack(
            SealedPayload(
                type = row.type,
                body = row.body,
                meta = meta.takeIf { it.isNotEmpty() },
                media = ChatJson.media(row.mediaJson)?.toSealedRef(),
            ),
        )
        runCatching {
            val envelope = e2ee.sealForDevice(userId, deviceId, plaintext) ?: return
            api.addEnvelopes(messageId, com.viroreach.core.network.AddEnvelopesBody(listOf(EnvelopeBody(envelope.deviceId, envelope.ciphertext, envelope.type))))
            dao.putKv(KvEntity(key, now.toString()))
            Log.i(TAG, "RESEND_ANSWERED msg=$messageId")
        }.onFailure { Log.w(TAG, "RESEND_ANSWER_FAILED ${it.javaClass.simpleName}") }
    }

    /** The server's copy with what was inside the seal put back. */
    private suspend fun withPayload(dto: MsgDto, plain: String): MsgDto {
        // A message sealed before messages carried their own shape was just
        // text, and still opens as text.
        val payload = SealedMessage.unpack(plain) ?: return dto.copy(type = "TEXT", body = plain)
        return dto.copy(
            type = payload.type,
            body = payload.body,
            metadata = payload.meta,
            media = payload.media?.let { SealedMessage.mediaDto(it, payload.type) },
            poll = pollFrom(payload.meta, dto),
            replyTo = quotedFrom(dto),
        )
    }

    /**
     * Tries the retry queue again.
     *
     * Runs through [upsertMessages], the same path as everything else, so a
     * message that opens here lands exactly as it would have on arrival — in
     * its own place in the chat, since its row keeps the server's time.
     *
     * @param fromDevice only what came from this device, including anything
     *   scheduled retries have given up on: a new session with it is the one
     *   thing that can still change the answer.
     * @param everything every retryable entry, due or not.
     */
    suspend fun retryPendingDecryption(trigger: String, fromDevice: String? = null, everything: Boolean = false) {
        // A tick of the timer or a sync has nothing to add to a pass already
        // running. A new session or registration does: it waits its turn.
        val event = fromDevice != null || everything
        if (event) retryMutex.lock() else if (!retryMutex.tryLock()) return
        try {
            val rows = when {
                fromDevice != null -> dao.pendingFromDevice(fromDevice)
                everything -> dao.pendingRetryable(RETRY_BATCH)
                else -> dao.pendingDue(System.currentTimeMillis(), RETRY_BATCH)
            }
            if (rows.isEmpty()) return
            val dtos = rows.mapNotNull { row ->
                runCatching { ChatJson.gson.fromJson(row.messageJson, MsgDto::class.java) }.getOrNull()
                    ?: run { dao.deletePending(listOf(row.messageId)); null }
            }
            // Oldest first: the message that sets up a session comes before
            // the ones that need it, so a stretch delivered out of order
            // opens in one pass.
            upsertMessages(dtos.sortedBy { parseIso(it.createdAt) ?: 0L }, "$RETRY$trigger")
        } catch (e: Exception) {
            Log.w(TAG, "PENDING_RETRY_FAILED trigger=$trigger ${e.javaClass.simpleName}")
        } finally {
            retryMutex.unlock()
        }
    }

    /**
     * Sealed rows kept from before this phone held on to ciphertext.
     *
     * They were written with nothing to retry from. The server still has
     * their envelopes, so each conversation's stretch of them is fetched once
     * more and put through the ordinary path: whatever can open now opens,
     * and whatever cannot is marked as unavailable rather than waiting
     * forever for something that is not coming.
     */
    private suspend fun repairUnrecoveredSealed() {
        if (!repaired.compareAndSet(false, true)) return
        runCatching {
            // Once: the build before this one marked messages it could not
            // open as unavailable for good. Most of them can be asked for
            // again from their author's phone, so they go back through.
            if (dao.kv(KEY_REQUEUED_V1) == null) {
                val n = dao.requeueUnavailableSealed()
                dao.putKv(KvEntity(KEY_REQUEUED_V1, System.currentTimeMillis().toString()))
                Log.i(TAG, "SEALED_REQUEUED count=$n")
            }
            for (gap in dao.unrecoveredSealed()) {
                if (gap.conversationId.startsWith(PLACEHOLDER)) continue
                var before = gap.newest + 1
                for (page in 0 until REPAIR_PAGES) {
                    // A network failure ends the repair without settling
                    // anything: offline is not the same as "not coming back".
                    val batch = api.history(gap.conversationId, 60, Instant.ofEpochMilli(before).toString())
                    if (batch.isEmpty()) break
                    upsertMessages(batch, "repair")
                    val oldest = batch.mapNotNull { parseIso(it.createdAt) }.minOrNull() ?: break
                    if (oldest <= gap.oldest) break
                    before = oldest
                }
                // The server's pages were read: whatever is still without a
                // state was not among them, and is not coming back.
                dao.settleUnrecoveredSealed(gap.conversationId, CRYPTO_UNAVAILABLE)
            }
        }.onFailure {
            // Tried again at the next registration check (next launch or sign-in).
            repaired.set(false)
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w(TAG, "SEALED_REPAIR_FAILED ${it.javaClass.simpleName}")
        }
    }

    /**
     * The line quoted above a reply.
     *
     * The server sends what it knows of the message being replied to, which
     * for an encrypted one is nothing. This phone opened that message, so it
     * fills the quote in from its own copy.
     */
    private suspend fun quotedFrom(dto: MsgDto): ReplyDto? {
        val reply = dto.replyTo ?: return null
        if (!reply.body.isNullOrBlank() || reply.deleted == true) return reply
        val quoted = dao.message(reply.id) ?: return reply
        return reply.copy(
            type = quoted.type,
            body = quoted.body?.take(160),
            deleted = quoted.deletedAt != null,
        )
    }

    /** The message as this phone already holds it, with the votes brought up to date. */
    private suspend fun asOpenedBefore(dto: MsgDto, existing: MessageEntity): MsgDto {
        val meta = ChatJson.map(existing.metadataJson).takeIf { it.isNotEmpty() }
        return dto.copy(
            type = existing.type,
            body = existing.body,
            metadata = meta,
            media = ChatJson.media(existing.mediaJson),
            poll = pollFrom(meta, dto)
                ?: existing.pollJson?.let { runCatching { ChatJson.gson.fromJson(it, PollDto::class.java) }.getOrNull() },
            replyTo = ChatJson.reply(existing.replyJson) ?: quotedFrom(dto),
        )
    }

    /**
     * A poll, put back together: the question and its options come out of the
     * sealed message, the counts out of what the server tallied. The server
     * holds option numbers and never learns what they stand for.
     */
    @Suppress("UNCHECKED_CAST")
    private fun pollFrom(meta: Map<String, Any?>?, dto: MsgDto): PollDto? {
        val poll = meta?.get("poll") as? Map<String, Any?> ?: return null
        val question = poll["question"] as? String ?: return null
        val options = (poll["options"] as? List<Any?>)?.mapNotNull { it as? String } ?: return null
        val votes = dto.pollVotes.orEmpty()
        val me = myUserId()
        return PollDto(
            question = question,
            multi = poll["multi"] as? Boolean ?: false,
            options = options.mapIndexed { index, text ->
                val chose = votes.filter { it.optionIndex == index }
                PollOptionDto(text, chose.size, chose.map { it.userId })
            },
            totalVoters = votes.map { it.userId }.distinct().size,
            myVotes = votes.filter { it.userId == me }.map { it.optionIndex }.sorted(),
        )
    }

    // -------------------------------------------------------------- frames

    private suspend fun onFrame(type: String, p: JSONObject) {
        val conversationId = p.optString("conversationId").takeIf { it.isNotBlank() }
        when (type) {
            "message.new", "message.updated" -> {
                val obj = p.optJSONObject("message") ?: return
                val dto = ChatJson.gson.fromJson(obj.toString(), MsgDto::class.java)
                if (dao.conversation(dto.conversationId) == null) {
                    // First message in a conversation this phone has not seen.
                    syncNow()
                    if (dao.message(dto.id) != null) announceIfIncoming(type, dto)
                    return
                }
                upsertMessages(listOf(dto), "websocket")
                announceIfIncoming(type, dto)
            }
            "message.resend-request" -> scope.launch { answerResendRequest(p) }
            "message.hidden" -> p.optString("messageId").takeIf { it.isNotBlank() }?.let { dao.deleteMessages(listOf(it)) }
            "message.removed" -> {
                val arr = p.optJSONArray("messageIds") ?: return
                dao.deleteMessages((0 until arr.length()).map { arr.getString(it) })
            }
            "message.receipt" -> {
                conversationId ?: return
                parseIso(p.optString("lastReadAt").takeIf { it.isNotBlank() })?.let { dao.bumpPeerRead(conversationId, it) }
                parseIso(p.optString("lastDeliveredAt").takeIf { it.isNotBlank() })?.let { dao.bumpPeerDelivered(conversationId, it) }
            }
            "conversation.read" -> conversationId?.let { dao.clearUnread(it) }
            "conversation.cleared" -> {
                conversationId ?: return
                parseIso(p.optString("clearedAt"))?.let { dao.deleteMessagesUpTo(conversationId, it) }
                syncNow()
            }
            "conversation.reset" -> {
                conversationId ?: return
                dao.deleteConversationMessages(conversationId)
                syncNow()
            }
            "conversation.erased" -> {
                conversationId ?: return
                dao.deleteConversationMessages(conversationId)
                dao.deleteConversations(listOf(conversationId))
                _conversationGone.tryEmit(conversationId)
            }
            "conversation.changed" -> syncNow()
            "chat.typing" -> {
                conversationId ?: return
                val userId = p.optString("userId")
                val state = p.optString("state")
                if (state == "present") {
                    _present.value = _present.value + (conversationId to System.currentTimeMillis())
                    return
                }
                if (state == "left") {
                    _present.value = _present.value - conversationId
                    return
                }
                _typing.value = if (state == "idle") {
                    _typing.value - conversationId
                } else {
                    _typing.value + (conversationId to TypingState(userId, state, System.currentTimeMillis()))
                }
            }
            "loop.updated", "loop.removed" -> conversationId?.let { _loopChanges.tryEmit(it) }
        }
    }

    private suspend fun announceIfIncoming(type: String, dto: MsgDto) {
        if (type != "message.new") return
        // A sealed reaction arrives as a message and is not one: no badge, no
        // notification, nothing in the chat.
        if (dao.message(dto.id)?.type == TYPE_REACTION) return
        val me = myUserId()
        if (dto.senderUserId == me || dto.type == "SYSTEM") {
            if (dto.type == "SYSTEM" && dto.senderUserId != me) _loopChanges.tryEmit(dto.conversationId)
            return
        }
        // A message from someone clears "typing…" for them.
        _typing.value = _typing.value - dto.conversationId
        if (dto.type == "LOOP") _loopChanges.tryEmit(dto.conversationId)
        if (openConversationId == dto.conversationId) {
            markRead(dto.conversationId)
        } else {
            val conv = dao.conversation(dto.conversationId)
            if (conv != null) dao.upsertConversations(listOf(conv.copy(unread = conv.unread + 1)))
            val msg = dao.message(dto.id)?.toChat(me) ?: return
            // Still being opened: the notification waits for the words, and
            // is posted by the retry that opens it.
            if (msg.type == TYPE_ENCRYPTED) return
            _incoming.tryEmit(dto.conversationId to msg)
        }
    }

    // ---------------------------------------------------------- open chat

    /**
     * Marks the chat as on screen: incoming messages count as read, and a
     * light sync runs while it stays open as a backstop for the socket.
     */
    fun openChat(conversationId: String) {
        openConversationId = conversationId
        // Someone looking at the chat is the best reason to try anything
        // still sealed again, whatever its schedule says.
        scope.launch { retryPendingDecryption("chat-open", everything = true) }
        openPoller?.cancel()
        openPoller = scope.launch {
            if (!conversationId.startsWith(PLACEHOLDER)) markRead(conversationId)
            while (true) {
                // "I'm here" every 20s; the other side shows "together now"
                // while both keep saying it. The same beat is the backstop sync.
                if (!conversationId.startsWith(PLACEHOLDER)) callManager.sendChatTyping(conversationId, "present")
                delay(PRESENT_EVERY_MS)
                syncNow()
                flushOutbox()
            }
        }
    }

    fun closeChat(conversationId: String) {
        if (openConversationId == conversationId) {
            openConversationId = null
            openPoller?.cancel()
            openPoller = null
            if (!conversationId.startsWith(PLACEHOLDER)) callManager.sendChatTyping(conversationId, "left")
        }
    }

    fun markRead(conversationId: String) {
        if (conversationId.startsWith(PLACEHOLDER)) return
        scope.launch {
            dao.clearUnread(conversationId)
            runCatching { api.markRead(conversationId) }
        }
    }

    private var lastTypingSent = 0L
    private var lastTypingState = "idle"

    /** Throttled: at most one frame every few seconds per state. */
    fun sendTyping(conversationId: String, state: String) {
        if (conversationId.startsWith(PLACEHOLDER)) return
        val now = System.currentTimeMillis()
        if (state == lastTypingState && now - lastTypingSent < 3_000 && state != "idle") return
        lastTypingSent = now
        lastTypingState = state
        callManager.sendChatTyping(conversationId, state)
    }

    // ---------------------------------------------------------------- send

    data class Outgoing(
        val type: String = "TEXT",
        val body: String? = null,
        val replyToId: String? = null,
        val localFile: File? = null,
        val mime: String? = null,
        val durationMs: Long? = null,
        val waveform: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val viewOnce: Boolean = false,
        val forwarded: Boolean = false,
        val deliverAt: Long? = null,
        val effect: String? = null,
        val poll: PollBody? = null,
        val gif: GifSendDto? = null,
        val sticker: StickerRef? = null,
        val linkPreview: LinkPreviewDto? = null,
        /** A document's name as the sender's phone knows it. */
        val fileName: String? = null,
        val contact: ContactCardBody? = null,
        val location: LocationBody? = null,
        /** User ids named with @ in this message. */
        val mentions: List<String> = emptyList(),
    )

    /**
     * Shows the message immediately and queues it. [conversationId] may be a
     * placeholder for a person with no conversation yet.
     */
    suspend fun send(conversationId: String, out: Outgoing): String {
        val clientMsgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val pendingMedia = if (out.localFile != null) {
            MediaDto(
                id = "",
                kind = when (out.type) {
                    "VOICE" -> "VOICE"
                    "FILE" -> "FILE"
                    else -> "IMAGE"
                },
                mime = out.mime,
                sizeBytes = out.localFile.length(), durationMs = out.durationMs, waveform = out.waveform,
                width = out.width, height = out.height,
                originalName = out.fileName,
            )
        } else null
        val reply = out.replyToId?.let { dao.message(it) }
        val meta = mutableMapOf<String, Any?>()
        out.effect?.let { meta["effect"] = it }
        // Shown while sending exactly as they will look once sent.
        out.gif?.let { meta["gif"] = mapOf("url" to it.url, "previewUrl" to it.previewUrl, "width" to it.width, "height" to it.height, "provider" to it.provider) }
        out.sticker?.let { meta["sticker"] = mapOf("pack" to it.pack, "id" to it.id) }
        out.contact?.let {
            meta["contact"] = mapOf<String, Any?>("name" to it.name, "phones" to it.phones, "viroId" to it.viroId, "userId" to it.userId)
        }
        out.fileName?.let { meta["file"] = mapOf<String, Any?>("name" to it, "mime" to out.mime, "size" to out.localFile?.length()) }
        if (out.mentions.isNotEmpty()) meta["mentions"] = out.mentions
        out.location?.let {
            meta["location"] = mapOf<String, Any?>(
                "lat" to it.lat,
                "lng" to it.lng,
                "accuracy" to it.accuracy,
                "label" to it.label,
                // Shown as live straight away; the server sets the real end time.
                "liveUntil" to it.liveSeconds?.let { s ->
                    java.time.Instant.now().plusSeconds(s.toLong()).toString()
                },
                "updatedAt" to java.time.Instant.now().toString(),
            )
        }
        out.linkPreview?.let { meta["linkPreview"] = mapOf("url" to it.url, "title" to it.title, "description" to it.description, "siteName" to it.siteName, "mediaId" to it.mediaId) }
        // Everything the outbox needs to retry lives on the row itself.
        meta["outbox"] = mapOf(
            "replyToId" to out.replyToId,
            "viewOnce" to out.viewOnce,
            "forwarded" to out.forwarded,
            "deliverAt" to out.deliverAt,
            "mime" to out.mime,
            "fileName" to out.fileName,
        )
        dao.upsertMessages(
            listOf(
                MessageEntity(
                    id = "$LOCAL$clientMsgId",
                    clientMsgId = clientMsgId,
                    conversationId = conversationId,
                    senderUserId = myUserId().orEmpty(),
                    type = out.type,
                    body = out.body?.trim()?.takeIf { it.isNotEmpty() },
                    createdAt = now,
                    updatedAt = now,
                    deliverAt = out.deliverAt,
                    replyJson = reply?.let {
                        ChatJson.toJson(com.viroreach.core.network.ReplyDto(it.id, it.senderUserId, it.type, it.body?.take(160), it.deletedAt != null))
                    },
                    reactionsJson = "[]",
                    mediaJson = ChatJson.toJson(pendingMedia),
                    viewOnce = out.viewOnce,
                    forwarded = out.forwarded,
                    metadataJson = ChatJson.toJson(meta),
                    status = "SENDING",
                    localMediaPath = out.localFile?.absolutePath,
                    pollJson = out.poll?.let { p ->
                        ChatJson.toJson(PollDto(p.question, p.multi, p.options.map { PollOptionDto(it, 0, emptyList()) }, 0, emptyList()))
                    },
                ),
            ),
        )
        scope.launch { flushOutbox() }
        return clientMsgId
    }

    /** Retries a failed message on request. */
    fun retry(clientMsgId: String) {
        scope.launch {
            dao.byClientMsgId(clientMsgId)?.let { dao.upsertMessages(listOf(it.copy(status = "SENDING"))) }
            flushOutbox()
        }
    }

    fun discard(messageId: String) {
        scope.launch { dao.deleteMessages(listOf(messageId)) }
    }

    suspend fun flushOutbox() = outboxMutex.withLock {
        for (row in dao.outbox()) {
            try {
                sendOne(row)
            } catch (e: Exception) {
                val failure = ApiDiagnostics.parseFailure("POST", "/api/v1/messages", e)
                Log.w(TAG, "OUTBOX_SEND_FAILED ${failure.summary()}")
                // 4xx will not fix itself; show it as failed. Network errors retry.
                val permanent = failure.httpStatus in 400..499
                dao.upsertMessages(listOf(row.copy(status = if (permanent) "FAILED" else "SENDING")))
                if (permanent) _lastError.value = failure.message
                if (!permanent) break
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun sendOne(row: MessageEntity) {
        val meta = ChatJson.map(row.metadataJson)
        val out = (meta["outbox"] as? Map<String, Any?>) ?: emptyMap()
        val conversation = dao.conversation(row.conversationId)
        val audience = audienceOf(
            conversation,
            row.conversationId.takeIf { it.startsWith(PLACEHOLDER) }?.removePrefix(PLACEHOLDER),
        )
        if (shouldSeal(row, conversation, audience)) {
            sendSealed(row, meta, out, audience)
            return
        }
        var mediaId: String? = null
        var mediaJson = row.mediaJson
        if (row.type == "VOICE" || row.type == "IMAGE" || row.type == "FILE") {
            val existing = ChatJson.media(row.mediaJson)
            mediaId = existing?.id?.takeIf { it.isNotBlank() }
            if (mediaId == null) {
                val file = row.localMediaPath?.let { File(it) }?.takeIf { it.exists() }
                    ?: throw IllegalStateException(
                        if (row.type == "FILE") "That file is no longer on this phone"
                        else "The recording is no longer on this phone",
                    )
                val uploaded = media.upload(
                    file = file,
                    mime = (out["mime"] as? String) ?: existing?.mime ?: "application/octet-stream",
                    durationMs = existing?.durationMs,
                    waveform = existing?.waveform,
                    width = existing?.width,
                    height = existing?.height,
                    kind = if (row.type == "FILE") "FILE" else null,
                    fileName = if (row.type == "FILE") (out["fileName"] as? String ?: existing?.originalName) else null,
                )
                mediaId = uploaded.id
                mediaJson = ChatJson.toJson(uploaded)
                // Keep the upload so a retry does not upload twice.
                dao.upsertMessages(listOf(row.copy(mediaJson = mediaJson)))
            }
        }
        val convId = row.conversationId
        val toUserId = if (convId.startsWith(PLACEHOLDER)) convId.removePrefix(PLACEHOLDER) else null
        val deliverAt = (out["deliverAt"] as? Number)?.toLong()
        val res = api.send(
            SendBody(
                toUserId = toUserId,
                conversationId = if (toUserId == null) convId else null,
                body = row.body,
                clientMsgId = row.clientMsgId ?: row.id.removePrefix(LOCAL),
                type = row.type,
                replyToId = out["replyToId"] as? String,
                mediaId = mediaId,
                viewOnce = (out["viewOnce"] as? Boolean)?.takeIf { it },
                forwarded = (out["forwarded"] as? Boolean)?.takeIf { it },
                deliverAt = deliverAt?.let { Instant.ofEpochMilli(it).toString() },
                effect = meta["effect"] as? String,
                poll = row.pollJson?.let { ChatJson.gson.fromJson(it, PollDto::class.java) }?.let { p ->
                    PollBody(p.question, p.options.orEmpty().map { it.text }, p.multi == true)
                },
                gif = (meta["gif"] as? Map<String, Any?>)?.let { g ->
                    GifSendDto(g["url"] as String, g["previewUrl"] as? String, (g["width"] as? Number)?.toInt(), (g["height"] as? Number)?.toInt(), g["provider"] as? String)
                },
                sticker = (meta["sticker"] as? Map<String, Any?>)?.let { StickerRef(it["pack"] as String, it["id"] as String) },
                mentions = (meta["mentions"] as? List<Any?>)?.mapNotNull { it as? String }?.takeIf { it.isNotEmpty() },
                location = (meta["location"] as? Map<String, Any?>)?.let { l ->
                    LocationBody(
                        lat = (l["lat"] as? Number)?.toDouble() ?: 0.0,
                        lng = (l["lng"] as? Number)?.toDouble() ?: 0.0,
                        accuracy = (l["accuracy"] as? Number)?.toDouble(),
                        label = l["label"] as? String,
                        liveSeconds = (l["liveUntil"] as? String)?.let { until ->
                            val ms = parseIso(until) ?: return@let null
                            val seconds = ((ms - System.currentTimeMillis()) / 1000).toInt()
                            // Back to the choice the sender made, even if the
                            // message waited in the outbox first.
                            listOf(900, 3600, 28800).minByOrNull { c -> kotlin.math.abs(c - seconds) }
                        },
                    )
                },
                contact = (meta["contact"] as? Map<String, Any?>)?.let { c ->
                    ContactCardBody(
                        name = c["name"] as? String ?: "",
                        phones = (c["phones"] as? List<Any?>)?.mapNotNull { p -> p as? String }.orEmpty(),
                        viroId = c["viroId"] as? String,
                        userId = c["userId"] as? String,
                    )
                },
                linkPreview = (meta["linkPreview"] as? Map<String, Any?>)?.let { lp ->
                    LinkPreviewDto(lp["url"] as String, lp["title"] as? String, lp["description"] as? String, lp["siteName"] as? String, lp["mediaId"] as? String)
                },
            ),
        )
        dao.deleteMessages(listOf(row.id))
        dao.upsertMessages(listOf(res.message.toEntity(row)))
        if (toUserId != null) {
            // The server made the conversation; fetch it and move anything else queued.
            syncNow()
            dao.moveMessages(convId, res.conversationId)
            _conversationMoved.tryEmit(convId to res.conversationId)
        }
    }

    /**
     * Whether this message goes out sealed.
     *
     * One-to-one chats only: a group needs sender keys, which is the next
     * piece of work. A chat becomes encrypted the first time both sides can
     * manage it and stays that way, so once it has, everything in it is
     * sealed — which is what makes the promise true rather than mostly true.
     */
    private suspend fun shouldSeal(
        row: MessageEntity,
        conv: ConversationEntity?,
        audience: List<String>,
    ): Boolean {
        if (audience.isEmpty() || row.type == "SYSTEM" || row.type == "LOOP") return false
        if (conv?.encrypted == true) return true
        // Otherwise this deployment decides when chats start encrypting.
        if (_features.value.e2ee != true) return false
        if (!e2ee.isRegistered()) return false
        // Everyone, not just most people: a group where one person's app is too
        // old to decrypt stays in the clear until it is not.
        return e2ee.everyoneCanReceive(audience)
    }

    /**
     * Who a message in this conversation has to be sealed for.
     *
     * In a group that is every member but me — one sealed copy per device.
     * Sender keys would make it one ciphertext for the whole group instead;
     * this is the same promise, paid for in bandwidth rather than complexity,
     * and worth revisiting if groups here ever get large.
     */
    private fun audienceOf(conv: ConversationEntity?, placeholderPeer: String?): List<String> {
        val me = myUserId()
        val members = conv?.participantsCsv?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        if (members.isNotEmpty()) return members.filter { it != me }
        return listOfNotNull(conv?.peerUserId ?: placeholderPeer)
    }

    /**
     * Sends a message with everything it is inside the ciphertext.
     *
     * A file is encrypted with its own key before it goes anywhere, and that
     * key travels inside the message — so the server holds bytes it cannot
     * read and a message it cannot open. What is left outside is the shape of
     * the thing: who it is for, that there is a file, when a live share ends.
     */
    private suspend fun sendSealed(
        row: MessageEntity,
        meta: Map<String, Any?>,
        out: Map<String, Any?>,
        audience: List<String>,
    ) {
        val me = myUserId() ?: throw IllegalStateException("Not signed in")
        var mediaId: String? = null
        var mediaRef: SealedMediaRef? = null

        if (row.type == "VOICE" || row.type == "IMAGE" || row.type == "FILE") {
            val existing = ChatJson.media(row.mediaJson)
            if (!existing?.id.isNullOrBlank() && existing?.sealedKey != null && existing.sealedIv != null) {
                // A previous attempt already uploaded it; uploading again would
                // cost the person their data twice.
                mediaId = existing.id
                mediaRef = existing.toSealedRef()
            } else {
                val file = row.localMediaPath?.let { File(it) }?.takeIf { it.exists() }
                    ?: throw IllegalStateException(
                        if (row.type == "FILE") "That file is no longer on this phone"
                        else "The recording is no longer on this phone",
                    )
                val mime = (out["mime"] as? String) ?: existing?.mime ?: "application/octet-stream"
                val sealedFile = media.newOutgoingFile("sealed")
                val key = try {
                    MediaCrypto.seal(file, sealedFile)
                } catch (e: Exception) {
                    sealedFile.delete()
                    throw e
                }
                val uploaded = try {
                    media.upload(
                        file = sealedFile,
                        mime = mime,
                        kind = when (row.type) {
                            "FILE" -> "FILE"
                            "VOICE" -> "VOICE"
                            else -> "IMAGE"
                        },
                        sealed = true,
                    )
                } finally {
                    sealedFile.delete()
                }
                mediaId = uploaded.id
                mediaRef = SealedMediaRef(
                    id = uploaded.id,
                    key = key.key,
                    iv = key.iv,
                    mime = mime,
                    name = (out["fileName"] as? String) ?: existing?.originalName,
                    durationMs = existing?.durationMs,
                    waveform = existing?.waveform,
                    width = existing?.width,
                    height = existing?.height,
                    sizeBytes = file.length(),
                )
                // Held on the row so a retry reuses the upload.
                dao.upsertMessages(
                    listOf(row.copy(mediaJson = ChatJson.toJson(SealedMessage.mediaDto(mediaRef, row.type)))),
                )
            }
        }

        val poll = row.pollJson?.let { runCatching { ChatJson.gson.fromJson(it, PollDto::class.java) }.getOrNull() }
        val payloadMeta = meta.filterKeys { it != "outbox" && it != "mentions" }.toMutableMap()
        poll?.let {
            payloadMeta["poll"] = mapOf(
                "question" to it.question,
                "options" to it.options.orEmpty().map { option -> option.text },
                "multi" to (it.multi == true),
            )
        }
        val envelopes = e2ee.seal(
            me,
            audience,
            SealedMessage.pack(
                SealedPayload(
                    type = row.type,
                    body = row.body,
                    meta = payloadMeta.takeIf { it.isNotEmpty() },
                    media = mediaRef,
                ),
            ),
        )
        if (envelopes.isEmpty()) {
            // The server would refuse this anyway; say something the person can
            // act on rather than failing silently.
            throw IllegalStateException("This chat is encrypted — waiting for their phone")
        }

        val convId = row.conversationId
        val toUserId = if (convId.startsWith(PLACEHOLDER)) convId.removePrefix(PLACEHOLDER) else null
        val deliverAt = (out["deliverAt"] as? Number)?.toLong()
        val res = api.send(
            SendBody(
                toUserId = toUserId,
                conversationId = if (toUserId == null) convId else null,
                // Nothing the server could read: the body and everything that
                // describes the message are inside the envelopes.
                body = null,
                envelopes = envelopes.map { EnvelopeBody(it.deviceId, it.ciphertext, it.type) },
                clientMsgId = row.clientMsgId ?: row.id.removePrefix(LOCAL),
                type = row.type,
                replyToId = out["replyToId"] as? String,
                mediaId = mediaId,
                viewOnce = (out["viewOnce"] as? Boolean)?.takeIf { it },
                forwarded = (out["forwarded"] as? Boolean)?.takeIf { it },
                deliverAt = deliverAt?.let { Instant.ofEpochMilli(it).toString() },
                mentions = (meta["mentions"] as? List<Any?>)?.mapNotNull { it as? String }?.takeIf { it.isNotEmpty() },
                liveSeconds = liveSecondsOf(meta),
            ),
        )

        // The server's copy has no body; this phone keeps what it just sent,
        // because nothing can give it back later.
        //
        // Under the ingestion lock: the server also sends this message back
        // to this phone on the socket, and that copy is sealed only for the
        // other devices. Between the delete and the write below, it used to
        // find neither the outbox row nor the sent one, fail to open a
        // message that was never addressed here, and write the sealed copy
        // over my own words — "Waiting for this message" on something I had
        // just typed.
        ingestMutex.withLock {
            dao.deleteMessages(listOf(row.id))
            dao.upsertMessages(
                listOf(
                    res.message.toEntity(row).copy(
                        type = row.type,
                        body = row.body,
                        metadataJson = row.metadataJson,
                        mediaJson = mediaRef?.let { ChatJson.toJson(SealedMessage.mediaDto(it, row.type)) },
                        pollJson = row.pollJson,
                        localMediaPath = row.localMediaPath,
                        cryptoState = null,
                    ),
                ),
            )
        }
        if (toUserId != null) {
            syncNow()
            dao.moveMessages(convId, res.conversationId)
            _conversationMoved.tryEmit(convId to res.conversationId)
        }
    }

    /** How long a live share runs, as the person chose it. */
    @Suppress("UNCHECKED_CAST")
    private fun liveSecondsOf(meta: Map<String, Any?>): Int? {
        val location = meta["location"] as? Map<String, Any?> ?: return null
        val until = parseIso(location["liveUntil"] as? String) ?: return null
        val seconds = ((until - System.currentTimeMillis()) / 1000).toInt()
        // Back to the choice the sender made, even if it waited in the outbox.
        return listOf(900, 3600, 28800).minByOrNull { kotlin.math.abs(it - seconds) }
    }

    /**
     * A Loop answer, ready to send — sealed when the chat is encrypted.
     *
     * A Loop's promise is that neither of you sees the other's answer until
     * you have both answered, and the server is what makes that true. It can
     * go on doing that with ciphertext: it withholds the same thing, and can
     * no longer read what it is withholding.
     */
    suspend fun loopAnswerBody(
        conversationId: String?,
        kind: String,
        text: String? = null,
        localFile: File? = null,
        mime: String? = null,
        durationMs: Long? = null,
        waveform: String? = null,
        width: Int? = null,
        height: Int? = null,
    ): LoopAnswerBody {
        val conv = conversationId?.let { dao.conversation(it) }
        val me = myUserId()
        val audience = audienceOf(conv, null)
        if (conv?.encrypted != true || me == null || audience.isEmpty()) {
            val media = localFile?.let {
                media.upload(it, mime ?: "application/octet-stream", durationMs, waveform, width, height)
            }
            return LoopAnswerBody(kind = kind, text = text, mediaId = media?.id)
        }

        // A photo or a recording in an answer is sealed the way one in a
        // message is: its own key, and the bytes are bytes to the server.
        var mediaRef: SealedMediaRef? = null
        if (localFile != null) {
            val sealedFile = media.newOutgoingFile("sealed")
            val key = try {
                MediaCrypto.seal(localFile, sealedFile)
            } catch (e: Exception) {
                sealedFile.delete()
                throw e
            }
            val uploaded = try {
                media.upload(
                    file = sealedFile,
                    mime = mime ?: "application/octet-stream",
                    kind = if (kind == "VOICE") "VOICE" else "IMAGE",
                    sealed = true,
                )
            } finally {
                sealedFile.delete()
            }
            mediaRef = SealedMediaRef(
                id = uploaded.id,
                key = key.key,
                iv = key.iv,
                mime = mime ?: "application/octet-stream",
                durationMs = durationMs,
                waveform = waveform,
                width = width,
                height = height,
                sizeBytes = localFile.length(),
            )
        }
        val envelopes = e2ee.seal(
            me,
            audience,
            SealedMessage.pack(
                SealedPayload(type = "LOOP_ANSWER", body = text, meta = mapOf("kind" to kind), media = mediaRef),
            ),
        )
        if (envelopes.isEmpty()) throw IllegalStateException("This chat is encrypted — waiting for their phone")
        return LoopAnswerBody(
            kind = kind,
            mediaId = mediaRef?.id,
            envelopes = envelopes.map { EnvelopeBody(it.deviceId, it.ciphertext, it.type) },
        )
    }

    /**
     * Opens a sealed Loop answer for showing.
     *
     * A sealed thing opens once, so the opened form is kept here — otherwise
     * looking at the same Loop twice would show nothing the second time.
     */
    suspend fun openLoopAnswer(answer: LoopAnswerDto): SealedPayload? {
        val cached = dao.kv("$LOOP_ANSWER_KEY${answer.id}")?.value
        if (cached != null) return SealedMessage.unpack(cached)
        val envelope = answer.envelopes?.firstOrNull { it.deviceId == e2ee.myDeviceId() } ?: return null
        val senderDeviceId = answer.senderDeviceId ?: return null
        val plain = e2ee.open(answer.userId, senderDeviceId, envelope.ciphertext, envelope.type ?: 1) ?: return null
        dao.putKv(KvEntity("$LOOP_ANSWER_KEY${answer.id}", plain))
        return SealedMessage.unpack(plain)
    }

    /** Marks a security-code change as seen, once the person has been shown it. */
    fun acknowledgeIdentityChangeLater(peerUserId: String) {
        scope.launch { runCatching { e2ee.acknowledgeIdentityChange(peerUserId) } }
    }

    // ------------------------------------------------------------- actions

    private suspend fun <T> act(what: String, block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Exception) {
        val f = ApiDiagnostics.parseFailure("", what, e)
        _lastError.value = f.message ?: "Couldn't $what. Check your connection."
        Result.failure(IllegalStateException(f.message ?: "Couldn't $what", e))
    }

    suspend fun edit(messageId: String, body: String) = act("edit message") {
        val text = body.trim()
        val row = dao.message(messageId)
        val conv = row?.let { dao.conversation(it.conversationId) }
        // An encrypted message is edited the way it was sent: the new words
        // are sealed again and replace the copies each device holds.
        val dto = if (conv?.encrypted == true && row != null) {
            val me = myUserId() ?: throw IllegalStateException("Not signed in")
            val audience = audienceOf(conv, null)
            if (audience.isEmpty()) throw IllegalStateException("Nobody to send to")
            val meta = ChatJson.map(row.metadataJson).filterKeys { it != "outbox" && it != "mentions" }
            val envelopes = e2ee.seal(
                me,
                audience,
                SealedMessage.pack(
                    SealedPayload(
                        type = row.type,
                        body = text,
                        meta = meta.takeIf { it.isNotEmpty() },
                        media = ChatJson.media(row.mediaJson)?.toSealedRef(),
                    ),
                ),
            )
            if (envelopes.isEmpty()) throw IllegalStateException("This chat is encrypted — waiting for their phone")
            // Kept here too: the server's copy of the edit is unreadable.
            dao.upsertMessages(listOf(row.copy(body = text, editedAt = System.currentTimeMillis())))
            api.edit(messageId, EditBody(envelopes = envelopes.map { EnvelopeBody(it.deviceId, it.ciphertext, it.type) }))
        } else {
            api.edit(messageId, EditBody(body = text))
        }
        upsertMessages(listOf(dto))
    }

    suspend fun delete(messageId: String, forEveryone: Boolean) = act("delete message") {
        if (messageId.startsWith(LOCAL)) {
            dao.deleteMessages(listOf(messageId))
            return@act
        }
        api.delete(messageId, if (forEveryone) "everyone" else "me")
        if (!forEveryone) dao.deleteMessages(listOf(messageId))
        else dao.message(messageId)?.let {
            dao.upsertMessages(listOf(it.copy(body = null, mediaJson = null, deletedAt = System.currentTimeMillis(), reactionsJson = "[]")))
        }
    }

    suspend fun react(messageId: String, emoji: String?) = act("react") {
        val me = myUserId().orEmpty()
        val row = dao.message(messageId)
        // Shown at once; the server's copy replaces it.
        row?.let {
            val others = ChatJson.reactions(it.reactionsJson).filter { r -> r.userId != me }
            val next = if (emoji == null) others else others + ReactionDto(me, emoji)
            dao.upsertMessages(listOf(it.copy(reactionsJson = ChatJson.toJson(next))))
        }
        val conv = row?.let { dao.conversation(it.conversationId) }
        if (conv?.encrypted == true && row != null) {
            // Which emoji someone chose says something, so in an encrypted
            // chat a reaction travels the way everything else does: sealed,
            // and applied by the phones to the message it belongs to.
            sendSealedReaction(row, conv, messageId, emoji)
        } else {
            val dto = if (emoji == null) api.unreact(messageId) else api.react(messageId, ReactBody(emoji))
            upsertMessages(listOf(dto))
        }
    }

    /** A reaction as a sealed message: quiet, and unreadable by the server. */
    private suspend fun sendSealedReaction(
        row: MessageEntity,
        conv: ConversationEntity,
        targetId: String,
        emoji: String?,
    ) {
        val me = myUserId() ?: throw IllegalStateException("Not signed in")
        val audience = audienceOf(conv, null)
        if (audience.isEmpty()) throw IllegalStateException("Nobody to send to")
        val envelopes = e2ee.seal(
            me,
            audience,
            SealedMessage.pack(
                SealedPayload(
                    type = TYPE_REACTION,
                    meta = mapOf("targetId" to targetId, "emoji" to emoji),
                ),
            ),
        )
        if (envelopes.isEmpty()) throw IllegalStateException("This chat is encrypted — waiting for their phone")
        api.send(
            SendBody(
                conversationId = row.conversationId,
                body = null,
                clientMsgId = UUID.randomUUID().toString(),
                envelopes = envelopes.map { EnvelopeBody(it.deviceId, it.ciphertext, it.type) },
                // Nobody's phone should light up because someone reacted.
                silent = true,
            ),
        )
    }

    suspend fun star(messageId: String, on: Boolean) = act("star message") {
        dao.message(messageId)?.let { dao.upsertMessages(listOf(it.copy(starred = on))) }
        if (on) api.star(messageId) else api.unstar(messageId)
    }

    suspend fun pin(messageId: String, on: Boolean) = act("pin message") {
        if (on) api.pin(messageId) else api.unpin(messageId)
        syncNow()
    }

    suspend fun markViewed(messageId: String) = act("open message") {
        api.viewed(messageId)
        dao.message(messageId)?.let { dao.upsertMessages(listOf(it.copy(viewed = true, mediaJson = null, body = null))) }
    }

    suspend fun setHidden(conversationId: String, hidden: Boolean) = act("hide chat") {
        dao.conversation(conversationId)?.let { dao.upsertConversations(listOf(it.copy(hidden = hidden))) }
        api.settings(conversationId, ConvSettingsBody(hidden = hidden))
    }

    suspend fun setMuted(conversationId: String, untilMs: Long?) = act("mute chat") {
        api.settings(
            conversationId,
            if (untilMs == null) ConvSettingsBody(clearMute = true) else ConvSettingsBody(mutedUntil = Instant.ofEpochMilli(untilMs).toString()),
        )
        syncNow()
    }

    suspend fun setDisappearing(conversationId: String, seconds: Int?) = act("change disappearing messages") {
        api.settings(
            conversationId,
            if (seconds == null) ConvSettingsBody(clearDisappearing = true) else ConvSettingsBody(disappearingSeconds = seconds),
        )
        syncNow()
    }

    /** The group's shareable link. Admins only, server-side. */
    suspend fun groupInvite(conversationId: String): Result<GroupInviteDto> = act("get the group link") {
        api.groupInvite(conversationId)
    }

    suspend fun resetGroupInvite(conversationId: String): Result<GroupInviteDto> = act("reset the group link") {
        api.resetGroupInvite(conversationId)
    }

    suspend fun revokeGroupInvite(conversationId: String): Result<GroupInviteDto> = act("turn off the group link") {
        api.revokeGroupInvite(conversationId)
    }

    /** What a shared group link leads to. */
    suspend fun groupInvitePreview(code: String): Result<GroupInvitePreviewDto> = act("open that group link") {
        api.groupInvitePreview(code)
    }

    /** Joins the group behind a link and returns its conversation id. */
    suspend fun joinGroupByInvite(code: String): Result<String> = act("join that group") {
        val conv = api.joinGroupByInvite(code)
        syncNow()
        conv.id
    }

    /** Moves my live location on. Quiet on failure: the next tick tries again. */
    suspend fun updateLiveLocation(messageId: String, lat: Double, lng: Double, accuracy: Double?): Boolean =
        runCatching {
            val row = dao.message(messageId)
            val conv = row?.let { dao.conversation(it.conversationId) }
            // An encrypted share seals every new position exactly as the first
            // one was sealed, and the server swaps one for the other without
            // ever seeing a coordinate.
            if (conv?.encrypted == true && row != null) {
                val envelopes = sealedPosition(row, conv, lat, lng, accuracy)
                    ?: throw IllegalStateException("Cannot seal this position")
                api.updateLocation(messageId, LocationUpdateBody(envelopes = envelopes))
            } else {
                api.updateLocation(messageId, LocationUpdateBody(lat = lat, lng = lng, accuracy = accuracy))
            }
        }
            .onSuccess { upsertMessages(listOf(it)) }
            .onFailure { Log.w(TAG, "LIVE_LOCATION_UPDATE_FAILED ${it.message}") }
            .isSuccess

    /** The new position, sealed for every device in the chat. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun sealedPosition(
        row: MessageEntity,
        conv: ConversationEntity,
        lat: Double,
        lng: Double,
        accuracy: Double?,
    ): List<EnvelopeBody>? {
        val me = myUserId() ?: return null
        val audience = audienceOf(conv, null)
        if (audience.isEmpty()) return null
        val meta = ChatJson.map(row.metadataJson).toMutableMap()
        val was = (meta["location"] as? Map<String, Any?>).orEmpty()
        meta["location"] = was + mapOf(
            "lat" to lat,
            "lng" to lng,
            "accuracy" to accuracy,
            "updatedAt" to Instant.now().toString(),
        )
        meta.remove("outbox")
        val payload = SealedPayload(type = "LOCATION", body = row.body, meta = meta)
        val envelopes = e2ee.seal(me, audience, SealedMessage.pack(payload))
        if (envelopes.isEmpty()) return null
        // Kept locally too, so the map on this phone moves with the share.
        dao.upsertMessages(listOf(row.copy(metadataJson = ChatJson.toJson(meta), updatedAt = System.currentTimeMillis())))
        return envelopes.map { EnvelopeBody(it.deviceId, it.ciphertext, it.type) }
    }

    /** Ends my live location share. */
    suspend fun stopLiveLocation(messageId: String) = act("stop sharing") {
        api.stopLocation(messageId).also { upsertMessages(listOf(it)) }
    }

    /** Live shares of mine that are still running — what the service keeps going. */
    suspend fun myLiveLocations(): List<ChatMessage> {
        val me = myUserId() ?: return emptyList()
        val now = System.currentTimeMillis()
        return dao.liveLocations().map { it.toChat(me) }
            .filter { it.mine && (it.place?.liveUntil ?: 0L) > now }
    }

    /** Archive or bring back a chat. Mine alone — the other side is never told. */
    suspend fun setArchived(conversationId: String, archived: Boolean) = act(if (archived) "archive chat" else "unarchive chat") {
        dao.conversation(conversationId)?.let { dao.upsertConversations(listOf(it.copy(archived = archived))) }
        api.settings(conversationId, ConvSettingsBody(archived = archived))
    }

    /** Pin a chat to the top of the inbox. */
    suspend fun setPinned(conversationId: String, pinned: Boolean) = act(if (pinned) "pin chat" else "unpin chat") {
        dao.conversation(conversationId)?.let {
            dao.upsertConversations(listOf(it.copy(pinnedAt = if (pinned) System.currentTimeMillis() else null)))
        }
        api.settings(conversationId, ConvSettingsBody(pinned = pinned))
    }

    /** Keeps the unread dot on until the chat is opened again. */
    suspend fun markUnread(conversationId: String) = act("mark as unread") {
        dao.conversation(conversationId)?.let { dao.upsertConversations(listOf(it.copy(unreadMarked = true))) }
        api.settings(conversationId, ConvSettingsBody(markUnread = true))
    }

    suspend fun setLocked(conversationId: String, locked: Boolean) {
        dao.setLocked(conversationId, locked)
    }

    /** Delete chat — for me only. */
    suspend fun clearForMe(conversationId: String) = act("delete chat") {
        api.clear(conversationId)
        dao.deleteMessagesUpTo(conversationId, Long.MAX_VALUE)
    }

    /** Reset — wipes the chat for both people, keeps the contact. */
    suspend fun reset(conversationId: String) = act("reset chat") {
        api.reset(conversationId)
        dao.deleteConversationMessages(conversationId)
        syncNow()
    }

    /** Erase & disconnect — every shared conversation, for both. */
    suspend fun eraseWith(peerUserId: String) = act("erase conversations") {
        api.eraseWith(peerUserId)
        syncNow()
    }

    suspend fun startPrivate(peerUserId: String, durationSeconds: Int): Result<String> = act("start a private session") {
        val conv = api.startPrivate(PrivateBody(peerUserId, durationSeconds))
        dao.upsertConversations(listOf(conv.toEntity(myUserId(), null)))
        syncNow()
        conv.id
    }

    suspend fun endPrivate(conversationId: String) = act("end the private session") {
        api.endPrivate(conversationId)
        dao.deleteConversationMessages(conversationId)
        dao.deleteConversations(listOf(conversationId))
    }

    // -------------------------------------------------------------- groups

    suspend fun createGroup(title: String, memberIds: List<String>, description: String? = null): Result<String> = act("create the group") {
        val conv = api.createGroup(GroupBody(title.trim(), memberIds, description?.trim()?.ifBlank { null }))
        dao.upsertConversations(listOf(conv.toEntity(myUserId(), null)))
        syncNow()
        conv.id
    }

    suspend fun members(conversationId: String): List<MemberDto> =
        runCatching { api.members(conversationId) }.getOrDefault(emptyList())

    suspend fun addMembers(conversationId: String, userIds: List<String>) = act("add people") {
        api.addMembers(conversationId, MembersBody(userIds)).also { syncNow() }
    }

    suspend fun removeMember(conversationId: String, userId: String) = act("remove this person") {
        api.removeMember(conversationId, userId).also { syncNow() }
    }

    suspend fun setRole(conversationId: String, userId: String, admin: Boolean) = act("change admins") {
        api.setRole(conversationId, userId, RoleBody(if (admin) "ADMIN" else "MEMBER"))
    }

    suspend fun leaveGroup(conversationId: String) = act("leave the group") {
        api.leaveGroup(conversationId)
        dao.deleteConversationMessages(conversationId)
        dao.deleteConversations(listOf(conversationId))
    }

    suspend fun updateGroup(conversationId: String, title: String?, description: String?) = act("update the group") {
        val conv = api.updateGroup(conversationId, GroupPatchBody(title?.trim(), description?.trim() ?: ""))
        dao.upsertConversations(listOf(conv.toEntity(myUserId(), dao.conversation(conversationId))))
    }

    // --------------------------------------------------------------- polls

    suspend fun vote(messageId: String, options: List<Int>) = act("vote") {
        val me = myUserId().orEmpty()
        // The bars move at once; the server's tally replaces this.
        dao.message(messageId)?.let { row ->
            val p = row.pollJson?.let { ChatJson.gson.fromJson(it, PollDto::class.java) } ?: return@let
            val opts = p.options.orEmpty().mapIndexed { i, o ->
                val voters = o.voters.orEmpty().filter { it != me } + if (i in options) listOf(me) else emptyList()
                o.copy(votes = voters.size, voters = voters)
            }
            val total = opts.flatMap { it.voters.orEmpty() }.toSet().size
            dao.upsertMessages(listOf(row.copy(pollJson = ChatJson.toJson(p.copy(options = opts, totalVoters = total, myVotes = options)))))
        }
        upsertMessages(listOf(api.vote(messageId, VoteBody(options))))
    }

    // -------------------------------------------------------------- search

    /**
     * Search: this phone first (instant, works offline), then the server
     * (reaches messages older than what the phone keeps). Newest first.
     */
    suspend fun search(q: String, conversationId: String? = null): List<ChatMessage> {
        val term = q.trim()
        if (term.length < 2) return emptyList()
        val escaped = term.replace("!", "!!").replace("%", "!%").replace("_", "!_")
        val local = dao.search(escaped, conversationId).map { it.toChat(myUserId()) }
        val remote = runCatching { api.search(term, conversationId) }.getOrDefault(emptyList())
        if (remote.isNotEmpty()) {
            // Keep them so tapping a result can open the chat at that message.
            val known = remote.filter { dao.conversation(it.conversationId) != null }
            upsertMessages(known.filter { dao.message(it.id) == null }, "search")
        }
        val merged = (local + remote.map { it.toEntity(null).toChat(myUserId()) }).distinctBy { it.id }
        return merged.sortedByDescending { it.createdAt }
    }

    // ------------------------------------------------ link previews / GIFs

    suspend fun linkPreview(url: String): LinkPreviewDto? {
        if (previewCache.containsKey(url)) return previewCache[url]
        val p = runCatching { api.linkPreview(url).preview }.getOrNull()
        previewCache[url] = p
        return p
    }

    suspend fun gifs(q: String?, pos: String? = null): Result<GifPageDto> = runCatching { api.gifs(q?.ifBlank { null }, pos) }

    // --------------------------------------------------------- transcripts

    /** Transcribes a voice note once; the text then syncs to everyone in the chat. */
    suspend fun transcribe(messageId: String): Result<String> = act("transcribe") {
        val row = dao.message(messageId) ?: throw IllegalStateException("Message not found")
        val media = ChatJson.media(row.mediaJson) ?: throw IllegalStateException("No recording")
        media.transcript?.let { return@act it }
        val t = api.transcribe(media.id)
        val text = t.text.orEmpty()
        dao.message(messageId)?.let { r ->
            dao.upsertMessages(listOf(r.copy(mediaJson = ChatJson.toJson(media.copy(transcript = text, transcriptLang = t.language)))))
        }
        text
    }

    /** Older page for scrolling back beyond what sync kept. */
    suspend fun loadOlder(conversationId: String, beforeMs: Long): Result<Int> {
        if (conversationId.startsWith(PLACEHOLDER)) return Result.success(0)
        return runCatching {
            val page = api.history(conversationId, 60, Instant.ofEpochMilli(beforeMs).toString())
            upsertMessages(page, "history")
            page.size
        }
    }

    fun clearError() {
        _lastError.value = null
    }

    companion object {
        private const val TAG = "ViroMessaging"
        const val PLACEHOLDER = "peer:"
        /** The server's type for a message it cannot read. */
        const val TYPE_ENCRYPTED = "ENCRYPTED"
        /** A sealed reaction: carried as a message, shown as a reaction. */
        const val TYPE_REACTION = "REACTION"
        const val LOCAL = "local:"
        private const val KEY_CURSOR = "sync_cursor"
        /** Source prefix for anything coming out of the pending-decryption queue. */
        private const val RETRY = "retry:"
        /** How many queued messages one retry pass takes on. */
        private const val RETRY_BATCH = 200
        /** How far back the one-off repair looks per conversation, in pages of 60. */
        private const val REPAIR_PAGES = 10
        /** Set once the earlier build's unavailable messages have been put back in line. */
        private const val KEY_REQUEUED_V1 = "sealed_requeued_v1"
        /** How often waiting re-send requests are collected from the server. */
        private const val RESEND_SWEEP_MS = 3 * 60 * 1000L
        /** Queue reason prefix for a message waiting on its author's phone. */
        private const val RESEND_REASON = "RESEND:"
        /** When this phone last asked for a message again. */
        private const val RESEND_ASKED = "resend_asked:"
        /** When this phone last answered a request for one of its messages. */
        private const val RESENT = "resent:"
        /** How often to ask again while the author's phone has not answered. */
        private const val RESEND_EVERY_MS = 60 * 60 * 1000L
        /** How long to keep asking before saying the message is unavailable. */
        private const val RESEND_WINDOW_MS = 48 * 60 * 60 * 1000L
        /** How often the author answers the same device about the same message. */
        private const val RESEND_ANSWER_EVERY_MS = 10 * 60 * 1000L
        /** Retries of a drifting session before asking the author instead. */
        private const val RESEND_AFTER_ATTEMPTS = 3
        /** A message opened later than this after it was sent is not announced. */
        private const val LATE_NOTIFY_MS = 6 * 60 * 60 * 1000L
        /** Where an opened Loop answer is kept: a sealed thing opens once. */
        private const val LOOP_ANSWER_KEY = "loop_answer:"
        private const val PRESENT_EVERY_MS = 20_000L
        private const val PRESENT_TTL_MS = 45_000L
        private const val TYPING_TTL_MS = 7_000L

        fun placeholder(peerUserId: String) = "$PLACEHOLDER$peerUserId"
    }
}

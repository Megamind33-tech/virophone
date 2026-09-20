package com.viroreach.app.messaging

import android.content.Context
import android.util.Log
import com.viroreach.core.database.ConversationEntity
import com.viroreach.core.database.KvEntity
import com.viroreach.core.database.MessageEntity
import com.viroreach.core.database.MessagingDatabase
import com.viroreach.core.network.ApiDiagnostics
import com.viroreach.core.network.ConvDto
import com.viroreach.core.network.ContactCardBody
import com.viroreach.core.network.ConvSettingsBody
import com.viroreach.core.network.EditBody
import com.viroreach.core.network.MediaDto
import com.viroreach.core.network.MsgDto
import com.viroreach.core.network.PrivateBody
import com.viroreach.core.network.ReactBody
import com.viroreach.core.network.ReactionDto
import com.viroreach.core.network.SendBody
import com.viroreach.core.network.TokenStore
import com.viroreach.core.network.ViroMessagingApi
import com.viroreach.core.network.FeaturesDto
import com.viroreach.core.network.GifPageDto
import com.viroreach.core.network.GifSendDto
import com.viroreach.core.network.GroupBody
import com.viroreach.core.network.GroupPatchBody
import com.viroreach.core.network.LinkPreviewDto
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
) {
    private val appContext = context.applicationContext
    private val dao = MessagingDatabase.get(appContext).dao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val outboxMutex = Mutex()
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
                val now = System.currentTimeMillis()
                _typing.value = _typing.value.filterValues { now - it.at < TYPING_TTL_MS }
                _present.value = _present.value.filterValues { now - it < PRESENT_TTL_MS }
                delay(5_000)
            }
        }
        scope.launch { syncNow() }
        scope.launch { refreshFeatures() }
    }

    suspend fun refreshFeatures() {
        runCatching { api.features() }.onSuccess { _features.value = it }
    }

    /** Wipes everything local: another account is signing in. */
    suspend fun clearLocal() {
        dao.wipeAll()
        media.wipe()
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
            rows.filter { it.expiresAt == null || it.expiresAt!! > now }.map { it.toChat(myUserId()) }
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
        if (tokenStore.getUserId().isNullOrBlank()) return@withLock false
        try {
            var cursor = dao.kv(KEY_CURSOR)?.value
            val initial = cursor == null
            var pages = 0
            while (true) {
                val res = api.sync(cursor)
                apply(res.conversations.orEmpty(), res.messages.orEmpty(), pruneConversations = true)
                cursor = res.serverTime
                dao.putKv(KvEntity(KEY_CURSOR, cursor))
                pages++
                if (res.hasMore != true || pages > 20) break
            }
            if (initial) Log.i(TAG, "SYNC_INITIAL_DONE")
            _lastError.value = null
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
            val gone = dao.allConversations().map { it.id }.filter { it !in live }
            if (gone.isNotEmpty()) {
                gone.forEach { dao.deleteConversationMessages(it) }
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
        upsertMessages(msgs)
    }

    private suspend fun upsertMessages(msgs: List<MsgDto>) {
        if (msgs.isEmpty()) return
        val rows = msgs.map { dto ->
            val existing = dao.message(dto.id)
                ?: dto.clientMsgId?.let { dao.byClientMsgId(it) }
            // The outbox copy is replaced by the server's.
            if (existing != null && existing.id != dto.id) dao.deleteMessages(listOf(existing.id))
            dto.toEntity(existing)
        }
        dao.upsertMessages(rows)
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
                upsertMessages(listOf(dto))
                announceIfIncoming(type, dto)
            }
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

    // ------------------------------------------------------------- actions

    private suspend fun <T> act(what: String, block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Exception) {
        val f = ApiDiagnostics.parseFailure("", what, e)
        _lastError.value = f.message ?: "Couldn't $what. Check your connection."
        Result.failure(IllegalStateException(f.message ?: "Couldn't $what", e))
    }

    suspend fun edit(messageId: String, body: String) = act("edit message") {
        val dto = api.edit(messageId, EditBody(body.trim()))
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
        // Shown at once; the server's copy replaces it.
        dao.message(messageId)?.let { row ->
            val others = ChatJson.reactions(row.reactionsJson).filter { it.userId != me }
            val next = if (emoji == null) others else others + ReactionDto(me, emoji)
            dao.upsertMessages(listOf(row.copy(reactionsJson = ChatJson.toJson(next))))
        }
        val dto = if (emoji == null) api.unreact(messageId) else api.react(messageId, ReactBody(emoji))
        upsertMessages(listOf(dto))
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
            upsertMessages(known.filter { dao.message(it.id) == null })
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
    suspend fun loadOlder(conversationId: String, beforeMs: Long): Int {
        if (conversationId.startsWith(PLACEHOLDER)) return 0
        return runCatching {
            val page = api.history(conversationId, 60, Instant.ofEpochMilli(beforeMs).toString())
            upsertMessages(page)
            page.size
        }.getOrDefault(0)
    }

    fun clearError() {
        _lastError.value = null
    }

    companion object {
        private const val TAG = "ViroMessaging"
        const val PLACEHOLDER = "peer:"
        const val LOCAL = "local:"
        private const val KEY_CURSOR = "sync_cursor"
        private const val PRESENT_EVERY_MS = 20_000L
        private const val PRESENT_TTL_MS = 45_000L
        private const val TYPING_TTL_MS = 7_000L

        fun placeholder(peerUserId: String) = "$PLACEHOLDER$peerUserId"
    }
}

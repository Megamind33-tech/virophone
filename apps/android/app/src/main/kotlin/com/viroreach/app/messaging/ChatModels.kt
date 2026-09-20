package com.viroreach.app.messaging

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.viroreach.core.database.ConversationEntity
import com.viroreach.core.database.ConversationRow
import com.viroreach.core.database.MessageEntity
import com.viroreach.core.network.ConvDto
import com.viroreach.core.network.MediaDto
import com.viroreach.core.network.MsgDto
import com.viroreach.core.network.PollDto
import com.viroreach.core.network.ReactionDto
import com.viroreach.core.network.ReplyDto
import java.time.Instant

/** What a single tick row says about my own message. */
enum class Delivery { SCHEDULED, SENDING, FAILED, SENT, DELIVERED, READ, INCOMING }

/** A place in a chat. [liveUntil] in the future means it is still moving. */
data class SharedPlace(
    val lat: Double,
    val lng: Double,
    val accuracy: Double? = null,
    val label: String? = null,
    val liveUntil: Long? = null,
    val updatedAt: Long? = null,
) {
    val isLive: Boolean get() = (liveUntil ?: 0L) > System.currentTimeMillis()
    val hasEnded: Boolean get() = liveUntil != null && !isLive
}

/** A contact someone shared in a chat. */
data class ContactCard(
    val name: String,
    val phones: List<String> = emptyList(),
    val viroId: String? = null,
    val userId: String? = null,
)

data class ChatMessage(
    val id: String,
    val clientMsgId: String?,
    val conversationId: String,
    val senderUserId: String,
    val mine: Boolean,
    /** TEXT | VOICE | IMAGE | SYSTEM | LOOP | POLL | GIF | STICKER | FILE | CONTACT | LOCATION */
    val type: String,
    val body: String?,
    val createdAt: Long,
    val editedAt: Long?,
    val deleted: Boolean,
    val expiresAt: Long?,
    val deliverAt: Long?,
    val replyTo: ReplyDto?,
    val reactions: List<ReactionDto>,
    val media: MediaDto?,
    val viewOnce: Boolean,
    val viewed: Boolean,
    val forwarded: Boolean,
    val starred: Boolean,
    val metadata: Map<String, Any?>,
    val outboxStatus: String?,
    val localMediaPath: String?,
    val poll: PollDto? = null,
) {
    val isPending: Boolean get() = outboxStatus != null
    val effect: String? get() = metadata["effect"] as? String
    val event: String? get() = metadata["event"] as? String
    val loopId: String? get() = metadata["loopId"] as? String

    @Suppress("UNCHECKED_CAST")
    private fun obj(key: String): Map<String, Any?>? = metadata[key] as? Map<String, Any?>
    val gifUrl: String? get() = obj("gif")?.get("url") as? String
    val gifSize: Pair<Int, Int>? get() = obj("gif")?.let { g ->
        val w = (g["width"] as? Number)?.toInt()
        val h = (g["height"] as? Number)?.toInt()
        if (w != null && h != null && w > 0 && h > 0) w to h else null
    }
    /** Documents: the sender's file name, and its size in bytes. */
    val fileName: String? get() = media?.originalName ?: obj("file")?.get("name") as? String
    val fileSize: Long? get() = media?.sizeBytes?.toLong() ?: (obj("file")?.get("size") as? Number)?.toLong()

    /** User ids named with @ in this message. */
    val mentions: List<String> get() = (metadata["mentions"] as? List<Any?>)?.mapNotNull { it as? String }.orEmpty()

    /** A place someone sent, or a live share while it runs. */
    val place: SharedPlace? get() = obj("location")?.let { l ->
        val lat = (l["lat"] as? Number)?.toDouble() ?: return@let null
        val lng = (l["lng"] as? Number)?.toDouble() ?: return@let null
        SharedPlace(
            lat = lat,
            lng = lng,
            accuracy = (l["accuracy"] as? Number)?.toDouble(),
            label = l["label"] as? String,
            liveUntil = parseIso(l["liveUntil"] as? String),
            updatedAt = parseIso(l["updatedAt"] as? String),
        )
    }

    /** A shared contact card. */
    val contactCard: ContactCard? get() = obj("contact")?.let { c ->
        val name = c["name"] as? String ?: return@let null
        @Suppress("UNCHECKED_CAST")
        ContactCard(
            name = name,
            phones = (c["phones"] as? List<Any?>)?.mapNotNull { it as? String }.orEmpty(),
            viroId = c["viroId"] as? String,
            userId = c["userId"] as? String,
        )
    }

    val stickerPack: String? get() = obj("sticker")?.get("pack") as? String
    val stickerId: String? get() = obj("sticker")?.get("id") as? String
    val linkPreview: com.viroreach.core.network.LinkPreviewDto? get() = obj("linkPreview")?.let { lp ->
        val url = lp["url"] as? String ?: return@let null
        com.viroreach.core.network.LinkPreviewDto(url, lp["title"] as? String, lp["description"] as? String, lp["siteName"] as? String, lp["mediaId"] as? String)
    }

    fun delivery(peerLastDeliveredAt: Long?, peerLastReadAt: Long?): Delivery = when {
        !mine -> Delivery.INCOMING
        outboxStatus == "FAILED" -> Delivery.FAILED
        outboxStatus != null -> Delivery.SENDING
        deliverAt != null -> Delivery.SCHEDULED
        peerLastReadAt != null && peerLastReadAt >= createdAt -> Delivery.READ
        peerLastDeliveredAt != null && peerLastDeliveredAt >= createdAt -> Delivery.DELIVERED
        else -> Delivery.SENT
    }
}

data class ConversationItem(
    val id: String,
    val kind: String,
    val title: String?,
    val myRole: String,
    val peerUserId: String?,
    val participants: List<String>,
    val unread: Int,
    val hidden: Boolean,
    val muted: Boolean,
    val locked: Boolean,
    val expiresAt: Long?,
    val disappearingSeconds: Int?,
    val peerLastReadAt: Long?,
    val peerLastDeliveredAt: Long?,
    val pinnedIds: List<String>,
    val lastMessageId: String?,
    val lastBody: String?,
    val lastType: String?,
    val lastMine: Boolean,
    val lastSender: String?,
    val lastAt: Long?,
    val lastDeleted: Boolean,
    val lastPending: String?,
    val updatedAt: Long,
    val archived: Boolean = false,
    val pinnedAt: Long? = null,
    val unreadMarked: Boolean = false,
    val mentionedUnread: Boolean = false,
) {
    val isPrivate: Boolean get() = kind == "PRIVATE"
    val isGroup: Boolean get() = kind == "GROUP"
}

data class TypingState(val userId: String, val state: String, val at: Long)

internal object ChatJson {
    val gson = Gson()
    private val reactionsType = object : TypeToken<List<ReactionDto>>() {}.type
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type

    fun reactions(json: String?): List<ReactionDto> =
        json?.let { runCatching { gson.fromJson<List<ReactionDto>>(it, reactionsType) }.getOrNull() } ?: emptyList()

    fun map(json: String?): Map<String, Any?> =
        json?.let { runCatching { gson.fromJson<Map<String, Any?>>(it, mapType) }.getOrNull() } ?: emptyMap()

    fun media(json: String?): MediaDto? = json?.let { runCatching { gson.fromJson(it, MediaDto::class.java) }.getOrNull() }
    fun reply(json: String?): ReplyDto? = json?.let { runCatching { gson.fromJson(it, ReplyDto::class.java) }.getOrNull() }
    fun <T> toJson(value: T?): String? = value?.let { gson.toJson(it) }
}

fun parseIso(value: String?): Long? =
    value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

internal fun MsgDto.toEntity(existing: MessageEntity?): MessageEntity = MessageEntity(
    id = id,
    clientMsgId = clientMsgId,
    conversationId = conversationId,
    senderUserId = senderUserId,
    type = type ?: "TEXT",
    body = body,
    createdAt = parseIso(createdAt) ?: System.currentTimeMillis(),
    updatedAt = parseIso(updatedAt) ?: System.currentTimeMillis(),
    editedAt = parseIso(editedAt),
    deletedAt = parseIso(deletedAt),
    expiresAt = parseIso(expiresAt),
    deliverAt = parseIso(deliverAt),
    replyJson = ChatJson.toJson(replyTo),
    reactionsJson = ChatJson.toJson(reactions ?: emptyList<ReactionDto>()),
    mediaJson = ChatJson.toJson(media),
    viewOnce = viewOnce == true,
    viewed = viewed == true,
    forwarded = forwarded == true,
    starred = starred == true,
    metadataJson = ChatJson.toJson(metadata),
    pollJson = ChatJson.toJson(poll),
    status = null,
    // A recording I sent, or a file already downloaded, stays usable offline.
    localMediaPath = if (media != null && deletedAt == null) existing?.localMediaPath else null,
)

internal fun ConvDto.toEntity(myUserId: String?, existing: ConversationEntity?): ConversationEntity {
    val people = participants ?: emptyList()
    return ConversationEntity(
        id = id,
        kind = kind ?: "DM",
        // A group has members, not "the other person".
        peerUserId = if (kind == "GROUP") null else people.firstOrNull { it != myUserId },
        title = title,
        participantsCsv = people.joinToString(","),
        unread = unread ?: 0,
        updatedAt = parseIso(updatedAt) ?: System.currentTimeMillis(),
        hidden = hidden == true,
        mutedUntil = parseIso(mutedUntil),
        clearedAt = parseIso(clearedAt),
        resetAt = parseIso(resetAt),
        disappearingSeconds = disappearingSeconds,
        expiresAt = parseIso(expiresAt),
        peerLastReadAt = parseIso(peerLastReadAt),
        peerLastDeliveredAt = parseIso(peerLastDeliveredAt),
        pinnedCsv = (pinnedMessageIds ?: emptyList()).joinToString(","),
        locked = existing?.locked ?: false,
        description = description,
        myRole = myRole ?: "MEMBER",
        // An older server doesn't send these; keep whatever this phone has.
        archived = archived ?: existing?.archived ?: false,
        pinnedAt = parseIso(pinnedAt) ?: existing?.pinnedAt.takeIf { pinnedAt == null },
        unreadMarked = unreadMarked ?: existing?.unreadMarked ?: false,
        mentionedUnread = mentionedUnread ?: existing?.mentionedUnread ?: false,
        // Encryption never switches off: an older server that says nothing
        // must not make an encrypted chat look ordinary.
        encrypted = encrypted == true || existing?.encrypted == true,
    )
}

internal fun MessageEntity.toChat(myUserId: String?): ChatMessage = ChatMessage(
    id = id,
    clientMsgId = clientMsgId,
    conversationId = conversationId,
    senderUserId = senderUserId,
    mine = senderUserId == myUserId,
    type = type,
    body = body,
    createdAt = createdAt,
    editedAt = editedAt,
    deleted = deletedAt != null,
    expiresAt = expiresAt,
    deliverAt = deliverAt,
    replyTo = ChatJson.reply(replyJson),
    reactions = ChatJson.reactions(reactionsJson),
    media = ChatJson.media(mediaJson),
    viewOnce = viewOnce,
    viewed = viewed,
    forwarded = forwarded,
    starred = starred,
    metadata = ChatJson.map(metadataJson),
    outboxStatus = status,
    localMediaPath = localMediaPath,
    poll = pollJson?.let { runCatching { ChatJson.gson.fromJson(it, PollDto::class.java) }.getOrNull() },
)

internal fun ConversationRow.toItem(myUserId: String?): ConversationItem = ConversationItem(
    id = id,
    kind = kind,
    title = title,
    myRole = myRole,
    peerUserId = peerUserId,
    participants = participantsCsv.split(',').filter { it.isNotBlank() },
    unread = unread,
    hidden = hidden,
    muted = (mutedUntil ?: 0L) > System.currentTimeMillis(),
    locked = locked,
    expiresAt = expiresAt,
    disappearingSeconds = disappearingSeconds,
    peerLastReadAt = peerLastReadAt,
    peerLastDeliveredAt = peerLastDeliveredAt,
    pinnedIds = pinnedCsv.split(',').filter { it.isNotBlank() },
    lastMessageId = lastId,
    lastBody = lastBody,
    lastType = lastType,
    lastMine = lastSender != null && lastSender == myUserId,
    lastSender = lastSender,
    lastAt = lastAt,
    lastDeleted = lastDeleted != null,
    lastPending = lastStatus,
    updatedAt = updatedAt,
    archived = archived,
    pinnedAt = pinnedAt,
    unreadMarked = unreadMarked,
    mentionedUnread = mentionedUnread,
)

/** One line for the inbox: what the last message was, in words. */
fun ConversationItem.preview(): String = when {
    lastMessageId == null -> if (isPrivate) "Private session started" else ""
    lastDeleted -> "This message was deleted"
    lastType == "VOICE" -> "🎤 Voice message"
    lastType == "IMAGE" -> if (lastBody.isNullOrBlank()) "📷 Photo" else "📷 $lastBody"
    lastType == "LOOP" -> "🔁 ${lastBody ?: "Loop"}"
    lastType == "POLL" -> "📊 Poll"
    lastType == "GIF" -> "GIF"
    lastType == "FILE" -> "📎 " + (lastBody?.takeIf { it.isNotBlank() } ?: "Document")
    lastType == "CONTACT" -> "👤 " + (lastBody?.takeIf { it.isNotBlank() } ?: "Contact")
    lastType == "LOCATION" -> "📍 " + (lastBody?.takeIf { it.isNotBlank() } ?: "Location")
    lastType == "STICKER" -> "${lastBody ?: ""} Sticker".trim()
    lastType == "SYSTEM" -> lastBody?.replaceFirstChar { it.uppercase() } ?: ""
    else -> lastBody.orEmpty()
}

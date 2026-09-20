package com.viroreach.core.network

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Messaging v2, Loops and relationship intelligence.
 *
 * DTO fields are nullable wherever the server can omit them: Gson ignores
 * Kotlin defaults and non-null types, so a "non-null" field can still arrive
 * null and crash far from here.
 */
interface ViroMessagingApi {
    // --- messages --------------------------------------------------------
    @POST("api/v1/messages")
    suspend fun send(@Body body: SendBody): SendResult

    @GET("api/v1/messages/sync")
    suspend fun sync(@Query("since") since: String?): SyncResult

    @GET("api/v1/messages/conversations/{id}")
    suspend fun history(
        @Path("id") id: String,
        @Query("limit") limit: Int = 60,
        @Query("before") before: String? = null,
    ): List<MsgDto>

    @GET("api/v1/messages/conversations/{id}/summary")
    suspend fun summary(@Path("id") id: String): ConvDto

    @POST("api/v1/messages/conversations/{id}/read")
    suspend fun markRead(@Path("id") id: String): OkResult

    /** The group's shareable link (admins only). */
    @GET("api/v1/messages/conversations/{id}/invite")
    suspend fun groupInvite(@Path("id") id: String): GroupInviteDto

    @POST("api/v1/messages/conversations/{id}/invite/reset")
    suspend fun resetGroupInvite(@Path("id") id: String): GroupInviteDto

    @DELETE("api/v1/messages/conversations/{id}/invite")
    suspend fun revokeGroupInvite(@Path("id") id: String): GroupInviteDto

    /** What a link leads to, before joining. */
    @GET("api/v1/messages/groups/invite/{code}")
    suspend fun groupInvitePreview(@Path("code") code: String): GroupInvitePreviewDto

    @POST("api/v1/messages/groups/invite/{code}/join")
    suspend fun joinGroupByInvite(@Path("code") code: String): ConvDto

    /** Moves my live location on. */
    @PUT("api/v1/messages/{id}/location")
    suspend fun updateLocation(@Path("id") id: String, @Body body: LocationUpdateBody): MsgDto

    /** Ends my live location share. */
    @POST("api/v1/messages/{id}/location/stop")
    suspend fun stopLocation(@Path("id") id: String): MsgDto

    @PATCH("api/v1/messages/conversations/{id}/settings")
    suspend fun settings(@Path("id") id: String, @Body body: ConvSettingsBody): ConvDto

    @POST("api/v1/messages/conversations/{id}/clear")
    suspend fun clear(@Path("id") id: String): OkResult

    @POST("api/v1/messages/conversations/{id}/reset")
    suspend fun reset(@Path("id") id: String): OkResult

    @POST("api/v1/messages/conversations/{id}/end-private")
    suspend fun endPrivate(@Path("id") id: String): OkResult

    @POST("api/v1/messages/private")
    suspend fun startPrivate(@Body body: PrivateBody): ConvDto

    @POST("api/v1/messages/erase-with/{userId}")
    suspend fun eraseWith(@Path("userId") userId: String): OkResult

    @PATCH("api/v1/messages/{id}")
    suspend fun edit(@Path("id") id: String, @Body body: EditBody): MsgDto

    @DELETE("api/v1/messages/{id}")
    suspend fun delete(@Path("id") id: String, @Query("scope") scope: String): OkResult

    @PUT("api/v1/messages/{id}/reaction")
    suspend fun react(@Path("id") id: String, @Body body: ReactBody): MsgDto

    @DELETE("api/v1/messages/{id}/reaction")
    suspend fun unreact(@Path("id") id: String): MsgDto

    @POST("api/v1/messages/{id}/viewed")
    suspend fun viewed(@Path("id") id: String): OkResult

    @PUT("api/v1/messages/{id}/star")
    suspend fun star(@Path("id") id: String): OkResult

    @DELETE("api/v1/messages/{id}/star")
    suspend fun unstar(@Path("id") id: String): OkResult

    @PUT("api/v1/messages/{id}/pin")
    suspend fun pin(@Path("id") id: String): OkResult

    @DELETE("api/v1/messages/{id}/pin")
    suspend fun unpin(@Path("id") id: String): OkResult

    @PUT("api/v1/messages/{id}/vote")
    suspend fun vote(@Path("id") id: String, @Body body: VoteBody): MsgDto

    @GET("api/v1/messages/features")
    suspend fun features(): FeaturesDto

    @GET("api/v1/messages/search")
    suspend fun search(@Query("q") q: String, @Query("conversationId") conversationId: String? = null): List<MsgDto>

    @GET("api/v1/messages/link-preview")
    suspend fun linkPreview(@Query("url") url: String): LinkPreviewResult

    @GET("api/v1/messages/gifs")
    suspend fun gifs(@Query("q") q: String?, @Query("pos") pos: String? = null): GifPageDto

    @POST("api/v1/messages/media/{id}/transcribe")
    suspend fun transcribe(@Path("id") mediaId: String): TranscriptDto

    // --- groups
    @POST("api/v1/messages/groups")
    suspend fun createGroup(@Body body: GroupBody): ConvDto

    @GET("api/v1/messages/conversations/{id}/members")
    suspend fun members(@Path("id") id: String): List<MemberDto>

    @POST("api/v1/messages/conversations/{id}/members")
    suspend fun addMembers(@Path("id") id: String, @Body body: MembersBody): List<MemberDto>

    @DELETE("api/v1/messages/conversations/{id}/members/{userId}")
    suspend fun removeMember(@Path("id") id: String, @Path("userId") userId: String): List<MemberDto>

    @PUT("api/v1/messages/conversations/{id}/members/{userId}/role")
    suspend fun setRole(@Path("id") id: String, @Path("userId") userId: String, @Body body: RoleBody): List<MemberDto>

    @POST("api/v1/messages/conversations/{id}/leave")
    suspend fun leaveGroup(@Path("id") id: String): OkResult

    @PATCH("api/v1/messages/conversations/{id}/group")
    suspend fun updateGroup(@Path("id") id: String, @Body body: GroupPatchBody): ConvDto

    // --- loops -----------------------------------------------------------
    @POST("api/v1/loops")
    suspend fun createLoop(@Body body: LoopBody): LoopDto

    @GET("api/v1/loops")
    suspend fun loops(@Query("conversationId") conversationId: String? = null): List<LoopDto>

    @PATCH("api/v1/loops/{id}")
    suspend fun updateLoop(@Path("id") id: String, @Body body: LoopPatchBody): LoopDto

    @DELETE("api/v1/loops/{id}")
    suspend fun deleteLoop(@Path("id") id: String): OkResult

    @POST("api/v1/loops/{id}/answer")
    suspend fun answerLoop(@Path("id") id: String, @Body body: LoopAnswerBody): LoopDto

    @GET("api/v1/loops/{id}/history")
    suspend fun loopHistory(@Path("id") id: String): List<LoopPeriodDto>

    // --- relationships (private) ----------------------------------------
    @GET("api/v1/relationships/overview")
    suspend fun overview(): OverviewDto

    @GET("api/v1/relationships/nudges")
    suspend fun nudges(): NudgesDto

    @GET("api/v1/relationships/for-subject")
    suspend fun forSubject(@Query("userId") userId: String?, @Query("phone") phone: String?): ForSubjectDto

    @PUT("api/v1/relationships")
    suspend fun upsertRelationship(@Body body: RelationshipBody): RelationshipRow

    @DELETE("api/v1/relationships/{id}")
    suspend fun deleteRelationship(@Path("id") id: String): OkResult

    @GET("api/v1/relationships/{id}/timeline")
    suspend fun timeline(@Path("id") id: String): TimelineDto

    @POST("api/v1/relationships/{id}/dates")
    suspend fun addDate(@Path("id") id: String, @Body body: DateBody): DateRow

    @DELETE("api/v1/relationships/dates/{dateId}")
    suspend fun deleteDate(@Path("dateId") dateId: String): OkResult

    @POST("api/v1/relationships/{id}/checkins")
    suspend fun checkIn(@Path("id") id: String, @Body body: CheckinBody): Any

    @GET("api/v1/relationships/commitments/all")
    suspend fun commitments(@Query("status") status: String? = null): List<CommitmentDto>

    @POST("api/v1/relationships/commitments")
    suspend fun addCommitment(@Body body: CommitmentBody): CommitmentDto

    @PATCH("api/v1/relationships/commitments/{id}")
    suspend fun updateCommitment(@Path("id") id: String, @Body body: CommitmentPatchBody): CommitmentDto

    @GET("api/v1/relationships/settings")
    suspend fun reminderSettings(): ReminderSettingsDto

    @PUT("api/v1/relationships/settings")
    suspend fun updateReminderSettings(@Body body: ReminderSettingsDto): ReminderSettingsDto
}

data class OkResult(val ok: Boolean?)

// --- messages -----------------------------------------------------------

data class SendBody(
    val toUserId: String? = null,
    val conversationId: String? = null,
    val body: String? = null,
    val clientMsgId: String,
    val type: String = "TEXT",
    val replyToId: String? = null,
    val mediaId: String? = null,
    val viewOnce: Boolean? = null,
    val forwarded: Boolean? = null,
    val deliverAt: String? = null,
    val effect: String? = null,
    val poll: PollBody? = null,
    val linkPreview: LinkPreviewDto? = null,
    val gif: GifSendDto? = null,
    val sticker: StickerRef? = null,
    val contact: ContactCardBody? = null,
    val location: LocationBody? = null,
    /** User ids named with @ in a group message. */
    val mentions: List<String>? = null,
    /**
     * End-to-end encrypted: one sealed copy per recipient device. When these
     * are present the server stores nothing it could read, so [body] is left
     * empty.
     */
    val envelopes: List<EnvelopeBody>? = null,
    /**
     * For a sealed live location: how long the share runs. Where the person is
     * goes inside the ciphertext — this only tells the server when to stop
     * carrying updates.
     */
    val liveSeconds: Int? = null,
)

data class EnvelopeBody(val deviceId: String, val ciphertext: String, val type: Int)

/** A place, or the start of a live share (liveSeconds: 900, 3600 or 28800). */
data class LocationBody(
    val lat: Double,
    val lng: Double,
    val accuracy: Double? = null,
    val label: String? = null,
    val liveSeconds: Int? = null,
)

data class LocationPoint(val lat: Double, val lng: Double, val accuracy: Double? = null)

/**
 * A new position for a live share — either the coordinates, or, when the chat
 * is encrypted, sealed copies of them for each device. Never both.
 */
data class LocationUpdateBody(
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracy: Double? = null,
    val envelopes: List<EnvelopeBody>? = null,
)

data class GroupInviteDto(val code: String?, val url: String?, val createdAt: String? = null)
data class GroupInvitePreviewDto(
    val conversationId: String,
    val title: String?,
    val description: String?,
    val memberCount: Int?,
    val alreadyMember: Boolean?,
)

/** A shared contact card: a name, plus numbers and/or a Viro ID. */
data class ContactCardBody(
    val name: String,
    val phones: List<String> = emptyList(),
    val viroId: String? = null,
    val userId: String? = null,
)

data class PollBody(val question: String, val options: List<String>, val multi: Boolean = false)
data class PollOptionDto(val text: String, val votes: Int?, val voters: List<String>?)
data class PollDto(
    val question: String,
    val multi: Boolean?,
    val options: List<PollOptionDto>?,
    val totalVoters: Int?,
    val myVotes: List<Int>?,
)
data class VoteBody(val options: List<Int>)

data class LinkPreviewDto(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val siteName: String? = null,
    val mediaId: String? = null,
)
data class LinkPreviewResult(val preview: LinkPreviewDto?)

data class GifSendDto(val url: String, val previewUrl: String? = null, val width: Int? = null, val height: Int? = null, val provider: String? = null)
data class GifDto(val id: String, val url: String, val previewUrl: String?, val width: Int?, val height: Int?, val provider: String?)
data class GifPageDto(val items: List<GifDto>?, val next: String?, val provider: String?)
data class StickerRef(val pack: String, val id: String)

data class FeaturesDto(
    val gifs: Boolean?,
    val gifProvider: String?,
    val transcripts: Boolean?,
    /** Whether this deployment has switched end-to-end encryption on. */
    val e2ee: Boolean? = null,
)
data class TranscriptDto(val text: String?, val language: String?)

data class GroupBody(val title: String, val memberIds: List<String>, val description: String? = null)
data class GroupPatchBody(val title: String? = null, val description: String? = null)
data class MembersBody(val userIds: List<String>)
data class RoleBody(val role: String)
data class MemberDto(val userId: String, val role: String?, val displayName: String?, val joinedAt: String?)

data class SendResult(val conversationId: String, val message: MsgDto)

data class MediaDto(
    val id: String,
    val kind: String?,
    val mime: String?,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val waveform: String?,
    val width: Int?,
    val height: Int?,
    val transcript: String? = null,
    val transcriptLang: String? = null,
    /** For documents: the name the sender gave it. */
    val originalName: String? = null,
    /**
     * For an end-to-end encrypted file: the key that opens it, taken from
     * inside the message. The server never sends these and never sees them —
     * they are written here on this phone once the message is opened, so that
     * whatever displays the file can decrypt it.
     */
    val sealedKey: String? = null,
    val sealedIv: String? = null,
)

data class ReplyDto(val id: String, val senderUserId: String?, val type: String?, val body: String?, val deleted: Boolean?)

data class ReactionDto(val userId: String, val emoji: String)

data class MsgDto(
    val id: String,
    val conversationId: String,
    val senderUserId: String,
    val body: String?,
    val type: String?,
    val clientMsgId: String?,
    val createdAt: String,
    val updatedAt: String?,
    val editedAt: String?,
    val deletedAt: String?,
    val expiresAt: String?,
    val deliverAt: String?,
    val replyTo: ReplyDto?,
    val reactions: List<ReactionDto>?,
    val media: MediaDto?,
    val viewOnce: Boolean?,
    val viewed: Boolean?,
    val forwarded: Boolean?,
    val starred: Boolean?,
    val metadata: Map<String, Any?>?,
    val poll: PollDto? = null,
    /** Which device sealed it — needed to find the session it belongs to. */
    val senderDeviceId: String? = null,
    /** For an encrypted message: the sealed copies addressed to my own devices. */
    val envelopes: List<EnvelopeDto>? = null,
    /**
     * For an encrypted poll: who chose which option number. The question and
     * the options come out of the message itself; this is what the server
     * counted without being able to read either.
     */
    val pollVotes: List<PollVoteDto>? = null,
    /** When a live location share ends. */
    val liveUntil: String? = null,
)

data class PollVoteDto(val userId: String, val optionIndex: Int)

/** One sealed copy of a message, for one device. */
data class EnvelopeDto(val deviceId: String, val ciphertext: String, val type: Int? = null)

data class ConvDto(
    val id: String,
    val kind: String?,
    val isGroup: Boolean?,
    val title: String?,
    val participants: List<String>?,
    val createdBy: String?,
    val lastMessage: MsgDto?,
    val unread: Int?,
    val updatedAt: String?,
    val hidden: Boolean?,
    val mutedUntil: String?,
    val clearedAt: String?,
    val resetAt: String?,
    val disappearingSeconds: Int?,
    val expiresAt: String?,
    val description: String? = null,
    val myRole: String? = null,
    val peerLastReadAt: String?,
    val peerLastDeliveredAt: String?,
    val pinnedMessageIds: List<String>?,
    /** Someone named me with @ in a message I haven't read. */
    val mentionedUnread: Boolean? = null,
    /** Inbox state, mine alone (archive / pin / mark unread). */
    val archived: Boolean? = null,
    val pinnedAt: String? = null,
    val unreadMarked: Boolean? = null,
    /** End-to-end encrypted: the server carries this chat without reading it. */
    val encrypted: Boolean? = null,
)

data class SyncResult(
    val serverTime: String,
    val conversations: List<ConvDto>?,
    val messages: List<MsgDto>?,
    val hasMore: Boolean?,
)

data class ConvSettingsBody(
    val hidden: Boolean? = null,
    val archived: Boolean? = null,
    val pinned: Boolean? = null,
    val markUnread: Boolean? = null,
    val mutedUntil: String? = null,
    val disappearingSeconds: Int? = null,
    // Gson drops nulls, so "turn off" and "unmute" need explicit fields.
    val clearDisappearing: Boolean? = null,
    val clearMute: Boolean? = null,
)

data class PrivateBody(val toUserId: String, val durationSeconds: Int)
/** New words for a message: in the clear, or sealed for each device. */
data class EditBody(val body: String? = null, val envelopes: List<EnvelopeBody>? = null)
data class ReactBody(val emoji: String)

// --- loops --------------------------------------------------------------

data class LoopBody(
    val conversationId: String? = null,
    val toUserId: String? = null,
    val title: String,
    val prompt: String,
    val frequency: String,
    val daysMask: Int? = null,
    val timeOfDay: String = "19:00",
    val timezone: String,
    val responseKind: String = "ANY",
    val choices: List<String>? = null,
    val reciprocal: Boolean = true,
)

data class LoopPatchBody(val active: Boolean? = null, val title: String? = null, val prompt: String? = null, val timeOfDay: String? = null)

data class LoopAnswerBody(val kind: String, val text: String? = null, val mediaId: String? = null)

data class LoopAnswerDto(
    val id: String,
    val userId: String,
    val kind: String,
    val text: String?,
    val media: MediaDto?,
    val createdAt: String?,
)

data class LoopDto(
    val id: String,
    val conversationId: String,
    val createdBy: String?,
    val title: String,
    val prompt: String,
    val frequency: String,
    val daysMask: Int?,
    val timeOfDay: String?,
    val timezone: String?,
    val responseKind: String?,
    val choices: List<String>?,
    val reciprocal: Boolean?,
    val active: Boolean?,
    val participants: List<String>?,
    val periodKey: String?,
    val answeredBy: List<String>?,
    val waitingFor: List<String>?,
    val myAnswer: LoopAnswerDto?,
    val answers: List<LoopAnswerDto>?,
    val revealed: Boolean?,
    val completedTotal: Int?,
    val completedThisMonth: Int?,
)

data class LoopPeriodDto(val periodKey: String, val complete: Boolean?, val answers: List<LoopAnswerDto>?)

// --- relationships ------------------------------------------------------

data class RelationshipBody(
    val subjectUserId: String? = null,
    val subjectPhone: String? = null,
    val displayName: String? = null,
    val category: String? = null,
    val relationshipType: String? = null,
    val customLabel: String? = null,
    val vibe: String? = null,
    val targetCadence: String? = null,
    val targetCount: Int? = null,
    val targetEveryDays: Int? = null,
    val targetWeekday: Int? = null,
    val targetLabel: String? = null,
    val remindersEnabled: Boolean? = null,
    val notes: String? = null,
)

data class RelationshipRow(val id: String)

data class HealthDto(val code: String, val text: String)
data class ProgressDto(val done: Int?, val needed: Int?, val met: Boolean?, val text: String?)
data class NextDateDto(val id: String, val kind: String, val label: String, val date: String, val daysAway: Int, val sentence: String)
data class DateRow(
    val id: String,
    val kind: String,
    val label: String?,
    val month: Int,
    val day: Int,
    val year: Int?,
    val remindDaysBefore: Int?,
)
data class RelLoopDto(val id: String, val title: String, val waitingOnMe: Boolean?, val completedThisMonth: Int?, val completedTotal: Int?)

data class RelationshipDto(
    val id: String,
    val subjectUserId: String?,
    val subjectPhone: String?,
    val displayName: String?,
    val category: String?,
    val relationshipType: String?,
    val customLabel: String?,
    val vibe: String?,
    val vibeExplicit: String?,
    val icon: String?,
    val targetCadence: String?,
    val targetCount: Int?,
    val targetEveryDays: Int?,
    val targetWeekday: Int?,
    val targetLabel: String?,
    val targetText: String?,
    val remindersEnabled: Boolean?,
    val notes: String?,
    val health: HealthDto?,
    val flags: List<HealthDto>?,
    val progress: ProgressDto?,
    val lastInteractionAt: String?,
    val interactionsThisWeek: Int?,
    val nextDate: NextDateDto?,
    val dates: List<DateRow>?,
    val openCommitments: List<CommitmentDto>?,
    val loops: List<RelLoopDto>?,
)

data class AttentionDto(
    val relationshipId: String?,
    val subjectUserId: String?,
    val subjectPhone: String?,
    val name: String,
    val icon: String?,
    val category: String?,
    val code: String,
    val text: String,
    val loopId: String?,
    val commitmentId: String?,
)

data class ComingUpDto(val date: String, val daysAway: Int, val label: String, val icon: String?, val text: String, val relationshipId: String?)
data class TargetGroupDto(val label: String, val met: Int, val total: Int)
data class AchievementDto(val key: String, val title: String, val detail: String, val unlockedAt: String?, val shared: Boolean?)
data class BriefLineDto(val icon: String?, val name: String, val text: String)
data class BriefDto(val title: String, val summary: String, val lines: List<BriefLineDto>?)

data class OverviewDto(
    val today: String?,
    val timezone: String?,
    val relationships: List<RelationshipDto>?,
    val attention: List<AttentionDto>?,
    val comingUp: List<ComingUpDto>?,
    val targets: List<TargetGroupDto>?,
    val achievements: List<AchievementDto>?,
    val brief: BriefDto?,
    val moments: List<String>?,
    val achievementProgress: List<AchievementProgressDto>? = null,
)

data class AchievementProgressDto(val key: String, val title: String, val detail: String, val done: Int, val total: Int)

data class ForSubjectDto(val relationship: RelationshipDto?)

data class TimelineItemDto(val at: String, val day: String, val kind: String, val title: String, val detail: String?)
data class TimelineDto(val relationshipId: String, val items: List<TimelineItemDto>?)

data class DateBody(val kind: String, val label: String? = null, val month: Int, val day: Int, val year: Int? = null, val remindDaysBefore: Int = 1)
data class CheckinBody(val note: String? = null)

data class CommitmentBody(
    val text: String,
    val dueAt: String,
    val kind: String = "OTHER",
    val relationshipId: String? = null,
    val subjectUserId: String? = null,
    val subjectPhone: String? = null,
    val displayName: String? = null,
    val conversationId: String? = null,
    val messageId: String? = null,
)

data class CommitmentPatchBody(val status: String? = null, val dueAt: String? = null, val text: String? = null)

data class CommitmentDto(
    val id: String,
    val relationshipId: String?,
    val subjectUserId: String?,
    val conversationId: String?,
    val messageId: String?,
    val kind: String?,
    val text: String,
    val dueAt: String,
    val status: String?,
    val completedAt: String?,
    val createdAt: String?,
)

data class NudgeDto(
    val key: String,
    val priority: String,
    val kind: String,
    val title: String,
    val body: String,
    val relationshipId: String?,
    val subjectUserId: String?,
    val notBefore: String?,
)

data class NudgeSettingsDto(
    val timezone: String?,
    val quietStart: String?,
    val quietEnd: String?,
    val briefEnabled: Boolean?,
    val briefTime: String?,
    val dailyCap: Int?,
)

data class NudgesDto(val settings: NudgeSettingsDto?, val brief: BriefDto?, val nudges: List<NudgeDto>?)

data class ReminderSettingsDto(
    val timezone: String? = null,
    val quietStart: String? = null,
    val quietEnd: String? = null,
    val briefEnabled: Boolean? = null,
    val briefTime: String? = null,
    val frequency: String? = null,
    val personalReminders: Boolean? = null,
    val professionalReminders: Boolean? = null,
    val dateReminders: Boolean? = null,
    val loopNotifications: Boolean? = null,
    val achievementNotifications: Boolean? = null,
)

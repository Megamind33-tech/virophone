package com.viroreach.app.moments

import com.viroreach.core.network.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MomentsRepositoryTest {
    private fun moment(end: Long = System.currentTimeMillis() + 60_000) = MomentDto(
        "moment", "alice", "FREE", null, "CONNECTIONS", "Alice", null,
        Instant.now().toString(), Instant.ofEpochMilli(end).toString(), true, 1,
    )
    private fun message(id: String = "m1", sender: String = "bob", body: String = "hello") = MomentMessageDto(
        id, "moment", sender, if (sender == "alice") "Alice" else "Bob", body, Instant.now().toString(),
        listOf(MomentReactionDto("🔥", listOf("alice"))),
    )
    private fun room(messages: List<MomentMessageDto> = listOf(message()),
                     participants: List<MomentParticipantDto> = listOf(
                         MomentParticipantDto("alice", "Alice", true, Instant.now().toString()),
                         MomentParticipantDto("bob", "Bob", false, Instant.now().toString()),
                     )) = MomentRoomDto(moment(), Instant.now().toString(), participants, messages)

    private class Api(var list: List<MomentDto>) : ViroMomentsApi {
        var fail = false
        var failRooms = false
        var afterMutation: () -> Unit = {}
        var roomSnapshot: MomentRoomDto = MomentRoomDto(
            MomentDto("moment", "alice", "FREE", null, "CONNECTIONS", "Alice", null,
                Instant.now().toString(), Instant.ofEpochMilli(System.currentTimeMillis() + 60_000).toString(), true, 1),
            Instant.now().toString(),
            listOf(MomentParticipantDto("alice", "Alice", true, Instant.now().toString())),
            emptyList(),
        )
        var sent = mutableListOf<MomentMessageDto>()
        var knocksSent = 0
        var invited = mutableListOf<String>()
        var invitationList = emptyList<MomentInvitationDto>()
        var afterInvitations: () -> Unit = {}
        var knockResponses = mutableListOf<Pair<String, Boolean>>()
        var left = 0
        override suspend fun now(): MomentsNowDto {
            if (fail) error("offline")
            return MomentsNowDto(Instant.now().toString(), list)
        }
        override suspend fun get(id: String) = list.first { it.id == id }
        override suspend fun create(body: CreateMomentBody): MomentDto {
            val created = list.first()
            afterMutation()
            return created
        }
        override suspend fun extend(id: String, body: ExtendMomentBody) = error("unused")
        override suspend fun end(id: String) { list = emptyList(); afterMutation() }
        override suspend fun invitations(): MomentInvitationsDto {
            if (fail) error("offline")
            val result = MomentInvitationsDto(invitationList)
            afterInvitations()
            return result
        }
        override suspend fun declineInvitation(id: String) {}
        override suspend fun join(id: String): MomentRoomDto {
            if (failRooms) throw retrofit2.HttpException(retrofit2.Response.error<Any?>(404, okhttp3.ResponseBody.create(null, "")))
            return roomSnapshot
        }
        override suspend fun leave(id: String) { left++ }
        override suspend fun room(id: String): MomentRoomDto {
            if (failRooms) throw retrofit2.HttpException(retrofit2.Response.error<Any?>(404, okhttp3.ResponseBody.create(null, "")))
            return roomSnapshot.copy(messages = roomSnapshot.messages + sent)
        }
        /** What the last send actually put on the wire, sealed or not. */
        var lastSend: SendMomentMessageBody? = null
        override suspend fun sendMessage(id: String, body: SendMomentMessageBody): MomentMessageDto {
            lastSend = body
            // A sealed message comes back with no body, exactly as the server
            // returns it: it has none to return.
            val m = MomentMessageDto(
                "sent-${sent.size}", id, "bob", "Bob", body.body, Instant.now().toString(),
                sealed = body.envelopes != null,
                senderDeviceId = if (body.envelopes != null) "bob-device" else null,
            )
            sent.add(m)
            return m
        }
        override suspend fun react(id: String, messageId: String, body: MomentReactBody) {}
        var cheers = mutableListOf<Pair<String, String?>>()
        override suspend fun cheer(id: String, body: MomentReactBody): MomentDto {
            cheers.add(id to body.emoji)
            return list.first { it.id == id }
        }
        var visibilities = mutableListOf<Pair<String, String>>()
        override suspend fun setVisibility(id: String, body: MomentVisibilityBody): MomentDto {
            visibilities.add(id to body.visibility)
            list = list.map { if (it.id == id) it.copy(visibility = body.visibility) else it }
            return list.first { it.id == id }
        }
        override suspend fun knock(id: String) { knocksSent++ }
        override suspend fun knocks(id: String) = KnocksDto(emptyList())
        override suspend fun respondToKnock(id: String, knockerId: String, body: KnockResponseBody) {
            knockResponses.add(knockerId to body.accept)
        }
        override suspend fun invite(id: String, body: InviteBody) { invited.add(body.userId) }
        /** What the ending offered, and what was actually asked to be kept. */
        var offers = listOf(
            MomentKeepsakeOfferDto("o1", "MOMENT", "Cooking together", null),
            MomentKeepsakeOfferDto("o2", "DECISION", "Which rice?", "Jollof"),
        )
        var endingFails = false
        val keptCalls = mutableListOf<List<String>>()
        override suspend fun ending(id: String): MomentEndingDto {
            if (endingFails) throw retrofit2.HttpException(
                retrofit2.Response.error<Any?>(404, okhttp3.ResponseBody.create(null, "")))
            return MomentEndingDto(id, listOf("Natasha"), 4_320_000, null, offers)
        }
        override suspend fun keep(id: String, body: KeepBody) { keptCalls.add(body.offerIds) }
        override suspend fun keepsakes() = MomentKeepsakesDto(emptyList())
        override suspend fun forgetKeepsake(id: String) {}
        /** The room shape the fake server holds, and every change asked of it. */
        var runtimeState = MomentRuntimeDto("moment", 1, "COOK", "PRESENCE", emptyList(), "KITCHEN")
        val changes = mutableListOf<MomentRoomChangeBody>()
        override suspend fun shareMedia(id: String, file: okhttp3.MultipartBody.Part, title: okhttp3.RequestBody?, durationMs: okhttp3.RequestBody?) = error("unused")
        override suspend fun listMedia(id: String) = MomentMediaListDto(emptyList())
        override suspend fun unshareMedia(id: String, mediaId: String) {}
        override suspend fun streamUrl(id: String, mediaId: String) = error("unused")
        override suspend fun playback(id: String, body: MomentPlaybackBody) = error("unused")
        override suspend fun timer(id: String, body: MomentTimerBody) = error("unused")
        override suspend fun choice(id: String, body: MomentChoiceBody) = error("unused")
        override suspend fun touch(id: String, body: MomentTouchBody) {}
        override suspend fun presence(id: String) = MomentPresenceDto("wss://media.test", "token", "viro-moment-$id")
        override suspend fun changeRoom(id: String, body: MomentRoomChangeBody): MomentRuntimeDto {
            changes.add(body)
            runtimeState = runtimeState.copy(
                revision = runtimeState.revision + 1,
                intent = body.intent ?: runtimeState.intent,
                primary = when (body.intent) { "STAY" -> "QUIET"; "WATCH" -> "VIDEO"; else -> runtimeState.primary },
            )
            return runtimeState
        }
    }

    @Test fun `expired cached entries never return`() = runTest {
        val api = Api(listOf(moment(System.currentTimeMillis()-1000)))
        val repo = MomentsRepository(api) { "bob" }
        repo.refresh(); assertTrue(repo.moments.value.isEmpty())
    }
    @Test fun `invitations load without visiting Now and survive its first refresh`() = runTest {
        val api = Api(listOf(moment()))
        api.invitationList = listOf(MomentInvitationDto("invite", Instant.now().toString(), moment()))
        val repo = MomentsRepository(api) { "bob" }
        repo.refreshInvitations()
        repo.refresh()
        assertEquals(1, repo.invitations.value.size)
    }
    @Test fun `invitation response cannot cross accounts`() = runTest {
        var user: String? = "bob"
        val api = Api(listOf(moment()))
        api.invitationList = listOf(MomentInvitationDto("invite", Instant.now().toString(), moment()))
        val repo = MomentsRepository(api) { user }
        api.afterInvitations = { user = "carol" }
        repo.refreshInvitations()
        assertTrue(repo.invitations.value.isEmpty())
        api.afterInvitations = { user = null }
        repo.refreshInvitations()
        assertTrue(repo.invitations.value.isEmpty())
    }
    @Test fun `expired invitations are not displayed`() = runTest {
        val api = Api(emptyList())
        api.invitationList = listOf(MomentInvitationDto("invite", Instant.now().toString(), moment(System.currentTimeMillis() - 1_000)))
        val repo = MomentsRepository(api) { "bob" }
        repo.refreshInvitations()
        assertTrue(repo.invitations.value.isEmpty())
    }
    @Test fun `audience updates remove invitations that are no longer visible`() = runTest {
        val api = Api(listOf(moment()))
        api.invitationList = listOf(MomentInvitationDto("invite", Instant.now().toString(), moment()))
        val repo = MomentsRepository(api) { "bob" }
        repo.refreshInvitations()
        api.invitationList = emptyList()
        repo.onFrame("moment.updated", emptyMap())
        assertTrue(repo.invitations.value.isEmpty())
    }
    @Test fun `failed refresh preserves still active session cache`() = runTest {
        val api = Api(listOf(moment())); val repo = MomentsRepository(api) { "bob" }
        repo.refresh(); api.fail = true; repo.refresh()
        assertEquals(1,repo.moments.value.size); assertNotNull(repo.error.value)
    }
    @Test fun `account change cannot reuse another accounts cache`() = runTest {
        var user = "bob"; val api = Api(listOf(moment())); val repo = MomentsRepository(api) { user }
        repo.refresh(); user = "stranger"; api.fail = true; repo.refresh()
        assertTrue(repo.moments.value.isEmpty())
    }
    @Test fun `authoritative removal clears ended or blocked moments`() = runTest {
        val api = Api(listOf(moment())); val repo = MomentsRepository(api) { "bob" }
        repo.refresh(); api.list = emptyList(); repo.refresh(); assertTrue(repo.moments.value.isEmpty())
    }
    @Test fun `create response remains visible when the follow up refresh fails`() = runTest {
        val api = Api(listOf(moment())); val repo = MomentsRepository(api) { "alice" }
        api.afterMutation = { api.fail = true }
        assertTrue(repo.create(CreateMomentBody("FREE", null, "CONNECTIONS", 15)).isSuccess)
        assertEquals(1, repo.moments.value.size)
    }
    @Test fun `ending a Moment removes it even when the follow up refresh fails`() = runTest {
        val api = Api(listOf(moment())); val repo = MomentsRepository(api) { "alice" }
        repo.refresh(); api.afterMutation = { api.fail = true }
        assertTrue(repo.end("moment").isSuccess)
        assertTrue(repo.moments.value.isEmpty())
    }
    @Test fun `mutation response from an old account is discarded`() = runTest {
        var user = "alice"
        val api = Api(listOf(moment())); val repo = MomentsRepository(api) { user }
        api.afterMutation = { user = "bob" }
        assertTrue(repo.create(CreateMomentBody("FREE", null, "CONNECTIONS", 15)).isFailure)
        assertTrue(repo.moments.value.isEmpty())
    }
    @Test fun `countdown rounds up but never goes negative`() {
        assertEquals(1,remainingMinutes(1001,1000)); assertEquals(0,remainingMinutes(999,1000))
        assertEquals(2,remainingMinutes(61001,1000))
    }
    @Test fun `free activity has consumer label`() { assertEquals("Free for a quick call", moment().activity()) }

    // ------------------------------------------------------------- Phase 2

    @Test fun `entering a room applies its snapshot once`() = runTest {
        val api = Api(emptyList()); api.roomSnapshot = room()
        val state = MomentRoomState(api, "moment") { "bob" }
        assertTrue(state.enter().isSuccess)
        assertEquals(2, state.participants.value.size)
        assertEquals(1, state.messages.value.size)
        assertEquals("alice", state.moment.value?.creatorUserId)
    }
    @Test fun `a vanished Moment closes the room instead of erroring forever`() = runTest {
        val api = Api(emptyList()); api.failRooms = true
        val state = MomentRoomState(api, "moment") { "bob" }
        assertTrue(state.enter().isFailure)
        assertTrue(state.closed.value)
    }
    @Test fun `reconnect cannot duplicate messages - the server list is authoritative`() = runTest {
        val api = Api(emptyList())
        val state = MomentRoomState(api, "moment") { "bob" }
        api.roomSnapshot = room(messages = listOf(message()))
        state.enter()
        // The same message arrives twice from the socket while refetching.
        val frame = mapOf("momentId" to "moment", "message" to mapOf(
            "id" to "m1", "momentId" to "moment", "senderUserId" to "bob", "senderName" to "Bob",
            "body" to "hello", "createdAt" to Instant.now().toString(), "reactions" to emptyList<Any>()))
        state.onFrame("moment.message", frame)
        state.onFrame("moment.message", frame)
        assertEquals(1, state.messages.value.count { it.id == "m1" })
        state.refresh()
        assertEquals(listOf("m1"), state.messages.value.map { it.id })
    }
    @Test fun `frames for other rooms are ignored`() = runTest {
        val api = Api(emptyList())
        val state = MomentRoomState(api, "moment") { "bob" }
        state.enter()
        state.onFrame("moment.ended", mapOf("momentId" to "other-moment"))
        assertFalse(state.closed.value)
    }
    @Test fun `end and expiry frames close the room`() = runTest {
        val api = Api(emptyList())
        val state = MomentRoomState(api, "moment") { "bob" }
        state.enter()
        state.onFrame("moment.ended", mapOf("momentId" to "moment"))
        assertTrue(state.closed.value)
    }
    @Test fun `guests leave on close, the host does not`() = runTest {
        val api = Api(emptyList())
        val guest = MomentRoomState(api, "moment") { "bob" }
        guest.enter(); guest.leave()
        assertEquals(1, api.left)
        val host = MomentRoomState(api, "moment") { "alice" }
        host.enter(); host.leave()
        assertEquals(1, api.left)
    }
    @Test fun `empty sends never reach the server`() = runTest {
        val api = Api(emptyList())
        val state = MomentRoomState(api, "moment") { "bob" }
        state.enter()
        assertTrue(state.send("   ").isFailure)
        assertEquals(0, api.sent.size)
    }
    @Test fun `sending appends the authoritative message`() = runTest {
        val api = Api(emptyList())
        val state = MomentRoomState(api, "moment") { "bob" }
        state.enter()
        assertTrue(state.send("I'm in").isSuccess)
        assertEquals("I'm in", state.messages.value.last().body)
    }
    @Test fun `a knock is remembered for the session and forgotten on sign out`() = runTest {
        var user: String? = "bob"
        val api = Api(listOf(moment()))
        val repo = MomentsRepository(api) { user }
        repo.refresh()
        assertTrue(repo.knocked.value.isEmpty())
        assertTrue(repo.knock("moment").isSuccess)
        assertEquals(setOf("moment"), repo.knocked.value)
        user = null
        repo.refresh()
        assertTrue(repo.knocked.value.isEmpty())
    }
    @Test fun `a fresh list is not stale and an unloaded one is`() = runTest {
        val api = Api(listOf(moment()))
        val repo = MomentsRepository(api) { "bob" }
        assertTrue(repo.isStale(30_000))
        repo.refresh()
        assertFalse(repo.isStale(30_000))
        assertTrue(repo.isStale(-1))
    }
    @Test fun `knock and invite delegate to the api`() = runTest {
        val api = Api(emptyList())
        val repo = MomentsRepository(api) { "bob" }
        assertTrue(repo.knock("moment").isSuccess)
        assertEquals(1, api.knocksSent)
        assertTrue(repo.invite("moment", "carol").isSuccess)
        assertEquals(listOf("carol"), api.invited)
        assertTrue(repo.respondToKnock("moment", "bob", true).isSuccess)
        assertEquals(listOf("bob" to true), api.knockResponses)
    }
/**
     * A fake that seals by wrapping, so the test can tell sealed from plain
     * without libsignal — what matters here is which path the room took, not
     * the cryptography, which is E2eeEngine's own concern.
     */
    private class FakeCrypto(private val canSeal: Boolean) : RoomCrypto {
        var sealedFor: List<String> = emptyList()
        override suspend fun canSealFor(userIds: List<String>) = canSeal
        override suspend fun seal(recipients: List<String>, plaintext: String): List<MomentEnvelopeBody> {
            sealedFor = recipients
            return recipients.map { MomentEnvelopeBody("device-$it", "sealed:$plaintext", 3) }
        }
        override suspend fun open(
            senderUserId: String,
            senderDeviceId: String,
            ciphertext: String,
            type: Int,
        ) = ciphertext.removePrefix("sealed:")
    }

    @Test fun `seals a room message for everyone else in the room`() = runTest {
        val api = Api(listOf(moment()))
        api.roomSnapshot = room(messages = emptyList())
        val crypto = FakeCrypto(canSeal = true)
        val state = MomentRoomState(api, "moment", crypto) { "bob" }
        state.enter()
        state.send("hello")
        assertNull("a sealed message must not carry a readable body", api.lastSend?.body)
        assertEquals(listOf("alice"), crypto.sealedFor)
        assertEquals(listOf("sealed:hello"), api.lastSend?.envelopes?.map { it.ciphertext })
        // The sender sees what they said, though the server sent nothing back.
        assertEquals("hello", state.messages.value.last().body)
        assertTrue(state.encrypted.value)
    }

    @Test fun `sends in the clear when someone in the room has no keys`() = runTest {
        val api = Api(listOf(moment()))
        api.roomSnapshot = room(messages = emptyList())
        val state = MomentRoomState(api, "moment", FakeCrypto(canSeal = false)) { "bob" }
        state.enter()
        state.send("hello")
        assertEquals("hello", api.lastSend?.body)
        assertNull(api.lastSend?.envelopes)
        assertFalse(state.encrypted.value)
    }

    @Test fun `opens a sealed message that arrives on the socket`() = runTest {
        val api = Api(listOf(moment()))
        api.roomSnapshot = room(messages = emptyList())
        val state = MomentRoomState(api, "moment", FakeCrypto(canSeal = true)) { "bob" }
        state.enter()
        state.onFrame("moment.message", mapOf(
            "momentId" to "moment",
            "message" to mapOf(
                "id" to "incoming", "momentId" to "moment", "senderUserId" to "alice",
                "senderName" to "Alice", "body" to null, "sealed" to true,
                "senderDeviceId" to "alice-device", "createdAt" to Instant.now().toString(),
                "envelope" to mapOf("ciphertext" to "sealed:hi there", "type" to 3),
            ),
        ))
        assertEquals("hi there", state.messages.value.single { it.id == "incoming" }.body)
    }

    @Test fun `a sealed message this device cannot open keeps its place`() = runTest {
        val api = Api(listOf(moment()))
        api.roomSnapshot = room(messages = listOf(MomentMessageDto(
            "old", "moment", "alice", "Alice", null, Instant.now().toString(),
            sealed = true, senderDeviceId = null, envelope = null,
        )))
        val state = MomentRoomState(api, "moment", FakeCrypto(canSeal = true)) { "bob" }
        state.enter()
        assertEquals(SEALED_UNREADABLE, state.messages.value.single { it.id == "old" }.body)
    }

    @Test fun `reacting to a Moment and changing its audience reach the server`() = runTest {
        val api = Api(listOf(moment()))
        val repo = MomentsRepository(api) { "bob" }
        repo.cheer("moment", "🔥")
        repo.cheer("moment", null)
        assertEquals(listOf("moment" to "🔥", "moment" to null), api.cheers)
        repo.setVisibility("moment", "CONTACTS")
        assertEquals(listOf("moment" to "CONTACTS"), api.visibilities)
        assertEquals("CONTACTS", repo.moments.value.single().visibility)
    }
// ----------------------------------------------------- keeping something

    @Test fun `an ending offers what the Moment had, and how long it was`() = runTest {
        val api = Api(listOf(moment()))
        val repo = MomentsRepository(api) { "bob" }
        val ending = repo.ending("moment")!!
        assertEquals(listOf("Natasha"), ending.withPeople)
        assertEquals(4_320_000L, ending.togetherMs)
        assertEquals(listOf("Cooking together", "Which rice?"), ending.offers.map { it.title })
    }

    @Test fun `keeping nothing sends nothing at all`() = runTest {
        val api = Api(listOf(moment()))
        val repo = MomentsRepository(api) { "bob" }
        assertTrue(repo.keep("moment", emptyList()).isSuccess)
        // Not an empty request — no request. Keeping nothing is the default,
        // and the default must not need the server's permission.
        assertEquals(emptyList<List<String>>(), api.keptCalls)
    }

    @Test fun `keeping sends exactly what was chosen`() = runTest {
        val api = Api(listOf(moment()))
        val repo = MomentsRepository(api) { "bob" }
        assertTrue(repo.keep("moment", listOf("o2")).isSuccess)
        assertEquals(listOf(listOf("o2")), api.keptCalls)
    }

    @Test fun `an ending that is gone is not an error, it is simply over`() = runTest {
        val api = Api(listOf(moment()))
        api.endingFails = true
        val repo = MomentsRepository(api) { "bob" }
        assertNull(repo.ending("moment"))
    }
}

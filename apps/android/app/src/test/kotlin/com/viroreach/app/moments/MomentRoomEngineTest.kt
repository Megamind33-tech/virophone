package com.viroreach.app.moments

import com.viroreach.app.moments.engine.MomentIntent
import com.viroreach.app.moments.engine.MomentCapabilities
import com.viroreach.core.network.*
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/**
 * The room engine on the phone: what the room is, and never moving it
 * backwards when frames arrive late, twice, or out of order.
 */
class MomentRoomEngineTest {
    private fun shape(revision: Int, primary: String = "PRESENCE", scene: String = "KITCHEN", momentId: String = "moment") =
        MomentRuntimeDto(momentId, revision, "COOK", primary, emptyList(), scene)

    private fun frame(state: MomentRuntimeDto): Map<String, Any?> = mapOf(
        "momentId" to state.momentId,
        "state" to mapOf(
            "momentId" to state.momentId,
            "revision" to state.revision,
            "intent" to state.intent,
            "primary" to state.primary,
            "secondary" to state.secondary,
            "scene" to state.scene,
            "scenePinned" to state.scenePinned,
        ),
    )

    private fun roomWith(state: MomentRuntimeDto?) = MomentRoomDto(
        MomentDto("moment", "alice", "FREE", null, "CONNECTIONS", "Alice", null,
            Instant.now().toString(), Instant.ofEpochMilli(System.currentTimeMillis() + 60_000).toString(), true, 2),
        Instant.now().toString(),
        listOf(
            MomentParticipantDto("alice", "Alice", true, Instant.now().toString()),
            MomentParticipantDto("bob", "Bob", false, Instant.now().toString()),
        ),
        emptyList(),
        state,
    )

    /** The smallest server that serves a room and a room shape. */
    private class Server(var room: MomentRoomDto) : ViroMomentsApi by Unused {
        var shape = room.state!!
        val changes = mutableListOf<MomentRoomChangeBody>()
        var refuse = false
        override suspend fun join(id: String) = room.copy(state = shape)
        override suspend fun room(id: String) = room.copy(state = shape)
        override suspend fun changeRoom(id: String, body: MomentRoomChangeBody): MomentRuntimeDto {
            if (refuse) throw retrofit2.HttpException(
                retrofit2.Response.error<Any?>(400, "".toResponseBody("text/plain".toMediaTypeOrNull())),
            )
            changes += body
            shape = shape.copy(
                revision = shape.revision + 1,
                intent = body.intent ?: shape.intent,
                primary = if (body.intent == "STAY") "QUIET" else shape.primary,
                scene = if (body.intent == "STAY") "QUIET" else shape.scene,
            )
            return shape
        }
    }

    @Test fun `entering the room takes its current shape`() = runTest {
        val server = Server(roomWith(shape(3, primary = "QUIET", scene = "QUIET")))
        val room = MomentRoomState(server, "moment") { "bob" }
        room.enter()
        assertEquals(3, room.runtime.value?.revision)
        assertEquals("QUIET", room.runtime.value?.primary)
    }

    @Test fun `a newer frame changes the room and an older one never takes it back`() = runTest {
        val server = Server(roomWith(shape(1)))
        val room = MomentRoomState(server, "moment") { "bob" }
        room.enter()

        room.onFrame("moment.state", frame(shape(3, primary = "QUIET", scene = "QUIET")))
        assertEquals("QUIET", room.runtime.value?.primary)

        // Revision 2 arrives late, after 3: it describes a room that no longer exists.
        room.onFrame("moment.state", frame(shape(2, primary = "PRESENCE", scene = "CINEMA")))
        assertEquals(3, room.runtime.value?.revision)
        assertEquals("QUIET", room.runtime.value?.primary)

        // The same frame twice changes nothing either.
        room.onFrame("moment.state", frame(shape(3, primary = "QUIET", scene = "QUIET")))
        assertEquals(3, room.runtime.value?.revision)
    }

    @Test fun `a frame for another Moment is not this room's business`() = runTest {
        val server = Server(roomWith(shape(1)))
        val room = MomentRoomState(server, "moment") { "bob" }
        room.enter()
        room.onFrame("moment.state", frame(shape(9, primary = "QUIET", momentId = "elsewhere")))
        assertEquals(1, room.runtime.value?.revision)
    }

    @Test fun `a phone that was away lands in what the room became`() = runTest {
        val server = Server(roomWith(shape(1)))
        val room = MomentRoomState(server, "moment") { "bob" }
        room.enter()
        // The room moved on while no frames reached this phone.
        server.shape = shape(5, primary = "QUIET", scene = "QUIET")
        room.refresh()
        assertEquals(5, room.runtime.value?.revision)
        assertEquals("QUIET", room.runtime.value?.primary)
    }

    @Test fun `changing the room shows at once and ignores its own echo`() = runTest {
        val server = Server(roomWith(shape(1)))
        val room = MomentRoomState(server, "moment") { "bob" }
        room.enter()
        assertTrue(room.change(MomentRoomChangeBody(op = "TRANSFORM", intent = "STAY")).isSuccess)
        assertEquals(2, room.runtime.value?.revision)
        assertEquals("QUIET", room.runtime.value?.primary)
        assertEquals(listOf("TRANSFORM"), server.changes.map { it.op })
        // The server's frame for the same change arrives afterwards; nothing moves.
        room.onFrame("moment.state", frame(server.shape))
        assertEquals(2, room.runtime.value?.revision)
    }

    @Test fun `a refused change leaves the room as it was and says why`() = runTest {
        val server = Server(roomWith(shape(1)))
        val room = MomentRoomState(server, "moment") { "bob" }
        room.enter()
        server.refuse = true
        val result = room.change(MomentRoomChangeBody(op = "ADD", module = "PRESENCE"))
        assertTrue(result.isFailure)
        assertEquals("That can't be done in this room.", result.exceptionOrNull()?.message)
        assertEquals(1, room.runtime.value?.revision)
    }

    @Test fun `being taken out of the room closes it on this phone, someone else leaving does not`() = runTest {
        val server = Server(roomWith(shape(1)))
        val room = MomentRoomState(server, "moment") { "bob" }
        room.enter()
        room.onFrame("moment.left", mapOf("momentId" to "moment", "userId" to "alice"))
        assertFalse(room.closed.value)
        room.onFrame("moment.left", mapOf("momentId" to "moment", "userId" to "bob"))
        assertTrue(room.closed.value)
    }

    @Test fun `only activities this build can deliver are offered`() {
        val offered = MomentIntent.offered().map { it.key }
        // Everything but play: a question is not a game, so Play waits for one.
        listOf("BE", "TALK", "WATCH", "LISTEN", "COOK", "WALK", "CHOOSE", "LEARN", "CELEBRATE", "REMEMBER", "STAY").forEach { assertTrue(it, it in offered) }
        assertFalse("PLAY" in offered)
        offered.forEach { key ->
            val intent = MomentIntent.of(key)!!
            assertTrue("$key needs something missing", intent.needs.all { MomentCapabilities.has(it) })
        }
    }

    private object Unused : ViroMomentsApi {
        override suspend fun now() = error("unused")
        override suspend fun invitations() = error("unused")
        override suspend fun declineInvitation(id: String) = error("unused")
        override suspend fun get(id: String) = error("unused")
        override suspend fun create(body: CreateMomentBody) = error("unused")
        override suspend fun extend(id: String, body: ExtendMomentBody) = error("unused")
        override suspend fun end(id: String) = error("unused")
        override suspend fun setVisibility(id: String, body: MomentVisibilityBody) = error("unused")
        override suspend fun cheer(id: String, body: MomentReactBody) = error("unused")
        override suspend fun join(id: String) = error("unused")
        override suspend fun leave(id: String) {}
        override suspend fun room(id: String) = error("unused")
        override suspend fun presence(id: String) = error("unused")
        override suspend fun timer(id: String, body: MomentTimerBody) = error("unused")
        override suspend fun choice(id: String, body: MomentChoiceBody) = error("unused")
        override suspend fun touch(id: String, body: MomentTouchBody) {}
        override suspend fun shareMedia(id: String, file: okhttp3.MultipartBody.Part, title: okhttp3.RequestBody?, durationMs: okhttp3.RequestBody?) = error("unused")
        override suspend fun listMedia(id: String) = MomentMediaListDto(emptyList())
        override suspend fun unshareMedia(id: String, mediaId: String) {}
        override suspend fun streamUrl(id: String, mediaId: String) = error("unused")
        override suspend fun playback(id: String, body: MomentPlaybackBody) = error("unused")
        override suspend fun changeRoom(id: String, body: MomentRoomChangeBody) = error("unused")
        override suspend fun sendMessage(id: String, body: SendMomentMessageBody) = error("unused")
        override suspend fun react(id: String, messageId: String, body: MomentReactBody) = error("unused")
        override suspend fun knock(id: String) = error("unused")
        override suspend fun knocks(id: String) = error("unused")
        override suspend fun respondToKnock(id: String, knockerId: String, body: KnockResponseBody) = error("unused")
        override suspend fun invite(id: String, body: InviteBody) = error("unused")
        override suspend fun ending(id: String) = error("unused")
        override suspend fun keep(id: String, body: KeepBody) = error("unused")
        override suspend fun keepsakes() = error("unused")
        override suspend fun forgetKeepsake(id: String) = error("unused")
    }
}

package com.viroreach.app.moments

import com.viroreach.app.moments.engine.timerLeftMs
import com.viroreach.core.network.*
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** The timer, the question and touches, as one phone in the room sees them. */
class TogetherToolsTest {
    private var phoneNow = 500_000L
    private val skew = 2_000L

    private inner class Server : ViroMomentsApi by Unused {
        var status: Int? = null
        val touches = mutableListOf<MomentTouchBody>()
        private fun room() = MomentRoomDto(
            MomentDto("m", "natasha", "FREE", null, "CONNECTIONS", "Natasha", null,
                Instant.now().toString(), Instant.now().plusSeconds(3600).toString(), true, 2),
            Instant.now().toString(),
            listOf(MomentParticipantDto("natasha", "Natasha", true, Instant.now().toString()),
                MomentParticipantDto("mosty", "Mosty", false, Instant.now().toString())),
            emptyList(), MomentRuntimeDto("m", 2, "COOK", "PRESENCE", listOf("TIMER"), "KITCHEN"),
            serverNow = phoneNow + skew,
            timer = MomentTimerDto("m", 4, "RUNNING", "Rice", endsAt = phoneNow + skew + 90_000, durationMs = 600_000),
        )
        override suspend fun join(id: String) = room()
        override suspend fun room(id: String) = room()
        override suspend fun timer(id: String, body: MomentTimerBody): MomentTimerResultDto {
            fail()
            return MomentTimerResultDto(MomentTimerDto("m", 5, "PAUSED", "Rice", remainingMs = 88_000), phoneNow + skew)
        }
        override suspend fun touch(id: String, body: MomentTouchBody) { fail(); touches += body }
        private fun fail() {
            status?.let { throw retrofit2.HttpException(retrofit2.Response.error<Any?>(it, "".toResponseBody("text/plain".toMediaTypeOrNull()))) }
        }
    }

    private suspend fun entered(): Pair<Server, MomentRoomState> {
        val server = Server()
        val room = MomentRoomState(server, "m", clock = { phoneNow }) { "mosty" }
        room.enter()
        return server to room
    }

    @Test fun `a phone that comes into the kitchen counts down the same timer, on the server's clock`() = runTest {
        val (_, room) = entered()
        val t = room.timer.value!!
        assertEquals("Rice", t.label)
        assertEquals(90_000L, timerLeftMs(t, room.serverNow()))
        phoneNow += 30_000
        assertEquals(60_000L, timerLeftMs(t, room.serverNow()))
        assertEquals(88_000L, timerLeftMs(t.copy(status = "PAUSED", remainingMs = 88_000, endsAt = null), room.serverNow()))
        assertNull(timerLeftMs(t.copy(status = "NONE"), room.serverNow()))
    }

    @Test fun `a newer timer frame wins and an older one never takes it back`() = runTest {
        val (_, room) = entered()
        room.onFrame("moment.timer", mapOf("momentId" to "m", "timer" to mapOf(
            "momentId" to "m", "revision" to 6, "status" to "PAUSED", "label" to "Rice", "remainingMs" to 70_000)))
        assertEquals("PAUSED", room.timer.value?.status)
        room.onFrame("moment.timer", mapOf("momentId" to "m", "timer" to mapOf(
            "momentId" to "m", "revision" to 5, "status" to "RUNNING", "endsAt" to 1)))
        assertEquals(6, room.timer.value?.revision)
    }

    @Test fun `pausing the timer applies the answer at once`() = runTest {
        val (_, room) = entered()
        assertTrue(room.timer(MomentTimerBody("PAUSE")).isSuccess)
        assertEquals("PAUSED", room.timer.value?.status)
        assertEquals(88_000L, room.timer.value?.remainingMs)
    }

    @Test fun `the question arrives whole with its options, who picked what and who asked`() = runTest {
        val (_, room) = entered()
        room.onFrame("moment.choice", mapOf("momentId" to "m", "choice" to mapOf(
            "momentId" to "m", "revision" to 2, "status" to "OPEN", "question" to "Which dress?",
            "options" to listOf(mapOf("id" to "1", "text" to "Blue"), mapOf("id" to "2", "text" to "Green")),
            "picks" to mapOf("mosty" to "2"), "askedBy" to "natasha")))
        val c = room.choice.value!!
        assertEquals("Which dress?", c.question)
        assertEquals(listOf("Blue", "Green"), c.options?.map { it.text })
        assertEquals(mapOf("mosty" to "2"), c.picks)
        assertEquals("natasha", c.askedBy)
    }

    @Test fun `a touch from someone else is felt once, but this phone's own echo is not`() = runTest {
        val (_, room) = entered()
        val felt = async { room.touches.first() }
        // A touch is felt once and never kept, so nothing is replayed to a
        // collector that arrives late — which is right for the room and means
        // this test has to be collecting before the frames land.
        room.touches.subscriptionCount.first { it > 0 }
        room.onFrame("moment.touch", mapOf("momentId" to "m", "from" to "mosty", "fromName" to "Mosty", "kind" to "HUG"))
        room.onFrame("moment.touch", mapOf("momentId" to "m", "from" to "natasha", "fromName" to "Natasha Banda", "kind" to "HUG", "to" to "mosty"))
        val t = felt.await()
        assertEquals("natasha", t.from)
        assertEquals("Natasha Banda", t.fromName)
        assertEquals("mosty", t.to)
    }

    @Test fun `too many touches are refused in plain words`() = runTest {
        val (server, room) = entered()
        assertTrue(room.touch("HEART").isSuccess)
        server.status = 429
        assertEquals("Slow down a little.", room.touch("TAP").exceptionOrNull()?.message)
        assertEquals(listOf("HEART"), server.touches.map { it.kind })
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
        override suspend fun shareMedia(id: String, file: okhttp3.MultipartBody.Part, title: okhttp3.RequestBody?, durationMs: okhttp3.RequestBody?) = error("unused")
        override suspend fun listMedia(id: String) = MomentMediaListDto(emptyList())
        override suspend fun unshareMedia(id: String, mediaId: String) {}
        override suspend fun streamUrl(id: String, mediaId: String) = error("unused")
        override suspend fun playback(id: String, body: MomentPlaybackBody) = error("unused")
        override suspend fun timer(id: String, body: MomentTimerBody) = error("unused")
        override suspend fun choice(id: String, body: MomentChoiceBody) = error("unused")
        override suspend fun touch(id: String, body: MomentTouchBody) {}
        override suspend fun presence(id: String) = error("unused")
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

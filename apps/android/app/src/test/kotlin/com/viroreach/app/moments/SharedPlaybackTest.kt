package com.viroreach.app.moments

import com.viroreach.app.moments.engine.Correction
import com.viroreach.app.moments.engine.LocalPlayer
import com.viroreach.app.moments.engine.PlaybackMath
import com.viroreach.app.moments.engine.SharedPlayback
import com.viroreach.core.network.*
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/**
 * Watching together on one phone: it follows the room's single playback
 * record, on the server's clock, and corrects drift without anyone noticing.
 */
class SharedPlaybackTest {
    /** A player that moves only when the test says time has passed. */
    private class FakePlayer : LocalPlayer {
        override var positionMs = 0L
        override var playing = false
        override var buffering = false
        var loaded: String? = null
        var loads = 0
        var speed = 1f
        val seeks = mutableListOf<Long>()
        override fun load(url: String, video: Boolean) { loaded = url; loads++; positionMs = 0; playing = false }
        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun seekTo(positionMs: Long) { this.positionMs = positionMs; seeks += positionMs }
        override fun setRate(rate: Float) { speed = rate }
        override fun clear() { loaded = null; playing = false; positionMs = 0 }
        fun advance(ms: Long) { if (playing && !buffering) positionMs += (ms * speed).toLong() }
    }

    /** The server's clock runs 5 s ahead of this phone's: the phone must use the server's. */
    private val skew = 5_000L
    private var phoneNow = 1_000_000L
    private val serverNow get() = phoneNow + skew

    private fun pb(rev: Int, status: String, pos: Long, anchor: Long = serverNow, media: String? = "film", by: String = "natasha") =
        MomentPlaybackDto("m", rev, media, if (media == null) null else "VIDEO", if (media == null) null else "Our holiday",
            if (media == null) null else 600_000L, status, pos, anchor, 1.0, by)

    private inner class Server : ViroMomentsApi by Unused {
        var current = pb(0, "IDLE", 0, media = null)
        var urls = 0
        var refuseUrl = false
        val sent = mutableListOf<MomentPlaybackBody>()
        private val room get() = MomentRoomDto(
            MomentDto("m", "natasha", "WATCHING", null, "CONNECTIONS", "Natasha", null,
                Instant.now().toString(), Instant.now().plusSeconds(3600).toString(), true, 2),
            Instant.now().toString(),
            listOf(MomentParticipantDto("natasha", "Natasha", true, Instant.now().toString()),
                MomentParticipantDto("mosty", "Mosty", false, Instant.now().toString())),
            emptyList(), MomentRuntimeDto("m", 1, "WATCH", "VIDEO", emptyList(), "CINEMA"),
            playback = current, serverNow = serverNow, media = emptyList(),
        )
        override suspend fun join(id: String) = room
        override suspend fun room(id: String) = room
        override suspend fun streamUrl(id: String, mediaId: String): MomentStreamDto {
            if (refuseUrl) throw retrofit2.HttpException(retrofit2.Response.error<Any?>(404, "".toResponseBody("text/plain".toMediaTypeOrNull())))
            urls++
            return MomentStreamDto("/api/v1/moment-media/$mediaId?s=$urls")
        }
        override suspend fun playback(id: String, body: MomentPlaybackBody): MomentPlaybackResultDto {
            sent += body
            current = when (body.op) {
                "PAUSE" -> pb(current.revision + 1, "PAUSED", body.positionMs ?: 0, by = "mosty")
                "PLAY" -> pb(current.revision + 1, "PLAYING", body.positionMs ?: current.positionMs, by = "mosty")
                "SEEK" -> pb(current.revision + 1, current.status, body.positionMs ?: 0, by = "mosty")
                "LOAD" -> pb(current.revision + 1, "PAUSED", 0, media = body.mediaId, by = "mosty")
                else -> pb(current.revision + 1, "IDLE", 0, media = null, by = "mosty")
            }
            return MomentPlaybackResultDto(current, serverNow)
        }
    }

    /** The server of the test in progress, which every announced change also reaches. */
    private var server: Server? = null

    private suspend fun setup(): Triple<Server, MomentRoomState, Pair<FakePlayer, SharedPlayback>> {
        val server = Server().also { this.server = it }
        val room = MomentRoomState(server, "m", clock = { phoneNow }) { "mosty" }
        room.enter()
        val player = FakePlayer()
        val shared = SharedPlayback(room, player) { "https://api.viro$it" }
        return Triple(server, room, player to shared)
    }

    /** The server announcing a change, the way the socket delivers it. */
    private suspend fun MomentRoomState.hear(p: MomentPlaybackDto) = server!!.let { it.current = p }.let { onFrame("moment.playback", mapOf(
        "momentId" to "m",
        "playback" to mapOf("momentId" to p.momentId, "revision" to p.revision, "mediaId" to p.mediaId, "kind" to p.kind,
            "title" to p.title, "durationMs" to p.durationMs, "status" to p.status, "positionMs" to p.positionMs,
            "anchorAt" to p.anchorAt, "rate" to p.rate, "updatedBy" to p.updatedBy),
        "serverNow" to serverNow,
    )) }

    @Test fun `the drift rules`() {
        val p = pb(3, "PLAYING", 10_000, anchor = 50_000)
        assertEquals(15_000, PlaybackMath.expectedAt(p, 55_000))
        assertEquals(10_000, PlaybackMath.expectedAt(p.copy(status = "PAUSED"), 99_000))
        assertEquals(600_000, PlaybackMath.expectedAt(p, 99_000_000)) // never past the end
        assertEquals(Correction.None, PlaybackMath.correction(10_000, 10_200, 1f))
        assertEquals(Correction.Rate(0.95f), PlaybackMath.correction(10_000, 10_800, 1f))
        assertEquals(Correction.Rate(1.05f), PlaybackMath.correction(10_000, 9_200, 1f))
        assertEquals(Correction.Seek(10_000), PlaybackMath.correction(10_000, 12_000, 1f))
        assertEquals(Correction.Rate(1f), PlaybackMath.correction(10_000, 10_050, 0.95f))
        assertEquals("overshot while catching up", Correction.Rate(1f), PlaybackMath.correction(10_000, 10_200, 1.05f))
    }

    @Test fun `what the room loads is loaded here, paused at the start, from a fresh address`() = runTest {
        val (_, room, pair) = setup()
        val (player, shared) = pair
        room.hear(pb(1, "PAUSED", 0))
        shared.follow(room.playback.value)
        assertEquals("https://api.viro/api/v1/moment-media/film?s=1", player.loaded)
        assertFalse(player.playing)
        assertEquals("Our holiday", shared.view.value.title)
    }

    @Test fun `play follows the server's clock, not this phone's`() = runTest {
        val (_, room, pair) = setup()
        val (player, shared) = pair
        room.hear(pb(1, "PAUSED", 0))
        shared.follow(room.playback.value)
        // Natasha pressed play two seconds ago by the server's clock.
        room.hear(pb(2, "PLAYING", 0, anchor = serverNow - 2_000))
        shared.follow(room.playback.value)
        assertTrue(player.playing)
        assertEquals(2_000, player.seeks.last())
    }

    @Test fun `a phone running ahead is slowed, one running far behind jumps, and it settles back to normal speed`() = runTest {
        val (_, room, pair) = setup()
        val (player, shared) = pair
        room.hear(pb(1, "PLAYING", 0, anchor = serverNow))
        shared.follow(room.playback.value)
        player.positionMs += 800 // decoder raced ahead
        shared.tick()
        assertEquals(0.95f, player.speed)
        // At 0.95 it loses 50 ms a second: 15 s later it is back in step.
        repeat(15) { phoneNow += 1_000; player.advance(1_000); shared.tick() }
        assertEquals(1f, player.speed)
        // A stall that lost three seconds is a jump, not a crawl.
        player.positionMs -= 3_000
        shared.tick()
        assertEquals(PlaybackMath.expectedAt(room.playback.value!!, room.serverNow()), player.seeks.last())
    }

    @Test fun `when he pauses, it stops where he saw it, and says who`() = runTest {
        val (server, room, pair) = setup()
        val (player, shared) = pair
        room.hear(pb(1, "PLAYING", 0, anchor = serverNow))
        shared.follow(room.playback.value)
        phoneNow += 12_345; player.advance(12_345)
        assertTrue(shared.pause().isSuccess)
        assertEquals(12_345L, server.sent.last().positionMs)
        assertFalse(player.playing)
        assertEquals("mosty", shared.view.value.lastChangeBy)
        // The echo of his own pause changes nothing.
        room.hear(server.current)
        shared.follow(room.playback.value)
        assertFalse(player.playing)
        assertEquals(12_345, player.positionMs)
    }

    @Test fun `a phone that was away comes back to where the film is now`() = runTest {
        val (server, room, pair) = setup()
        val (player, shared) = pair
        room.hear(pb(1, "PLAYING", 0, anchor = serverNow))
        shared.follow(room.playback.value)
        // Offline for a while; the room paused, seeked and played again without this phone.
        phoneNow += 90_000
        server.current = pb(5, "PLAYING", 40_000, anchor = serverNow - 3_000)
        room.refresh()
        shared.tick()
        assertEquals(5, room.playback.value?.revision)
        assertTrue(player.playing)
        assertEquals(43_000, player.seeks.last())
    }

    @Test fun `only this phone waits while it buffers, nobody else is paused for it`() = runTest {
        val (server, room, pair) = setup()
        val (player, shared) = pair
        room.hear(pb(1, "PLAYING", 0, anchor = serverNow))
        shared.follow(room.playback.value)
        player.buffering = true
        phoneNow += 4_000
        shared.tick()
        assertTrue("no correction mid-buffer", player.seeks.size == 1)
        assertTrue("nothing sent to the room", server.sent.isEmpty())
        player.buffering = false
        shared.tick()
        assertEquals(4_000, player.seeks.last())
    }

    @Test fun `off screen this phone goes quiet, and on its return it rejoins the film`() = runTest {
        val (server, room, pair) = setup()
        val (player, shared) = pair
        room.hear(pb(1, "PLAYING", 0, anchor = serverNow))
        shared.follow(room.playback.value)
        shared.pauseHere()
        assertFalse(player.playing)
        phoneNow += 30_000
        shared.tick()
        assertFalse("stays quiet while away", player.playing)
        assertTrue("the room was not paused", server.sent.isEmpty())
        shared.resumeHere()
        shared.tick()
        assertTrue(player.playing)
        assertEquals(30_000, player.seeks.last())
    }

    @Test fun `an expired address is renewed, and a file that won't play says so instead of retrying forever`() = runTest {
        val (server, room, pair) = setup()
        val (player, shared) = pair
        room.hear(pb(1, "PLAYING", 0, anchor = serverNow))
        shared.follow(room.playback.value)
        repeat(2) {
            shared.playerFailed()
            shared.tick()
        }
        assertEquals(3, player.loads)
        assertEquals("https://api.viro/api/v1/moment-media/film?s=3", player.loaded)
        shared.playerFailed()
        shared.tick()
        assertEquals(3, player.loads)
        assertEquals("This can't be played on your phone.", shared.view.value.error)
        assertEquals(3, server.urls)
    }

    @Test fun `something no longer there is said once, not asked for every second`() = runTest {
        val (server, room, pair) = setup()
        val (_, shared) = pair
        server.refuseUrl = true
        room.hear(pb(1, "PAUSED", 0))
        shared.follow(room.playback.value)
        repeat(5) { shared.tick() }
        assertEquals("Couldn't open this on your phone.", shared.view.value.error)
    }

    @Test fun `stopping clears the player`() = runTest {
        val (_, room, pair) = setup()
        val (player, shared) = pair
        room.hear(pb(1, "PLAYING", 0, anchor = serverNow))
        shared.follow(room.playback.value)
        room.hear(pb(2, "IDLE", 0, media = null))
        shared.follow(room.playback.value)
        assertNull(player.loaded)
        assertNull(shared.view.value.mediaId)
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
        override suspend fun presence(id: String) = error("unused")
        override suspend fun changeRoom(id: String, body: MomentRoomChangeBody) = error("unused")
        override suspend fun sendMessage(id: String, body: SendMomentMessageBody) = error("unused")
        override suspend fun react(id: String, messageId: String, body: MomentReactBody) = error("unused")
        override suspend fun knock(id: String) = error("unused")
        override suspend fun knocks(id: String) = error("unused")
        override suspend fun respondToKnock(id: String, knockerId: String, body: KnockResponseBody) = error("unused")
        override suspend fun invite(id: String, body: InviteBody) = error("unused")
    }
}

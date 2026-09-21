package com.viroreach.app.moments

import com.viroreach.app.moments.engine.MomentLive
import com.viroreach.app.moments.engine.WeakNetworkPolicy
import com.viroreach.core.network.*
import com.viroreach.voice.webrtc.MomentMediaTransport
import com.viroreach.voice.webrtc.PresenceLink
import com.viroreach.voice.webrtc.PresenceQuality
import com.viroreach.voice.webrtc.PresenceSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/**
 * The live part of a room: it joins without publishing, fails soft into
 * presence, and only ever turns the camera or microphone *off* by itself.
 */
class MomentLiveTest {
    /** Media that does what it is told and remembers it. */
    private class FakeMedia : MomentMediaTransport {
        override val state = MutableStateFlow(PresenceSnapshot())
        val connections = mutableListOf<Pair<String, String>>()
        var cameraCalls = mutableListOf<Boolean>()
        override suspend fun connect(url: String, token: String) {
            connections += url to token
            state.value = PresenceSnapshot(link = PresenceLink.LIVE)
        }
        override suspend fun setCamera(on: Boolean): Boolean {
            cameraCalls += on
            state.value = state.value.copy(cameraOn = on)
            return true
        }
        override suspend fun setMicrophone(on: Boolean): Boolean {
            state.value = state.value.copy(micOn = on)
            return true
        }
        override fun flipCamera() {}
        override suspend fun disconnect() { state.value = PresenceSnapshot() }
        fun quality(q: PresenceQuality) { state.value = state.value.copy(quality = q) }
    }

    private class Server : ViroMomentsApi by Unused {
        var status: Int? = null
        var passes = 0
        override suspend fun presence(id: String): MomentPresenceDto {
            status?.let { code ->
                throw retrofit2.HttpException(retrofit2.Response.error<Any?>(code, "".toResponseBody("text/plain".toMediaTypeOrNull())))
            }
            passes++
            return MomentPresenceDto("wss://media.viro", "pass-$passes", "viro-moment-$id")
        }
    }

    private var now = 0L
    private fun live(server: Server = Server(), media: FakeMedia = FakeMedia()) =
        Triple(MomentLive(server, "m1", media) { now }, server, media)

    @Test fun `joining the room's media turns nothing on`() = runTest {
        val (live, _, media) = live()
        assertTrue(live.start())
        assertEquals(listOf("wss://media.viro" to "pass-1"), media.connections)
        assertFalse(media.state.value.cameraOn)
        assertFalse(media.state.value.micOn)
        assertTrue("nothing asked the camera to start", media.cameraCalls.none { it })
    }

    @Test fun `a server without live media leaves a room that still works, and says so`() = runTest {
        val (live, server, media) = live()
        server.status = 503
        assertFalse(live.start())
        assertEquals("Live video isn't available right now. You're still here together.", live.unavailable.value)
        assertTrue(media.connections.isEmpty())
        // Trying again later, once it is back, simply works.
        server.status = null
        assertTrue(live.start())
        assertNull(live.unavailable.value)
    }

    @Test fun `someone no longer in the room gets no media and no false error`() = runTest {
        val (live, server, _) = live()
        server.status = 403
        assertFalse(live.start())
        assertNull(live.unavailable.value)
    }

    @Test fun `a connection that stays weak costs the camera, and getting better never brings it back`() = runTest {
        val (live, _, media) = live()
        live.start()
        live.setMicrophone(true)
        live.setCamera(true)
        media.quality(PresenceQuality.WEAK)
        now = 0; live.tick()
        now = 3_000; live.tick()
        assertTrue("a dip is not enough", media.state.value.cameraOn)
        now = 6_000; live.tick()
        assertFalse(media.state.value.cameraOn)
        assertEquals("Your connection is weak, so your camera is off. Your voice carries on.", live.notice.value)
        assertTrue("the voice stays", media.state.value.micOn)

        media.quality(PresenceQuality.GOOD)
        now = 60_000; live.tick()
        assertFalse("never switched back on by itself", media.state.value.cameraOn)
        assertEquals(listOf(true, false), media.cameraCalls)
    }

    @Test fun `a short dip that recovers leaves the camera alone`() = runTest {
        val (live, _, media) = live()
        live.start(); live.setCamera(true)
        media.quality(PresenceQuality.WEAK); now = 0; live.tick()
        media.quality(PresenceQuality.GOOD); now = 4_000; live.tick()
        media.quality(PresenceQuality.WEAK); now = 8_000; live.tick()
        now = 12_000; live.tick()
        assertTrue(media.state.value.cameraOn)
    }

    @Test fun `a quiet room is not filmed`() = runTest {
        val (live, _, media) = live()
        live.start(); live.setCamera(true)
        live.onRoomShape("PRESENCE")
        assertTrue(media.state.value.cameraOn)
        live.onRoomShape("QUIET")
        assertFalse(media.state.value.cameraOn)
        assertEquals("Camera off for the quiet.", live.notice.value)
    }

    @Test fun `leaving the screen turns the camera and microphone off and says which`() = runTest {
        val (live, _, media) = live()
        live.start(); live.setCamera(true); live.setMicrophone(true)
        live.onBackground()
        assertFalse(media.state.value.cameraOn)
        assertFalse(media.state.value.micOn)
        assertEquals("Your camera and microphone went off while Viro was in the background.", live.notice.value)
    }

    @Test fun `with nothing on, going to the background says nothing`() = runTest {
        val (live, _, _) = live()
        live.start()
        live.onBackground()
        assertNull(live.notice.value)
    }

    @Test fun `a link that gave up is retried, and comes back with nothing on`() = runTest {
        val (live, server, media) = live()
        live.start(); live.setCamera(true)
        media.state.value = PresenceSnapshot(link = PresenceLink.FAILED)
        now = 20_000; live.tick()
        assertEquals(2, server.passes)
        assertEquals(PresenceLink.LIVE, media.state.value.link)
        assertFalse(media.state.value.cameraOn)
        assertEquals("Back. Your camera and microphone are off until you turn them on.", live.notice.value)
        // Not hammered: the next try waits.
        media.state.value = PresenceSnapshot(link = PresenceLink.FAILED)
        now = 25_000; live.tick()
        assertEquals(2, server.passes)
    }

    @Test fun `once the room is over nothing reconnects`() = runTest {
        val (live, server, media) = live()
        live.start()
        live.stop()
        assertEquals(PresenceLink.OFF, media.state.value.link)
        assertFalse(live.start())
        assertFalse(live.setCamera(true))
        assertEquals(1, server.passes)
    }

    @Test fun `the weak network rule on its own`() {
        val policy = WeakNetworkPolicy(holdMs = 6_000)
        assertFalse(policy.observe(PresenceQuality.LOST, cameraOn = true, now = 0))
        assertTrue(policy.observe(PresenceQuality.LOST, cameraOn = true, now = 6_000))
        // With the camera already off there is nothing to take.
        assertFalse(policy.observe(PresenceQuality.WEAK, cameraOn = false, now = 20_000))
        assertFalse(policy.observe(PresenceQuality.UNKNOWN, cameraOn = true, now = 30_000))
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
    }
}

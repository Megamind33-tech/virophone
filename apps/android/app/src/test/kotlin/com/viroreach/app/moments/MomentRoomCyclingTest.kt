package com.viroreach.app.moments

import com.viroreach.app.moments.engine.MomentLive
import com.viroreach.core.network.*
import com.viroreach.voice.webrtc.MomentMediaTransport
import com.viroreach.voice.webrtc.PresenceLink
import com.viroreach.voice.webrtc.PresenceSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Opening and leaving Moments over and over must not accumulate anything.
 *
 * A person uses Viro by going in and out of rooms all evening, and the app has
 * to survive that indefinitely rather than for the first few. What goes wrong
 * here is not usually dramatic: a room that was left keeps a connection, or
 * retries in the background while a newer room is open, and the native side
 * grows quietly until the system takes the whole process away — which leaves
 * no stack trace and looks like a mystery crash somewhere else entirely.
 *
 * This covers the part that can be tested off a device. It cannot measure
 * native memory; what it can do is pin the property that lets it grow, which
 * is a room outliving its own departure.
 */
class MomentRoomCyclingTest {
    /** Remembers every connect and disconnect it was asked for. */
    private class CountingMedia : MomentMediaTransport {
        override val state = MutableStateFlow(PresenceSnapshot())
        var connects = 0
        var disconnects = 0
        val live: Boolean get() = state.value.link == PresenceLink.LIVE
        override suspend fun connect(url: String, token: String) {
            connects++
            state.value = PresenceSnapshot(link = PresenceLink.LIVE)
        }
        override suspend fun setCamera(on: Boolean) = true
        override suspend fun setMicrophone(on: Boolean) = true
        override fun flipCamera() {}
        override suspend fun disconnect() {
            disconnects++
            state.value = PresenceSnapshot()
        }
    }

    private class Server : ViroMomentsApi by MomentLiveTest.Unused {
        var passes = 0
        override suspend fun presence(id: String): MomentPresenceDto {
            passes++
            return MomentPresenceDto("wss://media.viro", "pass-$passes", "viro-moment-$id")
        }
    }

    @Test
    fun `fifty rooms opened and left leave nothing connected`() = runTest {
        val media = CountingMedia()
        val server = Server()
        repeat(50) { i ->
            // A fresh room each time, exactly as entering one builds a new one.
            val live = MomentLive(server, "moment-$i", media)
            assertTrue("room $i should have joined", live.start())
            assertTrue("room $i should be live", media.live)
            live.stop()
            assertFalse("room $i should be disconnected after leaving", media.live)
        }
        assertEquals("every join is matched by a departure", media.connects, media.disconnects)
        assertEquals(50, media.connects)
    }

    @Test
    fun `a room that has been left never reconnects`() = runTest {
        val media = CountingMedia()
        val live = MomentLive(Server(), "m1", media)
        live.start()
        live.stop()

        // Whatever happens afterwards — a retry timer, a late frame, a tick
        // from a coroutine that had not noticed yet — the room is gone and
        // must stay gone. This is the shape of the leak: an abandoned room
        // quietly holding media open underneath a newer one.
        assertFalse(live.start())
        live.tick()
        assertFalse(media.live)
        assertEquals(1, media.connects)
        assertEquals(1, media.disconnects)
    }
}

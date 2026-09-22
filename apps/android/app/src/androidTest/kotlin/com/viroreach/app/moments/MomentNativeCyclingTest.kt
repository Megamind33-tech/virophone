package com.viroreach.app.moments

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.viroreach.app.moments.engine.MomentPhase
import com.viroreach.app.moments.engine.MomentSessionGate
import com.viroreach.voice.webrtc.MomentPresenceEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The fifty-cycle requirement, on the phone rather than on an argument.
 *
 * This is the part that cannot be faked off a device. Building and releasing a
 * LiveKit room is the moment a PeerConnectionFactory, an EglBase and an audio
 * device are created and destroyed, all of it native, and whether that leaks
 * or dies is a property of the actual hardware and the actual driver — a JVM
 * test with a fake transport cannot see any of it.
 *
 * No server is needed. Connecting is not what was suspected; constructing and
 * tearing down the native stack over and over is, and that happens without any
 * network at all.
 *
 * Run on the affected device:
 *
 *   adb -s <serial> shell am instrument -w \
 *     -e class com.viroreach.app.moments.MomentNativeCyclingTest \
 *     com.viroreach.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class MomentNativeCyclingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Fifty rooms built and released. The process surviving is most of the
     * point — if the native stack is being torn down underneath itself, this
     * is where it dies and the test simply never reports.
     */
    @Test
    fun fiftyRoomsBuiltAndReleasedDoNotGrowNativeMemory() = runBlocking {
        val samples = mutableListOf<Long>()
        // One warm-up first: the first room ever built loads native libraries
        // and allocates things that are kept on purpose, and counting that as
        // growth would condemn a perfectly healthy app.
        cycle()
        Runtime.getRuntime().gc()
        val baseline = Debug.getNativeHeapAllocatedSize()

        repeat(50) { i ->
            cycle()
            if (i % 10 == 9) {
                Runtime.getRuntime().gc()
                samples += Debug.getNativeHeapAllocatedSize()
            }
        }

        val worst = samples.max()
        val growth = worst - baseline
        // Some movement is normal — allocators keep pools, drivers cache. What
        // must not happen is growth proportional to the number of rooms.
        assertTrue(
            "native heap grew ${growth / (1024 * 1024)}MB over 50 rooms " +
                "(baseline ${baseline / (1024 * 1024)}MB, samples ${samples.map { it / (1024 * 1024) }})",
            growth < 64L * 1024 * 1024,
        )
    }

    /** One room's worth of native life: build it, then give it all back. */
    private suspend fun cycle() {
        val engine = MomentPresenceEngine(context)
        // Never connected, so this exercises construction and teardown rather
        // than the network — which is the part under suspicion.
        withTimeout(10_000) { engine.disconnect() }
    }

    /**
     * The gate's promise: nothing may begin while something is being released.
     *
     * The crash this protects against is two native stacks overlapping, so the
     * test is that the second cycle genuinely waits rather than merely being
     * told to.
     */
    @Test
    fun aRoomCannotBeginWhileThePreviousOneIsStillBeingReleased() = runBlocking {
        var releaseFinished = false
        var beganBeforeReleaseFinished = false

        repeat(25) {
            MomentSessionGate.entering("m-$it")
            MomentSessionGate.active()
            releaseFinished = false
            MomentSessionGate.leaving {
                kotlinx.coroutines.delay(20)
                releaseFinished = true
            }
            MomentSessionGate.awaitReleased()
            if (!releaseFinished) beganBeforeReleaseFinished = true
        }

        assertTrue("a room began before the previous one finished releasing", !beganBeforeReleaseFinished)
        assertEquals(MomentPhase.IDLE, MomentSessionGate.phase.value)
    }
}

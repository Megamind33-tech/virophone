package com.viroreach.core.e2ee

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.viroreach.core.network.DeviceBundleDto
import com.viroreach.core.network.KeyBundleBody
import com.viroreach.core.network.OneTimePrekeyBody
import com.viroreach.core.network.PrekeyBatchBody
import com.viroreach.core.network.PrekeyStatusDto
import com.viroreach.core.network.UserBundlesDto
import com.viroreach.core.network.UserDevicesDto
import com.viroreach.core.network.ViroKeysApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two phones, one key server, real libsignal: the whole path a chat message
 * takes between devices, without a network or a second handset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TwoPhonesTest {

    /** The key directory, as the server keeps it. */
    private class KeyServer {
        val published = mutableMapOf<String, KeyBundleBody>()
        val owner = mutableMapOf<String, String>()
        val prekeys = mutableMapOf<String, ArrayDeque<OneTimePrekeyBody>>()

        fun api(userId: String, deviceId: String) = object : ViroKeysApi {
            override suspend fun publish(body: KeyBundleBody): PrekeyStatusDto {
                published[deviceId] = body
                owner[deviceId] = userId
                body.oneTimePreKeys?.let { prekeys.getOrPut(deviceId) { ArrayDeque() }.addAll(it) }
                return status()
            }
            override suspend fun status() = PrekeyStatusDto(available = prekeys[deviceId]?.size ?: 0, lowWater = 20)
            override suspend fun topUp(body: PrekeyBatchBody): PrekeyStatusDto {
                prekeys.getOrPut(deviceId) { ArrayDeque() }.addAll(body.oneTimePreKeys)
                return status()
            }
            override suspend fun devices(userId: String) =
                UserDevicesDto(userId, published.keys.filter { owner[it] == userId })
            override suspend fun bundles(userId: String, deviceIds: String): UserBundlesDto {
                val wanted = deviceIds.split(',').toSet()
                val out = published.filter { owner[it.key] == userId && it.key in wanted }.map { (id, b) ->
                    DeviceBundleDto(id, b.registrationId, b.identityKey, b.signedPreKey, b.kyberPreKey, prekeys[id]?.removeFirstOrNull())
                }
                return UserBundlesDto(userId, out)
            }
        }
    }

    private class Phone(val userId: String, val deviceId: String, server: KeyServer, val db: E2eeDatabase = newDb()) {
        val engine = E2eeEngine(db, server.api(userId, deviceId))
        companion object {
            fun newDb(): E2eeDatabase = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                E2eeDatabase::class.java,
            ).allowMainThreadQueries().build()
        }
    }

    private data class Wire(val id: String, val from: Phone, val envelopes: List<SealedEnvelope>)

    private val server = KeyServer()
    private var seq = 0

    private suspend fun send(from: Phone, to: Phone, text: String): Wire {
        val sealed = from.engine.seal(from.userId, listOf(to.userId), text)
        assertTrue("nothing sealed for $text", sealed.isNotEmpty())
        return Wire("m${++seq}", from, sealed)
    }

    private suspend fun open(at: Phone, w: Wire): OpenResult {
        val mine = at.engine.myDeviceId()
        val copy = w.envelopes.firstOrNull { it.deviceId == mine }
            ?: fail("no envelope for ${at.deviceId}; sealed for ${w.envelopes.map { it.deviceId }}") as Nothing
        return at.engine.openMessage(w.id, w.from.userId, w.from.deviceId, copy.ciphertext, copy.type)
    }

    private suspend fun expect(at: Phone, w: Wire, text: String) {
        val r = open(at, w)
        if (r !is OpenResult.Opened) fail("${w.id} at ${at.deviceId}: $r")
        assertEquals(text, (r as OpenResult.Opened).plaintext)
    }

    private fun phones(): Pair<Phone, Phone> = runBlocking {
        val a = Phone("alice", "a1", server)
        val b = Phone("bob", "b1", server)
        assertTrue(a.engine.ensureRegistered("alice", "a1"))
        assertTrue(b.engine.ensureRegistered("bob", "b1"))
        a to b
    }

    @Test fun `first message and a reply open on both sides`() = runBlocking {
        val (a, b) = phones()
        expect(b, send(a, b, "hi"), "hi")
        expect(a, send(b, a, "hello back"), "hello back")
        expect(b, send(a, b, "how are you"), "how are you")
    }

    @Test fun `ten in a row before the other side replies`() = runBlocking {
        val (a, b) = phones()
        val wires = (1..10).map { send(a, b, "m$it") }
        wires.forEachIndexed { i, w -> expect(b, w, "m${i + 1}") }
    }

    @Test fun `alternating quickly never desynchronises`() = runBlocking {
        val (a, b) = phones()
        repeat(20) { i ->
            val (from, to) = if (i % 2 == 0) a to b else b to a
            expect(to, send(from, to, "t$i"), "t$i")
        }
    }

    @Test fun `out of order before any reply`() = runBlocking {
        val (a, b) = phones()
        val w = (1..5).map { send(a, b, "o$it") }
        listOf(4, 1, 3, 0, 2).forEach { expect(b, w[it], "o${it + 1}") }
    }

    @Test fun `out of order in an established session`() = runBlocking {
        val (a, b) = phones()
        expect(b, send(a, b, "start"), "start")
        expect(a, send(b, a, "ok"), "ok")
        val w = (1..5).map { send(a, b, "s$it") }
        listOf(2, 0, 4, 1, 3).forEach { expect(b, w[it], "s${it + 1}") }
    }

    @Test fun `the same message delivered twice opens both times`() = runBlocking {
        val (a, b) = phones()
        val w = send(a, b, "twice")
        expect(b, w, "twice")
        // Socket then sync: the second arrival must not be a failure.
        expect(b, w, "twice")
    }

    @Test fun `a restart keeps every session`() = runBlocking {
        val (a, b) = phones()
        expect(b, send(a, b, "before"), "before")
        expect(a, send(b, a, "reply"), "reply")
        val b2 = Phone("bob", "b1", server, b.db)
        assertTrue(b2.engine.ensureRegistered("bob", "b1"))
        expect(b2, send(a, b2, "after restart"), "after restart")
        expect(a, send(b2, a, "still here"), "still here")
    }

    @Test fun `both start a session at the same moment`() = runBlocking {
        val (a, b) = phones()
        // Each seals before seeing anything from the other.
        val fromA = send(a, b, "from a")
        val fromB = send(b, a, "from b")
        expect(b, fromA, "from a")
        expect(a, fromB, "from b")
        expect(b, send(a, b, "next a"), "next a")
        expect(a, send(b, a, "next b"), "next b")
    }

    // --------------------------------------------------- resending on request

    private suspend fun resend(author: Phone, w: Wire, to: Phone, text: String): Wire {
        val copy = author.engine.sealForDevice(to.userId, to.deviceId, text)
            ?: fail("author could not reseal ${w.id}") as Nothing
        return w.copy(envelopes = listOf(copy))
    }

    @Test fun `a message whose key was already spent opens once its author reseals it`() = runBlocking {
        val (a, b) = phones()
        val w = send(a, b, "lost words")
        expect(b, w, "lost words")
        // The phone kept nothing of it, as the old bug did: the key is gone.
        b.engine.forgetOpened(w.id)
        val again = open(b, w)
        assertTrue("expected a spent key, got $again", again is OpenResult.Failed && again.failure == DecryptFailure.ALREADY_OPENED)
        expect(b, resend(a, w, b, "lost words"), "lost words")
        // And the conversation carries on both ways afterwards.
        expect(b, send(a, b, "after"), "after")
        expect(a, send(b, a, "reply"), "reply")
        expect(b, send(a, b, "and again"), "and again")
    }

    @Test fun `a message sealed before a phone signed in again opens once resealed for it`() = runBlocking {
        val (a, b) = phones()
        val old = send(a, b, "sent to the old phone")
        // Bob signs in again: a new device with a new identity.
        val b2 = Phone("bob", "b2", server)
        assertTrue(b2.engine.ensureRegistered("bob", "b2"))
        assertTrue(old.envelopes.none { it.deviceId == "b2" })
        expect(b2, resend(a, old, b2, "sent to the old phone"), "sent to the old phone")
        expect(a, send(b2, a, "new phone here"), "new phone here")
    }

    @Test fun `a receiving session that was lost recovers through a reseal`() = runBlocking {
        val (a, b) = phones()
        expect(b, send(a, b, "one"), "one")
        expect(a, send(b, a, "two"), "two")
        // Bob loses his session with Alice's device.
        b.db.dao().deleteSession("a1")
        val w = send(a, b, "three")
        val r = open(b, w)
        assertTrue("expected a failure, got $r", r is OpenResult.Failed && r.failure.retryable)
        expect(b, resend(a, w, b, "three"), "three")
        expect(a, send(b, a, "four"), "four")
        expect(b, send(a, b, "five"), "five")
    }

    @Test fun `messages already in flight still open after a reseal`() = runBlocking {
        val (a, b) = phones()
        expect(b, send(a, b, "hello"), "hello")
        expect(a, send(b, a, "hi"), "hi")
        val inFlight = send(a, b, "in flight")
        val w = send(a, b, "needs resend")
        val resent = resend(a, w, b, "needs resend")
        expect(b, resent, "needs resend")
        expect(b, inFlight, "in flight")
        expect(a, send(b, a, "all good"), "all good")
    }

    @Test fun `many old messages resealed at once share one fresh session and all open`() = runBlocking {
        val (a, b) = phones()
        val old = (1..15).map { send(a, b, "old $it") }
        old.forEach { expect(b, it, it.envelopes.let { _ -> "old ${old.indexOf(it) + 1}" }) }
        old.forEach { b.engine.forgetOpened(it.id) }
        val prekeysBefore = server.prekeys["b1"]?.size ?: 0
        val resent = old.mapIndexed { i, w -> resend(a, w, b, "old ${i + 1}") }
        // One bundle for the whole burst.
        assertEquals(prekeysBefore - 1, server.prekeys["b1"]?.size ?: 0)
        // Opened in any order.
        resent.indices.shuffled(java.util.Random(7)).forEach { expect(b, resent[it], "old ${it + 1}") }
        expect(a, send(b, a, "got them"), "got them")
        expect(b, send(a, b, "great"), "great")
    }

    // ------------------------------------------ one phone, two accounts

    @Test fun `two accounts on one phone keep reading each other across sign-outs`() = runBlocking {
        // One phone: each account has its own key store on it, and the server
        // hands each account back the same device when it signs in again.
        val aliceStore = Phone.newDb()
        val bobStore = Phone.newDb()
        val alice = Phone("alice", "a1", server, aliceStore)
        assertTrue(alice.engine.ensureRegistered("alice", "a1"))
        val bobOnce = Phone("bob", "b1", server, bobStore)
        assertTrue(bobOnce.engine.ensureRegistered("bob", "b1"))

        // Alice is signed in and writes while Bob is signed out.
        val sent = (1..3).map { send(alice, bobOnce, "while you were out $it") }

        // Alice signs out, Bob signs in on the same phone: his own keys, his
        // own device, and every message waiting for him opens.
        val bob = Phone("bob", "b1", server, bobStore)
        assertTrue(bob.engine.ensureRegistered("bob", "b1"))
        sent.forEachIndexed { i, w -> expect(bob, w, "while you were out ${i + 1}") }
        val reply = send(bob, alice, "got all three")

        // Bob signs out, Alice back in: her keys were never touched.
        val aliceAgain = Phone("alice", "a1", server, aliceStore)
        assertTrue(aliceAgain.engine.ensureRegistered("alice", "a1"))
        expect(aliceAgain, reply, "got all three")
        expect(bob, send(aliceAgain, bob, "good"), "good")
    }

    // ------------- the server handing this phone a new device at sign-in

    /** One account's key stores on one phone, able to set an old device's aside. */
    private class MemoryKeyStores : KeyStores {
        var cur: E2eeDatabase = Phone.newDb()
        val old = mutableMapOf<String, E2eeDatabase>()
        override fun current() = cur
        override fun retireCurrent(deviceId: String) { old[deviceId] = cur; cur = Phone.newDb() }
        override fun retired(deviceId: String) = old[deviceId]
        override fun retiredDeviceIds() = old.keys.toSet()
        override fun wipeRetired() = old.clear()
    }

    @Test fun `a new device at sign-in still opens what was sealed for the old one`() = runBlocking {
        val alice = Phone("alice", "a1", server)
        assertTrue(alice.engine.ensureRegistered("alice", "a1"))
        val stores = MemoryKeyStores()
        val bob1 = E2eeEngine(stores, server.api("bob", "b1"))
        assertTrue(bob1.ensureRegistered("bob", "b1"))
        val bobPhone1 = Phone("bob", "b1", server, stores.cur)

        expect(bobPhone1, send(alice, bobPhone1, "before"), "before")
        expect(alice, send(bobPhone1, alice, "hi"), "hi")
        // Bob signs out; Alice keeps writing to the device she knows.
        val whileAway = (1..3).map { send(alice, bobPhone1, "while away $it") }

        // Bob signs back in and the server gives this phone a new device.
        val bob2 = E2eeEngine(stores, server.api("bob", "b2"))
        assertTrue(bob2.ensureRegistered("bob", "b2"))
        assertEquals(setOf("b1"), bob2.retiredDeviceIds())
        assertEquals("b2", bob2.myDeviceId())

        // Everything sealed for the old device opens with its kept keys.
        whileAway.forEachIndexed { i, w ->
            val copy = w.envelopes.first { it.deviceId == "b1" }
            val r = bob2.openRetired(w.id, "b1", "alice", "a1", copy.ciphertext, copy.type)
            assertEquals("while away ${i + 1}", (r as OpenResult.Opened).plaintext)
        }

        // And the new device is a full participant from here on.
        val bobPhone2 = Phone("bob", "b2", server, stores.cur)
        val fromNew = send(bobPhone2, alice, "new phone, same me")
        expect(alice, fromNew, "new phone, same me")
    }

    @Test fun `on real files, the old device's keys are set aside and still open its messages`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val alice = Phone("alice", "a1", server)
        assertTrue(alice.engine.ensureRegistered("alice", "a1"))
        E2eeDatabase.useAccount("bob-files")
        try {
            val bob1 = E2eeEngine(ctx, server.api("bob", "b1"))
            assertTrue(bob1.ensureRegistered("bob", "b1"))
            val first = alice.engine.seal("alice", listOf("bob"), "sealed for the old device")
            val w = Wire("f1", alice, first)

            // Same account, new device from the server: a fresh engine, as after a sign-in.
            val bob2 = E2eeEngine(ctx, server.api("bob", "b2"))
            assertTrue(bob2.ensureRegistered("bob", "b2"))
            assertEquals("b2", bob2.myDeviceId())
            assertEquals(setOf("b1"), bob2.retiredDeviceIds())
            assertTrue(ctx.getDatabasePath("viro_e2ee_bob-files__b1.db").exists())

            val copy = w.envelopes.first { it.deviceId == "b1" }
            val r = bob2.openRetired(w.id, "b1", "alice", "a1", copy.ciphertext, copy.type)
            assertEquals("sealed for the old device", (r as OpenResult.Opened).plaintext)

            // Deleting the account takes the kept keys with it.
            bob2.wipe()
            assertTrue(bob2.retiredDeviceIds().isEmpty())
        } finally {
            E2eeDatabase.useAccount(null)
        }
    }

    @Test fun `a stale device with unusable keys does not stop the message`() = runBlocking {
        val (a, b) = phones()
        // Old sign-ins left behind on both accounts, whose published keys no
        // longer make a session: one of Bob's, and one of Alice's own.
        for ((user, device) in listOf("bob" to "b-old", "alice" to "a-old")) {
            val good = server.published.getValue(if (user == "bob") "b1" else "a1")
            server.published[device] = good.copy(identityKey = "AAAA")
            server.owner[device] = user
        }
        val sealed = a.engine.seal("alice", listOf("bob"), "still gets there")
        assertEquals(listOf("b1"), sealed.map { it.deviceId })
        expect(b, Wire("m${++seq}", a, sealed), "still gets there")
    }

    @Test fun `nothing is sent when none of their devices can be sealed for`() = runBlocking {
        val (a, _) = phones()
        server.published["b1"] = server.published.getValue("b1").copy(identityKey = "AAAA")
        try {
            a.engine.seal("alice", listOf("bob"), "waits")
            fail("sealed with no copy Bob could open")
        } catch (_: SealUnavailableException) {
        }
    }
}

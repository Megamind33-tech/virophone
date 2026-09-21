package com.viroreach.core.e2ee

import android.content.Context
import android.util.Base64
import android.util.Log
import com.viroreach.core.network.DeviceBundleDto
import com.viroreach.core.network.KeyBundleBody
import com.viroreach.core.network.OneTimePrekeyBody
import com.viroreach.core.network.PrekeyBatchBody
import com.viroreach.core.network.PublicKeyBody
import com.viroreach.core.network.ViroKeysApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.util.KeyHelper
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord


/** One sealed copy of a message, addressed to one device. */
data class SealedEnvelope(val deviceId: String, val ciphertext: String, val type: Int)

/**
 * End-to-end encryption on this phone.
 *
 * The protocol itself is libsignal's — X3DH with a Kyber prekey to start a
 * session, then the Double Ratchet for every message after. Nothing here
 * invents cryptography; what this class does is generate this device's keys,
 * publish the public half, start sessions with other people's devices, and
 * seal and open messages.
 *
 * Everything runs off the main thread: libsignal calls the store synchronously.
 */
class E2eeEngine(
    context: Context,
    private val api: ViroKeysApi,
) {
    private val dao = E2eeDatabase.get(context).dao()
    private val lock = Mutex()
    /** Who has which devices, so sealing is not a network round trip per message. */
    private val deviceCache = java.util.concurrent.ConcurrentHashMap<String, CachedDevices>()

    // --------------------------------------------------------- registration

    /**
     * Makes sure this device has keys and that the server has their public
     * half. Safe to call on every launch: it does nothing once registered.
     *
     * Keys belong to the person who is signed in — if a different account
     * signs in on this phone, the old identity is wiped rather than reused.
     */
    suspend fun ensureRegistered(userId: String, deviceId: String): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val existing = dao.ownIdentity()
                if (existing != null && existing.userId == userId && existing.deviceId == deviceId) {
                    // Registered here, but the directory may never have heard
                    // of it: keys are generated locally and uploaded second,
                    // and the upload can fail. A device the directory does not
                    // know cannot be sealed to, and nothing would ever ask
                    // again — so ask again here, with the keys it already has
                    // rather than a new identity, which would set off the
                    // safety-number warning on every phone it talks to.
                    val ready = if (existing.publishedAt == 0L) republish(existing) else existing
                    rotateIfStale(ready)
                    topUpIfLowInternal()
                    return@runCatching ready.publishedAt != 0L
                }
                if (existing != null) wipeInternal()
                register(userId, deviceId)
                true
            }.getOrElse {
                Log.w(TAG, "could not set up encryption: ${it.javaClass.simpleName}")
                false
            }
        }
    }

    /**
     * True when this device can send and receive encrypted messages.
     *
     * Holding keys is not enough — the other side has to be able to fetch a
     * bundle for this device, which it can only do once the directory has one.
     */
    suspend fun isRegistered(): Boolean = withContext(Dispatchers.IO) {
        dao.ownIdentity()?.publishedAt?.let { it != 0L } == true
    }

    /** This device's id, as other people's envelopes address it. */
    suspend fun myDeviceId(): String? = withContext(Dispatchers.IO) { dao.ownIdentity()?.deviceId }

    private suspend fun register(userId: String, deviceId: String) {
        val identity = IdentityKeyPair.generate()
        val registrationId = KeyHelper.generateRegistrationId(false)
        val now = System.currentTimeMillis()

        val signed = newSignedPreKey(identity, 1, now)
        val kyber = newKyberPreKey(identity, 1, now)
        val oneTime = (1..PREKEY_BATCH).map { id -> PreKeyRecord(id, Curve.generateKeyPair()) }

        val own = OwnIdentityEntity(
            userId = userId,
            deviceId = deviceId,
            registrationId = registrationId,
            identityKeyPair = identity.serialize(),
            createdAt = now,
            nextPreKeyId = PREKEY_BATCH + 1,
            nextSignedPreKeyId = 2,
            nextKyberPreKeyId = 2,
            signedPrekeyRotatedAt = now,
            // Not published yet, and said so on disk: if the upload below
            // never lands, the next launch retries instead of assuming it did.
            publishedAt = 0L,
        )
        dao.saveOwnIdentity(own)
        dao.saveSignedPreKey(SignedPreKeyEntity(signed.id, signed.serialize()))
        dao.saveKyberPreKey(KyberPreKeyEntity(kyber.id, kyber.serialize()))
        oneTime.forEach { dao.savePreKey(PreKeyEntity(it.id, it.serialize())) }

        api.publish(
            KeyBundleBody(
                registrationId = registrationId,
                identityKey = identity.publicKey.serialize().b64(),
                signedPreKey = PublicKeyBody(signed.id, signed.keyPair.publicKey.serialize().b64(), signed.signature.b64()),
                kyberPreKey = PublicKeyBody(kyber.id, kyber.keyPair.publicKey.serialize().b64(), kyber.signature.b64()),
                oneTimePreKeys = oneTime.map { OneTimePrekeyBody(it.id, it.keyPair.publicKey.serialize().b64()) },
            ),
        )
        dao.saveOwnIdentity(own.copy(publishedAt = System.currentTimeMillis()))
    }

    /**
     * Uploads the keys this device already holds, for a registration whose
     * first upload never arrived.
     *
     * The identity, signed and Kyber prekeys are the stored ones, not new
     * ones: a fresh identity key would show up on every contact's phone as a
     * safety-number change, which is a warning about an attack and must not be
     * spent on our own retry. Returns the row as it now stands, so a failure
     * here leaves publishedAt at 0 and the next launch tries again.
     */
    private suspend fun republish(own: OwnIdentityEntity): OwnIdentityEntity {
        val identity = IdentityKeyPair(own.identityKeyPair)
        val signed = dao.signedPreKey(own.nextSignedPreKeyId - 1)?.let { SignedPreKeyRecord(it.record) }
            ?: dao.signedPreKeys().lastOrNull()?.let { SignedPreKeyRecord(it.record) }
            ?: return own
        val kyber = dao.kyberPreKey(own.nextKyberPreKeyId - 1)?.let { KyberPreKeyRecord(it.record) }
            ?: dao.kyberPreKeys().lastOrNull()?.let { KyberPreKeyRecord(it.record) }
            ?: return own
        val oneTime = dao.preKeys(PREKEY_BATCH).map { PreKeyRecord(it.record) }
        api.publish(
            KeyBundleBody(
                registrationId = own.registrationId,
                identityKey = identity.publicKey.serialize().b64(),
                signedPreKey = PublicKeyBody(signed.id, signed.keyPair.publicKey.serialize().b64(), signed.signature.b64()),
                kyberPreKey = PublicKeyBody(kyber.id, kyber.keyPair.publicKey.serialize().b64(), kyber.signature.b64()),
                oneTimePreKeys = oneTime.map { OneTimePrekeyBody(it.id, it.keyPair.publicKey.serialize().b64()) },
            ),
        )
        Log.i(TAG, "published this device's keys on a retry")
        val updated = own.copy(publishedAt = System.currentTimeMillis())
        dao.saveOwnIdentity(updated)
        return updated
    }

    private fun newSignedPreKey(identity: IdentityKeyPair, id: Int, now: Long): SignedPreKeyRecord {
        val pair = Curve.generateKeyPair()
        val signature = identity.privateKey.calculateSignature(pair.publicKey.serialize())
        return SignedPreKeyRecord(id, now, pair, signature)
    }

    private fun newKyberPreKey(identity: IdentityKeyPair, id: Int, now: Long): KyberPreKeyRecord {
        val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val signature = identity.privateKey.calculateSignature(pair.publicKey.serialize())
        return KyberPreKeyRecord(id, now, pair, signature)
    }

    /**
     * Replaces the signed and Kyber prekeys once they are old.
     *
     * They are reused for every session this device starts, so rotating them
     * limits how much a leaked one could ever open. The old records stay in
     * the store: a message sealed against the previous prekey may still be in
     * flight, and it must still open when it lands.
     */
    private suspend fun rotateIfStale(own: OwnIdentityEntity) {
        if (System.currentTimeMillis() - own.signedPrekeyRotatedAt < SIGNED_PREKEY_MAX_AGE_MS) return
        val identity = IdentityKeyPair(own.identityKeyPair)
        val now = System.currentTimeMillis()
        val signed = newSignedPreKey(identity, own.nextSignedPreKeyId, now)
        val kyber = newKyberPreKey(identity, own.nextKyberPreKeyId, now)
        dao.saveSignedPreKey(SignedPreKeyEntity(signed.id, signed.serialize()))
        dao.saveKyberPreKey(KyberPreKeyEntity(kyber.id, kyber.serialize()))
        api.publish(
            KeyBundleBody(
                registrationId = own.registrationId,
                identityKey = identity.publicKey.serialize().b64(),
                signedPreKey = PublicKeyBody(signed.id, signed.keyPair.publicKey.serialize().b64(), signed.signature.b64()),
                kyberPreKey = PublicKeyBody(kyber.id, kyber.keyPair.publicKey.serialize().b64(), kyber.signature.b64()),
            ),
        )
        dao.saveOwnIdentity(
            own.copy(
                nextSignedPreKeyId = own.nextSignedPreKeyId + 1,
                nextKyberPreKeyId = own.nextKyberPreKeyId + 1,
                signedPrekeyRotatedAt = now,
            ),
        )
    }

    /** Publishes more one-time prekeys when the server is running low on them. */
    suspend fun topUpIfLow() = lock.withLock {
        withContext(Dispatchers.IO) { runCatching { topUpIfLowInternal() } }
        Unit
    }

    private suspend fun topUpIfLowInternal() {
        val own = dao.ownIdentity() ?: return
        val status = api.status()
        val available = status.available ?: return
        val lowWater = status.lowWater ?: 20
        if (available >= lowWater) return
        var nextId = own.nextPreKeyId
        val fresh = (1..PREKEY_BATCH).map { PreKeyRecord(nextId++, Curve.generateKeyPair()) }
        fresh.forEach { dao.savePreKey(PreKeyEntity(it.id, it.serialize())) }
        dao.saveOwnIdentity(own.copy(nextPreKeyId = nextId))
        api.topUp(PrekeyBatchBody(fresh.map { OneTimePrekeyBody(it.id, it.keyPair.publicKey.serialize().b64()) }))
    }

    /** Forgets every key on this phone. Any message already sent here becomes unreadable. */
    suspend fun wipe() = lock.withLock { withContext(Dispatchers.IO) { wipeInternal() } }

    private fun wipeInternal() {
        dao.wipeSessions()
        dao.wipePreKeys()
        dao.wipeSignedPreKeys()
        dao.wipeKyberPreKeys()
        dao.wipeRemoteIdentities()
        dao.wipeSenderKeys()
        dao.wipeIdentity()
    }

    // ------------------------------------------------------------- sealing

    /**
     * Seals [plaintext] once for every device of [recipientUserIds], and once
     * for this person's own other devices so their other phone can read it too.
     *
     * Returns an empty list when anyone in the chat has no keys at all: the
     * caller then sends the message the ordinary way rather than silently
     * leaving someone out.
     */
    suspend fun seal(
        myUserId: String,
        recipientUserIds: List<String>,
        plaintext: String,
    ): List<SealedEnvelope> = lock.withLock {
        withContext(Dispatchers.IO) {
            val own = dao.ownIdentity() ?: return@withContext emptyList()
            val store = storeOf(own)
            val out = mutableListOf<SealedEnvelope>()
            for (userId in (recipientUserIds + myUserId).distinct()) {
                val deviceIds = devicesOf(userId).filter { it != own.deviceId }
                // Someone in the chat cannot receive this at all: fall back
                // rather than send a message half the room cannot open.
                if (deviceIds.isEmpty() && userId != myUserId) return@withContext emptyList()

                // Only devices with no session yet need a bundle, and fetching
                // one spends a prekey — so ask about exactly those.
                val strangers = deviceIds.filter { !store.containsSession(SignalProtocolAddress(it, DEVICE_NUMBER)) }
                if (strangers.isNotEmpty()) {
                    val bundles = api.bundles(userId, strangers.joinToString(",")).devices.orEmpty()
                    for (device in bundles) {
                        startSession(store, SignalProtocolAddress(device.deviceId, DEVICE_NUMBER), device)
                    }
                }
                for (deviceId in deviceIds) {
                    val address = SignalProtocolAddress(deviceId, DEVICE_NUMBER)
                    if (!store.containsSession(address)) continue
                    store.rememberOwner(deviceId, userId)
                    val sealed = SessionCipher(store, address).encrypt(plaintext.toByteArray(Charsets.UTF_8))
                    out += SealedEnvelope(deviceId, sealed.serialize().b64(), sealed.type)
                }
            }
            out
        }
    }

    /**
     * Which devices this person can be reached on.
     *
     * Asked of the server at most every few minutes, and never on the critical
     * path when the phone is offline: a message written on a bus must still
     * seal and wait in the outbox, so a failed lookup falls back to the devices
     * this phone already has sessions with.
     */
    private suspend fun devicesOf(userId: String): List<String> {
        val cached = deviceCache[userId]
        if (cached != null && System.currentTimeMillis() - cached.at < DEVICE_CACHE_MS) return cached.deviceIds
        return try {
            val fresh = api.devices(userId).deviceIds.orEmpty()
            deviceCache[userId] = CachedDevices(fresh, System.currentTimeMillis())
            fresh
        } catch (e: Exception) {
            Log.w(TAG, "device list unavailable: ${e.javaClass.simpleName}")
            cached?.deviceIds ?: dao.remoteIdentitiesFor(userId).map { it.address }
        }
    }

    private data class CachedDevices(val deviceIds: List<String>, val at: Long)

    private fun startSession(store: ViroSignalStore, address: SignalProtocolAddress, device: DeviceBundleDto) {
        val bundle = PreKeyBundle(
            device.registrationId,
            DEVICE_NUMBER,
            device.preKey?.keyId ?: PreKeyBundle.NULL_PRE_KEY_ID,
            device.preKey?.publicKey?.let { org.signal.libsignal.protocol.ecc.ECPublicKey(it.b64Bytes()) },
            device.signedPreKey.keyId,
            org.signal.libsignal.protocol.ecc.ECPublicKey(device.signedPreKey.publicKey.b64Bytes()),
            device.signedPreKey.signature.b64Bytes(),
            IdentityKey(device.identityKey.b64Bytes()),
            device.kyberPreKey.keyId,
            org.signal.libsignal.protocol.kem.KEMPublicKey(device.kyberPreKey.publicKey.b64Bytes()),
            device.kyberPreKey.signature.b64Bytes(),
        )
        SessionBuilder(store, address).process(bundle)
    }

    /**
     * Opens one sealed copy. Returns null when this device cannot read it —
     * a message sent before this phone existed, or one already opened.
     */
    suspend fun open(
        senderUserId: String,
        senderDeviceId: String,
        ciphertext: String,
        type: Int,
    ): String? = lock.withLock {
        withContext(Dispatchers.IO) {
            val own = dao.ownIdentity() ?: return@withContext null
            val store = storeOf(own)
            val address = SignalProtocolAddress(senderDeviceId, DEVICE_NUMBER)
            runCatching {
                val bytes = ciphertext.b64Bytes()
                val plain = if (type == CiphertextMessage.PREKEY_TYPE) {
                    SessionCipher(store, address).decrypt(PreKeySignalMessage(bytes))
                } else {
                    SessionCipher(store, address).decrypt(SignalMessage(bytes))
                }
                store.rememberOwner(senderDeviceId, senderUserId)
                String(plain, Charsets.UTF_8)
            }.getOrElse {
                // A duplicate, or a message for a session this phone no longer
                // has. The kind matters for the log; the content never appears.
                Log.w(TAG, "could not open a message: ${it.javaClass.simpleName}")
                null
            }
        }
    }

    // ------------------------------------------------------ safety numbers

    /**
     * The number both people compare to be sure no one is in the middle. Null
     * until this phone has a session with one of their devices.
     */
    suspend fun safetyNumber(myUserId: String, peerUserId: String): String? = withContext(Dispatchers.IO) {
        val own = dao.ownIdentity() ?: return@withContext null
        val theirs = dao.remoteIdentitiesFor(peerUserId).minByOrNull { it.firstSeenAt } ?: return@withContext null
        runCatching {
            val mine = IdentityKeyPair(own.identityKeyPair).publicKey
            NumericFingerprintGenerator(FINGERPRINT_ITERATIONS)
                .createFor(
                    FINGERPRINT_VERSION,
                    myUserId.toByteArray(Charsets.UTF_8),
                    mine,
                    peerUserId.toByteArray(Charsets.UTF_8),
                    IdentityKey(theirs.identityKey),
                )
                .displayableFingerprint
                .displayText
        }.getOrNull()
    }

    /** True when this person's keys changed since this phone first saw them. */
    suspend fun identityChanged(peerUserId: String): Boolean = withContext(Dispatchers.IO) {
        dao.remoteIdentitiesFor(peerUserId).any { it.changedAt != null }
    }

    /** Called once the person has been told about the change. */
    suspend fun acknowledgeIdentityChange(peerUserId: String) = withContext(Dispatchers.IO) {
        dao.clearIdentityWarning(peerUserId)
    }

    /** Whether everyone here can receive encrypted messages. */
    suspend fun everyoneCanReceive(userIds: List<String>): Boolean = withContext(Dispatchers.IO) {
        runCatching { userIds.all { devicesOf(it).isNotEmpty() } }.getOrDefault(false)
    }

    private fun storeOf(own: OwnIdentityEntity) =
        ViroSignalStore(dao, IdentityKeyPair(own.identityKeyPair), own.registrationId)

    private fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.b64Bytes(): ByteArray = Base64.decode(this, Base64.DEFAULT)

    companion object {
        private const val TAG = "E2ee"
        /** Viro device ids are unique per device, so there is only ever one. */
        private const val DEVICE_NUMBER = 1
        /** How many one-time prekeys to publish at a time. */
        private const val PREKEY_BATCH = 100
        /** How long a device list is trusted before asking again. */
        private const val DEVICE_CACHE_MS = 5 * 60 * 1000L
        /** How long a signed prekey is used before it is replaced. */
        private const val SIGNED_PREKEY_MAX_AGE_MS = 30L * 24 * 3600 * 1000
        /** Signal's own parameters, so the numbers look and behave the same. */
        private const val FINGERPRINT_ITERATIONS = 5200
        private const val FINGERPRINT_VERSION = 2
    }
}

package com.viroreach.core.e2ee

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * The bridge between libsignal and this phone's storage.
 *
 * libsignal drives the protocol and calls in here to load and save state. Every
 * method is synchronous because that is how the library calls it, so callers
 * must stay off the main thread — [E2eeEngine] does that for them.
 *
 * A remote device is addressed by its server id, with device number 1: Viro's
 * device ids are already unique per device, so there is nothing to disambiguate.
 */
internal class ViroSignalStore(
    private val dao: E2eeDao,
    private val identity: IdentityKeyPair,
    private val registrationId: Int,
) : SignalProtocolStore {

    // ------------------------------------------------------------- identity

    override fun getIdentityKeyPair(): IdentityKeyPair = identity

    override fun getLocalRegistrationId(): Int = registrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val key = address.name
        val existing = dao.remoteIdentity(key)
        val now = System.currentTimeMillis()
        if (existing == null) {
            dao.saveRemoteIdentity(
                RemoteIdentityEntity(
                    address = key,
                    userId = "",
                    identityKey = identityKey.serialize(),
                    firstSeenAt = now,
                ),
            )
            return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
        if (existing.identityKey.contentEquals(identityKey.serialize())) {
            return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
        // They reinstalled, changed phone — or someone is in the middle. The
        // message still goes through, as it does in WhatsApp, and the chat says
        // the safety number changed so the two of them can check.
        dao.saveRemoteIdentity(existing.copy(identityKey = identityKey.serialize(), changedAt = now))
        return IdentityKeyStore.IdentityChange.REPLACED_EXISTING
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        // Trust on first use, and keep delivering after a change rather than
        // blocking the conversation: the warning is what makes it visible.
        return true
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        dao.remoteIdentity(address.name)?.let { IdentityKey(it.identityKey) }

    /** Records which person a device belongs to, for safety numbers. */
    fun rememberOwner(address: String, userId: String) {
        val row = dao.remoteIdentity(address) ?: return
        if (row.userId != userId) dao.saveRemoteIdentity(row.copy(userId = userId))
    }

    // -------------------------------------------------------------- session

    override fun loadSession(address: SignalProtocolAddress): SessionRecord? =
        dao.session(address.name)?.let { SessionRecord(it.record) }

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> {
        val rows = dao.sessions(addresses.map { it.name }).associateBy { it.address }
        return addresses.map { a ->
            val row = rows[a.name] ?: throw NoSuchElementException("no session for ${a.name}")
            SessionRecord(row.record)
        }
    }

    override fun getSubDeviceSessions(name: String): List<Int> = emptyList()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        dao.saveSession(SessionEntity(address.name, record.serialize(), System.currentTimeMillis()))
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean = dao.session(address.name) != null

    override fun deleteSession(address: SignalProtocolAddress) = dao.deleteSession(address.name)

    override fun deleteAllSessions(name: String) = dao.deleteSession(name)

    // --------------------------------------------------------------- prekeys

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val row = dao.preKey(preKeyId) ?: throw InvalidKeyIdException("no prekey $preKeyId")
        return PreKeyRecord(row.record)
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        dao.savePreKey(PreKeyEntity(preKeyId, record.serialize()))
    }

    override fun containsPreKey(preKeyId: Int): Boolean = dao.preKey(preKeyId) != null

    override fun removePreKey(preKeyId: Int) = dao.deletePreKey(preKeyId)

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val row = dao.signedPreKey(signedPreKeyId) ?: throw InvalidKeyIdException("no signed prekey $signedPreKeyId")
        return SignedPreKeyRecord(row.record)
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        dao.signedPreKeys().map { SignedPreKeyRecord(it.record) }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        dao.saveSignedPreKey(SignedPreKeyEntity(signedPreKeyId, record.serialize()))
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = dao.signedPreKey(signedPreKeyId) != null

    override fun removeSignedPreKey(signedPreKeyId: Int) = dao.deleteSignedPreKey(signedPreKeyId)

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val row = dao.kyberPreKey(kyberPreKeyId) ?: throw InvalidKeyIdException("no kyber prekey $kyberPreKeyId")
        return KyberPreKeyRecord(row.record)
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        dao.kyberPreKeys().map { KyberPreKeyRecord(it.record) }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        dao.saveKyberPreKey(KyberPreKeyEntity(kyberPreKeyId, record.serialize()))
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = dao.kyberPreKey(kyberPreKeyId) != null

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int) {
        // The published Kyber prekey is the last-resort one, which is reused
        // until it is rotated — so this records the use without deleting it.
        dao.markKyberUsed(kyberPreKeyId)
    }
}

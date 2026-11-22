package com.example.whatsapp

import android.util.Base64
import com.example.data.db.SignalKeyDao
import com.example.data.db.SignalKeyEntity
import com.example.security.SecureCredentialStore
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.groups.state.SenderKeyRecord
import org.whispersystems.libsignal.groups.state.SenderKeyStore
import org.whispersystems.libsignal.groups.SenderKeyName
import org.whispersystems.libsignal.state.PreKeyRecord
import org.whispersystems.libsignal.state.SessionRecord
import org.whispersystems.libsignal.state.SignalProtocolStore
import org.whispersystems.libsignal.state.SignedPreKeyRecord
import org.whispersystems.libsignal.util.KeyHelper

/**
 * Room + Android-Keystore-backed SignalProtocolStore & SenderKeyStore for E2EE WhatsApp sessions.
 */
class WASignalProtocolStore(
  private val credentialStore: SecureCredentialStore,
  private val signalKeyDao: SignalKeyDao,
) : SignalProtocolStore, SenderKeyStore {

  override fun getIdentityKeyPair(): IdentityKeyPair =
    credentialStore.getOrCreateCredentials().toSignalIdentityKeyPair()

  override fun getLocalRegistrationId(): Int =
    credentialStore.getOrCreateCredentials().registrationId

  override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
    val composite = "$TYPE_IDENTITY:${address.name}:${address.deviceId}"
    val existing = signalKeyDao.getByCompositeSync(composite)
    val encoded = Base64.encodeToString(identityKey.serialize(), Base64.NO_WRAP)
    signalKeyDao.upsertSync(
      SignalKeyEntity(
        compositeKey = composite,
        keyType = TYPE_IDENTITY,
        keyId = "${address.name}:${address.deviceId}",
        dataBase64 = encoded,
      )
    )
    return existing != null && existing.dataBase64 != encoded
  }

  override fun isTrustedIdentity(
    address: SignalProtocolAddress,
    identityKey: IdentityKey,
    direction: org.whispersystems.libsignal.state.IdentityKeyStore.Direction,
  ): Boolean {
    // Trust-on-first-use (TOFU) with key-change persistence matching WhatsApp MD companion behavior
    val composite = "$TYPE_IDENTITY:${address.name}:${address.deviceId}"
    val existing = signalKeyDao.getByCompositeSync(composite) ?: return true
    val storedBytes = Base64.decode(existing.dataBase64, Base64.NO_WRAP)
    return storedBytes.contentEquals(identityKey.serialize()) ||
      direction == org.whispersystems.libsignal.state.IdentityKeyStore.Direction.RECEIVING
  }

  override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
    val composite = "$TYPE_IDENTITY:${address.name}:${address.deviceId}"
    val existing = signalKeyDao.getByCompositeSync(composite) ?: return null
    return IdentityKey(Base64.decode(existing.dataBase64, Base64.NO_WRAP), 0)
  }

  override fun loadPreKey(preKeyId: Int): PreKeyRecord {
    val composite = "$TYPE_PREKEY:$preKeyId"
    val entity =
      signalKeyDao.getByCompositeSync(composite)
        ?: throw org.whispersystems.libsignal.InvalidKeyIdException("Missing preKey $preKeyId")
    return PreKeyRecord(Base64.decode(entity.dataBase64, Base64.NO_WRAP))
  }

  override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
    signalKeyDao.upsertSync(
      SignalKeyEntity(
        compositeKey = "$TYPE_PREKEY:$preKeyId",
        keyType = TYPE_PREKEY,
        keyId = preKeyId.toString(),
        dataBase64 = Base64.encodeToString(record.serialize(), Base64.NO_WRAP),
      )
    )
  }

  override fun containsPreKey(preKeyId: Int): Boolean =
    signalKeyDao.getByCompositeSync("$TYPE_PREKEY:$preKeyId") != null

  override fun removePreKey(preKeyId: Int) {
    signalKeyDao.deleteByCompositeSync("$TYPE_PREKEY:$preKeyId")
  }

  override fun loadSession(address: SignalProtocolAddress): SessionRecord {
    val composite = "$TYPE_SESSION:${address.name}:${address.deviceId}"
    val entity = signalKeyDao.getByCompositeSync(composite) ?: return SessionRecord()
    return SessionRecord(Base64.decode(entity.dataBase64, Base64.NO_WRAP))
  }

  override fun getSubDeviceSessions(name: String): MutableList<Int> {
    return signalKeyDao
      .getAllByTypeSync(TYPE_SESSION)
      .mapNotNull { entity ->
        val parts = entity.keyId.split(":")
        if (parts.size == 2 && parts[0] == name) parts[1].toIntOrNull() else null
      }
      .filter { it != 0 }
      .toMutableList()
  }

  override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
    signalKeyDao.upsertSync(
      SignalKeyEntity(
        compositeKey = "$TYPE_SESSION:${address.name}:${address.deviceId}",
        keyType = TYPE_SESSION,
        keyId = "${address.name}:${address.deviceId}",
        dataBase64 = Base64.encodeToString(record.serialize(), Base64.NO_WRAP),
      )
    )
  }

  override fun containsSession(address: SignalProtocolAddress): Boolean =
    signalKeyDao.getByCompositeSync("$TYPE_SESSION:${address.name}:${address.deviceId}") != null

  override fun deleteSession(address: SignalProtocolAddress) {
    signalKeyDao.deleteByCompositeSync("$TYPE_SESSION:${address.name}:${address.deviceId}")
  }

  override fun deleteAllSessions(name: String) {
    signalKeyDao.getAllByTypeSync(TYPE_SESSION).forEach { entity ->
      if (entity.keyId.startsWith("$name:")) {
        signalKeyDao.deleteByCompositeSync(entity.compositeKey)
      }
    }
  }

  override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
    val creds = credentialStore.getOrCreateCredentials()
    if (creds.signedPreKeyId == signedPreKeyId) {
      return creds.toSignedPreKeyRecord()
    }
    val entity =
      signalKeyDao.getByCompositeSync("$TYPE_SIGNED_PREKEY:$signedPreKeyId")
        ?: throw org.whispersystems.libsignal.InvalidKeyIdException("Missing signedPreKey $signedPreKeyId")
    return SignedPreKeyRecord(Base64.decode(entity.dataBase64, Base64.NO_WRAP))
  }

  override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> {
    val list = mutableListOf(credentialStore.getOrCreateCredentials().toSignedPreKeyRecord())
    signalKeyDao.getAllByTypeSync(TYPE_SIGNED_PREKEY).forEach { entity ->
      val rec = SignedPreKeyRecord(Base64.decode(entity.dataBase64, Base64.NO_WRAP))
      if (list.none { it.id == rec.id }) list.add(rec)
    }
    return list
  }

  override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
    signalKeyDao.upsertSync(
      SignalKeyEntity(
        compositeKey = "$TYPE_SIGNED_PREKEY:$signedPreKeyId",
        keyType = TYPE_SIGNED_PREKEY,
        keyId = signedPreKeyId.toString(),
        dataBase64 = Base64.encodeToString(record.serialize(), Base64.NO_WRAP),
      )
    )
  }

  override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
    return credentialStore.getOrCreateCredentials().signedPreKeyId == signedPreKeyId ||
      signalKeyDao.getByCompositeSync("$TYPE_SIGNED_PREKEY:$signedPreKeyId") != null
  }

  override fun removeSignedPreKey(signedPreKeyId: Int) {
    signalKeyDao.deleteByCompositeSync("$TYPE_SIGNED_PREKEY:$signedPreKeyId")
  }

  override fun storeSenderKey(senderKeyName: SenderKeyName, record: SenderKeyRecord) {
    val key = "${senderKeyName.groupId}|${senderKeyName.sender.name}:${senderKeyName.sender.deviceId}"
    signalKeyDao.upsertSync(
      SignalKeyEntity(
        compositeKey = "$TYPE_SENDER_KEY:$key",
        keyType = TYPE_SENDER_KEY,
        keyId = key,
        dataBase64 = Base64.encodeToString(record.serialize(), Base64.NO_WRAP),
      )
    )
  }

  override fun loadSenderKey(senderKeyName: SenderKeyName): SenderKeyRecord {
    val key = "${senderKeyName.groupId}|${senderKeyName.sender.name}:${senderKeyName.sender.deviceId}"
    val entity = signalKeyDao.getByCompositeSync("$TYPE_SENDER_KEY:$key") ?: return SenderKeyRecord()
    return SenderKeyRecord(Base64.decode(entity.dataBase64, Base64.NO_WRAP))
  }

  fun generateAndStorePreKeys(count: Int = 30): List<PreKeyRecord> {
    val creds = credentialStore.getOrCreateCredentials()
    val startId = creds.nextPreKeyId.coerceAtLeast(1)
    val preKeys = KeyHelper.generatePreKeys(startId, count)
    preKeys.forEach { storePreKey(it.id, it) }
    credentialStore.saveCredentials(creds.copy(nextPreKeyId = startId + count))
    return preKeys
  }

  fun clearAllSignalState() {
    signalKeyDao.clearAllSync()
  }

  companion object {
    const val TYPE_SESSION = "SESSION"
    const val TYPE_PREKEY = "PREKEY"
    const val TYPE_SIGNED_PREKEY = "SIGNED_PREKEY"
    const val TYPE_IDENTITY = "IDENTITY"
    const val TYPE_SENDER_KEY = "SENDER_KEY"
  }
}

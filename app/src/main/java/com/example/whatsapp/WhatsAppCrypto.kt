package com.example.whatsapp

import org.whispersystems.libsignal.SessionBuilder
import org.whispersystems.libsignal.SessionCipher
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.ecc.Curve
import org.whispersystems.libsignal.groups.GroupCipher
import org.whispersystems.libsignal.groups.GroupSessionBuilder
import org.whispersystems.libsignal.groups.SenderKeyName
import org.whispersystems.libsignal.protocol.CiphertextMessage
import org.whispersystems.libsignal.protocol.PreKeySignalMessage
import org.whispersystems.libsignal.protocol.SenderKeyDistributionMessage
import org.whispersystems.libsignal.protocol.SignalMessage
import org.whispersystems.libsignal.state.PreKeyBundle
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * WhatsAppCrypto: Signal Protocol 1:1 SessionCipher, Group GroupCipher,
 * WhatsApp message padding, and ADV device-identity signature verification.
 */
class WhatsAppCrypto(
  val signalStore: WASignalProtocolStore,
) {

  fun decryptSignalMessage(
    senderJid: String,
    encType: String,
    ciphertext: ByteArray,
    groupJid: String? = null,
  ): ByteArray {
    val parsedSender = WAJid.parse(senderJid)
    val address: SignalProtocolAddress = parsedSender.toSignalAddress()
    return when (encType) {
      "pkmsg" -> {
        val cipher = SessionCipher(signalStore, address)
        cipher.decrypt(PreKeySignalMessage(ciphertext))
      }
      "msg" -> {
        val cipher = SessionCipher(signalStore, address)
        cipher.decrypt(SignalMessage(ciphertext))
      }
      "skmsg" -> {
        val grp = groupJid ?: throw IllegalArgumentException("skmsg missing group JID")
        val senderKeyName = SenderKeyName(grp, address)
        GroupCipher(signalStore, senderKeyName).decrypt(ciphertext)
      }
      else -> throw IllegalArgumentException("Unsupported Signal enc type: $encType")
    }
  }

  fun processSenderKeyDistribution(
    groupJid: String,
    senderJid: String,
    distributionBytes: ByteArray,
  ) {
    val address = WAJid.parse(senderJid).toSignalAddress()
    val senderKeyName = SenderKeyName(groupJid, address)
    GroupSessionBuilder(signalStore).process(
      senderKeyName,
      SenderKeyDistributionMessage(distributionBytes),
    )
  }

  fun hasSession(targetJid: String): Boolean {
    return signalStore.containsSession(WAJid.parse(targetJid).toSignalAddress())
  }

  fun establishSession(
    targetJid: String,
    registrationId: Int,
    deviceId: Int,
    preKeyId: Int,
    preKeyPublic: ByteArray,
    signedPreKeyId: Int,
    signedPreKeyPublic: ByteArray,
    signedPreKeySignature: ByteArray,
    identityKeyBytes: ByteArray,
  ) {
    val parsed = WAJid.parse(targetJid).copy(device = deviceId)
    val addr = parsed.toSignalAddress()
    val preKeyPoint = Curve.decodePoint(ensureKeyPrefix(preKeyPublic), 0)
    val spkPoint = Curve.decodePoint(ensureKeyPrefix(signedPreKeyPublic), 0)
    val idKey = org.whispersystems.libsignal.IdentityKey(ensureKeyPrefix(identityKeyBytes), 0)
    val bundle =
      PreKeyBundle(
        registrationId,
        deviceId,
        preKeyId,
        preKeyPoint,
        signedPreKeyId,
        spkPoint,
        signedPreKeySignature,
        idKey,
      )
    SessionBuilder(signalStore, addr).process(bundle)
  }

  data class EncryptedSignalEnvelope(
    val encType: String, // pkmsg | msg
    val ciphertext: ByteArray,
  )

  fun encryptSignalMessage(targetJid: String, plaintextProto: ByteArray): EncryptedSignalEnvelope {
    val addr = WAJid.parse(targetJid).toSignalAddress()
    val padded = applyWhatsAppRandomPadding(plaintextProto)
    val ciphertextMessage = SessionCipher(signalStore, addr).encrypt(padded)
    val encType =
      if (ciphertextMessage.type == CiphertextMessage.PREKEY_TYPE) "pkmsg" else "msg"
    return EncryptedSignalEnvelope(encType = encType, ciphertext = ciphertextMessage.serialize())
  }

  fun encryptGroupMessage(groupJid: String, selfJid: String, plaintextProto: ByteArray): ByteArray {
    val addr = WAJid.parse(selfJid).toSignalAddress()
    val senderKeyName = SenderKeyName(groupJid, addr)
    val padded = applyWhatsAppRandomPadding(plaintextProto)
    return GroupCipher(signalStore, senderKeyName).encrypt(padded)
  }

  fun verifyAdvHmac(details: ByteArray, hmac: ByteArray, advSecretKey: ByteArray, isHosted: Boolean): Boolean {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(advSecretKey, "HmacSHA256"))
    if (isHosted) {
      mac.update(byteArrayOf(6, 5))
    }
    mac.update(details)
    return mac.doFinal().contentEquals(hmac)
  }

  fun verifyAdvAccountSignature(
    details: ByteArray,
    accountSignatureKey: ByteArray,
    accountSignature: ByteArray,
    identityRawPublic32: ByteArray,
  ): Boolean {
    val prefix = byteArrayOf(6, 0)
    val msg = prefix + details + identityRawPublic32
    val pubKey = Curve.decodePoint(ensureKeyPrefix(accountSignatureKey), 0)
    return Curve.verifySignature(pubKey, msg, accountSignature)
  }

  fun signAdvDeviceIdentity(
    details: ByteArray,
    accountSignatureKey: ByteArray,
    identityPrivateKey: ByteArray,
  ): ByteArray {
    val prefix = byteArrayOf(6, 1)
    val msg = prefix + details + accountSignatureKey
    val priv = Curve.decodePrivatePoint(identityPrivateKey)
    return Curve.calculateSignature(priv, msg)
  }

  private fun applyWhatsAppRandomPadding(plain: ByteArray): ByteArray {
    val padLen = (SecureRandom().nextInt(15) + 1)
    val out = ByteArray(plain.size + padLen)
    System.arraycopy(plain, 0, out, 0, plain.size)
    for (i in plain.size until out.size) {
      out[i] = padLen.toByte()
    }
    return out
  }

  private fun ensureKeyPrefix(bytes: ByteArray): ByteArray {
    return if (bytes.size == 32) byteArrayOf(0x05) + bytes else bytes
  }
}

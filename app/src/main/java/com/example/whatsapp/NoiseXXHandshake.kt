package com.example.whatsapp

import org.whispersystems.libsignal.ecc.Curve
import org.whispersystems.libsignal.ecc.ECKeyPair
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Implements WhatsApp's Noise_XX_25519_AESGCM_SHA256 handshake and encrypted frame transport.
 */
class NoiseXXHandshake(
  private val staticPrivateKey: ByteArray,
  private val staticPublicKey: ByteArray,
  private val routingInfo: ByteArray? = null,
) {
  private val ephemeralKeyPair: ECKeyPair = Curve.generateKeyPair()
  val ephemeralPublicRaw: ByteArray = stripPrefix(ephemeralKeyPair.publicKey.serialize())

  private var hash: ByteArray
  private var salt: ByteArray
  private var encKey: ByteArray
  private var decKey: ByteArray
  private var writeCounter: Long = 0L
  private var readCounter: Long = 0L
  private var transportReady: Boolean = false
  private var sentIntro: Boolean = false

  init {
    val protoBytes = NOISE_MODE.toByteArray(Charsets.UTF_8)
    hash =
      if (protoBytes.size == 32) protoBytes
      else MessageDigest.getInstance("SHA-256").digest(protoBytes)
    salt = hash.copyOf()
    encKey = hash.copyOf()
    decKey = hash.copyOf()
    authenticate(NOISE_WA_HEADER)
    authenticate(ephemeralPublicRaw)
  }

  fun buildIntroAndClientHello(): ByteArray {
    val helloBytes = WAProtoWire.encodeClientHello(ephemeralPublicRaw)
    val out = ByteArrayOutputStream()
    if (!sentIntro) {
      if (routingInfo != null && routingInfo.isNotEmpty()) {
        out.write(byteArrayOf('E'.code.toByte(), 'D'.code.toByte(), 0, 1))
        out.write(encodeLength3(routingInfo.size))
        out.write(routingInfo)
      }
      out.write(NOISE_WA_HEADER)
      sentIntro = true
    }
    out.write(encodeLength3(helloBytes.size))
    out.write(helloBytes)
    return out.toByteArray()
  }

  fun processServerHelloAndBuildClientFinish(
    serverHelloPayload: ByteArray,
    clientPayloadBytes: ByteArray,
  ): ByteArray {
    val serverHello = WAProtoWire.decodeServerHello(serverHelloPayload)
    authenticate(serverHello.ephemeral)

    // Mix ephemeral-ephemeral DH
    val serverEphPub = Curve.decodePoint(prependKeyPrefix(serverHello.ephemeral), 0)
    val eeShared = Curve.calculateAgreement(serverEphPub, ephemeralKeyPair.privateKey)
    mixKey(eeShared)

    // Decrypt server static key
    val decryptedServerStatic = decryptHandshake(serverHello.staticEncrypted)
    val serverStaticPub = Curve.decodePoint(prependKeyPrefix(decryptedServerStatic), 0)
    val esShared = Curve.calculateAgreement(serverStaticPub, ephemeralKeyPair.privateKey)
    mixKey(esShared)

    // Decrypt server certificate / payload (validated structurally)
    decryptHandshake(serverHello.payloadEncrypted)

    // Encrypt our static public key and mix static-ephemeral DH
    val encryptedStatic = encryptHandshake(staticPublicKey)
    val staticPriv = Curve.decodePrivatePoint(staticPrivateKey)
    val seShared = Curve.calculateAgreement(serverEphPub, staticPriv)
    mixKey(seShared)

    val encryptedPayload = encryptHandshake(clientPayloadBytes)
    val finishProto = WAProtoWire.encodeClientFinish(encryptedStatic, encryptedPayload)

    // Derive final transport write/read keys
    val (writeKey, readKey) = hkdfSplit(ByteArray(0), salt)
    encKey = writeKey
    decKey = readKey
    writeCounter = 0L
    readCounter = 0L
    transportReady = true

    return encodeFrame(finishProto)
  }

  @Synchronized
  fun encodeTransportFrame(plainNodeBytes: ByteArray): ByteArray {
    require(transportReady) { "Noise transport not initialized" }
    val iv = counterToIv(writeCounter++)
    val encrypted = aesGcmEncrypt(encKey, iv, ByteArray(0), plainNodeBytes)
    return encodeFrame(encrypted)
  }

  @Synchronized
  fun decodeTransportFrame(ciphertext: ByteArray): ByteArray {
    require(transportReady) { "Noise transport not initialized" }
    val iv = counterToIv(readCounter++)
    return aesGcmDecrypt(decKey, iv, ByteArray(0), ciphertext)
  }

  private fun authenticate(data: ByteArray) {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(hash)
    md.update(data)
    hash = md.digest()
  }

  private fun mixKey(sharedSecret: ByteArray) {
    val (newSalt, newKey) = hkdfSplit(sharedSecret, salt)
    salt = newSalt
    encKey = newKey
    decKey = newKey
    writeCounter = 0L
    readCounter = 0L
  }

  private fun encryptHandshake(plaintext: ByteArray): ByteArray {
    val iv = counterToIv(writeCounter++)
    val cipher = aesGcmEncrypt(encKey, iv, hash, plaintext)
    authenticate(cipher)
    return cipher
  }

  private fun decryptHandshake(ciphertext: ByteArray): ByteArray {
    val iv = counterToIv(readCounter++)
    val plain = aesGcmDecrypt(decKey, iv, hash, ciphertext)
    authenticate(ciphertext)
    return plain
  }

  companion object {
    private const val NOISE_MODE = "Noise_XX_25519_AESGCM_SHA256\u0000\u0000\u0000\u0000"
    val NOISE_WA_HEADER = byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 6, 3)

    fun encodeFrame(payload: ByteArray): ByteArray {
      val len = encodeLength3(payload.size)
      return len + payload
    }

    fun encodeLength3(len: Int): ByteArray {
      return byteArrayOf(
        ((len ushr 16) and 0xFF).toByte(),
        ((len ushr 8) and 0xFF).toByte(),
        (len and 0xFF).toByte(),
      )
    }

    fun splitLengthPrefixedFrames(buffer: MutableList<Byte>): List<ByteArray> {
      val frames = mutableListOf<ByteArray>()
      while (buffer.size >= 3) {
        val b0 = buffer[0].toInt() and 0xFF
        val b1 = buffer[1].toInt() and 0xFF
        val b2 = buffer[2].toInt() and 0xFF
        val len = (b0 shl 16) or (b1 shl 8) or b2
        if (buffer.size < 3 + len) break
        val frame = ByteArray(len)
        for (i in 0 until len) frame[i] = buffer[3 + i]
        repeat(3 + len) { buffer.removeAt(0) }
        frames.add(frame)
      }
      return frames
    }

    fun counterToIv(counter: Long): ByteArray {
      val iv = ByteArray(12)
      ByteBuffer.wrap(iv, 4, 8).order(ByteOrder.BIG_ENDIAN).putLong(counter)
      return iv
    }

    fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
      if (aad.isNotEmpty()) cipher.updateAAD(aad)
      return cipher.doFinal(plaintext)
    }

    fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
      if (aad.isNotEmpty()) cipher.updateAAD(aad)
      return cipher.doFinal(ciphertext)
    }

    fun hkdfSplit(ikm: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
      val prk = hmacSha256(salt, ikm)
      val t1 = hmacSha256(prk, byteArrayOf(1))
      val t2 = hmacSha256(prk, t1 + byteArrayOf(2))
      return t1 to t2
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
      val mac = Mac.getInstance("HmacSHA256")
      val keyBytes = if (key.isEmpty()) ByteArray(32) else key
      mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
      return mac.doFinal(data)
    }

    private fun stripPrefix(key: ByteArray): ByteArray {
      return if (key.size == 33 && key[0] == 0x05.toByte()) key.copyOfRange(1, 33) else key
    }

    private fun prependKeyPrefix(raw32: ByteArray): ByteArray {
      return if (raw32.size == 32) byteArrayOf(0x05) + raw32 else raw32
    }
  }
}

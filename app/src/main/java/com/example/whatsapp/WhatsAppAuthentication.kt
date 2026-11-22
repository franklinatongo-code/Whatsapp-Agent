package com.example.whatsapp

import android.content.Context
import android.util.Base64
import com.example.security.SecureCredentialStore
import com.example.security.WhatsAppAuthCredentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * WhatsAppAuthentication: Handles zero-latency cached WA client version resolution,
 * QR pairing string generation, Phone-Number Pairing Code cryptography,
 * resilient ADV pair-success validation, pre-key upload, and pre-key bundle fetch/parsing.
 */
class WhatsAppAuthentication(
  private val credentialStore: SecureCredentialStore,
  private val crypto: WhatsAppCrypto,
  context: Context? = null,
) {

  private val versionPrefs =
    context?.applicationContext?.getSharedPreferences("wa_version_cache", Context.MODE_PRIVATE)

  init {
    if (cachedVersion == null && versionPrefs != null) {
      val rev = versionPrefs.getInt("rev", 0)
      val ts = versionPrefs.getLong("ts", 0L)
      if (rev > 1_000_000) {
        cachedVersion = Triple(2, 3000, rev)
        cachedVersionAtMs = ts
      }
    }
  }

  data class PairSuccessResult(
    val credentials: WhatsAppAuthCredentials,
    val replyNode: WABinaryNode,
  )

  fun buildQrPayload(refToken: String, creds: WhatsAppAuthCredentials): String {
    val noisePubB64 = Base64.encodeToString(creds.noisePublicKey, Base64.NO_WRAP)
    val idPubB64 = Base64.encodeToString(creds.identityRawPublicKey, Base64.NO_WRAP)
    val advB64 = Base64.encodeToString(creds.advSecretKey, Base64.NO_WRAP)
    return "$refToken,$noisePubB64,$idPubB64,$advB64"
  }

  fun buildPairDeviceAck(stanzaId: String): WABinaryNode {
    return WABinaryNode(
      tag = "iq",
      attrs = linkedMapOf("to" to WAJid.SERVER_USER, "type" to "result", "id" to stanzaId),
    )
  }

  fun handlePairSuccess(node: WABinaryNode): PairSuccessResult {
    val creds = credentialStore.getOrCreateCredentials()
    val pairSuccess =
      node.child("pair-success")
        ?: node.findRecursive { it.findRecursive { c -> c.attrs.containsKey("jid") } != null && it.findRecursive { c -> (c.contentBytes()?.size ?: 0) >= 64 } != null }
        ?: throw IllegalArgumentException("Missing pair-success child node")

    val deviceNode =
      pairSuccess.child("device")
        ?: pairSuccess.findRecursive { it.attrs["jid"]?.contains("@") == true }
    val deviceJid = deviceNode?.attrs?.get("jid")
      ?: throw IllegalArgumentException("Missing paired device JID")
    val deviceLid = deviceNode.attrs["lid"]
    val bizName = pairSuccess.child("biz")?.attrs?.get("name")
    val platform = pairSuccess.child("platform")?.attrs?.get("name") ?: "Android"

    val devIdentityBytes =
      pairSuccess.child("device-identity")?.contentBytes()
        ?: pairSuccess.findRecursive { child ->
          val b = child.contentBytes() ?: return@findRecursive false
          b.size >= 64 && runCatching {
            val h = WAProtoWire.decodeSignedDeviceIdentityHmac(b)
            h.details.isNotEmpty() && h.hmac.size == 32
          }.getOrDefault(false)
        }?.contentBytes()
        ?: throw IllegalArgumentException("Missing device-identity bytes")

    val signedHmac = WAProtoWire.decodeSignedDeviceIdentityHmac(devIdentityBytes)
    val isHosted = signedHmac.accountType == 1
    if (!crypto.verifyAdvHmac(signedHmac.details, signedHmac.hmac, creds.advSecretKey, isHosted)) {
      throw SecurityException("ADV device-identity HMAC verification failed")
    }

    val accountIdentity = WAProtoWire.decodeSignedDeviceIdentity(signedHmac.details)
    if (!crypto.verifyAdvAccountSignature(
        details = accountIdentity.details,
        accountSignatureKey = accountIdentity.accountSignatureKey,
        accountSignature = accountIdentity.accountSignature,
        identityRawPublic32 = creds.identityRawPublicKey,
      )
    ) {
      throw SecurityException("ADV account signature verification failed")
    }

    val deviceSignature =
      crypto.signAdvDeviceIdentity(
        details = accountIdentity.details,
        accountSignatureKey = accountIdentity.accountSignatureKey,
        identityPrivateKey = creds.identityPrivateKey,
      )

    val updatedCreds =
      creds.copy(
        meJid = deviceJid,
        meLid = deviceLid,
        meName = bizName ?: creds.meName,
        platform = platform,
        accountSignatureKey = accountIdentity.accountSignatureKey,
        accountSignature = accountIdentity.accountSignature,
        deviceSignature = deviceSignature,
        accountDetails = accountIdentity.details,
      )
    credentialStore.saveCredentials(updatedCreds)

    val replyIdentity =
      WAProtoWire.encodeSignedDeviceIdentity(
        details = accountIdentity.details,
        accountSignatureKey = ByteArray(0),
        accountSignature = accountIdentity.accountSignature,
        deviceSignature = deviceSignature,
      )

    val detailsFields = WAProtoWire.decodeFields(accountIdentity.details)
    val keyIndex = detailsFields[3]?.firstOrNull()?.varint?.toString() ?: "0"

    val replyNode =
      WABinaryNode(
        tag = "iq",
        attrs =
          linkedMapOf(
            "to" to WAJid.SERVER_USER,
            "type" to "result",
            "id" to (node.attrs["id"] ?: "pair-ack"),
          ),
        content =
          listOf(
            WABinaryNode(
              tag = "pair-device-sign",
              content =
                listOf(
                  WABinaryNode(
                    tag = "device-identity",
                    attrs = linkedMapOf("key-index" to keyIndex),
                    content = replyIdentity,
                  )
                ),
            )
          ),
      )
    return PairSuccessResult(credentials = updatedCreds, replyNode = replyNode)
  }

  fun buildPhonePairingRequest(
    stanzaId: String,
    phoneDigitsOnly: String,
    creds: WhatsAppAuthCredentials,
  ): Pair<String, WABinaryNode> {
    val cleanPhone = phoneDigitsOnly.filter { it.isDigit() }
    require(cleanPhone.length >= 7) { "Enter full phone number with country code" }

    val codeBytes = ByteArray(5).also { SecureRandom().nextBytes(it) }
    val pairingCode = encodeCrockfordBase32(codeBytes).take(8)
    val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }

    val derivedKey = derivePairingKey(pairingCode, salt)
    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derivedKey, "AES"), IvParameterSpec(iv))
    val wrappedEphemeral = salt + iv + cipher.doFinal(creds.noisePublicKey)

    val iq =
      WABinaryNode(
        tag = "iq",
        attrs =
          linkedMapOf(
            "to" to WAJid.SERVER_USER,
            "type" to "set",
            "id" to stanzaId,
            "xmlns" to "md",
          ),
        content =
          listOf(
            WABinaryNode(
              tag = "link_code_companion_reg",
              attrs = linkedMapOf("jid" to "$cleanPhone@${WAJid.SERVER_USER}", "stage" to "companion_hello"),
              content =
                listOf(
                  WABinaryNode(tag = "link_code_pairing_wrapped_companion_ephemeral_pub", content = wrappedEphemeral),
                  WABinaryNode(tag = "companion_server_auth_key_pub", content = creds.noisePublicKey),
                  WABinaryNode(tag = "companion_platform_id", content = "1"),
                  WABinaryNode(tag = "companion_platform_display", content = "Chrome (Linux)"),
                  WABinaryNode(tag = "link_code_pairing_nonce", content = "0"),
                ),
            )
          ),
      )
    return pairingCode to iq
  }

  fun buildPreKeyUploadIq(stanzaId: String, count: Int = 30): WABinaryNode {
    val creds = credentialStore.getOrCreateCredentials()
    val preKeys = crypto.signalStore.generateAndStorePreKeys(count)

    val regIdBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(creds.registrationId).array()
    val spkIdBytes = intTo3Bytes(creds.signedPreKeyId)
    val spkPubRaw = stripPrefix(creds.signedPreKeyPublic)

    val keyNodes =
      preKeys.map { pk ->
        WABinaryNode(
          tag = "key",
          content =
            listOf(
              WABinaryNode(tag = "id", content = intTo3Bytes(pk.id)),
              WABinaryNode(tag = "value", content = stripPrefix(pk.keyPair.publicKey.serialize())),
            ),
        )
      }

    return WABinaryNode(
      tag = "iq",
      attrs =
        linkedMapOf(
          "id" to stanzaId,
          "xmlns" to "encrypt",
          "type" to "set",
          "to" to WAJid.SERVER_USER,
        ),
      content =
        listOf(
          WABinaryNode(tag = "registration", content = regIdBytes),
          WABinaryNode(tag = "type", content = byteArrayOf(5)),
          WABinaryNode(tag = "identity", content = creds.identityRawPublicKey),
          WABinaryNode(tag = "list", content = keyNodes),
          WABinaryNode(
            tag = "skey",
            content =
              listOf(
                WABinaryNode(tag = "id", content = spkIdBytes),
                WABinaryNode(tag = "value", content = spkPubRaw),
                WABinaryNode(tag = "signature", content = creds.signedPreKeySignature),
              ),
          ),
        ),
    )
  }

  fun buildPreKeyFetchIq(stanzaId: String, targetJid: String): WABinaryNode {
    return WABinaryNode(
      tag = "iq",
      attrs =
        linkedMapOf(
          "id" to stanzaId,
          "xmlns" to "encrypt",
          "type" to "get",
          "to" to WAJid.SERVER_USER,
        ),
      content =
        listOf(
          WABinaryNode(
            tag = "key",
            content = listOf(WABinaryNode(tag = "user", attrs = linkedMapOf("jid" to targetJid))),
          )
        ),
    )
  }

  fun parseAndEstablishPreKeySessions(resultNode: WABinaryNode, fallbackJid: String): Int {
    val userNodes = resultNode.collectAll().filter { it.tag == "user" }
    var established = 0
    for (userNode in userNodes) {
      val jid = userNode.attrs["jid"] ?: fallbackJid
      val parsed = WAJid.parse(jid)
      val regBytes = userNode.child("registration")?.contentBytes() ?: continue
      val regId = bytesToIntBE(regBytes)
      val identityBytes = userNode.child("identity")?.contentBytes() ?: continue
      val skeyNode = userNode.child("skey") ?: continue
      val skeyId = bytesToIntBE(skeyNode.child("id")?.contentBytes() ?: continue)
      val skeyVal = skeyNode.child("value")?.contentBytes() ?: continue
      val skeySig = skeyNode.child("signature")?.contentBytes() ?: continue

      val preKeyNode = userNode.child("key")
      val preKeyId = preKeyNode?.child("id")?.contentBytes()?.let { bytesToIntBE(it) } ?: 0
      val preKeyVal = preKeyNode?.child("value")?.contentBytes() ?: skeyVal

      runCatching {
        crypto.establishSession(
          targetJid = jid,
          registrationId = regId,
          deviceId = parsed.device,
          preKeyId = preKeyId,
          preKeyPublic = preKeyVal,
          signedPreKeyId = skeyId,
          signedPreKeyPublic = skeyVal,
          signedPreKeySignature = skeySig,
          identityKeyBytes = identityBytes,
        )
        established++
      }
    }
    return established
  }

  fun buildClientPayload(
    creds: WhatsAppAuthCredentials,
    appVersion: Triple<Int, Int, Int> = getImmediateWaWebVersion(),
  ): ByteArray {
    return if (creds.isRegistered && !creds.meJid.isNullOrBlank()) {
      val parsed = WAJid.parse(creds.meJid)
      val username = parsed.user.toLongOrNull() ?: 0L
      WAProtoWire.encodeLoginClientPayload(
        username = username,
        deviceId = parsed.device,
        passive = true,
        appVersion = appVersion,
      )
    } else {
      WAProtoWire.encodeWebRegistrationClientPayload(
        regId = creds.registrationId,
        identityPub32 = creds.identityRawPublicKey,
        signedPreKeyId = creds.signedPreKeyId,
        signedPreKeyPub32 = stripPrefix(creds.signedPreKeyPublic),
        signedPreKeySig64 = creds.signedPreKeySignature,
        deviceName = "Ubuntu",
        appVersion = appVersion,
      )
    }
  }

  /** Instant non-blocking version lookup (0ms delay for fast QR generation). */
  fun getImmediateWaWebVersion(): Triple<Int, Int, Int> = cachedVersion ?: DEFAULT_WA_VERSION

  fun isVersionCacheStale(): Boolean {
    val now = System.currentTimeMillis()
    return cachedVersion == null || (now - cachedVersionAtMs) >= VERSION_CACHE_TTL_MS
  }

  fun resolveLatestWaWebVersion(forceRefresh: Boolean = false): Triple<Int, Int, Int> {
    val now = System.currentTimeMillis()
    val existing = cachedVersion
    if (!forceRefresh && existing != null && (now - cachedVersionAtMs) < VERSION_CACHE_TTL_MS) {
      return existing
    }
    return runCatching {
      val client =
        OkHttpClient.Builder()
          .connectTimeout(3, TimeUnit.SECONDS)
          .readTimeout(3, TimeUnit.SECONDS)
          .build()
      val req =
        Request.Builder()
          .url("https://web.whatsapp.com/sw.js")
          .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
          .build()
      client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return@use null
        val body = resp.body?.string().orEmpty()
        val match = Regex("\"client_revision\"\\s*:\\s*(\\d+)").find(body)
        val rev = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (rev != null && rev > 1_000_000) {
          Triple(2, 3000, rev).also {
            cachedVersion = it
            cachedVersionAtMs = now
            versionPrefs?.edit()?.putInt("rev", rev)?.putLong("ts", now)?.apply()
          }
        } else null
      }
    }.getOrNull() ?: (existing ?: DEFAULT_WA_VERSION)
  }

  fun invalidateVersionCache() {
    cachedVersion = null
    cachedVersionAtMs = 0L
    versionPrefs?.edit()?.clear()?.apply()
  }

  companion object {
    private val DEFAULT_WA_VERSION = Triple(2, 3000, 1027934701)
    private const val VERSION_CACHE_TTL_MS = 6L * 60L * 60L * 1000L
    @Volatile private var cachedVersion: Triple<Int, Int, Int>? = null
    @Volatile private var cachedVersionAtMs: Long = 0L
  }

  private fun derivePairingKey(code: String, salt: ByteArray): ByteArray {
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val spec = PBEKeySpec(code.toCharArray(), salt, 131_072, 256)
    return factory.generateSecret(spec).encoded
  }

  private fun encodeCrockfordBase32(bytes: ByteArray): String {
    val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    val sb = StringBuilder()
    var buffer = 0
    var bitsLeft = 0
    for (b in bytes) {
      buffer = (buffer shl 8) or (b.toInt() and 0xFF)
      bitsLeft += 8
      while (bitsLeft >= 5) {
        sb.append(alphabet[(buffer ushr (bitsLeft - 5)) and 0x1F])
        bitsLeft -= 5
      }
    }
    if (bitsLeft > 0) {
      sb.append(alphabet[(buffer shl (5 - bitsLeft)) and 0x1F])
    }
    return sb.toString()
  }

  private fun intTo3Bytes(v: Int): ByteArray =
    byteArrayOf(((v shr 16) and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

  private fun bytesToIntBE(bytes: ByteArray): Int {
    var result = 0
    for (b in bytes) {
      result = (result shl 8) or (b.toInt() and 0xFF)
    }
    return result
  }

  private fun stripPrefix(bytes: ByteArray): ByteArray =
    if (bytes.size == 33 && bytes[0] == 0x05.toByte()) bytes.copyOfRange(1, 33) else bytes
}

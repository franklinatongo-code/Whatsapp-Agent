package com.example

import com.example.agent.ClawAgentRuntime
import com.example.whatsapp.NoiseXXHandshake
import com.example.whatsapp.WABinaryCodec
import com.example.whatsapp.WABinaryNode
import com.example.whatsapp.WAJid
import com.example.whatsapp.WAProtoWire
import com.example.whatsapp.WhatsAppMediaCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.whispersystems.libsignal.ecc.Curve

class WhatsAppProtocolTest {

  @Test
  fun `WABinaryCodec encodes and decodes nested node with JID and binary payload`() {
    val orig =
      WABinaryNode(
        tag = "iq",
        attrs = linkedMapOf("id" to "test-123", "to" to "s.whatsapp.net", "type" to "set", "xmlns" to "md"),
        content =
          listOf(
            WABinaryNode(
              tag = "pair-device",
              content = listOf(WABinaryNode(tag = "ref", content = "ref-token-abc".toByteArray())),
            )
          ),
      )

    val encoded = WABinaryCodec.encodeFrame(orig)
    val decoded = WABinaryCodec.decodeFrame(encoded)

    assertEquals("iq", decoded.tag)
    assertEquals("test-123", decoded.attrs["id"])
    assertEquals("s.whatsapp.net", decoded.attrs["to"])
    val pairDevice = decoded.child("pair-device")!!
    assertEquals("ref-token-abc", pairDevice.child("ref")!!.contentString())
  }

  @Test
  fun `WAProtoWire encodes and decodes text and quoted reply messages`() {
    val rawText = WAProtoWire.encodeTextMessage("Hello from ClawLink")
    val decodedText = WAProtoWire.decodeWhatsAppMessage(rawText) as WAProtoWire.DecodedWhatsAppPayload.Text
    assertEquals("Hello from ClawLink", decodedText.body)

    val replyBytes =
      WAProtoWire.encodeTextMessage(
        text = "Replying cleanly",
        quotedMessageId = "MSG-99",
        quotedParticipant = "15550001234@s.whatsapp.net",
        quotedText = "Original question",
      )
    val decodedReply = WAProtoWire.decodeWhatsAppMessage(replyBytes) as WAProtoWire.DecodedWhatsAppPayload.Text
    assertEquals("Replying cleanly", decodedReply.body)
    assertEquals("MSG-99", decodedReply.quotedId)
    assertEquals("Original question", decodedReply.quotedText)
  }

  @Test
  fun `NoiseXXHandshake intro framing and AES-GCM transport roundtrip`() {
    val kp = Curve.generateKeyPair()
    val priv = kp.privateKey.serialize()
    val pub = kp.publicKey.serialize().let { if (it.size == 33) it.copyOfRange(1, 33) else it }
    val noise = NoiseXXHandshake(priv, pub)
    val intro = noise.buildIntroAndClientHello()
    assertEquals('W'.code.toByte(), intro[0])
    assertEquals('A'.code.toByte(), intro[1])

    val key = ByteArray(32) { it.toByte() }
    val iv = NoiseXXHandshake.counterToIv(42L)
    val plain = "WhatsApp Noise Test".toByteArray()
    val cipher = NoiseXXHandshake.aesGcmEncrypt(key, iv, ByteArray(0), plain)
    val decrypted = NoiseXXHandshake.aesGcmDecrypt(key, iv, ByteArray(0), cipher)
    assertArrayEquals(plain, decrypted)

    val frame = NoiseXXHandshake.encodeFrame(plain)
    val buf = frame.toMutableList()
    val split = NoiseXXHandshake.splitLengthPrefixedFrames(buf)
    assertEquals(1, split.size)
    assertArrayEquals(plain, split.first())
    assertTrue(buf.isEmpty())
  }

  @Test
  fun `WhatsAppMediaCrypto encrypts and decrypts with HMAC verification`() {
    val sample = "Secret voice note or PDF bytes 1234567890".toByteArray()
    val encrypted = WhatsAppMediaCrypto.encryptMediaBytes(sample, "IMAGE")
    val decrypted = WhatsAppMediaCrypto.decryptMediaBytes(encrypted.encryptedBytes, encrypted.mediaKey, "IMAGE")
    assertArrayEquals(sample, decrypted)
  }

  @Test
  fun `WAJid normalizes devices and compares bare users`() {
    val parsed = WAJid.parse("15551234567:4@s.whatsapp.net")
    assertEquals("15551234567", parsed.user)
    assertEquals(4, parsed.device)
    assertEquals("15551234567@s.whatsapp.net", parsed.toBareJid())
    assertTrue(WAJid.areSameUser("15551234567:4@s.whatsapp.net", "15551234567@s.whatsapp.net"))
  }

  @Test
  fun `splitLongReply breaks oversized replies cleanly`() {
    val longText = List(40) { "Sentence number $it with enough length to exceed chunk size." }.joinToString(". ")
    val chunks = ClawAgentRuntime.splitLongReply(longText, maxChunkChars = 400)
    assertTrue(chunks.size > 1)
    assertTrue(chunks.all { it.length <= 400 })
  }

  @Test
  fun `WABinaryCodec roundtrips AD_JID LID and non-token strings without control tag collision`() {
    val node =
      WABinaryNode(
        tag = "iq",
        attrs = linkedMapOf(
          "id" to "pair-custom-id",
          "to" to "15551234567:2@lid",
          "from" to "15559876543@s.whatsapp.net",
          "xmlns" to "md",
          "type" to "set",
        ),
        content = listOf(
          WABinaryNode(
            tag = "pair-device-sign",
            attrs = linkedMapOf("stage" to "companion_finish"),
            content = "custom_literal_payload".toByteArray(),
          )
        ),
      )

    val encoded = WABinaryCodec.encodeFrame(node)
    val decoded = WABinaryCodec.decodeFrame(encoded)

    assertEquals("iq", decoded.tag)
    assertEquals("pair-custom-id", decoded.attrs["id"])
    assertEquals("15551234567:2@lid", decoded.attrs["to"])
    assertEquals("15559876543@s.whatsapp.net", decoded.attrs["from"])
    val child = decoded.child("pair-device-sign")!!
    assertEquals("companion_finish", child.attrs["stage"])
    assertEquals("custom_literal_payload", child.contentString())
  }

  @Test
  fun `WAProtoWire registration ClientPayload uses valid UserAgent wire types and MD5 buildHash`() {
    val payload =
      WAProtoWire.encodeWebRegistrationClientPayload(
        regId = 12345,
        identityPub32 = ByteArray(32) { 1 },
        signedPreKeyId = 1,
        signedPreKeyPub32 = ByteArray(32) { 2 },
        signedPreKeySig64 = ByteArray(64) { 3 },
        deviceName = "Ubuntu",
        appVersion = Triple(2, 3000, 1027934701),
      )
    val root = WAProtoWire.decodeFields(payload)
    val uaBytes = root[5]!!.first().bytes
    val ua = WAProtoWire.decodeFields(uaBytes)
    assertEquals(WAProtoWire.WIRE_LENGTH_DELIMITED, ua[7]!!.first().wireType) // device string
    assertEquals("Desktop", ua[7]!!.first().bytes.toString(Charsets.UTF_8))
    assertEquals(WAProtoWire.WIRE_VARINT, ua[10]!!.first().wireType) // releaseChannel enum
    assertEquals("en", ua[11]!!.first().bytes.toString(Charsets.UTF_8))

    val pairingBytes = root[19]!!.first().bytes
    val pairing = WAProtoWire.decodeFields(pairingBytes)
    val buildHash = pairing[7]!!.first().bytes
    assertEquals(16, buildHash.size)
    assertTrue(buildHash.any { it.toInt() != 0 })
  }

  @Test
  fun `HiddenVault verifies PIN 1957 only and unlocks owner API key cleanly`() {
    org.junit.Assert.assertFalse(com.example.security.HiddenVault.verifyPin("0000"))
    org.junit.Assert.assertFalse(com.example.security.HiddenVault.verifyPin("1958"))
    org.junit.Assert.assertNull(com.example.security.HiddenVault.unlock("1234"))
    assertTrue(com.example.security.HiddenVault.verifyPin("1957"))
    val unlocked = com.example.security.HiddenVault.unlock("1957")
    assertEquals("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", unlocked)
    assertTrue(com.example.BuildConfig.NVIDIA_API_KEY == "MY_NVIDIA_API_KEY" || com.example.BuildConfig.NVIDIA_API_KEY.isBlank())
  }

  @Test
  fun `AutomationRulesEngine evaluates midnight wraparound DND windows`() {
    val engine = com.example.agent.AutomationRulesEngine()
    assertTrue(engine.isWithinQuietHours(currentHour = 23, startHour = 22, endHour = 6))
    assertTrue(engine.isWithinQuietHours(currentHour = 2, startHour = 22, endHour = 6))
    org.junit.Assert.assertFalse(engine.isWithinQuietHours(currentHour = 12, startHour = 22, endHour = 6))
  }

  @Test
  fun `UtilityToolkit evaluates math, converts units, builds WiFi QR, and encodes digests`() {
    val mathRes = com.example.tools.UtilityToolkit.evaluateMath("(10 + 5) * 2 + sqrt(81)").getOrThrow()
    assertEquals(39.0, mathRes, 0.0001)

    val tempF = com.example.tools.UtilityToolkit.convertUnit(100.0, com.example.tools.UtilityToolkit.UnitCategory.TEMPERATURE, "C", "F")
    assertEquals(212.0, tempF, 0.0001)

    val sha = com.example.tools.UtilityToolkit.digestHex("clawlink", "SHA-256")
    assertEquals(64, sha.length)

    val wifi = com.example.tools.UtilityToolkit.buildWifiQrPayload("HomeNet", "pass123", "WPA")
    assertEquals("WIFI:T:WPA;S:HomeNet;P:pass123;H:false;;", wifi)
  }

  @Test
  fun `WAProtoWire decodeMessageBundle extracts SKDM and unwraps ephemeral messages`() {
    val innerText = WAProtoWire.encodeTextMessage("Ephemeral hello")
    val combined =
      WAProtoWire.Writer().apply {
        writeMessage(2) {
          writeString(1, "120363000111222@g.us")
          writeBytes(2, byteArrayOf(1, 2, 3, 4))
        }
        writeMessage(40) {
          writeBytes(1, innerText)
        }
      }.toByteArray()

    val bundle = WAProtoWire.decodeMessageBundle(combined)
    assertEquals(1, bundle.senderKeyDistributions.size)
    assertEquals("120363000111222@g.us", bundle.senderKeyDistributions.first().first)
    val txt = bundle.payload as WAProtoWire.DecodedWhatsAppPayload.Text
    assertEquals("Ephemeral hello", txt.body)
  }
}

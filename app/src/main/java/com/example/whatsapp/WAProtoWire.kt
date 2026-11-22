package com.example.whatsapp

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Self-contained Protobuf wire-format encoder/decoder for WhatsApp Multi-Device messages.
 * Supports HandshakeMessage, ClientPayload, ADVSignedDeviceIdentity, SenderKeyDistributionMessage,
 * ephemeral/viewOnce/deviceSent wrappers, and rich message types.
 */
object WAProtoWire {

  const val WIRE_VARINT = 0
  const val WIRE_FIXED64 = 1
  const val WIRE_LENGTH_DELIMITED = 2
  const val WIRE_FIXED32 = 5

  class Writer {
    private val out = ByteArrayOutputStream()

    fun writeVarint(fieldNumber: Int, value: Long) {
      writeTag(fieldNumber, WIRE_VARINT)
      writeRawVarint64(value)
    }

    fun writeBool(fieldNumber: Int, value: Boolean) {
      writeVarint(fieldNumber, if (value) 1L else 0L)
    }

    fun writeBytes(fieldNumber: Int, data: ByteArray) {
      writeTag(fieldNumber, WIRE_LENGTH_DELIMITED)
      writeRawVarint64(data.size.toLong())
      out.write(data)
    }

    fun writeString(fieldNumber: Int, value: String) {
      writeBytes(fieldNumber, value.toByteArray(Charsets.UTF_8))
    }

    fun writeMessage(fieldNumber: Int, block: Writer.() -> Unit) {
      val nested = Writer().apply(block).toByteArray()
      writeBytes(fieldNumber, nested)
    }

    fun writeFixed32(fieldNumber: Int, value: Int) {
      writeTag(fieldNumber, WIRE_FIXED32)
      val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
      out.write(buf)
    }

    fun writeDouble(fieldNumber: Int, value: Double) {
      writeTag(fieldNumber, WIRE_FIXED64)
      val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array()
      out.write(buf)
    }

    private fun writeTag(fieldNumber: Int, wireType: Int) {
      writeRawVarint64(((fieldNumber shl 3) or wireType).toLong())
    }

    private fun writeRawVarint64(value: Long) {
      var v = value
      while (true) {
        if ((v and 0x7FL.inv()) == 0L) {
          out.write(v.toInt())
          return
        }
        out.write(((v.toInt() and 0x7F) or 0x80))
        v = v ushr 7
      }
    }

    fun toByteArray(): ByteArray = out.toByteArray()
  }

  data class FieldValue(
    val wireType: Int,
    val varint: Long = 0L,
    val bytes: ByteArray = ByteArray(0),
    val fixed64: Long = 0L,
    val fixed32: Int = 0,
  )

  fun decodeFields(data: ByteArray): Map<Int, List<FieldValue>> {
    val result = linkedMapOf<Int, MutableList<FieldValue>>()
    var pos = 0

    fun readVarint(): Long {
      var shift = 0
      var res = 0L
      while (pos < data.size && shift < 64) {
        val b = data[pos++].toInt() and 0xFF
        res = res or ((b and 0x7F).toLong() shl shift)
        if ((b and 0x80) == 0) return res
        shift += 7
      }
      throw IllegalArgumentException("Malformed protobuf varint")
    }

    while (pos < data.size) {
      val tag = readVarint().toInt()
      val fieldNumber = tag ushr 3
      val wireType = tag and 0x07
      val fv =
        when (wireType) {
          WIRE_VARINT -> FieldValue(wireType = wireType, varint = readVarint())
          WIRE_FIXED64 -> {
            if (pos + 8 > data.size) throw IllegalArgumentException("Truncated fixed64")
            val v = ByteBuffer.wrap(data, pos, 8).order(ByteOrder.LITTLE_ENDIAN).long
            pos += 8
            FieldValue(wireType = wireType, fixed64 = v)
          }
          WIRE_LENGTH_DELIMITED -> {
            val len = readVarint().toInt()
            if (len < 0 || pos + len > data.size) throw IllegalArgumentException("Truncated length-delimited field")
            val slice = data.copyOfRange(pos, pos + len)
            pos += len
            FieldValue(wireType = wireType, bytes = slice)
          }
          WIRE_FIXED32 -> {
            if (pos + 4 > data.size) throw IllegalArgumentException("Truncated fixed32")
            val v = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int
            pos += 4
            FieldValue(wireType = wireType, fixed32 = v)
          }
          else -> throw IllegalArgumentException("Unsupported wireType=$wireType")
        }
      result.getOrPut(fieldNumber) { mutableListOf() }.add(fv)
    }
    return result
  }

  fun encodeClientHello(ephemeralPublic: ByteArray): ByteArray {
    return Writer()
      .apply {
        writeMessage(2) { // HandshakeMessage.clientHello
          writeBytes(1, ephemeralPublic) // ClientHello.ephemeral
        }
      }
      .toByteArray()
  }

  data class ServerHello(
    val ephemeral: ByteArray,
    val staticEncrypted: ByteArray,
    val payloadEncrypted: ByteArray,
  )

  fun decodeServerHello(data: ByteArray): ServerHello {
    val root = decodeFields(data)
    val shBytes =
      root[3]?.firstOrNull()?.bytes
        ?: throw IllegalArgumentException("Missing HandshakeMessage.serverHello")
    val sh = decodeFields(shBytes)
    return ServerHello(
      ephemeral = sh[1]?.firstOrNull()?.bytes ?: ByteArray(0),
      staticEncrypted = sh[2]?.firstOrNull()?.bytes ?: ByteArray(0),
      payloadEncrypted = sh[3]?.firstOrNull()?.bytes ?: ByteArray(0),
    )
  }

  fun encodeClientFinish(encryptedStatic: ByteArray, encryptedPayload: ByteArray): ByteArray {
    return Writer()
      .apply {
        writeMessage(4) { // HandshakeMessage.clientFinish
          writeBytes(1, encryptedStatic)
          writeBytes(2, encryptedPayload)
        }
      }
      .toByteArray()
  }

  fun encodeWebRegistrationClientPayload(
    regId: Int,
    identityPub32: ByteArray,
    signedPreKeyId: Int,
    signedPreKeyPub32: ByteArray,
    signedPreKeySig64: ByteArray,
    deviceName: String,
    appVersion: Triple<Int, Int, Int> = Triple(2, 3000, 1027934701),
  ): ByteArray {
    val devicePropsBytes =
      Writer()
        .apply {
          writeString(1, deviceName) // DeviceProps.os
          writeMessage(2) { // DeviceProps.version
            writeVarint(1, 10)
            writeVarint(2, 15)
            writeVarint(3, 7)
          }
          writeVarint(3, 1) // PlatformType.CHROME
          writeBool(4, false) // requireFullSync
        }
        .toByteArray()

    val versionStr = "${appVersion.first}.${appVersion.second}.${appVersion.third}"
    val buildHashMd5 =
      java.security.MessageDigest.getInstance("MD5").digest(versionStr.toByteArray(Charsets.UTF_8))

    val eRegid = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(regId).array()
    val eSkeyId =
      byteArrayOf(
        ((signedPreKeyId shr 16) and 0xFF).toByte(),
        ((signedPreKeyId shr 8) and 0xFF).toByte(),
        (signedPreKeyId and 0xFF).toByte(),
      )

    return Writer()
      .apply {
        writeBool(3, false) // passive
        writeUserAgent(appVersion)
        writeWebInfo()
        writeVarint(12, 1) // connectType = WIFI_UNKNOWN
        writeVarint(13, 1) // connectReason = USER_ACTIVATED
        writeMessage(19) { // devicePairingData
          writeBytes(1, eRegid)
          writeBytes(2, byteArrayOf(5)) // KEY_BUNDLE_TYPE
          writeBytes(3, identityPub32)
          writeBytes(4, eSkeyId)
          writeBytes(5, signedPreKeyPub32)
          writeBytes(6, signedPreKeySig64)
          writeBytes(7, buildHashMd5)
          writeBytes(8, devicePropsBytes)
        }
      }
      .toByteArray()
  }

  fun encodeLoginClientPayload(
    username: Long,
    deviceId: Int,
    passive: Boolean = true,
    appVersion: Triple<Int, Int, Int> = Triple(2, 3000, 1027934701),
  ): ByteArray {
    return Writer()
      .apply {
        writeVarint(1, username)
        writeBool(3, passive)
        writeUserAgent(appVersion)
        writeWebInfo()
        writeVarint(12, 1) // WIFI_UNKNOWN
        writeVarint(13, 1) // USER_ACTIVATED
        if (deviceId > 0) writeVarint(18, deviceId.toLong())
      }
      .toByteArray()
  }

  private fun Writer.writeUserAgent(appVersion: Triple<Int, Int, Int>) {
    writeMessage(5) {
      writeVarint(1, 14) // Platform.WEB
      writeMessage(2) {
        writeVarint(1, appVersion.first.toLong())
        writeVarint(2, appVersion.second.toLong())
        writeVarint(3, appVersion.third.toLong())
      }
      writeString(3, "000") // mcc
      writeString(4, "000") // mnc
      writeString(5, "0.1") // osVersion
      writeString(6, "") // manufacturer
      writeString(7, "Desktop") // device
      writeString(8, "0.1") // osBuildNumber
      writeString(9, "") // phoneId
      writeVarint(10, 0) // ReleaseChannel.RELEASE
      writeString(11, "en") // localeLanguageIso6391
      writeString(12, "US") // localeCountryIso31661Alpha2
    }
  }

  private fun Writer.writeWebInfo() {
    writeMessage(6) {
      writeVarint(4, 0) // WebSubPlatform.WEB_BROWSER
    }
  }

  data class ParsedSignedDeviceIdentityHmac(
    val details: ByteArray,
    val hmac: ByteArray,
    val accountType: Int,
  )

  data class ParsedSignedDeviceIdentity(
    val details: ByteArray,
    val accountSignatureKey: ByteArray,
    val accountSignature: ByteArray,
    val deviceSignature: ByteArray,
  )

  fun decodeSignedDeviceIdentityHmac(data: ByteArray): ParsedSignedDeviceIdentityHmac {
    val fields = decodeFields(data)
    return ParsedSignedDeviceIdentityHmac(
      details = fields[1]?.firstOrNull()?.bytes ?: ByteArray(0),
      hmac = fields[2]?.firstOrNull()?.bytes ?: ByteArray(0),
      accountType = fields[3]?.firstOrNull()?.varint?.toInt() ?: 0,
    )
  }

  fun decodeSignedDeviceIdentity(data: ByteArray): ParsedSignedDeviceIdentity {
    val fields = decodeFields(data)
    return ParsedSignedDeviceIdentity(
      details = fields[1]?.firstOrNull()?.bytes ?: ByteArray(0),
      accountSignatureKey = fields[2]?.firstOrNull()?.bytes ?: ByteArray(0),
      accountSignature = fields[3]?.firstOrNull()?.bytes ?: ByteArray(0),
      deviceSignature = fields[4]?.firstOrNull()?.bytes ?: ByteArray(0),
    )
  }

  fun encodeSignedDeviceIdentity(
    details: ByteArray,
    accountSignatureKey: ByteArray,
    accountSignature: ByteArray,
    deviceSignature: ByteArray,
  ): ByteArray {
    return Writer()
      .apply {
        writeBytes(1, details)
        if (accountSignatureKey.isNotEmpty()) writeBytes(2, accountSignatureKey)
        writeBytes(3, accountSignature)
        writeBytes(4, deviceSignature)
      }
      .toByteArray()
  }

  fun encodeTextMessage(text: String, quotedMessageId: String? = null, quotedParticipant: String? = null, quotedText: String? = null): ByteArray {
    return if (quotedMessageId.isNullOrBlank()) {
      Writer().apply { writeString(1, text) }.toByteArray()
    } else {
      Writer()
        .apply {
          writeMessage(6) { // extendedTextMessage
            writeString(1, text)
            writeMessage(17) { // contextInfo
              writeString(1, quotedMessageId)
              if (!quotedParticipant.isNullOrBlank()) writeString(2, quotedParticipant)
              if (!quotedText.isNullOrBlank()) {
                writeMessage(3) { // quotedMessage
                  writeString(1, quotedText)
                }
              }
            }
          }
        }
        .toByteArray()
    }
  }

  fun encodeReactionMessage(
    remoteJid: String,
    fromMe: Boolean,
    messageId: String,
    emoji: String,
  ): ByteArray {
    return Writer()
      .apply {
        writeMessage(46) { // reactionMessage
          writeMessage(1) { // MessageKey
            writeString(1, remoteJid)
            writeBool(2, fromMe)
            writeString(3, messageId)
          }
          writeString(2, emoji)
          writeVarint(4, System.currentTimeMillis())
        }
      }
      .toByteArray()
  }

  fun encodeImageMessage(
    url: String,
    directPath: String,
    mimetype: String,
    mediaKey: ByteArray,
    fileSha256: ByteArray,
    fileEncSha256: ByteArray,
    fileLength: Long,
    caption: String,
  ): ByteArray {
    return Writer()
      .apply {
        writeMessage(3) {
          writeString(1, url)
          writeString(2, mimetype)
          if (caption.isNotBlank()) writeString(3, caption)
          writeBytes(4, fileSha256)
          writeVarint(5, fileLength)
          writeBytes(8, mediaKey)
          writeBytes(9, fileEncSha256)
          writeString(10, directPath)
        }
      }
      .toByteArray()
  }

  fun encodeDocumentMessage(
    url: String,
    directPath: String,
    mimetype: String,
    fileName: String,
    mediaKey: ByteArray,
    fileSha256: ByteArray,
    fileEncSha256: ByteArray,
    fileLength: Long,
    caption: String = "",
  ): ByteArray {
    return Writer()
      .apply {
        writeMessage(7) {
          writeString(1, url)
          writeString(2, mimetype)
          writeString(3, fileName)
          writeBytes(4, fileSha256)
          writeVarint(5, fileLength)
          writeBytes(7, mediaKey)
          writeString(8, fileName)
          writeBytes(9, fileEncSha256)
          writeString(10, directPath)
          if (caption.isNotBlank()) writeString(20, caption)
        }
      }
      .toByteArray()
  }

  fun encodeSenderKeyDistributionMessage(groupId: String, axolotlBytes: ByteArray): ByteArray {
    return Writer()
      .apply {
        writeMessage(2) {
          writeString(1, groupId)
          writeBytes(2, axolotlBytes)
        }
      }
      .toByteArray()
  }

  sealed class DecodedWhatsAppPayload {
    data class Text(val body: String, val quotedId: String? = null, val quotedText: String? = null) : DecodedWhatsAppPayload()
    data class Media(
      val mediaKind: String, // IMAGE, AUDIO, PTT, VIDEO, DOCUMENT, STICKER
      val caption: String,
      val mimeType: String,
      val url: String,
      val directPath: String,
      val mediaKey: ByteArray,
      val fileSha256: ByteArray,
      val fileEncSha256: ByteArray,
      val fileLength: Long,
      val fileName: String? = null,
      val seconds: Int = 0,
    ) : DecodedWhatsAppPayload()
    data class Location(val latitude: Double, val longitude: Double, val name: String, val address: String) : DecodedWhatsAppPayload()
    data class Contact(val displayName: String, val vcard: String) : DecodedWhatsAppPayload()
    data class Reaction(val targetMessageId: String, val emoji: String) : DecodedWhatsAppPayload()
    data class Edit(val targetMessageId: String, val newText: String) : DecodedWhatsAppPayload()
    data class Revoke(val targetMessageId: String) : DecodedWhatsAppPayload()
    data class Unknown(val summary: String) : DecodedWhatsAppPayload()
  }

  data class ExtractedMessageBundle(
    val senderKeyDistributions: List<Pair<String, ByteArray>> = emptyList(),
    val payload: DecodedWhatsAppPayload? = null,
  )

  fun decodeMessageBundle(rawBytes: ByteArray): ExtractedMessageBundle {
    val unpadded = stripPkcs7PaddingIfValid(rawBytes)
    val fields = decodeFields(unpadded)
    val skdms = mutableListOf<Pair<String, ByteArray>>()

    // Collect top-level senderKeyDistributionMessage (field 2) or fastRatchet (field 37 if groupId+skdm)
    fields[2]?.forEach { fv ->
      runCatching {
        val f = decodeFields(fv.bytes)
        val groupId = f[1]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8)
        val skdm = f[2]?.firstOrNull()?.bytes
        if (!groupId.isNullOrBlank() && skdm != null && skdm.isNotEmpty()) {
          skdms.add(groupId to skdm)
        }
      }
    }

    // Check wrapped message containers:
    // deviceSentMessage (31) -> field 2
    fields[31]?.firstOrNull()?.bytes?.let { dsmBytes ->
      val dsm = decodeFields(dsmBytes)
      dsm[2]?.firstOrNull()?.bytes?.let { inner ->
        val innerBundle = decodeMessageBundle(inner)
        return ExtractedMessageBundle(
          senderKeyDistributions = skdms + innerBundle.senderKeyDistributions,
          payload = innerBundle.payload,
        )
      }
    }

    // ephemeralMessage (40), documentWithCaptionMessage (53), viewOnceMessageV2 (55), editedMessage (58) -> FutureProofMessage field 1
    for (wrapperField in listOf(40, 53, 55, 58, 59)) {
      fields[wrapperField]?.firstOrNull()?.bytes?.let { wrapperBytes ->
        val wrapper = decodeFields(wrapperBytes)
        wrapper[1]?.firstOrNull()?.bytes?.let { inner ->
          val innerBundle = decodeMessageBundle(inner)
          return ExtractedMessageBundle(
            senderKeyDistributions = skdms + innerBundle.senderKeyDistributions,
            payload = innerBundle.payload,
          )
        }
      }
    }

    val payload = decodeContentFields(fields)
    return ExtractedMessageBundle(
      senderKeyDistributions = skdms,
      payload = if (payload is DecodedWhatsAppPayload.Unknown && skdms.isNotEmpty() && fields.keys == setOf(2)) null else payload,
    )
  }

  fun decodeWhatsAppMessage(rawBytes: ByteArray): DecodedWhatsAppPayload {
    return decodeMessageBundle(rawBytes).payload ?: DecodedWhatsAppPayload.Unknown("SenderKey distribution")
  }

  private fun decodeContentFields(fields: Map<Int, List<FieldValue>>): DecodedWhatsAppPayload {
    fields[1]?.firstOrNull()?.bytes?.let { textBytes ->
      return DecodedWhatsAppPayload.Text(textBytes.toString(Charsets.UTF_8))
    }

    fields[6]?.firstOrNull()?.bytes?.let { extBytes ->
      val ext = decodeFields(extBytes)
      val body = ext[1]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: ""
      val ctx = ext[17]?.firstOrNull()?.bytes?.let { decodeFields(it) }
      val qId = ctx?.get(1)?.firstOrNull()?.bytes?.toString(Charsets.UTF_8)
      val qText =
        ctx?.get(3)?.firstOrNull()?.bytes?.let { qm ->
          val qmFields = decodeFields(qm)
          qmFields[1]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8)
        }
      return DecodedWhatsAppPayload.Text(body, qId, qText)
    }

    fields[3]?.firstOrNull()?.bytes?.let { imgBytes ->
      val f = decodeFields(imgBytes)
      return DecodedWhatsAppPayload.Media(
        mediaKind = "IMAGE",
        caption = f[3]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: "",
        mimeType = f[2]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: "image/jpeg",
        url = f[1]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: "",
        directPath = f[10]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: "",
        mediaKey = f[8]?.firstOrNull()?.bytes ?: ByteArray(0),
        fileSha256 = f[4]?.firstOrNull()?.bytes ?: ByteArray(0),
        fileEncSha256 = f[9]?.firstOrNull()?.bytes ?: ByteArray(0),
        fileLength = f[5]?.firstOrNull()?.varint ?: 0L,
      )
    }

    fields[8]?.firstOrNull()?.bytes?.let { audBytes ->
      val f = decodeFields(audBytes)
      val isPtt = (f[6]?.firstOrNull()?.varint ?: 0L) != 0L
      return DecodedWhatsAppPayload.Media(
        mediaKind = if (isPtt) "PTT" else "AUDIO",
        caption = "",
        mimeType = f[2]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: "audio/ogg; codecs=opus",
        url = f[1]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: "",
        directPath = f[9]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: "",
        mediaKey = f[7]?.firstOrNull()?.bytes ?: ByteArray(0),
        fileSha256 = f[3]?.firstOrNull()?.bytes ?: ByteArray(0),
        fileEncSha256 = f[8]?.firstOrNull()?.bytes ?: ByteArray(0),
        fileLength = f[4]?.firstOrNull()?.varint ?: 0L,
        seconds = f[5]?.firstOrNull()?.varint?.toInt() ?: 0,
      )
    }

    fields[9]?.firstOrNull()?.bytes?.let { vidBytes ->
      val f = decodeFields(vidBytes)
      return DecodedWhatsAppPayload.Media(
        mediaKind = "VIDEO",
        caption = f[7]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: "",
        mimeType = f[2]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: "video/mp4",
        url = f[1]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: "",
        directPath = f[13]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: "",
        mediaKey = f[6]?.firstOrNull()?.bytes ?: ByteArray(0),
        fileSha256 = f[3]?.firstOrNull()?.bytes ?: ByteArray(0),
        fileEncSha256 = f[11]?.firstOrNull()?.bytes ?: ByteArray(0),
        fileLength = f[4]?.firstOrNull()?.varint ?: 0L,
        seconds = f[5]?.firstOrNull()?.varint?.toInt() ?: 0,
      )
    }

    fields[7]?.firstOrNull()?.bytes?.let { docBytes ->
      val f = decodeFields(docBytes)
      return DecodedWhatsAppPayload.Media(
        mediaKind = "DOCUMENT",
        caption = f[20]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: "",
        mimeType = f[2]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: "application/octet-stream",
        url = f[1]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: "",
        directPath = f[10]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: "",
        mediaKey = f[7]?.firstOrNull()?.bytes ?: ByteArray(0),
        fileSha256 = f[4]?.firstOrNull()?.bytes ?: ByteArray(0),
        fileEncSha256 = f[9]?.firstOrNull()?.bytes ?: ByteArray(0),
        fileLength = f[5]?.firstOrNull()?.varint ?: 0L,
        fileName = f[8]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8)
          ?: f[3]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8),
      )
    }

    fields[26]?.firstOrNull()?.bytes?.let { stkBytes ->
      val f = decodeFields(stkBytes)
      return DecodedWhatsAppPayload.Media(
        mediaKind = "STICKER",
        caption = "",
        mimeType = f[5]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: "image/webp",
        url = f[1]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: "",
        directPath = f[8]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: "",
        mediaKey = f[4]?.firstOrNull()?.bytes ?: ByteArray(0),
        fileSha256 = f[2]?.firstOrNull()?.bytes ?: ByteArray(0),
        fileEncSha256 = f[3]?.firstOrNull()?.bytes ?: ByteArray(0),
        fileLength = f[9]?.firstOrNull()?.varint ?: 0L,
      )
    }

    fields[5]?.firstOrNull()?.bytes?.let { locBytes ->
      val f = decodeFields(locBytes)
      val lat = java.lang.Double.longBitsToDouble(f[1]?.firstOrNull()?.fixed64 ?: 0L)
      val lon = java.lang.Double.longBitsToDouble(f[2]?.firstOrNull()?.fixed64 ?: 0L)
      val name = f[3]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: ""
      val addr = f[4]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: ""
      return DecodedWhatsAppPayload.Location(lat, lon, name, addr)
    }

    fields[4]?.firstOrNull()?.bytes?.let { contactBytes ->
      val f = decodeFields(contactBytes)
      val name = f[1]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: "Contact"
      val vcard = f[16]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: ""
      return DecodedWhatsAppPayload.Contact(name, vcard)
    }

    fields[46]?.firstOrNull()?.bytes?.let { reactBytes ->
      val f = decodeFields(reactBytes)
      val key = f[1]?.firstOrNull()?.bytes?.let { decodeFields(it) }
      val targetId = key?.get(3)?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: ""
      val emoji = f[2]?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: ""
      return DecodedWhatsAppPayload.Reaction(targetId, emoji)
    }

    fields[12]?.firstOrNull()?.bytes?.let { protoBytes ->
      val f = decodeFields(protoBytes)
      val key = f[1]?.firstOrNull()?.bytes?.let { decodeFields(it) }
      val targetId = key?.get(3)?.firstOrNull()?.bytes?.toString(Charsets.UTF_8) ?: ""
      val type = f[2]?.firstOrNull()?.varint?.toInt() ?: -1
      if (type == 0) return DecodedWhatsAppPayload.Revoke(targetId)
      if (type == 14) {
        val edited = f[14]?.firstOrNull()?.bytes?.let { decodeWhatsAppMessage(it) }
        val newText = (edited as? DecodedWhatsAppPayload.Text)?.body ?: ""
        return DecodedWhatsAppPayload.Edit(targetId, newText)
      }
    }

    return DecodedWhatsAppPayload.Unknown("Protobuf message (${fields.keys.joinToString(",")})")
  }

  private fun stripPkcs7PaddingIfValid(data: ByteArray): ByteArray {
    if (data.isEmpty()) return data
    val padLen = data.last().toInt() and 0xFF
    if (padLen in 1..16 && padLen <= data.size) {
      var valid = true
      for (i in data.size - padLen until data.size) {
        if ((data[i].toInt() and 0xFF) != padLen) {
          valid = false
          break
        }
      }
      if (valid) return data.copyOfRange(0, data.size - padLen)
    }
    return data
  }
}

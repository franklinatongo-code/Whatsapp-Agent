package com.example.whatsapp

import android.content.Context
import com.example.data.repository.ClawRepository
import okhttp3.OkHttpClient
import java.io.File

sealed class WhatsAppSendResult {
  data class Confirmed(val messageId: String) : WhatsAppSendResult()
  data class SentUnconfirmed(val placeholderId: String, val reason: String) : WhatsAppSendResult()
  data class UnknownTimeout(val reason: String) : WhatsAppSendResult()
  data class Failed(val error: String) : WhatsAppSendResult()

  fun sentIdOrNull(): String? =
    when (this) {
      is Confirmed -> messageId
      is SentUnconfirmed -> placeholderId
      is UnknownTimeout, is Failed -> null
    }
}

enum class ChatType {
  INDIVIDUAL,
  GROUP,
  CHANNEL,
  STATUS,
  UNKNOWN,
}

data class IncomingWhatsAppEvent(
  val messageId: String,
  val chatJid: String,
  val senderJid: String,
  val senderName: String,
  val isGroup: Boolean,
  val fromMe: Boolean,
  val timestampMs: Long,
  val messageType: String,
  val textContent: String,
  val mediaMimeType: String? = null,
  val mediaLocalFile: File? = null,
  val quotedMessageId: String? = null,
  val quotedText: String? = null,
  val targetMessageId: String? = null, // for reactions, edits, revokes
  val reactionEmoji: String? = null,
  val isBacklog: Boolean = false,
  val mediaDirectPath: String? = null,
  val mediaDownloadUrl: String? = null,
  val mediaStaticUrl: String? = null,
  val mediaKeyBase64: String? = null,
  val mediaIsThumbnail: Boolean = false,
  val chatTitle: String = "",
  val mentionedJids: List<String> = emptyList(),
  val quotedParticipantJid: String? = null,
  val chatType: ChatType = if (isGroup) ChatType.GROUP else ChatType.INDIVIDUAL,
  val quotedFromMe: Boolean = false,
)


/**
 * WhatsAppMessaging: Handles incoming stanza decryption/normalization (1:1 pkmsg/msg + Group skmsg + SKDM),
 * retry receipts on missing keys, and outgoing Signal-encrypted WhatsApp messages with automatic
 * pre-key bundle session establishment.
 */
class WhatsAppMessaging(
  private val context: Context,
  private val transport: WhatsAppTransport,
  private val crypto: WhatsAppCrypto,
  private val repository: ClawRepository,
  private val httpClient: OkHttpClient = OkHttpClient(),
  private val webEngine: WhatsAppWebEngine? = null,
) {

  var ensureSessionHandler: (suspend (String) -> Boolean)? = null

  suspend fun handleMessageStanza(
    node: WABinaryNode,
    meJid: String?,
  ): IncomingWhatsAppEvent? {
    val msgId = node.attrs["id"] ?: return null
    val from = node.attrs["from"] ?: return null
    val participant = node.attrs["participant"]
    val senderPn = node.attrs["sender_pn"] ?: node.attrs["participant_pn"]
    val effectiveSenderRaw = participant ?: senderPn ?: from
    val pushName = node.attrs["notify"] ?: WAJid.parse(effectiveSenderRaw).user
    val timestampSec = node.attrs["t"]?.toLongOrNull()
    val timestampMs = if (timestampSec != null) timestampSec * 1000L else System.currentTimeMillis()

    // Always acknowledge receipt of the stanza to WhatsApp server
    sendStanzaAck(node)

    val chatJid = WAJid.normalizeUser(if (!WAJid.parse(from).isGroup && !senderPn.isNullOrBlank()) senderPn else from)
    val senderJid = WAJid.normalizeUser(senderPn ?: effectiveSenderRaw)
    if (WAJid.parse(chatJid).isStatusBroadcast) return null

    val fromMe = WAJid.areSameUser(senderJid, meJid)

    if (repository.isDuplicateMessage(msgId)) {
      repository.log("DEBUG", "PROTOCOL", "Skipped duplicate stanza $msgId")
      return null
    }

    val encNodes = node.children().filter { it.tag == "enc" }
    if (encNodes.isEmpty()) {
      val body = node.child("body")?.contentString()
      if (!body.isNullOrBlank()) {
        return IncomingWhatsAppEvent(
          messageId = msgId,
          chatJid = chatJid,
          senderJid = senderJid,
          senderName = pushName,
          isGroup = WAJid.parse(chatJid).isGroup,
          fromMe = fromMe,
          timestampMs = timestampMs,
          messageType = "TEXT",
          textContent = body,
        )
      }
      return null
    }

    // Order enc nodes so pkmsg/msg (which may carry SenderKeyDistributionMessage) decrypt before skmsg
    val orderedEncNodes =
      encNodes.sortedBy {
        when (it.attrs["type"]) {
          "pkmsg" -> 0
          "msg" -> 1
          "skmsg" -> 2
          else -> 3
        }
      }

    var decodedPayload: WAProtoWire.DecodedWhatsAppPayload? = null
    var lastDecryptFailure: String? = null
    val isGroup = WAJid.parse(chatJid).isGroup

    for (enc in orderedEncNodes) {
      val encType = enc.attrs["type"] ?: "msg"
      val cipherBytes = enc.contentBytes() ?: continue
      try {
        val plainProto =
          crypto.decryptSignalMessage(
            senderJid = participant ?: from,
            encType = encType,
            ciphertext = cipherBytes,
            groupJid = if (isGroup) chatJid else null,
          )
        val bundle = WAProtoWire.decodeMessageBundle(plainProto)
        // Process any group senderKeyDistributionMessage first
        bundle.senderKeyDistributions.forEach { (grpId, skdmBytes) ->
          runCatching {
            crypto.processSenderKeyDistribution(grpId, participant ?: from, skdmBytes)
          }
        }
        if (bundle.payload != null) {
          decodedPayload = bundle.payload
        }
      } catch (t: Throwable) {
        lastDecryptFailure = "${encType}: ${t.message}"
        repository.log("WARN", "PROTOCOL", "Signal decrypt failed ($encType) for $msgId: ${t.message}")
      }
    }

    if (decodedPayload == null) {
      if (lastDecryptFailure != null) {
        sendRetryReceipt(node)
      }
      return null
    }

    return normalizePayload(
      msgId = msgId,
      chatJid = chatJid,
      senderJid = senderJid,
      senderName = pushName,
      isGroup = isGroup,
      fromMe = fromMe,
      timestampMs = timestampMs,
      payload = decodedPayload,
    )
  }

  private suspend fun normalizePayload(
    msgId: String,
    chatJid: String,
    senderJid: String,
    senderName: String,
    isGroup: Boolean,
    fromMe: Boolean,
    timestampMs: Long,
    payload: WAProtoWire.DecodedWhatsAppPayload,
  ): IncomingWhatsAppEvent {
    return when (payload) {
      is WAProtoWire.DecodedWhatsAppPayload.Text ->
        IncomingWhatsAppEvent(
          messageId = msgId,
          chatJid = chatJid,
          senderJid = senderJid,
          senderName = senderName,
          isGroup = isGroup,
          fromMe = fromMe,
          timestampMs = timestampMs,
          messageType = "TEXT",
          textContent = payload.body,
          quotedMessageId = payload.quotedId,
          quotedText = payload.quotedText,
        )
      is WAProtoWire.DecodedWhatsAppPayload.Media -> {
        val ext = extensionForMime(payload.mimeType, payload.mediaKind)
        val localFile =
          if (payload.mediaKey.isNotEmpty() && (payload.url.isNotBlank() || payload.directPath.isNotBlank())) {
            try {
              WhatsAppMediaCrypto.downloadAndSaveDecryptedMedia(
                context = context,
                httpClient = httpClient,
                url = payload.url,
                directPath = payload.directPath,
                mediaKey = payload.mediaKey,
                mediaKind = payload.mediaKind,
                fileExtension = ext,
                messageId = msgId,
              )
            } catch (t: Throwable) {
              repository.log("WARN", "MEDIA", "Failed decrypting ${payload.mediaKind}: ${t.message}")
              null
            }
          } else null
        val display =
          buildString {
            append("[${payload.mediaKind}]")
            if (!payload.fileName.isNullOrBlank()) append(" ${payload.fileName}")
            if (payload.seconds > 0) append(" (${payload.seconds}s)")
            if (payload.caption.isNotBlank()) append(" ${payload.caption}")
          }
        IncomingWhatsAppEvent(
          messageId = msgId,
          chatJid = chatJid,
          senderJid = senderJid,
          senderName = senderName,
          isGroup = isGroup,
          fromMe = fromMe,
          timestampMs = timestampMs,
          messageType = payload.mediaKind,
          textContent = display,
          mediaMimeType = payload.mimeType,
          mediaLocalFile = localFile,
        )
      }
      is WAProtoWire.DecodedWhatsAppPayload.Location ->
        IncomingWhatsAppEvent(
          messageId = msgId,
          chatJid = chatJid,
          senderJid = senderJid,
          senderName = senderName,
          isGroup = isGroup,
          fromMe = fromMe,
          timestampMs = timestampMs,
          messageType = "LOCATION",
          textContent = "Location: ${payload.name} ${payload.address} (${payload.latitude}, ${payload.longitude})".trim(),
        )
      is WAProtoWire.DecodedWhatsAppPayload.Contact ->
        IncomingWhatsAppEvent(
          messageId = msgId,
          chatJid = chatJid,
          senderJid = senderJid,
          senderName = senderName,
          isGroup = isGroup,
          fromMe = fromMe,
          timestampMs = timestampMs,
          messageType = "CONTACT",
          textContent = "Shared contact: ${payload.displayName}\n${payload.vcard.take(400)}",
        )
      is WAProtoWire.DecodedWhatsAppPayload.Reaction ->
        IncomingWhatsAppEvent(
          messageId = msgId,
          chatJid = chatJid,
          senderJid = senderJid,
          senderName = senderName,
          isGroup = isGroup,
          fromMe = fromMe,
          timestampMs = timestampMs,
          messageType = "REACTION",
          textContent = "Reacted ${payload.emoji}",
          targetMessageId = payload.targetMessageId,
          reactionEmoji = payload.emoji,
        )
      is WAProtoWire.DecodedWhatsAppPayload.Edit ->
        IncomingWhatsAppEvent(
          messageId = msgId,
          chatJid = chatJid,
          senderJid = senderJid,
          senderName = senderName,
          isGroup = isGroup,
          fromMe = fromMe,
          timestampMs = timestampMs,
          messageType = "EDIT",
          textContent = payload.newText,
          targetMessageId = payload.targetMessageId,
        )
      is WAProtoWire.DecodedWhatsAppPayload.Revoke ->
        IncomingWhatsAppEvent(
          messageId = msgId,
          chatJid = chatJid,
          senderJid = senderJid,
          senderName = senderName,
          isGroup = isGroup,
          fromMe = fromMe,
          timestampMs = timestampMs,
          messageType = "REVOKE",
          textContent = "[Message deleted]",
          targetMessageId = payload.targetMessageId,
        )
      is WAProtoWire.DecodedWhatsAppPayload.Unknown ->
        IncomingWhatsAppEvent(
          messageId = msgId,
          chatJid = chatJid,
          senderJid = senderJid,
          senderName = senderName,
          isGroup = isGroup,
          fromMe = fromMe,
          timestampMs = timestampMs,
          messageType = "SYSTEM",
          textContent = payload.summary,
        )
    }
  }

  suspend fun sendTextMessageDetailed(
    chatJid: String,
    text: String,
    meJid: String?,
    quotedMessageId: String? = null,
    quotedParticipant: String? = null,
    quotedText: String? = null,
  ): WhatsAppSendResult {
    if (webEngine != null) {
      return webEngine.sendTextDetailed(chatJid, text, quotedMessageId)
    }
    val messageId = "3EB0${System.currentTimeMillis().toString(16).uppercase()}"
    val protoBytes = WAProtoWire.encodeTextMessage(text, quotedMessageId, quotedParticipant, quotedText)
    return if (sendEncryptedProtoMessage(messageId, chatJid, meJid, "text", protoBytes)) {
      WhatsAppSendResult.Confirmed(messageId)
    } else {
      WhatsAppSendResult.Failed("Signal encryption/transport dispatch failed")
    }
  }

  suspend fun sendTextMessage(
    chatJid: String,
    text: String,
    meJid: String?,
    quotedMessageId: String? = null,
    quotedParticipant: String? = null,
    quotedText: String? = null,
  ): String? =
    sendTextMessageDetailed(chatJid, text, meJid, quotedMessageId, quotedParticipant, quotedText).sentIdOrNull()

  suspend fun sendMediaBytes(
    chatJid: String,
    bytes: ByteArray,
    mimeType: String,
    fileName: String,
    caption: String,
  ): String? {
    if (webEngine != null) {
      return webEngine.sendMedia(chatJid, bytes, mimeType, fileName, caption)
    }
    return null
  }

  suspend fun sendReaction(
    chatJid: String,
    targetMessageId: String,
    targetFromMe: Boolean,
    emoji: String,
    meJid: String?,
  ): String? {
    if (webEngine != null) {
      webEngine.sendReaction(targetMessageId, emoji)
      return targetMessageId
    }
    val messageId = "3EB0R${System.currentTimeMillis().toString(16).uppercase()}"
    val protoBytes = WAProtoWire.encodeReactionMessage(chatJid, targetFromMe, targetMessageId, emoji)
    return if (sendEncryptedProtoMessage(messageId, chatJid, meJid, "reaction", protoBytes)) {
      messageId
    } else null
  }

  suspend fun sendEncryptedProtoMessage(
    messageId: String,
    chatJid: String,
    meJid: String?,
    typeAttr: String,
    protoBytes: ByteArray,
  ): Boolean {
    val parsedChat = WAJid.parse(chatJid)
    return try {
      val encNode =
        if (parsedChat.isGroup) {
          val selfJid = meJid ?: return false
          val ciphertext = crypto.encryptGroupMessage(chatJid, selfJid, protoBytes)
          WABinaryNode(
            tag = "enc",
            attrs = linkedMapOf("v" to "2", "type" to "skmsg"),
            content = ciphertext,
          )
        } else {
          if (!crypto.hasSession(chatJid)) {
            val fetched = ensureSessionHandler?.invoke(chatJid) == true
            if (!fetched && !crypto.hasSession(chatJid)) {
              repository.log("WARN", "PROTOCOL", "No Signal session for $chatJid (even after pre-key fetch)")
              return false
            }
          }
          val env = crypto.encryptSignalMessage(chatJid, protoBytes)
          WABinaryNode(
            tag = "enc",
            attrs = linkedMapOf("v" to "2", "type" to env.encType),
            content = env.ciphertext,
          )
        }

      val stanza =
        WABinaryNode(
          tag = "message",
          attrs = linkedMapOf("id" to messageId, "to" to chatJid, "type" to typeAttr),
          content = listOf(encNode),
        )
      transport.sendNode(stanza)
    } catch (t: Throwable) {
      repository.log("ERROR", "PROTOCOL", "Failed encrypting/sending stanza $messageId: ${t.message}")
      false
    }
  }

  fun sendChatState(chatJid: String, composing: Boolean) {
    if (webEngine != null) {
      webEngine.sendChatState(chatJid, composing)
      return
    }
    val stateTag = if (composing) "composing" else "paused"
    val node =
      WABinaryNode(
        tag = "chatstate",
        attrs = linkedMapOf("to" to chatJid),
        content = listOf(WABinaryNode(tag = stateTag)),
      )
    transport.sendNode(node)
  }

  fun sendReadReceipt(chatJid: String, participantJid: String?, messageId: String) {
    if (webEngine != null) {
      webEngine.sendSeen(chatJid)
      return
    }
    val attrs = linkedMapOf("id" to messageId, "to" to chatJid, "type" to "read")
    if (!participantJid.isNullOrBlank() && WAJid.parse(chatJid).isGroup) {
      attrs["participant"] = participantJid
    }
    transport.sendNode(WABinaryNode(tag = "receipt", attrs = attrs))
  }

  private fun sendRetryReceipt(incoming: WABinaryNode) {
    val attrs = linkedMapOf<String, String>()
    incoming.attrs["id"]?.let { attrs["id"] = it }
    incoming.attrs["from"]?.let { attrs["to"] = it }
    attrs["type"] = "retry"
    incoming.attrs["participant"]?.let { attrs["participant"] = it }
    incoming.attrs["t"]?.let { attrs["t"] = it }
    transport.sendNode(
      WABinaryNode(
        tag = "receipt",
        attrs = attrs,
        content = listOf(WABinaryNode(tag = "retry", attrs = linkedMapOf("count" to "1", "v" to "1"))),
      )
    )
  }

  private fun sendStanzaAck(incoming: WABinaryNode) {
    val attrs = linkedMapOf<String, String>()
    incoming.attrs["id"]?.let { attrs["id"] = it }
    incoming.attrs["from"]?.let { attrs["to"] = it }
    attrs["class"] = incoming.tag
    incoming.attrs["type"]?.let { attrs["type"] = it }
    incoming.attrs["participant"]?.let { attrs["participant"] = it }
    transport.sendNode(WABinaryNode(tag = "ack", attrs = attrs))
  }

  private fun extensionForMime(mime: String, kind: String): String {
    val clean = mime.substringBefore(';').trim().lowercase()
    return when {
      clean.contains("jpeg") || clean.contains("jpg") -> "jpg"
      clean.contains("png") -> "png"
      clean.contains("webp") -> "webp"
      clean.contains("pdf") -> "pdf"
      clean.contains("ogg") || clean.contains("opus") -> "ogg"
      clean.contains("mp4") -> "mp4"
      clean.contains("mpeg") || clean.contains("mp3") -> "mp3"
      clean.contains("wav") -> "wav"
      clean.contains("plain") -> "txt"
      kind == "IMAGE" -> "jpg"
      kind == "PTT" || kind == "AUDIO" -> "ogg"
      kind == "VIDEO" -> "mp4"
      kind == "STICKER" -> "webp"
      else -> "bin"
    }
  }
}

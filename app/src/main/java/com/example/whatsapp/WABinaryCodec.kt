package com.example.whatsapp

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

data class WABinaryNode(
  val tag: String,
  val attrs: MutableMap<String, String> = linkedMapOf(),
  val content: Any? = null, // null | ByteArray | String | List<WABinaryNode>
) {
  fun children(): List<WABinaryNode> =
    (content as? List<*>)?.filterIsInstance<WABinaryNode>() ?: emptyList()

  fun child(tagName: String): WABinaryNode? = children().firstOrNull { it.tag == tagName }

  fun contentBytes(): ByteArray? =
    when (content) {
      is ByteArray -> content
      is String -> content.toByteArray(Charsets.UTF_8)
      else -> null
    }

  fun contentString(): String? =
    when (content) {
      is String -> content
      is ByteArray -> content.toString(Charsets.UTF_8)
      else -> null
    }

  fun collectAll(): List<WABinaryNode> {
    val result = mutableListOf(this)
    children().forEach { child -> result.addAll(child.collectAll()) }
    return result
  }

  fun findRecursive(predicate: (WABinaryNode) -> Boolean): WABinaryNode? {
    if (predicate(this)) return this
    for (child in children()) {
      val match = child.findRecursive(predicate)
      if (match != null) return match
    }
    return null
  }
}

/**
 * WhatsApp Multi-Device Binary XML (WABinary) encoder and decoder.
 * Supports LIST_EMPTY/8/16, BINARY_8/20/32, NIBBLE_8, HEX_8, JID_PAIR, AD_JID,
 * verified single-byte tokens, double-byte fallback markers, and zlib-compressed frames.
 */
object WABinaryCodec {

  private const val TAG_LIST_EMPTY = 0
  private const val TAG_DICTIONARY_0 = 236
  private const val TAG_DICTIONARY_1 = 237
  private const val TAG_DICTIONARY_2 = 238
  private const val TAG_DICTIONARY_3 = 239
  private const val TAG_AD_JID = 247
  private const val TAG_LIST_8 = 248
  private const val TAG_LIST_16 = 249
  private const val TAG_JID_PAIR = 250
  private const val TAG_HEX_8 = 251
  private const val TAG_BINARY_8 = 252
  private const val TAG_BINARY_20 = 253
  private const val TAG_BINARY_32 = 254
  private const val TAG_NIBBLE_8 = 255

  // Verified core WhatsApp MD single-byte tokens (indices 0..59).
  // Indices >= 60 decode deterministically as "token_<id>" unless explicitly verified,
  // avoiding false tag misidentification on incoming server stanzas.
  private val SINGLE_BYTE_TOKENS =
    listOf(
      "",
      "xmlstreamstart",
      "xmlstreamend",
      "s.whatsapp.net",
      "type",
      "participant",
      "from",
      "receipt",
      "id",
      "broadcast",
      "status",
      "message",
      "notification",
      "notify",
      "to",
      "jid",
      "user",
      "class",
      "offline",
      "g.us",
      "result",
      "mediatype",
      "enc",
      "skmsg",
      "off_cnt",
      "xmlns",
      "presence",
      "participants",
      "ack",
      "t",
      "iq",
      "device_hash",
      "read",
      "value",
      "media",
      "picture",
      "chatstate",
      "unavailable",
      "text",
      "urn:xmpp:whatsapp:push",
      "devices",
      "verified_name",
      "contact",
      "composing",
      "edge_routing",
      "routing_info",
      "item",
      "image",
      "verified_level",
      "get",
      "fallback_hostname",
      "2",
      "media_conn",
      "1",
      "v",
      "modify",
      "fallback_ip4",
      "fallback_ip6",
      "primary_version",
      "current",
    )

  private val DOUBLE_BYTE_TOKENS: List<List<String>> = listOf(emptyList(), emptyList(), emptyList(), emptyList())

  private val TOKEN_INDEX: Map<String, Int> =
    SINGLE_BYTE_TOKENS.mapIndexedNotNull { idx, tok ->
      if (idx in 1 until 60 && tok.isNotEmpty()) tok to idx else null
    }.toMap()

  fun decodeFrame(rawFrame: ByteArray): WABinaryNode {
    if (rawFrame.isEmpty()) throw IllegalArgumentException("Empty WABinary frame")
    val flags = rawFrame[0].toInt() and 0xFF
    val payload =
      if ((flags and 2) != 0) {
        decompressZlib(rawFrame, 1, rawFrame.size - 1)
      } else {
        rawFrame.copyOfRange(1, rawFrame.size)
      }
    return Reader(payload).readNode()
  }

  fun encodeFrame(node: WABinaryNode): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(0) // uncompressed flags byte
    writeNode(out, node)
    return out.toByteArray()
  }

  private fun writeNode(out: ByteArrayOutputStream, node: WABinaryNode) {
    val childrenList = (node.content as? List<*>)?.filterIsInstance<WABinaryNode>()
    val hasContent =
      when (val c = node.content) {
        null -> false
        is List<*> -> childrenList?.isNotEmpty() == true
        is ByteArray -> c.isNotEmpty()
        is String -> c.isNotEmpty()
        else -> true
      }
    val listSize = 1 + node.attrs.size * 2 + if (hasContent) 1 else 0
    writeListStart(out, listSize)
    writeString(out, node.tag)
    for ((k, v) in node.attrs) {
      writeString(out, k)
      writeString(out, v)
    }
    when (val c = node.content) {
      is List<*> -> {
        if (!childrenList.isNullOrEmpty()) {
          writeListStart(out, childrenList.size)
          childrenList.forEach { writeNode(out, it) }
        }
      }
      is ByteArray -> {
        if (c.isNotEmpty()) writeBytes(out, c)
      }
      is String -> {
        if (c.isNotEmpty()) writeString(out, c)
      }
    }
  }

  private fun writeListStart(out: ByteArrayOutputStream, size: Int) {
    when {
      size == 0 -> out.write(TAG_LIST_EMPTY)
      size < 256 -> {
        out.write(TAG_LIST_8)
        out.write(size)
      }
      else -> {
        out.write(TAG_LIST_16)
        out.write((size shr 8) and 0xFF)
        out.write(size and 0xFF)
      }
    }
  }

  private fun writeString(out: ByteArrayOutputStream, value: String) {
    val tokenIdx = TOKEN_INDEX[value]
    if (tokenIdx != null && tokenIdx < 236) {
      out.write(tokenIdx)
      return
    }
    if (value.contains('@')) {
      val jid = WAJid.parse(value)
      if (jid.device > 0) {
        out.write(TAG_AD_JID)
        out.write(jid.agent and 0xFF)
        out.write(jid.device and 0xFF)
        writeString(out, jid.user)
        return
      }
      out.write(TAG_JID_PAIR)
      if (jid.user.isEmpty()) out.write(TAG_LIST_EMPTY) else writeString(out, jid.user)
      writeString(out, jid.server)
      return
    }
    writeBytes(out, value.toByteArray(Charsets.UTF_8))
  }

  private fun writeBytes(out: ByteArrayOutputStream, bytes: ByteArray) {
    val len = bytes.size
    when {
      len < 256 -> {
        out.write(TAG_BINARY_8)
        out.write(len)
      }
      len < (1 shl 20) -> {
        out.write(TAG_BINARY_20)
        out.write((len shr 16) and 0x0F)
        out.write((len shr 8) and 0xFF)
        out.write(len and 0xFF)
      }
      else -> {
        out.write(TAG_BINARY_32)
        out.write((len ushr 24) and 0xFF)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
      }
    }
    out.write(bytes)
  }

  private class Reader(private val data: ByteArray) {
    private var pos = 0

    private fun readByte(): Int {
      if (pos >= data.size) throw IllegalStateException("Unexpected end of WABinary stream")
      return data[pos++].toInt() and 0xFF
    }

    private fun readInt16(): Int = (readByte() shl 8) or readByte()

    private fun readInt20(): Int = ((readByte() and 0x0F) shl 16) or (readByte() shl 8) or readByte()

    private fun readInt32(): Int =
      (readByte() shl 24) or (readByte() shl 16) or (readByte() shl 8) or readByte()

    private fun readBytes(len: Int): ByteArray {
      if (len < 0 || pos + len > data.size) {
        throw IllegalStateException("Truncated WABinary bytes ($len)")
      }
      return data.copyOfRange(pos, pos + len).also { pos += len }
    }

    private fun readListSize(tag: Int): Int =
      when (tag) {
        TAG_LIST_EMPTY -> 0
        TAG_LIST_8 -> readByte()
        TAG_LIST_16 -> readInt16()
        else -> throw IllegalStateException("Invalid list tag $tag")
      }

    fun readNode(): WABinaryNode {
      val listTag = readByte()
      val listSize = readListSize(listTag)
      if (listSize == 0) return WABinaryNode("")
      val tag = readString(readByte())
      val attrCount = (listSize - 1) / 2
      val attrs = linkedMapOf<String, String>()
      for (i in 0 until attrCount) {
        val k = readString(readByte())
        val v = readString(readByte())
        attrs[k] = v
      }
      val content: Any? =
        if (listSize % 2 == 0) {
          val contentTag = readByte()
          when (contentTag) {
            TAG_LIST_EMPTY -> emptyList<WABinaryNode>()
            TAG_LIST_8,
            TAG_LIST_16 -> {
              val count = readListSize(contentTag)
              List(count) { readNode() }
            }
            TAG_BINARY_8 -> readBytes(readByte())
            TAG_BINARY_20 -> readBytes(readInt20())
            TAG_BINARY_32 -> readBytes(readInt32())
            else -> readString(contentTag)
          }
        } else {
          null
        }
      return WABinaryNode(tag = tag, attrs = attrs, content = content)
    }

    private fun readString(tag: Int): String {
      return when (tag) {
        TAG_LIST_EMPTY -> ""
        TAG_DICTIONARY_0,
        TAG_DICTIONARY_1,
        TAG_DICTIONARY_2,
        TAG_DICTIONARY_3 -> {
          val dictIdx = tag - TAG_DICTIONARY_0
          val tokenIdx = readByte()
          DOUBLE_BYTE_TOKENS.getOrNull(dictIdx)?.getOrNull(tokenIdx) ?: "tok_${dictIdx}_$tokenIdx"
        }
        TAG_BINARY_8 -> readBytes(readByte()).toString(Charsets.UTF_8)
        TAG_BINARY_20 -> readBytes(readInt20()).toString(Charsets.UTF_8)
        TAG_BINARY_32 -> readBytes(readInt32()).toString(Charsets.UTF_8)
        TAG_NIBBLE_8 -> readPacked(isHex = false)
        TAG_HEX_8 -> readPacked(isHex = true)
        TAG_JID_PAIR -> {
          val user = readString(readByte())
          val server = readString(readByte())
          if (user.isEmpty()) server else "$user@$server"
        }
        TAG_AD_JID -> {
          val agent = readByte()
          val device = readByte()
          val user = readString(readByte())
          val suffix =
            when (agent) {
              1 -> WAJid.SERVER_LID
              128 -> "hosted"
              129 -> "hosted.lid"
              else -> WAJid.SERVER_USER
            }
          if (device > 0) "$user:$device@$suffix" else "$user@$suffix"
        }
        in 1 until SINGLE_BYTE_TOKENS.size -> SINGLE_BYTE_TOKENS[tag]
        else -> "token_$tag"
      }
    }

    private fun readPacked(isHex: Boolean): String {
      val startByte = readByte()
      val rounded = (startByte and 0x80) != 0
      val numBytes = startByte and 0x7F
      val sb = StringBuilder()
      for (i in 0 until numBytes) {
        val b = readByte()
        unpackNibble((b shr 4) and 0x0F, isHex)?.let { sb.append(it) }
        if (!(rounded && i == numBytes - 1)) {
          unpackNibble(b and 0x0F, isHex)?.let { sb.append(it) }
        }
      }
      return sb.toString()
    }

    private fun unpackNibble(nibble: Int, isHex: Boolean): Char? {
      return if (isHex) {
        if (nibble < 10) ('0' + nibble) else ('A' + (nibble - 10))
      } else {
        when (nibble) {
          in 0..9 -> '0' + nibble
          10 -> '-'
          11 -> '.'
          15 -> null // padding nibble
          else -> '?'
        }
      }
    }
  }

  private fun decompressZlib(input: ByteArray, offset: Int, length: Int): ByteArray {
    val inflater = Inflater()
    inflater.setInput(input, offset, length)
    val out = ByteArrayOutputStream()
    val buf = ByteArray(4096)
    try {
      while (!inflater.finished()) {
        val count = inflater.inflate(buf)
        if (count == 0) {
          if (inflater.needsInput() || inflater.needsDictionary()) break
        } else {
          out.write(buf, 0, count)
        }
      }
    } finally {
      inflater.end()
    }
    return out.toByteArray()
  }
}

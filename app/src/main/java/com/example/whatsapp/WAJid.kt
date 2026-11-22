package com.example.whatsapp

import org.whispersystems.libsignal.SignalProtocolAddress

data class WAJid(
  val user: String,
  val server: String,
  val device: Int = 0,
  val agent: Int = 0,
) {
  val isGroup: Boolean get() = server == SERVER_GROUP
  val isBroadcast: Boolean get() = server == SERVER_BROADCAST
  val isStatusBroadcast: Boolean get() = user == "status" && server == SERVER_BROADCAST
  val isLid: Boolean get() = server == SERVER_LID
  val isUser: Boolean get() = server == SERVER_USER || server == SERVER_LID

  fun toBareJid(): String = if (user.isNotEmpty()) "$user@$server" else server

  fun toFullJid(): String {
    if (user.isEmpty()) return server
    return if (device > 0) "$user:$device@$server" else "$user@$server"
  }

  fun toSignalAddress(): SignalProtocolAddress {
    val name = if (server == SERVER_LID) "${user}_lid" else user
    return SignalProtocolAddress(name, device)
  }

  companion object {
    const val SERVER_USER = "s.whatsapp.net"
    const val SERVER_GROUP = "g.us"
    const val SERVER_BROADCAST = "broadcast"
    const val SERVER_LID = "lid"
    const val SERVER_CALL = "call"

    fun parse(raw: String): WAJid {
      val trimmed = raw.trim()
      val atIdx = trimmed.indexOf('@')
      if (atIdx < 0) return WAJid(user = "", server = trimmed)
      val userPart = trimmed.substring(0, atIdx)
      val rawServer = trimmed.substring(atIdx + 1)
      val server = if (rawServer == "c.us") SERVER_USER else rawServer
      val agent =
        when (server) {
          SERVER_LID -> 1
          "hosted" -> 128
          "hosted.lid" -> 129
          else -> 0
        }
      val colonIdx = userPart.indexOf(':')
      return if (colonIdx >= 0) {
        val u = userPart.substring(0, colonIdx)
        val dev = userPart.substring(colonIdx + 1).toIntOrNull() ?: 0
        WAJid(user = u, server = server, device = dev, agent = agent)
      } else {
        WAJid(user = userPart, server = server, device = 0, agent = agent)
      }
    }

    fun normalizeUser(raw: String): String {
      return parse(raw).toBareJid()
    }

    fun areSameUser(jidA: String?, jidB: String?): Boolean {
      if (jidA.isNullOrBlank() || jidB.isNullOrBlank()) return false
      val a = parse(jidA)
      val b = parse(jidB)
      return a.user == b.user && a.server == b.server
    }
  }
}

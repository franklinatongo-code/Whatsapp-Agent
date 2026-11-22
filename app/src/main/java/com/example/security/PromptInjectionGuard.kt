package com.example.security

enum class PermissionTier(val label: String) {
  OWNER("Owner"),
  ADMIN("Admin"),
  TRUSTED("Trusted"),
  STANDARD("Standard"),
  BLOCKED("Blocked");

  companion object {
    fun fromName(raw: String?): PermissionTier {
      return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: STANDARD
    }
  }
}

enum class ToolSafetyLevel {
  SAFE,
  MEMORY_WRITE,
  PRIVILEGED_DEVICE,
  ADMIN_WHATSAPP,
}

data class GuardedMessageResult(
  val sanitizedEnvelope: String,
  val suspicionScore: Int,
  val detectedFlags: List<String>,
)

object PromptInjectionGuard {

  private val suspiciousPatterns =
    listOf(
      "ignore previous instructions" to "Instruction override attempt",
      "ignore all previous" to "Instruction override attempt",
      "disregard system prompt" to "System prompt override attempt",
      "you are now" to "Role hijack attempt",
      "reveal your api key" to "Secret exfiltration attempt",
      "print nvidia_api_key" to "Secret exfiltration attempt",
      "show system prompt" to "System prompt extraction attempt",
      "<system>" to "XML delimiter spoofing",
      "</untrusted_whatsapp_message>" to "Boundary escape attempt",
      "execute shell" to "Command execution attempt",
      "rm -rf" to "Destructive command pattern",
    )

  fun wrapUntrustedInput(
    senderName: String,
    senderJid: String,
    chatJid: String,
    isGroup: Boolean,
    rawBody: String,
    mediaAttachmentSummary: String? = null,
    quotedSenderLabel: String? = null,
    quotedMessageContent: String? = null,
  ): GuardedMessageResult {
    val normalized = rawBody.take(12_000)
    val lower = normalized.lowercase()
    val flags =
      suspiciousPatterns.mapNotNull { (pattern, label) ->
        if (lower.contains(pattern)) label else null
      }

    // Neutralize any boundary tags inside user-controlled text
    val escapedBody =
      normalized
        .replace("</untrusted_whatsapp_message>", "[/untrusted_whatsapp_message]")
        .replace("</replied_to_message>", "[/replied_to_message]")
        .replace("<system>", "[system]")
        .replace("</system>", "[/system]")

    val quotedBlock =
      if (!quotedMessageContent.isNullOrBlank()) {
        val safeQuoted =
          quotedMessageContent
            .take(2_400)
            .replace("</replied_to_message>", "[/replied_to_message]")
            .replace("</untrusted_whatsapp_message>", "[/untrusted_whatsapp_message]")
        val whoAttr = sanitizeAttr(quotedSenderLabel?.takeIf { it.isNotBlank() } ?: "Previous message")
        "<replied_to_message sender=\"$whoAttr\">\n$safeQuoted\n</replied_to_message>\n"
      } else {
        ""
      }

    val attachmentBlock =
      if (!mediaAttachmentSummary.isNullOrBlank()) {
        val safeAttachment =
          mediaAttachmentSummary
            .take(8_000)
            .replace("</untrusted_whatsapp_attachment>", "[/untrusted_whatsapp_attachment]")
        "\n<untrusted_whatsapp_attachment>\n$safeAttachment\n</untrusted_whatsapp_attachment>"
      } else {
        ""
      }

    val envelope =
      buildString {
        append(quotedBlock)
        append("<untrusted_whatsapp_message ")
        append("sender=\"${sanitizeAttr(senderName)}\" ")
        append("sender_jid=\"${sanitizeAttr(senderJid)}\" ")
        append("chat_jid=\"${sanitizeAttr(chatJid)}\" ")
        append("is_group=\"$isGroup\">\n")
        append(escapedBody)
        append("\n</untrusted_whatsapp_message>")
        append(attachmentBlock)
      }

    return GuardedMessageResult(
      sanitizedEnvelope = envelope,
      suspicionScore = flags.size,
      detectedFlags = flags,
    )
  }

  fun isToolAllowed(
    tier: PermissionTier,
    safetyLevel: ToolSafetyLevel,
    isGroup: Boolean,
    allowGroupTools: Boolean,
  ): Boolean {
    if (tier == PermissionTier.BLOCKED) return false
    if (isGroup && !allowGroupTools && safetyLevel != ToolSafetyLevel.SAFE) return false
    return when (safetyLevel) {
      ToolSafetyLevel.SAFE -> true
      ToolSafetyLevel.MEMORY_WRITE ->
        tier == PermissionTier.OWNER ||
          tier == PermissionTier.ADMIN ||
          tier == PermissionTier.TRUSTED ||
          tier == PermissionTier.STANDARD
      ToolSafetyLevel.PRIVILEGED_DEVICE ->
        tier == PermissionTier.OWNER || tier == PermissionTier.ADMIN || tier == PermissionTier.TRUSTED
      ToolSafetyLevel.ADMIN_WHATSAPP ->
        tier == PermissionTier.OWNER || tier == PermissionTier.ADMIN
    }
  }

  private fun sanitizeAttr(value: String): String {
    return value.replace("\"", "'").replace("\n", " ").take(80)
  }
}

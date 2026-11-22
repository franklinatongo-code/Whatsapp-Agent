package com.example.agent

import com.example.data.db.ConversationEntity
import com.example.data.prefs.AgentSettings
import com.example.security.PermissionTier

enum class TriggerSource {
  AUTO_REPLY,
  TEXT_AI_TOOL,
  REPLY_RIGHT,
  KEEP_REPLYING,
  SCHEDULED_JOB,
}

sealed class PermissionDecision {
  data object Allowed : PermissionDecision()
  data class Blocked(
    val reason: String,
    val requiresGroupEnable: Boolean = false,
  ) : PermissionDecision()
}

/**
 * Centralized safety & permission policy enforced at the outbound execution layer.
 * Ensures AI never sends messages to WhatsApp groups when Group Chatting is turned OFF,
 * regardless of whether the trigger is auto-reply, Text My AI tool calls, Reply Right,
 * Keep Replying, or scheduled messages.
 */
object GroupPermissionPolicy {

  fun evaluateOutgoingPermission(
    chatJid: String,
    settings: AgentSettings,
    conversation: ConversationEntity?,
    triggerSource: TriggerSource,
  ): PermissionDecision {
    val normalizedJid = chatJid.lowercase()
    if (normalizedJid == "status@broadcast" || normalizedJid.endsWith("@newsletter")) {
      return PermissionDecision.Blocked(
        reason = "WhatsApp Status/Channels cannot receive automated AI replies.",
        requiresGroupEnable = false,
      )
    }

    val tier = conversation?.let { PermissionTier.fromName(it.permissionTier) } ?: PermissionTier.STANDARD
    if (tier == PermissionTier.BLOCKED) {
      return PermissionDecision.Blocked(
        reason = "This contact or group is marked as BLOCKED in Permissions.",
        requiresGroupEnable = false,
      )
    }

    val isGroup = normalizedJid.endsWith("@g.us")
    val perGroupOverride = conversation?.perGroupPermissionOverride?.uppercase()
    if (isGroup) {
      if (perGroupOverride == "DENY") {
        return PermissionDecision.Blocked(
          reason = "This group has a per-chat Group AI DENY override.",
          requiresGroupEnable = false,
        )
      }
      if (!settings.autoReplyGroups && perGroupOverride != "ALLOW") {
        return PermissionDecision.Blocked(
          reason =
            "Group chatting is currently turned OFF. Turn ON 'Reply in Group Chats' in AI Studio settings before messaging a group.",
          requiresGroupEnable = true,
        )
      }
    }

    if (triggerSource == TriggerSource.AUTO_REPLY) {
      if (conversation?.autoReplyEnabled == false) {
        return PermissionDecision.Blocked(
          reason = "Auto-reply is paused for this conversation.",
          requiresGroupEnable = false,
        )
      }
      if (!isGroup && !settings.autoReplyDirectChats) {
        return PermissionDecision.Blocked(
          reason = "Direct chat auto-reply is currently turned OFF.",
          requiresGroupEnable = false,
        )
      }
    }

    return PermissionDecision.Allowed
  }
}

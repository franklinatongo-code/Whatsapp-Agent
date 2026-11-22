package com.example.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.example.ai.NimToolDefinition
import com.example.data.db.ChatMessageEntity
import com.example.data.db.ConversationEntity
import com.example.data.repository.ClawRepository
import com.example.security.PermissionTier
import com.example.security.PromptInjectionGuard
import com.example.security.ToolSafetyLevel
import com.example.util.optNonNullString
import com.example.whatsapp.WAJid
import com.example.whatsapp.WhatsAppConnectionState
import com.example.whatsapp.WhatsAppSendResult
import com.example.whatsapp.WhatsAppSessionManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AgentToolSpec(
  val definition: NimToolDefinition,
  val safetyLevel: ToolSafetyLevel,
)

/**
 * Native OpenClaw-compatible tool registry with strict permission gating.
 * Contacts tagged ADMIN (or OWNER) unlock full WhatsApp delegation tools
 * (summarize, catch-up briefing, read/list chats, reply/send WhatsApp messages,
 * schedule messages, manage auto-reply settings, cross-chat search) while strictly
 * honoring GroupPermissionPolicy and Blocked tiers.
 */
class AgentTools(
  private val context: Context,
  private val repository: ClawRepository,
  private val sessionManager: WhatsAppSessionManager? = null,
  private val summarizeChatHandler: (suspend (String) -> Result<String>)? = null,
  private val catchUpBriefingHandler: (suspend () -> Result<String>)? = null,
) {

  private val allTools: List<AgentToolSpec> =
    listOf(
      AgentToolSpec(
        definition =
          NimToolDefinition(
            name = "save_contact_memory",
            description = "Persist an important preference, fact, or reminder specific to this contact or group.",
            parametersSchemaJson =
              JSONObject()
                .put("type", "object")
                .put(
                  "properties",
                  JSONObject()
                    .put("fact_key", JSONObject().put("type", "string").put("description", "Short snake_case key"))
                    .put("fact_value", JSONObject().put("type", "string").put("description", "Concise fact value"))
                    .put("category", JSONObject().put("type", "string").put("description", "PREFERENCE, PROFILE, TASK, or GENERAL")),
                )
                .put("required", JSONArray().put("fact_key").put("fact_value")),
          ),
        safetyLevel = ToolSafetyLevel.MEMORY_WRITE,
      ),
      AgentToolSpec(
        definition =
          NimToolDefinition(
            name = "get_contact_memory",
            description = "Retrieve all stored memory facts isolated to this specific conversation.",
            parametersSchemaJson =
              JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()),
          ),
        safetyLevel = ToolSafetyLevel.SAFE,
      ),
      AgentToolSpec(
        definition =
          NimToolDefinition(
            name = "search_chat_history",
            description = "Search prior messages inside this conversation by keyword (including attachment notes and quotes).",
            parametersSchemaJson =
              JSONObject()
                .put("type", "object")
                .put(
                  "properties",
                  JSONObject()
                    .put("query", JSONObject().put("type", "string").put("description", "Search keyword")),
                )
                .put("required", JSONArray().put("query")),
          ),
        safetyLevel = ToolSafetyLevel.SAFE,
      ),
      AgentToolSpec(
        definition =
          NimToolDefinition(
            name = "summarize_chat",
            description = "Admin tool: Generate an AI summary of this conversation OR another contact/group's conversation by name, phone number, or chat JID.",
            parametersSchemaJson =
              JSONObject()
                .put("type", "object")
                .put(
                  "properties",
                  JSONObject()
                    .put("contact_query", JSONObject().put("type", "string").put("description", "Optional contact/group name or JID; omit or leave blank to summarize this current chat.")),
                ),
          ),
        safetyLevel = ToolSafetyLevel.ADMIN_WHATSAPP,
      ),
      AgentToolSpec(
        definition =
          NimToolDefinition(
            name = "catch_up_briefing",
            description = "Admin tool: Build an executive Catch-Up Briefing across recent and unread WhatsApp chats showing urgent items, updates, and handled replies.",
            parametersSchemaJson =
              JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()),
          ),
        safetyLevel = ToolSafetyLevel.ADMIN_WHATSAPP,
      ),
      AgentToolSpec(
        definition =
          NimToolDefinition(
            name = "list_whatsapp_chats",
            description = "Admin tool: List synced WhatsApp contacts and groups with JIDs, unread counts, tags, auto-reply status, and latest previews.",
            parametersSchemaJson =
              JSONObject()
                .put("type", "object")
                .put(
                  "properties",
                  JSONObject()
                    .put("limit", JSONObject().put("type", "integer").put("description", "Max chats to return (default 20)")),
                ),
          ),
        safetyLevel = ToolSafetyLevel.ADMIN_WHATSAPP,
      ),
      AgentToolSpec(
        definition =
          NimToolDefinition(
            name = "read_whatsapp_chat",
            description = "Admin tool: Read recent messages, owner reply status (whether the owner already replied to each message), media insights, summary, and memories for a contact or group.",
            parametersSchemaJson =
              JSONObject()
                .put("type", "object")
                .put(
                  "properties",
                  JSONObject()
                    .put("contact_query", JSONObject().put("type", "string").put("description", "Contact/group name, phone, or JID"))
                    .put("limit", JSONObject().put("type", "integer").put("description", "Recent messages count (default 20)")),
                )
                .put("required", JSONArray().put("contact_query")),
          ),
        safetyLevel = ToolSafetyLevel.ADMIN_WHATSAPP,
      ),
      AgentToolSpec(
        definition =
          NimToolDefinition(
            name = "reply_or_send_whatsapp",
            description = "Admin tool: Send a WhatsApp reply or message to a contact, group, or phone number (strictly checks Group Chatting & Blocked rules). Optionally quote a specific message ID.",
            parametersSchemaJson =
              JSONObject()
                .put("type", "object")
                .put(
                  "properties",
                  JSONObject()
                    .put("contact_query", JSONObject().put("type", "string").put("description", "Recipient name, chat_jid, or phone number"))
                    .put("message_text", JSONObject().put("type", "string").put("description", "Exact WhatsApp text to send"))
                    .put("quote_message_id", JSONObject().put("type", "string").put("description", "Optional specific message ID to swipe-reply to")),
                )
                .put("required", JSONArray().put("contact_query").put("message_text")),
          ),
        safetyLevel = ToolSafetyLevel.ADMIN_WHATSAPP,
      ),
      AgentToolSpec(
        definition =
          NimToolDefinition(
            name = "schedule_whatsapp_message",
            description = "Admin tool: Schedule a WhatsApp message to send automatically after delay_minutes.",
            parametersSchemaJson =
              JSONObject()
                .put("type", "object")
                .put(
                  "properties",
                  JSONObject()
                    .put("contact_query", JSONObject().put("type", "string").put("description", "Recipient name, chat_jid, or phone number"))
                    .put("message_text", JSONObject().put("type", "string").put("description", "Message to deliver later"))
                    .put("delay_minutes", JSONObject().put("type", "integer").put("description", "Minutes from now (min 1)")),
                )
                .put("required", JSONArray().put("contact_query").put("message_text").put("delay_minutes")),
          ),
        safetyLevel = ToolSafetyLevel.ADMIN_WHATSAPP,
      ),
      AgentToolSpec(
        definition =
          NimToolDefinition(
            name = "manage_contact_ai",
            description = "Admin tool: Pause or resume AI auto-reply, update custom instructions, language, or response style for a specific contact or group.",
            parametersSchemaJson =
              JSONObject()
                .put("type", "object")
                .put(
                  "properties",
                  JSONObject()
                    .put("contact_query", JSONObject().put("type", "string").put("description", "Contact or group name/JID"))
                    .put("auto_reply_enabled", JSONObject().put("type", "boolean"))
                    .put("custom_instructions", JSONObject().put("type", "string"))
                    .put("response_style", JSONObject().put("type", "string"))
                    .put("preferred_language", JSONObject().put("type", "string")),
                )
                .put("required", JSONArray().put("contact_query")),
          ),
        safetyLevel = ToolSafetyLevel.ADMIN_WHATSAPP,
      ),
      AgentToolSpec(
        definition =
          NimToolDefinition(
            name = "search_all_chats",
            description = "Admin tool: Search across recent WhatsApp conversations for messages or attachments matching a keyword.",
            parametersSchemaJson =
              JSONObject()
                .put("type", "object")
                .put(
                  "properties",
                  JSONObject()
                    .put("query", JSONObject().put("type", "string").put("description", "Keyword to search across chats")),
                )
                .put("required", JSONArray().put("query")),
          ),
        safetyLevel = ToolSafetyLevel.ADMIN_WHATSAPP,
      ),
      AgentToolSpec(
        definition =
          NimToolDefinition(
            name = "get_device_telemetry",
            description = "Read local Android time, timezone, and battery status of the host device.",
            parametersSchemaJson =
              JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()),
          ),
        safetyLevel = ToolSafetyLevel.PRIVILEGED_DEVICE,
      ),
      AgentToolSpec(
        definition =
          NimToolDefinition(
            name = "evaluate_math",
            description = "Safely evaluate a mathematical expression (+, -, *, /, %, ^, sqrt, sin, cos, log, ln, pi).",
            parametersSchemaJson =
              JSONObject()
                .put("type", "object")
                .put(
                  "properties",
                  JSONObject()
                    .put("expression", JSONObject().put("type", "string").put("description", "Math expression, e.g. (12.5 * 4) + sqrt(81)")),
                )
                .put("required", JSONArray().put("expression")),
          ),
        safetyLevel = ToolSafetyLevel.SAFE,
      ),
      AgentToolSpec(
        definition =
          NimToolDefinition(
            name = "convert_units",
            description = "Convert numeric values between length, weight, temperature, speed, data, time, area, or volume units.",
            parametersSchemaJson =
              JSONObject()
                .put("type", "object")
                .put(
                  "properties",
                  JSONObject()
                    .put("value", JSONObject().put("type", "number"))
                    .put("category", JSONObject().put("type", "string").put("description", "LENGTH, MASS, TEMPERATURE, SPEED, DATA, TIME, AREA, VOLUME"))
                    .put("from_unit", JSONObject().put("type", "string"))
                    .put("to_unit", JSONObject().put("type", "string")),
                )
                .put("required", JSONArray().put("value").put("category").put("from_unit").put("to_unit")),
          ),
        safetyLevel = ToolSafetyLevel.SAFE,
      ),
      AgentToolSpec(
        definition =
          NimToolDefinition(
            name = "encode_or_hash",
            description = "Encode, decode, or hash text using base64_encode, base64_decode, sha256, sha512, md5, hex, or uuid.",
            parametersSchemaJson =
              JSONObject()
                .put("type", "object")
                .put(
                  "properties",
                  JSONObject()
                    .put("mode", JSONObject().put("type", "string").put("description", "base64_encode, base64_decode, sha256, sha512, md5, hex, uuid"))
                    .put("input", JSONObject().put("type", "string")),
                )
                .put("required", JSONArray().put("mode")),
          ),
        safetyLevel = ToolSafetyLevel.SAFE,
      ),
      AgentToolSpec(
        definition =
          NimToolDefinition(
            name = "generate_password",
            description = "Generate a cryptographically random password or passphrase.",
            parametersSchemaJson =
              JSONObject()
                .put("type", "object")
                .put(
                  "properties",
                  JSONObject()
                    .put("length", JSONObject().put("type", "integer"))
                    .put("passphrase", JSONObject().put("type", "boolean")),
                ),
          ),
        safetyLevel = ToolSafetyLevel.SAFE,
      ),
    )

  fun definitionsFor(
    tier: PermissionTier,
    isGroup: Boolean,
    allowGroupTools: Boolean,
    stealthExternalChat: Boolean = false,
  ): List<NimToolDefinition> {
    val stealthMemoryOnly = setOf("save_contact_memory", "get_contact_memory", "search_chat_history")
    val isPrivilegedRole = tier == PermissionTier.OWNER || tier == PermissionTier.ADMIN
    return allTools
      .filter { spec ->
        val allowedByTier = PromptInjectionGuard.isToolAllowed(tier, spec.safetyLevel, isGroup, allowGroupTools)
        if (!allowedByTier) return@filter false
        if (stealthExternalChat && !isPrivilegedRole) {
          spec.definition.name in stealthMemoryOnly
        } else {
          true
        }
      }
      .map { it.definition }
  }

  suspend fun executeTool(
    name: String,
    argumentsJson: String,
    chatJid: String,
    tier: PermissionTier,
    isGroup: Boolean,
    allowGroupTools: Boolean,
  ): String {
    val spec = allTools.firstOrNull { it.definition.name == name }
      ?: return JSONObject().put("error", "Unknown tool: $name").toString()

    if (!PromptInjectionGuard.isToolAllowed(tier, spec.safetyLevel, isGroup, allowGroupTools)) {
      repository.log("WARN", "SECURITY", "Blocked tool '$name' for $chatJid (tier=$tier, group=$isGroup)")
      return JSONObject()
        .put("error", "Permission denied: '$name' requires Admin/Owner privileges (current tag: ${tier.label}).")
        .toString()
    }

    val args = runCatching { JSONObject(argumentsJson) }.getOrElse { JSONObject() }
    return when (name) {
      "save_contact_memory" -> {
        val key = args.optNonNullString("fact_key")
        val value = args.optNonNullString("fact_value")
        val cat = args.optNonNullString("category", "GENERAL").ifBlank { "GENERAL" }
        repository.saveContactMemory(chatJid, key, value, cat)
        repository.log("INFO", "AGENT", "Saved contact memory [$key] for $chatJid")
        JSONObject().put("status", "saved").put("fact_key", key).toString()
      }
      "get_contact_memory" -> {
        val list = repository.getMemories(chatJid)
        val arr = JSONArray()
        list.forEach { m ->
          arr.put(
            JSONObject()
              .put("key", m.factKey)
              .put("value", m.factValue)
              .put("category", m.category)
          )
        }
        JSONObject().put("memories", arr).toString()
      }
      "search_chat_history" -> {
        val query = args.optNonNullString("query")
        val matches = repository.searchChatHistory(chatJid, query)
        val arr = JSONArray()
        matches.forEach { msg ->
          val media = msg.mediaTranscription?.takeUnless { PersonMemoryExtractor.isPlaceholderOrBlankMediaNote(it) }
          arr.put(
            JSONObject()
              .put("id", msg.id)
              .put("sender", if (msg.fromMe) "Me" else msg.senderName)
              .put("from_me", msg.fromMe)
              .put("text", msg.textContent.take(240))
              .put("media_note", media?.take(180).orEmpty())
              .put("timestamp", msg.timestamp)
          )
        }
        JSONObject().put("matches", arr).toString()
      }
      "summarize_chat" -> {
        val query = args.optNonNullString("contact_query").trim()
        val targetJid =
          if (query.isBlank()) {
            chatJid
          } else {
            val matches = resolveConversations(query)
            when {
              matches.isEmpty() -> {
                val avail = repository.getAllConversations().take(10).map { it.displayName }
                return JSONObject()
                  .put("error", "No conversation matching '$query'")
                  .put("available_chats", JSONArray(avail))
                  .toString()
              }
              matches.size > 1 && !matches.first().displayName.equals(query, true) && !matches.first().chatJid.equals(query, true) -> {
                val candidates = JSONArray()
                matches.take(5).forEach {
                  candidates.put(JSONObject().put("chat_jid", it.chatJid).put("display_name", it.displayName).put("is_group", it.isGroup))
                }
                return JSONObject().put("ambiguous", true).put("candidates", candidates).toString()
              }
              else -> matches.first().chatJid
            }
          }
        val handler = summarizeChatHandler
        if (handler != null) {
          val res = handler(targetJid)
          if (res.isSuccess) {
            val conv = repository.getConversation(targetJid)
            repository.log("INFO", "ADMIN", "Admin ($chatJid) summarized chat $targetJid")
            JSONObject()
              .put("status", "ok")
              .put("chat_jid", targetJid)
              .put("display_name", conv?.displayName ?: targetJid)
              .put("summary", res.getOrThrow())
              .toString()
          } else {
            JSONObject()
              .put("error", res.exceptionOrNull()?.message ?: "Could not summarize conversation")
              .toString()
          }
        } else {
          val conv = repository.getConversation(targetJid)
          val msgs = repository.getRecentMessages(targetJid, 20)
          val snippet =
            msgs.joinToString("\n") { m ->
              val who = if (m.fromMe) "Me" else m.senderName.ifBlank { "Member" }
              "$who: ${m.textContent.take(160)}"
            }
          JSONObject()
            .put("status", "ok")
            .put("chat_jid", targetJid)
            .put("display_name", conv?.displayName ?: targetJid)
            .put("rolling_summary", conv?.rollingSummary.orEmpty())
            .put("recent_transcript", snippet)
            .toString()
        }
      }
      "catch_up_briefing" -> {
        val handler = catchUpBriefingHandler
        if (handler != null) {
          val res = handler()
          if (res.isSuccess) {
            repository.log("INFO", "ADMIN", "Admin ($chatJid) ran catch-up briefing")
            JSONObject().put("status", "ok").put("briefing", res.getOrThrow()).toString()
          } else {
            JSONObject().put("error", res.exceptionOrNull()?.message ?: "Briefing unavailable").toString()
          }
        } else {
          val chats = repository.getAllConversations().take(8)
          val arr = JSONArray()
          chats.forEach { c ->
            arr.put(
              JSONObject()
                .put("display_name", c.displayName)
                .put("unread_count", c.unreadCount)
                .put("last_preview", c.lastMessagePreview)
            )
          }
          JSONObject().put("status", "ok").put("recent_chats", arr).toString()
        }
      }
      "list_whatsapp_chats" -> {
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        val chats = repository.getAllConversations().take(limit)
        val arr = JSONArray()
        chats.forEach { c ->
          arr.put(
            JSONObject()
              .put("chat_jid", c.chatJid)
              .put("display_name", c.displayName)
              .put("is_group", c.isGroup)
              .put("tag", c.permissionTier)
              .put("unread_count", c.unreadCount)
              .put("auto_reply_enabled", c.autoReplyEnabled)
              .put("last_preview", c.lastMessagePreview)
              .put("business_category", c.businessCategory)
          )
        }
        JSONObject().put("count", arr.length()).put("chats", arr).toString()
      }
      "read_whatsapp_chat" -> {
        val query = args.optNonNullString("contact_query").trim()
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        val matches = resolveConversations(query)
        if (matches.isEmpty()) {
          val available = repository.getAllConversations().take(10).map { it.displayName }
          return JSONObject().put("error", "No chat matching '$query'").put("available_chats", JSONArray(available)).toString()
        }
        if (matches.size > 1 && !matches.first().displayName.equals(query, true) && !matches.first().chatJid.equals(query, true)) {
          val candidates = JSONArray()
          matches.take(5).forEach {
            candidates.put(JSONObject().put("chat_jid", it.chatJid).put("display_name", it.displayName).put("is_group", it.isGroup))
          }
          return JSONObject().put("ambiguous", true).put("candidates", candidates).toString()
        }
        val conv = matches.first()
        val msgs = repository.getRecentMessages(conv.chatJid, limit)
        val replyIndex = ReplyStatusTracker.analyze(msgs)
        val memories = repository.getMemories(conv.chatJid)
        val fmt = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
        val msgArr = JSONArray()
        msgs.forEach { m ->
          val attachmentNote = m.mediaTranscription?.takeUnless { PersonMemoryExtractor.isPlaceholderOrBlankMediaNote(it) }
          val ownerReply = if (!m.fromMe) replyIndex.ownerReplyFor(m.id) else null
          val obj =
            JSONObject()
              .put("id", m.id)
              .put("from", if (m.fromMe) "Me" else m.senderName.ifBlank { "Member" })
              .put("from_me", m.fromMe)
              .put("type", m.messageType)
              .put("text", HumanReplySanitizer.sanitizeHistoryTurn(m.textContent).take(360))
              .put("time", fmt.format(Date(m.timestamp)))
          if (!attachmentNote.isNullOrBlank()) obj.put("media_insight", attachmentNote.take(200))
          if (!m.quotedPreview.isNullOrBlank()) obj.put("quoted_preview", m.quotedPreview.take(120))
          if (!m.fromMe) {
            obj.put("already_replied_by_owner", ownerReply != null)
            if (ownerReply != null) obj.put("owner_reply_preview", ownerReply.textContent.take(120))
          }
          msgArr.put(obj)
        }
        val memArr = JSONArray()
        memories.take(15).forEach { mem ->
          memArr.put(JSONObject().put("key", mem.factKey).put("value", mem.factValue))
        }
        repository.log("INFO", "ADMIN", "Admin ($chatJid) read chat ${conv.displayName}")
        JSONObject()
          .put("chat_jid", conv.chatJid)
          .put("display_name", conv.displayName)
          .put("is_group", conv.isGroup)
          .put("tag", conv.permissionTier)
          .put("auto_reply_enabled", conv.autoReplyEnabled)
          .put("rolling_summary", conv.rollingSummary)
          .put("memories", memArr)
          .put("messages", msgArr)
          .toString()
      }
      "reply_or_send_whatsapp" -> {
        val query = args.optNonNullString("contact_query").trim()
        val msgText = args.optNonNullString("message_text").ifBlank { args.optNonNullString("message") }.trim()
        val quoteMsgId = args.optNonNullString("quote_message_id").trim().ifBlank { null }
        if (msgText.isBlank()) {
          return JSONObject().put("error", "message_text cannot be empty").toString()
        }
        val settings = repository.getCurrentSettings()
        val matches = resolveConversations(query)
        val matchedConv: ConversationEntity?
        val targetJid: String
        val targetName: String
        if (matches.size == 1 || (matches.isNotEmpty() && (matches.first().displayName.equals(query, true) || matches.first().chatJid.equals(query, true)))) {
          matchedConv = matches.first()
          targetJid = matchedConv.chatJid
          targetName = matchedConv.displayName
        } else if (matches.size > 1) {
          val candidates = JSONArray()
          matches.take(5).forEach {
            candidates.put(JSONObject().put("chat_jid", it.chatJid).put("display_name", it.displayName).put("is_group", it.isGroup))
          }
          return JSONObject()
            .put("status", "ambiguous")
            .put("message", "Multiple chats matched '$query'. Ask Admin which one.")
            .put("candidates", candidates)
            .toString()
        } else {
          val digits = query.filter { it.isDigit() }
          if (digits.length >= 7) {
            targetJid = if (query.endsWith("@g.us")) WAJid.normalizeUser(query) else WAJid.normalizeUser(digits)
            targetName = digits
            matchedConv = repository.getConversation(targetJid)
          } else {
            return JSONObject().put("status", "not_found").put("error", "No contact or group found matching '$query'.").toString()
          }
        }

        when (val perm = GroupPermissionPolicy.evaluateOutgoingPermission(targetJid, settings, matchedConv, TriggerSource.TEXT_AI_TOOL)) {
          is PermissionDecision.Blocked -> {
            return JSONObject()
              .put("status", "blocked_by_permission")
              .put("requires_group_chat_toggle", perm.requiresGroupEnable)
              .put("error", perm.reason)
              .put("recipient", targetName)
              .toString()
          }
          PermissionDecision.Allowed -> Unit
        }

        val sm = sessionManager
        if (sm == null || sm.state.value !is WhatsAppConnectionState.Connected) {
          return JSONObject()
            .put("status", "not_connected")
            .put("error", "WhatsApp is not currently connected on the host phone.")
            .toString()
        }

        val meJid = sm.credentialStore.getOrCreateCredentials().meJid
        val quotedTarget = quoteMsgId?.let { repository.findQuotedMessage(targetJid, it) }
        val sendRes =
          sm.messaging.sendTextMessageDetailed(
            chatJid = targetJid,
            text = msgText,
            meJid = meJid,
            quotedMessageId = quotedTarget?.id ?: quoteMsgId,
            quotedText = quotedTarget?.textContent?.take(140),
          )
        val recordId = sendRes.sentIdOrNull() ?: "admin_out_${System.nanoTime()}"
        val statusString =
          when (sendRes) {
            is WhatsAppSendResult.Confirmed -> "SENT"
            is WhatsAppSendResult.SentUnconfirmed -> "SENT_UNCONFIRMED"
            is WhatsAppSendResult.UnknownTimeout -> "UNKNOWN"
            is WhatsAppSendResult.Failed -> "FAILED"
          }
        if (sendRes !is WhatsAppSendResult.UnknownTimeout) {
          repository.recordMessage(
            ChatMessageEntity(
              id = recordId,
              chatJid = targetJid,
              senderJid = meJid ?: "me@local",
              senderName = "Me",
              fromMe = true,
              timestamp = System.currentTimeMillis(),
              messageType = "TEXT",
              textContent = msgText,
              quotedMessageId = quotedTarget?.id ?: quoteMsgId,
              quotedPreview = quotedTarget?.textContent?.take(140),
              deliveryStatus = statusString,
            )
          )
        }
        when (sendRes) {
          is WhatsAppSendResult.Confirmed, is WhatsAppSendResult.SentUnconfirmed -> {
            repository.log("INFO", "ADMIN", "Admin ($chatJid) sent WhatsApp message to $targetName ($targetJid)")
            JSONObject()
              .put("status", "sent")
              .put("chat_jid", targetJid)
              .put("recipient", targetName)
              .put("message_id", recordId)
              .toString()
          }
          is WhatsAppSendResult.UnknownTimeout ->
            JSONObject().put("status", "unknown_timeout").put("error", sendRes.reason).toString()
          is WhatsAppSendResult.Failed ->
            JSONObject().put("status", "failed").put("error", sendRes.error).toString()
        }
      }
      "schedule_whatsapp_message" -> {
        val query = args.optNonNullString("contact_query").trim()
        val msgText = args.optNonNullString("message_text").ifBlank { args.optNonNullString("message") }.trim()
        val delayMin = args.optInt("delay_minutes", 15).coerceIn(1, 60 * 24 * 14)
        if (msgText.isBlank()) {
          return JSONObject().put("error", "message_text cannot be empty").toString()
        }
        val settings = repository.getCurrentSettings()
        val matches = resolveConversations(query)
        val matchedConv: ConversationEntity?
        val targetJid: String
        val targetName: String
        if (matches.isNotEmpty()) {
          matchedConv = matches.first()
          targetJid = matchedConv.chatJid
          targetName = matchedConv.displayName
        } else {
          val digits = query.filter { it.isDigit() }
          if (digits.length >= 7) {
            targetJid = if (query.endsWith("@g.us")) WAJid.normalizeUser(query) else WAJid.normalizeUser(digits)
            targetName = digits
            matchedConv = repository.getConversation(targetJid)
          } else {
            return JSONObject().put("error", "No contact matching '$query'").toString()
          }
        }
        when (val perm = GroupPermissionPolicy.evaluateOutgoingPermission(targetJid, settings, matchedConv, TriggerSource.SCHEDULED_JOB)) {
          is PermissionDecision.Blocked -> {
            return JSONObject()
              .put("status", "blocked_by_permission")
              .put("requires_group_chat_toggle", perm.requiresGroupEnable)
              .put("error", perm.reason)
              .put("recipient", targetName)
              .toString()
          }
          PermissionDecision.Allowed -> Unit
        }
        val targetTimeMs = System.currentTimeMillis() + delayMin * 60_000L
        val item = repository.scheduleWhatsAppMessage(targetJid, targetName, msgText, targetTimeMs)
        repository.log("INFO", "ADMIN", "Admin ($chatJid) scheduled message to $targetName in ${delayMin}m")
        val timeFmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(targetTimeMs))
        JSONObject()
          .put("status", "scheduled")
          .put("id", item.id)
          .put("recipient", targetName)
          .put("scheduled_time", timeFmt)
          .toString()
      }
      "manage_contact_ai" -> {
        val query = args.optNonNullString("contact_query").trim()
        val conv =
          if (query.isBlank()) repository.getConversation(chatJid)
          else resolveConversations(query).firstOrNull()
        if (conv == null) {
          return JSONObject().put("error", "No contact matching '$query'").toString()
        }
        val updated =
          conv.copy(
            autoReplyEnabled = if (args.has("auto_reply_enabled") && !args.isNull("auto_reply_enabled")) args.optBoolean("auto_reply_enabled") else conv.autoReplyEnabled,
            customInstructions = if (args.has("custom_instructions") && !args.isNull("custom_instructions")) args.optNonNullString("custom_instructions") else conv.customInstructions,
            responseStyle = if (args.has("response_style") && !args.isNull("response_style")) args.optNonNullString("response_style") else conv.responseStyle,
            preferredLanguage = if (args.has("preferred_language") && !args.isNull("preferred_language")) args.optNonNullString("preferred_language") else conv.preferredLanguage,
          )
        repository.updateConversation(updated)
        repository.log("INFO", "ADMIN", "Admin ($chatJid) updated AI settings for ${conv.displayName}")
        JSONObject()
          .put("status", "updated")
          .put("contact", conv.displayName)
          .put("auto_reply_enabled", updated.autoReplyEnabled)
          .put("response_style", updated.responseStyle)
          .put("preferred_language", updated.preferredLanguage)
          .toString()
      }
      "search_all_chats" -> {
        val query = args.optNonNullString("query").trim()
        if (query.isBlank()) return JSONObject().put("error", "query cannot be empty").toString()
        val chats = repository.getAllConversations().take(25)
        val arr = JSONArray()
        for (c in chats) {
          val hits = repository.searchChatHistory(c.chatJid, query)
          for (m in hits.take(3)) {
            arr.put(
              JSONObject()
                .put("chat_name", c.displayName)
                .put("chat_jid", c.chatJid)
                .put("sender", if (m.fromMe) "Me" else m.senderName)
                .put("text", m.textContent.take(200))
                .put("media_note", m.mediaTranscription?.takeUnless { PersonMemoryExtractor.isPlaceholderOrBlankMediaNote(it) }?.take(140).orEmpty())
            )
            if (arr.length() >= 18) break
          }
          if (arr.length() >= 18) break
        }
        JSONObject().put("query", query).put("hits", arr).toString()
      }
      "get_device_telemetry" -> {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(Date())
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        JSONObject()
          .put("local_time", now)
          .put("battery_percent", pct)
          .toString()
      }
      "evaluate_math" -> {
        val expr = args.optNonNullString("expression")
        val res = com.example.tools.UtilityToolkit.evaluateMath(expr)
        if (res.isSuccess) {
          JSONObject().put("expression", expr).put("result", res.getOrThrow()).toString()
        } else {
          JSONObject().put("error", res.exceptionOrNull()?.message ?: "Invalid expression").toString()
        }
      }
      "convert_units" -> {
        val v = args.optDouble("value", 0.0)
        val catName = args.optNonNullString("category", "LENGTH").uppercase(Locale.US)
        val cat = com.example.tools.UtilityToolkit.UnitCategory.entries.firstOrNull { it.name == catName }
          ?: com.example.tools.UtilityToolkit.UnitCategory.LENGTH
        val from = args.optNonNullString("from_unit", cat.units.first())
        val to = args.optNonNullString("to_unit", cat.units.last())
        val converted = com.example.tools.UtilityToolkit.convertUnit(v, cat, from, to)
        JSONObject()
          .put("from", "$v $from")
          .put("to", "$converted $to")
          .put("result", converted)
          .toString()
      }
      "encode_or_hash" -> {
        val mode = args.optNonNullString("mode", "sha256").lowercase(Locale.US)
        val input = args.optNonNullString("input")
        val out = when (mode) {
          "base64_encode" -> com.example.tools.UtilityToolkit.base64Encode(input)
          "base64_decode" -> com.example.tools.UtilityToolkit.base64Decode(input).getOrElse { "Decode error" }
          "sha256" -> com.example.tools.UtilityToolkit.digestHex(input, "SHA-256")
          "sha512" -> com.example.tools.UtilityToolkit.digestHex(input, "SHA-512")
          "md5" -> com.example.tools.UtilityToolkit.digestHex(input, "MD5")
          "hex" -> com.example.tools.UtilityToolkit.textToHex(input)
          "uuid" -> com.example.tools.UtilityToolkit.generateUuidV4()
          else -> "Unsupported mode"
        }
        JSONObject().put("mode", mode).put("output", out).toString()
      }
      "generate_password" -> {
        val passphrase = args.optBoolean("passphrase", false)
        val len = args.optInt("length", 16)
        val pw = if (passphrase) {
          com.example.tools.UtilityToolkit.generatePassphrase()
        } else {
          com.example.tools.UtilityToolkit.generatePassword(length = len)
        }
        JSONObject().put("secret", pw).toString()
      }
      else -> JSONObject().put("error", "Unhandled tool").toString()
    }
  }

  private suspend fun resolveConversations(query: String): List<ConversationEntity> {
    if (query.isBlank()) return emptyList()
    val all = repository.getAllConversations()
    val exact =
      all.filter {
        it.chatJid.equals(query, ignoreCase = true) || it.displayName.equals(query, ignoreCase = true)
      }
    if (exact.isNotEmpty()) return exact

    val digits = query.filter { it.isDigit() }
    return all.filter {
      it.displayName.contains(query, ignoreCase = true) ||
        it.chatJid.contains(query, ignoreCase = true) ||
        (digits.length >= 4 && it.chatJid.filter { ch -> ch.isDigit() }.contains(digits))
    }
  }
}

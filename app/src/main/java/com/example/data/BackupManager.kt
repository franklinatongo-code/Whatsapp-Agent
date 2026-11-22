package com.example.data

import android.content.Context
import android.net.Uri
import com.example.agent.AutomationRulesEngine
import com.example.data.db.ChatMessageEntity
import com.example.data.db.ContactMemoryEntity
import com.example.data.db.ConversationEntity
import com.example.data.prefs.ContextStrategy
import com.example.data.repository.ClawRepository
import com.example.util.optNonNullString
import com.example.util.optNullableString
import org.json.JSONArray
import org.json.JSONObject

data class BackupStats(
  val conversationsCount: Int,
  val messagesCount: Int,
  val memoriesCount: Int,
  val exportedAtMs: Long,
)

/**
 * Full JSON Backup & Restore manager for ClawLink WA conversations, memories,
 * automation rules, snippets, notes, todos, and habits.
 * Strictly NEVER exports API keys or WhatsApp Signal private keys.
 */
object BackupManager {

  private const val BACKUP_VERSION = 2

  suspend fun exportToJsonString(repository: ClawRepository): String {
    val settings = repository.getCurrentSettings()
    val conversations = repository.getAllConversations()
    val messages = repository.getAllMessages()
    val memories = repository.getAllMemories()

    val convArr = JSONArray()
    conversations.forEach { c ->
      convArr.put(
        JSONObject()
          .put("chatJid", c.chatJid)
          .put("displayName", c.displayName)
          .put("isGroup", c.isGroup)
          .put("lastMessagePreview", c.lastMessagePreview)
          .put("lastMessageTimestamp", c.lastMessageTimestamp)
          .put("unreadCount", c.unreadCount)
          .put("rollingSummary", c.rollingSummary)
          .put("autoReplyEnabled", c.autoReplyEnabled)
          .put("permissionTier", c.permissionTier)
          .put("customInstructions", c.customInstructions)
          .put("preferredLanguage", c.preferredLanguage)
          .put("responseStyle", c.responseStyle)
          .put("totalTokensUsed", c.totalTokensUsed)
          .put("recentContextCountOverride", c.recentContextCountOverride ?: JSONObject.NULL)
          .put("contextModeOverride", c.contextModeOverride ?: JSONObject.NULL)
          .put("businessCategory", c.businessCategory)
          .put("businessLeadScore", c.businessLeadScore)
          .put("businessIntent", c.businessIntent)
          .put("businessNextAction", c.businessNextAction)
          .put("businessUpdatedAtMs", c.businessUpdatedAtMs)
      )
    }

    val msgArr = JSONArray()
    messages.forEach { m ->
      msgArr.put(
        JSONObject()
          .put("id", m.id)
          .put("chatJid", m.chatJid)
          .put("senderJid", m.senderJid)
          .put("senderName", m.senderName)
          .put("fromMe", m.fromMe)
          .put("timestamp", m.timestamp)
          .put("messageType", m.messageType)
          .put("textContent", m.textContent)
          .put("mediaMimeType", m.mediaMimeType ?: JSONObject.NULL)
          .put("mediaTranscription", m.mediaTranscription ?: JSONObject.NULL)
          .put("quotedMessageId", m.quotedMessageId ?: JSONObject.NULL)
          .put("quotedPreview", m.quotedPreview ?: JSONObject.NULL)
          .put("isEdited", m.isEdited)
          .put("isRevoked", m.isRevoked)
          .put("deliveryStatus", m.deliveryStatus)
          .put("aiTokensUsed", m.aiTokensUsed)
      )
    }

    val memArr = JSONArray()
    memories.forEach { mem ->
      memArr.put(
        JSONObject()
          .put("chatJid", mem.chatJid)
          .put("factKey", mem.factKey)
          .put("factValue", mem.factValue)
          .put("category", mem.category)
          .put("updatedAt", mem.updatedAt)
      )
    }

    val prefsObj =
      JSONObject()
        .put("agentIdentityName", settings.agentIdentityName)
        .put("systemInstructions", settings.systemInstructions)
        .put("responseStyle", settings.responseStyle)
        .put("preferredLanguage", settings.preferredLanguage)
        .put("conversationContextEnabled", settings.conversationContextEnabled)
        .put("contextStrategy", settings.contextStrategy.name)
        .put("recentMessageReadingEnabled", settings.recentMessageReadingEnabled)
        .put("recentMessageCount", settings.recentMessageCount)
        .put("duplicatePreventionEnabled", settings.duplicatePreventionEnabled)
        .put("businessModeEnabled", settings.businessModeEnabled)
        .put("businessName", settings.businessName)
        .put("businessCatalogOrInfo", settings.businessCatalogOrInfo)
        .put("businessTone", settings.businessTone)
        .put("dndEnabled", settings.dndEnabled)
        .put("dndStartHour", settings.dndStartHour)
        .put("dndEndHour", settings.dndEndHour)
        .put("awayModeEnabled", settings.awayModeEnabled)
        .put("awayMessage", settings.awayMessage)
        .put("autoReactEmoji", settings.autoReactEmoji)
        .put("replyCooldownSeconds", settings.replyCooldownSeconds)
        .put("welcomeNewContacts", settings.welcomeNewContacts)
        .put("welcomeMessage", settings.welcomeMessage)
        .put("keywordRulesJson", settings.keywordRulesJson)
        .put("quickRepliesJson", settings.quickRepliesJson)
        .put("notesJson", settings.notesJson)
        .put("todosJson", settings.todosJson)
        .put("habitsJson", settings.habitsJson)
        .put("scratchpadText", settings.scratchpadText)

    return JSONObject()
      .put("format", "clawlink_wa_backup")
      .put("version", BACKUP_VERSION)
      .put("exportedAtMs", System.currentTimeMillis())
      .put("conversations", convArr)
      .put("messages", msgArr)
      .put("memories", memArr)
      .put("preferences", prefsObj)
      .toString(2)
  }

  suspend fun importFromJsonString(json: String, repository: ClawRepository): BackupStats {
    val root = JSONObject(json)
    require(root.optNonNullString("format") == "clawlink_wa_backup") { "Unrecognized backup format" }

    val convArr = root.optJSONArray("conversations") ?: JSONArray()
    val convs =
      buildList {
        for (i in 0 until convArr.length()) {
          val o = convArr.optJSONObject(i) ?: continue
          val jid = o.optNonNullString("chatJid")
          if (jid.isBlank()) continue
          val countOverride =
            if (o.has("recentContextCountOverride") && !o.isNull("recentContextCountOverride")) {
              o.optInt("recentContextCountOverride", 0).takeIf { it > 0 }
            } else null
          add(
            ConversationEntity(
              chatJid = jid,
              displayName = o.optNonNullString("displayName", jid.substringBefore("@")),
              isGroup = o.optBoolean("isGroup", false),
              lastMessagePreview = o.optNonNullString("lastMessagePreview"),
              lastMessageTimestamp = o.optLong("lastMessageTimestamp", 0L),
              unreadCount = o.optInt("unreadCount", 0),
              rollingSummary = o.optNonNullString("rollingSummary"),
              autoReplyEnabled = o.optBoolean("autoReplyEnabled", true),
              permissionTier = o.optNonNullString("permissionTier", "STANDARD"),
              customInstructions = o.optNonNullString("customInstructions"),
              preferredLanguage = o.optNonNullString("preferredLanguage"),
              responseStyle = o.optNonNullString("responseStyle"),
              totalTokensUsed = o.optInt("totalTokensUsed", 0),
              recentContextCountOverride = countOverride,
              contextModeOverride = o.optNullableString("contextModeOverride"),
              businessCategory = o.optNonNullString("businessCategory"),
              businessLeadScore = o.optInt("businessLeadScore", 0),
              businessIntent = o.optNonNullString("businessIntent"),
              businessNextAction = o.optNonNullString("businessNextAction"),
              businessUpdatedAtMs = o.optLong("businessUpdatedAtMs", 0L),
            )
          )
        }
      }

    val msgArr = root.optJSONArray("messages") ?: JSONArray()
    val msgs =
      buildList {
        for (i in 0 until msgArr.length()) {
          val o = msgArr.optJSONObject(i) ?: continue
          val id = o.optNonNullString("id")
          val jid = o.optNonNullString("chatJid")
          if (id.isBlank() || jid.isBlank()) continue
          add(
            ChatMessageEntity(
              id = id,
              chatJid = jid,
              senderJid = o.optNonNullString("senderJid"),
              senderName = o.optNonNullString("senderName"),
              fromMe = o.optBoolean("fromMe", false),
              timestamp = o.optLong("timestamp", System.currentTimeMillis()),
              messageType = o.optNonNullString("messageType", "TEXT"),
              textContent = o.optNonNullString("textContent"),
              mediaMimeType = o.optNullableString("mediaMimeType"),
              mediaTranscription = o.optNullableString("mediaTranscription"),
              quotedMessageId = o.optNullableString("quotedMessageId"),
              quotedPreview = o.optNullableString("quotedPreview"),
              isEdited = o.optBoolean("isEdited", false),
              isRevoked = o.optBoolean("isRevoked", false),
              deliveryStatus = o.optNonNullString("deliveryStatus", "RECEIVED"),
              aiTokensUsed = o.optInt("aiTokensUsed", 0),
            )
          )
        }
      }

    val memArr = root.optJSONArray("memories") ?: JSONArray()
    val mems =
      buildList {
        for (i in 0 until memArr.length()) {
          val o = memArr.optJSONObject(i) ?: continue
          val jid = o.optNonNullString("chatJid")
          val key = o.optNonNullString("factKey")
          val value = o.optNonNullString("factValue")
          if (jid.isBlank() || key.isBlank() || value.isBlank()) continue
          add(
            ContactMemoryEntity(
              chatJid = jid,
              factKey = key,
              factValue = value,
              category = o.optNonNullString("category", "FACT"),
              updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            )
          )
        }
      }

    repository.importBackupEntities(convs, msgs, mems)

    root.optJSONObject("preferences")?.let { p ->
      repository.preferences.updateSettings { s ->
        s.copy(
          agentIdentityName = p.optNonNullString("agentIdentityName", s.agentIdentityName),
          systemInstructions = p.optNonNullString("systemInstructions", s.systemInstructions),
          responseStyle = p.optNonNullString("responseStyle", s.responseStyle),
          preferredLanguage = p.optNonNullString("preferredLanguage", s.preferredLanguage),
          conversationContextEnabled =
            p.optBoolean("conversationContextEnabled", s.conversationContextEnabled),
          contextStrategy =
            ContextStrategy.entries.firstOrNull { it.name == p.optNonNullString("contextStrategy") }
              ?: s.contextStrategy,
          recentMessageReadingEnabled =
            p.optBoolean("recentMessageReadingEnabled", s.recentMessageReadingEnabled),
          recentMessageCount = p.optInt("recentMessageCount", s.recentMessageCount).coerceIn(1, 50),
          duplicatePreventionEnabled =
            p.optBoolean("duplicatePreventionEnabled", s.duplicatePreventionEnabled),
          businessModeEnabled = p.optBoolean("businessModeEnabled", s.businessModeEnabled),
          businessName = p.optNonNullString("businessName", s.businessName),
          businessCatalogOrInfo = p.optNonNullString("businessCatalogOrInfo", s.businessCatalogOrInfo),
          businessTone = p.optNonNullString("businessTone", s.businessTone),
          dndEnabled = p.optBoolean("dndEnabled", s.dndEnabled),
          dndStartHour = p.optInt("dndStartHour", s.dndStartHour),
          dndEndHour = p.optInt("dndEndHour", s.dndEndHour),
          awayModeEnabled = p.optBoolean("awayModeEnabled", s.awayModeEnabled),
          awayMessage = p.optNonNullString("awayMessage", s.awayMessage),
          autoReactEmoji = p.optNonNullString("autoReactEmoji", s.autoReactEmoji),
          replyCooldownSeconds = p.optInt("replyCooldownSeconds", s.replyCooldownSeconds),
          welcomeNewContacts = p.optBoolean("welcomeNewContacts", s.welcomeNewContacts),
          welcomeMessage = p.optNonNullString("welcomeMessage", s.welcomeMessage),
          keywordRulesJson = p.optNonNullString("keywordRulesJson", s.keywordRulesJson),
          quickRepliesJson = p.optNonNullString("quickRepliesJson", s.quickRepliesJson),
          notesJson = p.optNonNullString("notesJson", s.notesJson),
          todosJson = p.optNonNullString("todosJson", s.todosJson),
          habitsJson = p.optNonNullString("habitsJson", s.habitsJson),
          scratchpadText = p.optNonNullString("scratchpadText", s.scratchpadText),
        )
      }
    }

    return BackupStats(
      conversationsCount = convs.size,
      messagesCount = msgs.size,
      memoriesCount = mems.size,
      exportedAtMs = root.optLong("exportedAtMs", System.currentTimeMillis()),
    )
  }

  suspend fun writeBackupToUri(context: Context, uri: Uri, repository: ClawRepository): BackupStats {
    val json = exportToJsonString(repository)
    context.contentResolver.openOutputStream(uri)?.use { out ->
      out.write(json.toByteArray(Charsets.UTF_8))
    } ?: error("Unable to open destination file")
    return BackupStats(
      conversationsCount = repository.getAllConversations().size,
      messagesCount = repository.getAllMessages().size,
      memoriesCount = repository.getAllMemories().size,
      exportedAtMs = System.currentTimeMillis(),
    )
  }

  suspend fun readBackupFromUri(context: Context, uri: Uri, repository: ClawRepository): BackupStats {
    val json =
      context.contentResolver.openInputStream(uri)?.use { inp ->
        inp.readBytes().toString(Charsets.UTF_8)
      } ?: error("Unable to read backup file")
    return importFromJsonString(json, repository)
  }

  fun formatChatAsReadableText(displayName: String, messages: List<ChatMessageEntity>): String {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return buildString {
      appendLine("ClawLink WA Chat Export — $displayName")
      appendLine("====================================")
      messages.forEach { m ->
        val who = if (m.fromMe) "You" else m.senderName
        appendLine("[${fmt.format(java.util.Date(m.timestamp))}] $who: ${m.textContent}")
      }
    }
  }
}

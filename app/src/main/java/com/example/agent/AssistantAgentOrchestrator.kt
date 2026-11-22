package com.example.agent

import android.content.Context
import com.example.ai.AiProviderManager
import com.example.ai.NimChatMessage
import com.example.ai.NimModelInfo
import com.example.ai.NimToolCall
import com.example.ai.NimToolDefinition
import com.example.ai.NvidiaModelCapabilityDetector
import com.example.ai.NvidiaNimClient
import com.example.data.db.AssistantMessageEntity
import com.example.data.db.ChatMessageEntity
import com.example.data.db.ConversationEntity
import com.example.data.prefs.AgentSettings
import com.example.data.prefs.TranscriptionMode
import com.example.data.repository.ClawRepository
import com.example.media.AndroidMediaIntelligence
import com.example.tools.UtilityToolkit
import com.example.util.optNonNullString
import com.example.whatsapp.WAJid
import com.example.whatsapp.WhatsAppConnectionState
import com.example.whatsapp.WhatsAppSendResult
import com.example.whatsapp.WhatsAppSessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Agentic runtime powering the "Text My AI" tab:
 * - Natural-language control over WhatsApp chats with strict Group Chatting policy enforcement
 * - Multi-provider AI & fast multimodal analysis of pictures, PDFs, text/code files, and audio
 * - Persistent Room conversation history & live SSE streaming preview
 */
class AssistantAgentOrchestrator(
  private val context: Context,
  private val repository: ClawRepository,
  private val sessionManager: WhatsAppSessionManager,
  private val nimClient: NvidiaNimClient,
  private val mediaIntelligence: AndroidMediaIntelligence,
  private val discoveredModels: StateFlow<List<NimModelInfo>>,
  private val aiProviderManager: AiProviderManager = AiProviderManager(sessionManager.credentialStore, nimClient),
  private val businessEngine: BusinessIntelligenceEngine = BusinessIntelligenceEngine(),
) {

  private val _isTyping = MutableStateFlow(false)
  val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

  private val _streamingPreview = MutableStateFlow("")
  val streamingPreview: StateFlow<String> = _streamingPreview.asStateFlow()

  private val _activeToolNote = MutableStateFlow<String?>(null)
  val activeToolNote: StateFlow<String?> = _activeToolNote.asStateFlow()

  private val assistantToolDefs: List<NimToolDefinition> =
    listOf(
      NimToolDefinition(
        name = "list_whatsapp_chats",
        description = "List recent synced WhatsApp contacts and groups with JIDs, display names, unread counts, and last previews.",
        parametersSchemaJson =
          JSONObject()
            .put("type", "object")
            .put(
              "properties",
              JSONObject()
                .put("limit", JSONObject().put("type", "integer").put("description", "Max chats to return (default 20)")),
            ),
      ),
      NimToolDefinition(
        name = "read_whatsapp_conversation",
        description = "Read recent WhatsApp messages, summary, and saved memories for a contact or group by name, phone number, or chat JID (useful for summarizing or answering questions about a chat).",
        parametersSchemaJson =
          JSONObject()
            .put("type", "object")
            .put(
              "properties",
              JSONObject()
                .put("contact_query", JSONObject().put("type", "string").put("description", "Contact display name, phone number, or chat_jid"))
                .put("limit", JSONObject().put("type", "integer").put("description", "Number of recent messages to fetch (default 24)")),
            )
            .put("required", JSONArray().put("contact_query")),
      ),
      NimToolDefinition(
        name = "send_whatsapp_message",
        description = "Actually send a WhatsApp text message on the owner's linked account to a contact, group, or phone number.",
        parametersSchemaJson =
          JSONObject()
            .put("type", "object")
            .put(
              "properties",
              JSONObject()
                .put("contact_query", JSONObject().put("type", "string").put("description", "Contact name, chat_jid, or phone number with country code"))
                .put("message_text", JSONObject().put("type", "string").put("description", "Exact WhatsApp message text to send")),
            )
            .put("required", JSONArray().put("contact_query").put("message_text")),
      ),
      NimToolDefinition(
        name = "set_contact_ai_instructions",
        description = "Update how ClawLink WA auto-replies to a specific contact (custom instructions, tone/style, language, or pause/enable auto-reply).",
        parametersSchemaJson =
          JSONObject()
            .put("type", "object")
            .put(
              "properties",
              JSONObject()
                .put("contact_query", JSONObject().put("type", "string"))
                .put("custom_instructions", JSONObject().put("type", "string"))
                .put("response_style", JSONObject().put("type", "string"))
                .put("preferred_language", JSONObject().put("type", "string"))
                .put("auto_reply_enabled", JSONObject().put("type", "boolean")),
            )
            .put("required", JSONArray().put("contact_query")),
      ),
      NimToolDefinition(
        name = "save_contact_fact",
        description = "Remember a long-term fact or preference about a WhatsApp contact.",
        parametersSchemaJson =
          JSONObject()
            .put("type", "object")
            .put(
              "properties",
              JSONObject()
                .put("contact_query", JSONObject().put("type", "string"))
                .put("fact_key", JSONObject().put("type", "string"))
                .put("fact_value", JSONObject().put("type", "string")),
            )
            .put("required", JSONArray().put("contact_query").put("fact_key").put("fact_value")),
      ),
      NimToolDefinition(
        name = "schedule_whatsapp_message",
        description = "Schedule a WhatsApp message to be sent automatically to a contact after a delay (e.g. delay_minutes = 30 or 120), even when the app is closed.",
        parametersSchemaJson =
          JSONObject()
            .put("type", "object")
            .put(
              "properties",
              JSONObject()
                .put("contact_query", JSONObject().put("type", "string").put("description", "Contact name, chat_jid, or phone number"))
                .put("message_text", JSONObject().put("type", "string").put("description", "Exact WhatsApp text to send when scheduled time arrives"))
                .put("delay_minutes", JSONObject().put("type", "integer").put("description", "Minutes from now before sending (min 1)")),
            )
            .put("required", JSONArray().put("contact_query").put("message_text").put("delay_minutes")),
      ),
      NimToolDefinition(
        name = "evaluate_math",
        description = "Evaluate a mathematical expression safely.",
        parametersSchemaJson =
          JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("expression", JSONObject().put("type", "string")))
            .put("required", JSONArray().put("expression")),
      ),
      NimToolDefinition(
        name = "get_device_telemetry",
        description = "Get current device time, battery level, RAM, storage, and network status.",
        parametersSchemaJson = JSONObject().put("type", "object").put("properties", JSONObject()),
      ),
    )

  suspend fun sendMessage(
    rawText: String,
    imageFile: File? = null,
    attachmentFile: File? = null,
    attachmentName: String? = null,
    attachmentMimeType: String? = null,
  ): AssistantMessageEntity {
    val cleanText = rawText.trim()
    val userDisplayText =
      cleanText.ifBlank {
        when {
          imageFile != null && attachmentFile != null -> "Look at this picture and file (${attachmentName ?: attachmentFile.name})."
          imageFile != null -> "Look at this picture I sent."
          attachmentFile != null -> "Read and help me with this file (${attachmentName ?: attachmentFile.name})."
          else -> ""
        }
      }

    val userMsg =
      AssistantMessageEntity(
        id = "u_${UUID.randomUUID().toString().take(12)}",
        role = "user",
        textContent = userDisplayText,
        imageLocalPath = imageFile?.absolutePath,
        fileLocalPath = attachmentFile?.absolutePath,
        fileName = attachmentName ?: attachmentFile?.name,
        fileMimeType = attachmentMimeType,
        timestamp = System.currentTimeMillis(),
      )
    repository.saveAssistantMessage(userMsg)

    val settings = repository.getCurrentSettings()
    val targets = aiProviderManager.buildTargetsForChat(settings, discoveredModels.value)
    val chatModel = targets.firstOrNull()?.model
    if (chatModel == null) {
      val fallback =
        AssistantMessageEntity(
          id = "a_${UUID.randomUUID().toString().take(12)}",
          role = "assistant",
          textContent = "Add an AI API key in the AI Studio tab first (or tap the ClawLink WA header 5 times on Home) so I can reply, read files, and manage your WhatsApp chats.",
          timestamp = System.currentTimeMillis(),
        )
      repository.saveAssistantMessage(fallback)
      return fallback
    }

    _isTyping.value = true
    _streamingPreview.value = ""
    _activeToolNote.value = null

    val toolBadges = linkedSetOf<String>()
    try {
      val visionTargets = aiProviderManager.buildVisionTargets(settings, discoveredModels.value)
      val primaryVisionTarget = visionTargets.firstOrNull()
      val inlineImages = mutableListOf<String>()
      val attachmentNotes = StringBuilder()

      if (imageFile != null && imageFile.exists()) {
        _activeToolNote.value = "Inspecting picture…"
        if (chatModel.supportsVision) {
          mediaIntelligence.encodeImageFileToBase64Jpeg(imageFile)?.let { inlineImages.add(it) }
        } else if (visionTargets.isNotEmpty()) {
          val prompt =
            if (cleanText.isNotBlank()) {
              "The user sent this picture/screenshot and asked: \"$cleanText\". Transcribe all legible text, tables, numbers, headers, and visual details thoroughly."
            } else {
              "Describe everything visible in this image or screenshot clearly, including all legible text, tables, numbers, UI elements, people, and context."
            }
          val res =
            mediaIntelligence.summarizeImageWithVisionCandidates(
              imageFile = imageFile,
              prompt = prompt,
              candidateModels = visionTargets.map { it.model },
              providerTargets = visionTargets,
            )
          attachmentNotes.appendLine(
            "[Picture analysis by Vision model (${visionTargets.first().model.shortName})]:\n" +
              res.getOrElse { "Vision error: ${it.message}" }
          )
        } else {
          attachmentNotes.appendLine("[User attached a picture (${imageFile.name}), but no Vision (VL) model is currently configured in AI Studio.]")
        }
      }

      if (attachmentFile != null && attachmentFile.exists()) {
        _activeToolNote.value = "Reading ${attachmentName ?: attachmentFile.name}…"
        val mime = attachmentMimeType.orEmpty().lowercase()
        val label = attachmentName ?: attachmentFile.name
        when {
          mime.startsWith("image/") -> {
            if (chatModel.supportsVision) {
              mediaIntelligence.encodeImageFileToBase64Jpeg(attachmentFile)?.let { inlineImages.add(it) }
            } else if (visionTargets.isNotEmpty()) {
              val res =
                mediaIntelligence.summarizeImageWithVisionCandidates(
                  imageFile = attachmentFile,
                  prompt = "Read and describe this image/screenshot thoroughly, including all visible text, tables, and numbers.",
                  candidateModels = visionTargets.map { it.model },
                  providerTargets = visionTargets,
                )
              attachmentNotes.appendLine("[Image file '$label' visual analysis]:\n${res.getOrElse { "Vision error: ${it.message}" }}")
            }
          }
          mime.startsWith("audio/") -> {
            val voiceEnabled = settings.autoTranscribeVoiceNotes && settings.transcriptionMode != TranscriptionMode.OFF
            if (voiceEnabled) {
              val audioTargets = aiProviderManager.buildAudioTargets(settings, discoveredModels.value)
              val transcript = mediaIntelligence.transcribeVoiceNote(attachmentFile, settings, discoveredModels.value, audioTargets)
              attachmentNotes.appendLine("[Audio file '$label' transcription]:\n$transcript")
            } else {
              attachmentNotes.appendLine(
                "[Audio file '$label' attached, but Voice Transcription is currently TURNED OFF in Settings. Inform the owner that Voice Transcription is turned off and can be switched back ON in Home or the AI tab.]"
              )
            }
          }
          else -> {
            val extracted =
              mediaIntelligence.extractDocumentIntelligence(
                file = attachmentFile,
                mimeType = attachmentMimeType,
                visionModel = primaryVisionTarget?.model,
                providerTarget = primaryVisionTarget,
              )
            attachmentNotes.appendLine("[Attached file '$label' ($mime) content]:\n$extracted")
          }
        }
      }

      _activeToolNote.value = null

      val history = repository.getRecentAssistantMessages(limit = 16)
      val chats = repository.getAllConversations().take(20)
      val systemPrompt = buildAssistantSystemPrompt(settings, chats, chatModel.supportsToolCalling)

      val nimMessages = mutableListOf<NimChatMessage>()
      nimMessages.add(NimChatMessage(role = "system", content = systemPrompt))

      // Prior turns excluding the just-saved user message at the end
      history.dropLast(1).forEach { msg ->
        nimMessages.add(
          NimChatMessage(
            role = if (msg.role == "user") "user" else "assistant",
            content = msg.textContent,
          )
        )
      }

      // If chosen model doesn't support tool calling, inject relevant matched chat context inline
      var preloadedContext = ""
      if (!chatModel.supportsToolCalling && cleanText.isNotBlank()) {
        val matched = chats.filter { c ->
          c.displayName.length >= 2 && cleanText.contains(c.displayName, ignoreCase = true)
        }.take(2)
        if (matched.isNotEmpty()) {
          val sb = StringBuilder("\n[Matched WhatsApp Conversation Context]:\n")
          for (conv in matched) {
            val recentMsgs = repository.getRecentMessages(conv.chatJid, limit = 16)
            sb.appendLine("--- ${conv.displayName} (${conv.chatJid}) ---")
            recentMsgs.forEach { m ->
              val who = if (m.fromMe) "Me" else m.senderName
              sb.appendLine("$who: ${m.textContent.take(260)}")
            }
          }
          preloadedContext = sb.toString()
        }
      }

      val finalUserPrompt =
        buildString {
          append(userDisplayText)
          if (attachmentNotes.isNotEmpty()) {
            append("\n\n").append(attachmentNotes.toString().trim())
          }
          if (preloadedContext.isNotBlank()) {
            append("\n\n").append(preloadedContext.trim())
          }
        }

      nimMessages.add(
        NimChatMessage(
          role = "user",
          content = finalUserPrompt,
          imageBase64Jpegs = inlineImages,
        )
      )

      var finalReply = ""
      var errorText: String? = null
      val toolsForCall = if (chatModel.supportsToolCalling) assistantToolDefs else emptyList()

      for (step in 0 until 5) {
        val stepStream = StringBuilder()
        val exec =
          aiProviderManager.executeWithFallback(
            targets = targets,
            messages = nimMessages,
            tools = toolsForCall,
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            stream = settings.streamingEnabled,
            requireVisionAnswer = inlineImages.isNotEmpty(),
            onDelta = { delta ->
              stepStream.append(delta)
              _streamingPreview.value = HumanReplySanitizer.stripThinkingTags(stepStream.toString()).takeLast(2400)
            },
          )

        if (exec.text == null && exec.toolCalls.isEmpty()) {
          errorText = exec.errorMessage ?: "AI provider unavailable"
          break
        }

        if (exec.toolCalls.isEmpty()) {
          val cleaned = HumanReplySanitizer.stripThinkingTags(exec.text.orEmpty()).trim()
          finalReply = cleaned.ifBlank { exec.text.orEmpty().trim() }
          break
        }

        val stepCalls: List<NimToolCall> = exec.toolCalls
        nimMessages.add(NimChatMessage(role = "assistant", content = exec.text.orEmpty(), toolCalls = stepCalls))
        for (tc in stepCalls) {
          _activeToolNote.value = toolLabelForUi(tc.name)
          val resultJson = executeAssistantTool(tc.name, tc.argumentsJson, toolBadges)
          nimMessages.add(NimChatMessage(role = "tool", content = resultJson, toolCallId = tc.id))
        }
        _activeToolNote.value = null
        _streamingPreview.value = ""
      }

      val resolvedText =
        when {
          finalReply.isNotBlank() -> finalReply
          !errorText.isNullOrBlank() -> "I ran into an issue contacting your AI provider: $errorText"
          else -> "I finished processing your request."
        }

      val assistantMsg =
        AssistantMessageEntity(
          id = "a_${UUID.randomUUID().toString().take(12)}",
          role = "assistant",
          textContent = resolvedText,
          toolActivitySummary = toolBadges.takeIf { it.isNotEmpty() }?.joinToString(" • "),
          timestamp = System.currentTimeMillis(),
        )
      repository.saveAssistantMessage(assistantMsg)
      return assistantMsg
    } finally {
      _isTyping.value = false
      _streamingPreview.value = ""
      _activeToolNote.value = null
    }
  }

  private suspend fun executeAssistantTool(
    name: String,
    argsJson: String,
    badges: MutableSet<String>,
  ): String {
    val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
    return when (name) {
      "list_whatsapp_chats" -> {
        badges.add("Checked chats")
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        val list = repository.getAllConversations().take(limit)
        val arr = JSONArray()
        list.forEach { c ->
          arr.put(
            JSONObject()
              .put("chat_jid", c.chatJid)
              .put("display_name", c.displayName)
              .put("is_group", c.isGroup)
              .put("unread_count", c.unreadCount)
              .put("auto_reply_enabled", c.autoReplyEnabled)
              .put("last_preview", c.lastMessagePreview)
          )
        }
        JSONObject().put("chats", arr).toString()
      }
      "read_whatsapp_conversation" -> {
        val query = args.optNonNullString("contact_query").trim()
        val limit = args.optInt("limit", 24).coerceIn(1, 60)
        val matches = resolveConversations(query)
        if (matches.isEmpty()) {
          val available = repository.getAllConversations().take(12).map { it.displayName }
          return JSONObject()
            .put("error", "No conversation matching '$query'")
            .put("available_chats", JSONArray(available))
            .toString()
        }
        if (matches.size > 1 && !matches.first().displayName.equals(query, ignoreCase = true) && matches.first().chatJid != query) {
          val candidates = JSONArray()
          matches.take(6).forEach {
            candidates.put(JSONObject().put("chat_jid", it.chatJid).put("display_name", it.displayName).put("is_group", it.isGroup))
          }
          return JSONObject()
            .put("ambiguous", true)
            .put("candidates", candidates)
            .toString()
        }
        val conv = matches.first()
        badges.add("Read chat: ${conv.displayName}")
        val msgs = repository.getRecentMessages(conv.chatJid, limit)
        val replyIndex = ReplyStatusTracker.analyze(msgs)
        val memories = repository.getMemories(conv.chatJid)
        val fmt = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
        val msgArr = JSONArray()
        msgs.forEach { m ->
          val attachmentNote =
            m.mediaTranscription?.takeUnless { PersonMemoryExtractor.isPlaceholderOrBlankMediaNote(it) }
          val cleanText = HumanReplySanitizer.sanitizeHistoryTurn(m.textContent).take(400)
          val quotedClean =
            m.quotedPreview?.takeIf { it.isNotBlank() && !it.equals("null", true) }?.take(120)
          val ownerReply = if (!m.fromMe) replyIndex.ownerReplyFor(m.id) else null
          val fullText =
            buildString {
              if (quotedClean != null) append("(replying to \"$quotedClean\") ")
              append(cleanText)
              if (attachmentNote != null) append(" [Attachment: ${attachmentNote.take(180)}]")
            }
          val itemObj =
            JSONObject()
              .put("id", m.id)
              .put("from", if (m.fromMe) "Me" else m.senderName.ifBlank { "Member" })
              .put("from_me", m.fromMe)
              .put("type", m.messageType)
              .put("text", fullText)
              .put("time", fmt.format(Date(m.timestamp)))
          if (!m.fromMe) {
            itemObj.put("already_replied_by_owner", ownerReply != null)
            if (ownerReply != null) {
              itemObj.put("owner_reply_preview", HumanReplySanitizer.sanitizeHistoryTurn(ownerReply.textContent).take(140))
            }
          }
          msgArr.put(itemObj)
        }
        val memArr = JSONArray()
        memories.forEach { mem ->
          memArr.put(JSONObject().put("key", mem.factKey).put("value", mem.factValue))
        }
        JSONObject()
          .put("chat_jid", conv.chatJid)
          .put("display_name", conv.displayName)
          .put("is_group", conv.isGroup)
          .put("rolling_summary", conv.rollingSummary)
          .put("custom_instructions", conv.customInstructions)
          .put("memories", memArr)
          .put("messages", msgArr)
          .toString()
      }
      "send_whatsapp_message" -> {
        val query = args.optNonNullString("contact_query").trim()
        val msgText = args.optNonNullString("message_text").ifBlank { args.optNonNullString("message") }.trim()
        if (msgText.isBlank()) {
          return JSONObject().put("error", "message_text cannot be empty").toString()
        }
        val settings = repository.getCurrentSettings()
        val matches = resolveConversations(query)
        val matchedConv: ConversationEntity?
        val targetJid: String
        val targetName: String
        if (matches.size == 1 || (matches.isNotEmpty() && (matches.first().displayName.equals(query, true) || matches.first().chatJid == query))) {
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
            .put("message", "Multiple contacts matched '$query'. Ask user which one to message.")
            .put("candidates", candidates)
            .toString()
        } else {
          val digits = query.filter { it.isDigit() }
          if (digits.length >= 7) {
            targetJid = if (query.endsWith("@g.us")) WAJid.normalizeUser(query) else WAJid.normalizeUser(digits)
            targetName = digits
            matchedConv = repository.getConversation(targetJid)
          } else {
            return JSONObject()
              .put("status", "not_found")
              .put("error", "Could not find a contact or group named '$query'. Ask the user for their full name or phone number.")
              .toString()
          }
        }

        when (val perm = GroupPermissionPolicy.evaluateOutgoingPermission(targetJid, settings, matchedConv, TriggerSource.TEXT_AI_TOOL)) {
          is PermissionDecision.Blocked -> {
            badges.add("Blocked: Group Chat OFF")
            return JSONObject()
              .put("status", "blocked_by_permission")
              .put("requires_group_chat_toggle", perm.requiresGroupEnable)
              .put("error", perm.reason)
              .put("recipient", targetName)
              .toString()
          }
          PermissionDecision.Allowed -> Unit
        }

        val connState = sessionManager.state.value
        if (connState !is WhatsAppConnectionState.Connected) {
          return JSONObject()
            .put("status", "not_connected")
            .put("error", "WhatsApp is not currently connected. Link/connect WhatsApp on the Home tab first.")
            .toString()
        }

        val meJid = sessionManager.credentialStore.getOrCreateCredentials().meJid
        val sendRes = sessionManager.messaging.sendTextMessageDetailed(targetJid, msgText, meJid)
        val recordId = sendRes.sentIdOrNull() ?: "ai_out_${System.nanoTime()}"
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
              senderName = "You",
              fromMe = true,
              timestamp = System.currentTimeMillis(),
              messageType = "TEXT",
              textContent = msgText,
              deliveryStatus = statusString,
            )
          )
        }
        when (sendRes) {
          is WhatsAppSendResult.Confirmed, is WhatsAppSendResult.SentUnconfirmed -> {
            badges.add("Sent WhatsApp to $targetName")
            repository.log("INFO", "AGENT", "Text-My-AI sent WhatsApp message to $targetName ($targetJid)")
            JSONObject()
              .put("status", "sent")
              .put("chat_jid", targetJid)
              .put("recipient", targetName)
              .put("message_id", recordId)
              .toString()
          }
          is WhatsAppSendResult.UnknownTimeout ->
            JSONObject()
              .put("status", "unknown_timeout")
              .put("error", sendRes.reason)
              .toString()
          is WhatsAppSendResult.Failed ->
            JSONObject()
              .put("status", "failed")
              .put("error", sendRes.error)
              .toString()
        }
      }
      "set_contact_ai_instructions" -> {
        val query = args.optNonNullString("contact_query").trim()
        val matches = resolveConversations(query)
        val conv = matches.firstOrNull()
          ?: return JSONObject().put("error", "No contact matching '$query'").toString()
        val updated =
          conv.copy(
            customInstructions = if (args.has("custom_instructions") && !args.isNull("custom_instructions")) args.optNonNullString("custom_instructions") else conv.customInstructions,
            responseStyle = if (args.has("response_style") && !args.isNull("response_style")) args.optNonNullString("response_style") else conv.responseStyle,
            preferredLanguage = if (args.has("preferred_language") && !args.isNull("preferred_language")) args.optNonNullString("preferred_language") else conv.preferredLanguage,
            autoReplyEnabled = if (args.has("auto_reply_enabled") && !args.isNull("auto_reply_enabled")) args.optBoolean("auto_reply_enabled") else conv.autoReplyEnabled,
          )
        repository.updateConversation(updated)
        badges.add("Updated rules: ${conv.displayName}")
        JSONObject().put("status", "updated").put("contact", conv.displayName).toString()
      }
      "save_contact_fact" -> {
        val query = args.optNonNullString("contact_query").trim()
        val conv = resolveConversations(query).firstOrNull()
          ?: return JSONObject().put("error", "No contact matching '$query'").toString()
        val k = args.optNonNullString("fact_key")
        val v = args.optNonNullString("fact_value")
        repository.saveContactMemory(conv.chatJid, k, v, "ASSISTANT")
        badges.add("Saved memory: ${conv.displayName}")
        JSONObject().put("status", "saved").put("contact", conv.displayName).put("key", k).toString()
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
            badges.add("Blocked: Group Chat OFF")
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
        badges.add("Scheduled for $targetName (${delayMin}m)")
        repository.log("INFO", "AGENT", "Scheduled WhatsApp message to $targetName in ${delayMin}m")
        val timeFmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(targetTimeMs))
        JSONObject()
          .put("status", "scheduled")
          .put("id", item.id)
          .put("recipient", targetName)
          .put("scheduled_time", timeFmt)
          .toString()
      }
      "evaluate_math" -> {
        val expr = args.optNonNullString("expression")
        val res = UtilityToolkit.evaluateMath(expr)
        if (res.isSuccess) JSONObject().put("result", res.getOrThrow()).toString()
        else JSONObject().put("error", res.exceptionOrNull()?.message ?: "Math error").toString()
      }
      "get_device_telemetry" -> {
        val snap = UtilityToolkit.readDeviceTelemetry(context)
        JSONObject()
          .put("device", snap.deviceModel)
          .put("battery_percent", snap.batteryPercent)
          .put("charging", snap.isCharging)
          .put("network", snap.networkTransport)
          .put("ram_free_mb", snap.availableRamMb)
          .put("storage_free_gb", snap.availableStorageGb)
          .put("local_time", snap.localTime)
          .toString()
      }
      else -> JSONObject().put("error", "Unknown tool: $name").toString()
    }
  }

  private suspend fun resolveConversations(query: String): List<ConversationEntity> {
    if (query.isBlank()) return emptyList()
    val all = repository.getAllConversations()
    val exact = all.filter {
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

  private fun buildAssistantSystemPrompt(
    settings: AgentSettings,
    chats: List<ConversationEntity>,
    toolsEnabled: Boolean,
  ): String =
    buildString {
      appendLine("You are ${settings.agentIdentityName}, the owner's personal WhatsApp & AI companion inside ClawLink WA.")
      appendLine("The owner is texting YOU directly in an iMessage-style private chat.")
      appendLine("You can:")
      appendLine("1) Summarize or search their WhatsApp conversations (both DMs and Groups).")
      appendLine("2) Text someone on WhatsApp for them (use send_whatsapp_message when explicitly asked).")
      appendLine("3) Schedule WhatsApp messages/reminders for later (schedule_whatsapp_message), even when the app is closed.")
      appendLine("4) Customize how ClawLink auto-replies to specific contacts or groups (set_contact_ai_instructions).")
      appendLine("5) Inspect pictures, screenshots, PDFs, code, and documents the owner sends you.")
      appendLine("Be concise, natural, warm, and genuinely helpful—like texting a super-capable personal assistant.")
      val waConnected = sessionManager.state.value is WhatsAppConnectionState.Connected
      appendLine("WhatsApp Connection Status: ${if (waConnected) "Connected" else "Not connected yet"}")
      appendLine(
        "Group Chatting Setting: ${if (settings.autoReplyGroups) "ON (allowed to message WhatsApp groups)" else "TURNED OFF (if the owner asks you to message or schedule a message to a group, do NOT pretend you sent it—clearly tell them Group Chatting is currently OFF and ask them to turn ON 'Reply in Group Chats' in AI Studio)"}"
      )
      val bizBlock = businessEngine.buildBusinessPromptBlock(settings)
      if (bizBlock.isNotBlank()) appendLine(bizBlock)
      val voiceStatus =
        when {
          !settings.autoTranscribeVoiceNotes || settings.transcriptionMode == TranscriptionMode.OFF ->
            "Voice Transcription: TURNED OFF (if asked or sent audio, let the owner know Voice Transcription is off and can be switched to Cloud AI or Offline Model in Home / AI tab)."
          settings.transcriptionMode == TranscriptionMode.LOCAL -> {
            val ready = mediaIntelligence.isLocalModelReady(settings.localWhisperModel)
            "Voice Transcription: ON — Offline Vosk Model (${settings.localWhisperModel.displayName}, ${if (ready) "Downloaded & Ready" else "Needs Download in AI tab"})."
          }
          else -> {
            val asrName =
              settings.audioModelId.takeIf { it.isNotBlank() }
                ?: com.example.ai.NvidiaModelCapabilityDetector.pickBestVoiceModel(discoveredModels.value)?.id
                ?: "Auto Cloud ASR"
            "Voice Transcription: ON — Cloud AI ($asrName)."
          }
        }
      appendLine(voiceStatus)
      appendLine("Tool Calling Available: $toolsEnabled")
      if (chats.isNotEmpty()) {
        appendLine(
          "Synced Contacts & Groups (${chats.size}): " +
            chats.joinToString(", ") { "${it.displayName} [${if (it.isGroup) "GROUP" else "DM"}:${it.chatJid}]" }
        )
      } else {
        appendLine("No synced WhatsApp chats in history yet.")
      }
    }

  private fun toolLabelForUi(toolName: String): String =
    when (toolName) {
      "list_whatsapp_chats" -> "checking chats…"
      "read_whatsapp_conversation" -> "reading chat history…"
      "send_whatsapp_message" -> "sending WhatsApp message…"
      "schedule_whatsapp_message" -> "scheduling WhatsApp message…"
      "set_contact_ai_instructions" -> "updating contact style…"
      "save_contact_fact" -> "saving memory…"
      "get_device_telemetry" -> "reading device info…"
      else -> "running tool…"
    }
}

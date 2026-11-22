package com.example.agent

import com.example.ai.AiProviderManager
import com.example.ai.AiRouteTarget
import com.example.ai.NimChatMessage
import com.example.data.db.ChatMessageEntity
import com.example.data.db.ConversationEntity
import com.example.data.prefs.AgentSettings
import com.example.data.repository.ClawRepository
import com.example.media.AndroidMediaIntelligence
import com.example.util.optNonNullString
import com.example.whatsapp.WAJid
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Automatically learns and maintains structured human-like memory about each contact/person
 * from texts, stickers, pictures, voice notes, documents, AND our own previous replies ("Me"),
 * plus an incremental rolling summary.
 *
 * Strictly isolated per chatJid and scoped by speaker inside group chats.
 */
class PersonMemoryExtractor {

  private data class FactPattern(
    val regex: Regex,
    val keyPrefix: String,
    val category: String,
    val labelBuilder: (String, String) -> String,
  )

  private val deterministicPatterns =
    listOf(
      FactPattern(
        regex = Regex("""\b(?:my\s+name\s+is|call\s+me|i'?m\s+called)\s+([A-Z][a-zA-Z'-]{1,28}(?:\s+[A-Z][a-zA-Z'-]{1,20})?)""", RegexOption.IGNORE_CASE),
        keyPrefix = "preferred_name",
        category = "PROFILE",
        labelBuilder = { _, value -> value.trim() },
      ),
      FactPattern(
        regex = Regex("""\b(?:i\s+live\s+in|i'?m\s+from|i\s+stay\s+in|based\s+in|moved\s+to)\s+([A-Za-z0-9 ,'-]{2,40})""", RegexOption.IGNORE_CASE),
        keyPrefix = "location",
        category = "PROFILE",
        labelBuilder = { speaker, value -> "$speaker lives/is based in ${value.trim().trimEnd('.', ',', '!')}" },
      ),
      FactPattern(
        regex = Regex("""\b(?:i\s+work\s+(?:as|at|for)|working\s+(?:as|at|for)|my\s+job\s+is|i'?m\s+(?:a|an)\s+)([A-Za-z0-9 &,'-]{3,48})""", RegexOption.IGNORE_CASE),
        keyPrefix = "work_role",
        category = "PROFILE",
        labelBuilder = { speaker, value -> "$speaker: ${value.trim().trimEnd('.', ',', '!')}" },
      ),
      FactPattern(
        regex = Regex("""\b(?:i\s+(?:really\s+)?(?:like|love|prefer|enjoy)|my\s+fav(?:orite)?\s+\w+\s+is)\s+([^.!?\n]{3,65})""", RegexOption.IGNORE_CASE),
        keyPrefix = "likes",
        category = "PREFERENCE",
        labelBuilder = { speaker, value -> "$speaker likes ${value.trim()}" },
      ),
      FactPattern(
        regex = Regex("""\b(?:i\s+(?:hate|dislike|don'?t\s+like|can'?t\s+stand))\s+([^.!?\n]{3,65})""", RegexOption.IGNORE_CASE),
        keyPrefix = "dislikes",
        category = "PREFERENCE",
        labelBuilder = { speaker, value -> "$speaker dislikes ${value.trim()}" },
      ),
      FactPattern(
        regex = Regex("""\b(?:i\s+have\s+a\s+(?:dog|cat|puppy|kitten|pet)\b[^.!?\n]{0,45}|my\s+(?:dog|cat|pet)\s+(?:named\s+)?[A-Za-z0-9 '-]{2,40})""", RegexOption.IGNORE_CASE),
        keyPrefix = "pets",
        category = "PROFILE",
        labelBuilder = { speaker, value -> "$speaker: ${value.trim()}" },
      ),
      FactPattern(
        regex = Regex("""\b(?:my\s+birthday\s+is\s+(?:on\s+)?|born\s+on\s+)([A-Za-z0-9 ,'-]{3,36})""", RegexOption.IGNORE_CASE),
        keyPrefix = "important_dates",
        category = "PROFILE",
        labelBuilder = { speaker, value -> "$speaker birthday: ${value.trim().trimEnd('.', ',', '!')}" },
      ),
      FactPattern(
        regex = Regex("""\b(?:i'?m\s+(?:planning\s+to|going\s+to|traveling\s+to|working\s+on|building|studying)|my\s+plan\s+is\s+to)\s+([^.!?\n]{4,75})""", RegexOption.IGNORE_CASE),
        keyPrefix = "current_plan",
        category = "TOPIC_SAID",
        labelBuilder = { speaker, value -> "$speaker said: ${value.trim()}" },
      ),
    )

  suspend fun extractAndPersist(
    chatJid: String,
    conversation: ConversationEntity,
    recentMessages: List<ChatMessageEntity>,
    settings: AgentSettings,
    targets: List<AiRouteTarget>,
    aiProviderManager: AiProviderManager,
    repository: ClawRepository,
    runAiEnrichment: Boolean = true,
    mediaIntelligence: AndroidMediaIntelligence? = null,
    visionTargets: List<AiRouteTarget> = emptyList(),
  ) {
    if (!settings.autoLearnContactMemory) return
    if (recentMessages.isEmpty()) return

    val inboundMessages = recentMessages.filter { !it.fromMe && !it.isRevoked }
    if (inboundMessages.isEmpty()) return

    // Hydrate any uncaptioned recent inbound images/stickers via SHA cache or vision targets
    val hydratedMap = linkedMapOf<String, String>()
    val inlineImagesToCaption = mutableListOf<Pair<ChatMessageEntity, String>>() // message -> base64

    val primarySupportsVision = targets.firstOrNull()?.model?.supportsVision == true
    val uncaptionedVisuals =
      inboundMessages
        .filter { it.messageType in setOf("IMAGE", "STICKER") && isPlaceholderOrBlankMediaNote(it.mediaTranscription) }
        .takeLast(2)

    for (msg in uncaptionedVisuals) {
      val path = msg.mediaLocalPath ?: continue
      val file = File(path)
      if (!file.exists() || file.length() == 0L) continue

      val sha = msg.mediaSha256 ?: mediaIntelligence?.computeFileSha256(file)
      val cached = sha?.let { repository.findCachedMediaTranscription(it) }
        ?.takeUnless { isPlaceholderOrBlankMediaNote(it) }
      if (!cached.isNullOrBlank()) {
        hydratedMap[msg.id] = cached
        repository.updateMediaTranscription(msg.id, cached, sha)
        continue
      }

      if (runAiEnrichment && mediaIntelligence != null) {
        if (primarySupportsVision && targets.isNotEmpty()) {
          val b64 = mediaIntelligence.encodeImageFileToBase64Jpeg(file, maxDim = 1024)
          if (!b64.isNullOrBlank()) {
            inlineImagesToCaption.add(msg to b64)
          }
        } else if (visionTargets.isNotEmpty()) {
          val prompt =
            if (msg.messageType == "STICKER") {
              "Briefly describe this WhatsApp sticker: character/subject, expression, any text, and emotion/meme vibe."
            } else {
              "Briefly describe what is shown in this WhatsApp image, including visible people, objects, places, and key readable text."
            }
          val desc =
            mediaIntelligence.summarizeImageWithVisionCandidates(
              imageFile = file,
              prompt = prompt,
              candidateModels = visionTargets.map { it.model },
              providerTargets = visionTargets,
            ).getOrNull()?.takeUnless { isPlaceholderOrBlankMediaNote(it) }
          if (!desc.isNullOrBlank()) {
            val formatted = desc.trim().take(500)
            hydratedMap[msg.id] = formatted
            repository.updateMediaTranscription(msg.id, formatted, sha)
          }
        }
      }
    }

    fun effectiveMediaNote(msg: ChatMessageEntity): String? {
      val raw = hydratedMap[msg.id] ?: msg.mediaTranscription
      return raw?.takeIf { !isPlaceholderOrBlankMediaNote(it) }?.trim()
    }

    // 1. Deterministic fast extraction — works instantaneously offline/with any model across text & media notes
    val latestInbound = inboundMessages.last()
    val speakerLabel =
      latestInbound.senderName
        .takeIf { it.isNotBlank() && !it.equals("null", true) }
        ?: conversation.displayName.takeIf { !conversation.isGroup && it.isNotBlank() }
        ?: WAJid.parse(latestInbound.senderJid).user

    val speakerSlug =
      speakerLabel.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .take(18)
        .ifBlank { "person" }

    val cleanLatestText = HumanReplySanitizer.sanitizeHistoryTurn(latestInbound.textContent)
    val latestMediaNote = effectiveMediaNote(latestInbound)

    val combinedLatest =
      buildString {
        append(cleanLatestText)
        if (!latestMediaNote.isNullOrBlank()) {
          if (isNotEmpty()) append(" | ")
          append(
            when (latestInbound.messageType) {
              "STICKER" -> "Sticker: "
              "IMAGE" -> "Picture: "
              "PTT", "AUDIO" -> "Voice note: "
              "DOCUMENT" -> "File: "
              else -> "Media: "
            }
          )
          append(latestMediaNote.take(220))
        }
      }.trim()

    if (combinedLatest.isNotBlank() && combinedLatest.length >= 3) {
      val key = if (conversation.isGroup) "${speakerSlug}_last_said" else "last_said"
      repository.saveContactMemory(
        chatJid = chatJid,
        key = key,
        value = "$speakerLabel: ${combinedLatest.take(260)}",
        category = "TOPIC_SAID",
      )
    }

    for (msg in inboundMessages.takeLast(5)) {
      val msgSpeaker =
        msg.senderName.takeIf { it.isNotBlank() && !it.equals("null", true) } ?: speakerLabel
      val msgSlug =
        msgSpeaker.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(16).ifBlank { "person" }
      val body = HumanReplySanitizer.sanitizeHistoryTurn(msg.textContent)
      val mediaNote = effectiveMediaNote(msg)

      if (!mediaNote.isNullOrBlank()) {
        when (msg.messageType) {
          "STICKER" -> {
            val k = if (conversation.isGroup) "${msgSlug}_last_sticker_vibe" else "last_sticker_vibe"
            repository.saveContactMemory(chatJid, k, "$msgSpeaker sent sticker: ${mediaNote.take(220)}", category = "STICKER")
          }
          "IMAGE" -> {
            val k = if (conversation.isGroup) "${msgSlug}_last_shared_picture" else "last_shared_picture"
            repository.saveContactMemory(chatJid, k, "$msgSpeaker shared picture: ${mediaNote.take(240)}", category = "MEDIA")
          }
          "PTT", "AUDIO" -> {
            val k = if (conversation.isGroup) "${msgSlug}_last_voice_note" else "last_voice_note"
            repository.saveContactMemory(chatJid, k, "$msgSpeaker said in voice note: ${mediaNote.take(240)}", category = "AUDIO")
          }
          "DOCUMENT" -> {
            val k = if (conversation.isGroup) "${msgSlug}_last_shared_doc" else "last_shared_doc"
            repository.saveContactMemory(chatJid, k, "$msgSpeaker shared file: ${mediaNote.take(240)}", category = "MEDIA")
          }
        }
      }

      val searchableText =
        listOf(body, mediaNote.orEmpty())
          .filter { it.isNotBlank() }
          .joinToString("\n")
      if (searchableText.isBlank()) continue

      deterministicPatterns.forEach { pat ->
        val match = pat.regex.find(searchableText)
        val captured = match?.groupValues?.getOrNull(1)?.trim() ?: match?.value?.trim()
        if (!captured.isNullOrBlank() && captured.length >= 2) {
          val memKey = if (conversation.isGroup) "${msgSlug}_${pat.keyPrefix}" else pat.keyPrefix
          repository.saveContactMemory(
            chatJid = chatJid,
            key = memKey,
            value = pat.labelBuilder(msgSpeaker, captured).take(240),
            category = pat.category,
          )
        }
      }
    }

    // Track "Me"'s own latest statements, commitments, or explanations deterministically (fast & offline-safe)
    val ownMessages = recentMessages.filter { it.fromMe && !it.isRevoked }
    val latestOwn = ownMessages.lastOrNull()
    if (latestOwn != null) {
      val cleanOwn = HumanReplySanitizer.sanitizeHistoryTurn(latestOwn.textContent)
      if (cleanOwn.length >= 6) {
        repository.saveContactMemory(
          chatJid = chatJid,
          key = "my_last_statement",
          value = "Me said: ${cleanOwn.take(240)}",
          category = "OWN_WORDS",
        )
      }
      val commitmentRegex =
        Regex(
          """\b(?:i'?ll|i\s+will|let'?s|we\s+can|because|since|the\s+reason\s+is|promised|agreed\s+to)\s+([^.!?\n]{5,120})""",
          RegexOption.IGNORE_CASE,
        )
      ownMessages.takeLast(4).forEach { om ->
        val txt = HumanReplySanitizer.sanitizeHistoryTurn(om.textContent)
        val match = commitmentRegex.find(txt)?.value?.trim()
        if (!match.isNullOrBlank() && match.length >= 8) {
          repository.saveContactMemory(
            chatJid = chatJid,
            key = "my_commitments_or_reasons",
            value = "Me: ${txt.take(240)}",
            category = "OWN_WORDS",
          )
        }
      }
    }

    // 2. Skip redundant AI enrichment only if latest message is already summarized AND no uncaptioned images await inspection
    val latestMsg = recentMessages.last()
    val alreadySummarized =
      conversation.lastSummarizedMessageId == latestMsg.id &&
        conversation.rollingSummary.isNotBlank() &&
        inlineImagesToCaption.isEmpty()

    if (conversation.rollingSummary.isBlank()) {
      val seedSummary = buildFallbackSummary(conversation, recentMessages, ::effectiveMediaNote)
      if (seedSummary.isNotBlank()) {
        repository.updateSummaryWithMetadata(
          chatJid = chatJid,
          summary = seedSummary,
          lastSummarizedMessageId = latestMsg.id,
          summaryMessageCount = recentMessages.size,
        )
      }
    }

    if (!runAiEnrichment || alreadySummarized || targets.isEmpty()) return

    // 3. Smart Multimodal AI-assisted memory + own-words tracking + summary update
    val existingMemories = repository.getMemories(chatJid).take(20)
    val existingMemBlock =
      if (existingMemories.isEmpty()) "(none yet)"
      else existingMemories.joinToString("\n") { "- ${it.factKey}: ${it.factValue}" }

    val transcript =
      recentMessages.takeLast(14).joinToString("\n") { m ->
        val who =
          if (m.fromMe) "Me"
          else m.senderName.ifBlank { WAJid.parse(m.senderJid).user }
        val clean = HumanReplySanitizer.sanitizeHistoryTurn(m.textContent).take(260)
        val quoteTag =
          m.quotedPreview?.takeIf { it.isNotBlank() }?.let { " [replying to \"${it.take(80)}\"]" }.orEmpty()
        val extra = effectiveMediaNote(m)?.let { " [${m.messageType} insight: ${it.take(260)}]" }.orEmpty()
        val pendingVisualNote =
          if (inlineImagesToCaption.any { it.first.id == m.id }) " [Visual attached id=${m.id}]" else ""
        "$who$quoteTag: $clean$extra$pendingVisualNote"
      }

    val attachedIdsHint =
      if (inlineImagesToCaption.isNotEmpty()) {
        "\nAttached images/stickers in order (describe each in media_notes): " +
          inlineImagesToCaption.joinToString(", ") { (m, _) -> "${m.messageType} message_id=${m.id}" }
      } else {
        ""
      }

    val sysPrompt =
      """
      You maintain smart, human-like long-term memory for a WhatsApp conversation across texts, stickers, pictures, voice notes, documents, AND our own past replies ("Me").
      Respond ONLY with valid JSON:
      {
        "media_notes": [{"message_id": "id_from_prompt", "description": "concise factual description of what the picture/sticker showed (people, text, items, meme/emotion)"}],
        "facts": [{"key": "canonical_snake_key", "value": "clear factual detail to remember"}],
        "obsolete_keys": ["optional_outdated_key_to_delete"],
        "summary": "Compact bullet summary covering: Who they are, What they shared/said (text, pictures, stickers, voice, files), What 'Me' promised/said, Preferences, and Open threads"
      }
      Canonical keys to prefer & merge cleanly:
      preferred_name, nickname, location, work_role, likes, dislikes, pets, hobbies, important_dates, current_plan, inside_jokes, sticker_vibe, shared_pictures, commitments, my_promises_or_stance, recent_topic.
      Rules:
      - Remember meaningful things like a sharp friend: personal details, pets, inside jokes, sticker humor/mood, things visible in photos/screenshots (receipts, events, hobbies, people), AND key commitments or stances "Me" stated.
      - Update/merge existing keys rather than inventing duplicate keys (e.g. merge new interests into "likes").
      - Put superseded keys in "obsolete_keys" ONLY when clearly contradicted (e.g., moved to a new city).
      - For groups, prefix keys/values with the speaker's name so people stay distinct.
      - Never record generic filler ("said ok", "said lol") as standalone facts. Keep summary under 700 chars.
      """.trimIndent()

    val userPrompt =
      buildString {
        appendLine("Chat: ${conversation.displayName} (isGroup=${conversation.isGroup})")
        if (conversation.rollingSummary.isNotBlank()) {
          appendLine("Previous Summary:\n${conversation.rollingSummary.take(550)}")
        }
        appendLine("Existing Known Memories:\n$existingMemBlock")
        appendLine("Recent Messages (including our 'Me' replies & media):$attachedIdsHint\n$transcript")
      }

    val exec =
      aiProviderManager.executeWithFallback(
        targets = targets,
        messages =
          listOf(
            NimChatMessage(role = "system", content = sysPrompt),
            NimChatMessage(
              role = "user",
              content = userPrompt,
              imageBase64Jpegs = inlineImagesToCaption.map { it.second },
            ),
          ),
        tools = emptyList(),
        temperature = 0.15f,
        maxTokens = 460,
        stream = false,
      )

    val raw = exec.text?.trim().orEmpty()
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start >= 0 && end > start) {
      runCatching {
        val obj = JSONObject(raw.substring(start, end + 1))

        val hydratedNotes = mutableListOf<Pair<ChatMessageEntity, String>>()
        val mediaNotesArr: JSONArray? = obj.optJSONArray("media_notes")
        if (mediaNotesArr != null) {
          for (i in 0 until mediaNotesArr.length()) {
            val mn = mediaNotesArr.optJSONObject(i) ?: continue
            val targetId = mn.optNonNullString("message_id").trim()
            val desc = mn.optNonNullString("description").trim().take(500)
            if (desc.isBlank() || isPlaceholderOrBlankMediaNote(desc)) continue
            val matchedMsg = inlineImagesToCaption.firstOrNull { it.first.id == targetId }?.first
              ?: inlineImagesToCaption.getOrNull(i)?.first
            if (matchedMsg != null) {
              hydratedNotes.add(matchedMsg to desc)
            }
          }
        } else {
          val mediaNotesObj = obj.optJSONObject("media_notes")
          if (mediaNotesObj != null) {
            val keys = mediaNotesObj.keys()
            while (keys.hasNext()) {
              val targetId = keys.next()
              val desc = mediaNotesObj.optNonNullString(targetId).trim().take(500)
              if (desc.isBlank() || isPlaceholderOrBlankMediaNote(desc)) continue
              val matchedMsg = inlineImagesToCaption.firstOrNull { it.first.id == targetId }?.first
              if (matchedMsg != null) {
                hydratedNotes.add(matchedMsg to desc)
              }
            }
          }
        }

        for ((matchedMsg, desc) in hydratedNotes) {
          val sha =
            matchedMsg.mediaSha256
              ?: matchedMsg.mediaLocalPath?.let { p -> mediaIntelligence?.computeFileSha256(File(p)) }
          repository.updateMediaTranscription(matchedMsg.id, desc, sha)
          if (!matchedMsg.fromMe) {
            val mSpeaker = matchedMsg.senderName.takeIf { it.isNotBlank() && !it.equals("null", true) } ?: speakerLabel
            val mSlug = mSpeaker.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(16).ifBlank { "person" }
            when (matchedMsg.messageType) {
              "STICKER" -> {
                val k = if (conversation.isGroup) "${mSlug}_last_sticker_vibe" else "last_sticker_vibe"
                repository.saveContactMemory(chatJid, k, "$mSpeaker sent sticker: ${desc.take(220)}", category = "STICKER")
              }
              "IMAGE" -> {
                val k = if (conversation.isGroup) "${mSlug}_last_shared_picture" else "last_shared_picture"
                repository.saveContactMemory(chatJid, k, "$mSpeaker shared picture: ${desc.take(240)}", category = "MEDIA")
              }
            }
          }
        }

        val obsoleteArr: JSONArray? = obj.optJSONArray("obsolete_keys")
        if (obsoleteArr != null) {
          for (i in 0 until obsoleteArr.length()) {
            val obsKey = obsoleteArr.optNonNullString(i).trim()
            if (obsKey.isNotBlank()) {
              repository.deleteMemoryByKey(chatJid, obsKey)
            }
          }
        }

        val factsArr: JSONArray? = obj.optJSONArray("facts")
        if (factsArr != null) {
          for (i in 0 until minOf(factsArr.length(), 8)) {
            val f = factsArr.optJSONObject(i) ?: continue
            val k = f.optNonNullString("key").lowercase(Locale.US).replace(Regex("[^a-z0-9_]+"), "_").trim('_').take(48)
            val v = f.optNonNullString("value").trim().take(260)
            if (k.isNotBlank() && v.isNotBlank()) {
              repository.saveContactMemory(chatJid, k, v, category = "PERSON_INFO")
            }
          }
        }
        val newSummary = HumanReplySanitizer.sanitizeHistoryTurn(obj.optNonNullString("summary")).take(1400)
        if (newSummary.isNotBlank()) {
          repository.updateSummaryWithMetadata(
            chatJid = chatJid,
            summary = newSummary,
            lastSummarizedMessageId = latestMsg.id,
            summaryMessageCount = recentMessages.size,
          )
        }
      }
    }
  }

  private fun buildFallbackSummary(
    conversation: ConversationEntity,
    messages: List<ChatMessageEntity>,
    mediaNoteResolver: (ChatMessageEntity) -> String?,
  ): String {
    val tail = messages.filter { !it.isRevoked }.takeLast(6)
    if (tail.isEmpty()) return ""
    val lines =
      tail.map { m ->
        val who = if (m.fromMe) "Me" else m.senderName.ifBlank { conversation.displayName }.ifBlank { "Contact" }
        val text = HumanReplySanitizer.sanitizeHistoryTurn(m.textContent)
        val note = mediaNoteResolver(m)
        val combined =
          buildString {
            if (text.isNotBlank()) append(text.take(120))
            if (!note.isNullOrBlank()) {
              if (isNotEmpty()) append(" ")
              append("[${m.messageType}: ${note.take(100)}]")
            }
            if (isEmpty()) append("[${m.messageType}]")
          }
        "• $who: $combined"
      }
    return lines.joinToString("\n").take(900)
  }

  companion object {
    fun isPlaceholderOrBlankMediaNote(note: String?): Boolean {
      if (note.isNullOrBlank() || note.equals("null", true)) return true
      val lower = note.lowercase()
      return lower.contains("(attached directly)") ||
        lower.contains("currently turned off") ||
        lower.contains("did not finish downloading") ||
        lower.contains("temporary vision ai error") ||
        lower.contains("configure a vision (vl) model")
    }
  }
}

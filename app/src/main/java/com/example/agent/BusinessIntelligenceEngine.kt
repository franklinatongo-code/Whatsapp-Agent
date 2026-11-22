package com.example.agent

import com.example.ai.AiProviderManager
import com.example.ai.NimChatMessage
import com.example.ai.NimModelInfo
import com.example.data.db.ChatMessageEntity
import com.example.data.db.ConversationEntity
import com.example.data.prefs.AgentSettings
import com.example.data.repository.ClawRepository
import com.example.util.optNonNullString
import org.json.JSONObject

data class BusinessAnalysisResult(
  val category: String,
  val leadScore: Int,
  val intent: String,
  val nextAction: String,
  val analyzedByAi: Boolean = false,
)

/**
 * BusinessIntelligenceEngine: Real, data-driven CRM & Business Mode assistant backed strictly by
 * persisted Room messages and owner-configured business details (zero fabricated metrics).
 */
class BusinessIntelligenceEngine {

  companion object {
    val BUSINESS_CATEGORIES =
      listOf(
        "Potential Customer",
        "Existing Customer",
        "Sales Inquiry",
        "Support Request",
        "Follow-Up Required",
        "Important Opportunity",
      )
  }

  fun buildBusinessPromptBlock(settings: AgentSettings): String {
    if (!settings.businessModeEnabled) return ""
    return buildString {
      appendLine("=== BUSINESS MODE ACTIVE ===")
      if (settings.businessName.isNotBlank()) {
        appendLine("Business Name: ${settings.businessName.trim()}")
      }
      appendLine("Business Communication Tone: ${settings.businessTone.ifBlank { "Consultative & Executive" }}")
      if (settings.businessCatalogOrInfo.isNotBlank()) {
        appendLine("Verified Business Products / Pricing / Policies:")
        appendLine(settings.businessCatalogOrInfo.trim().take(1500))
      } else {
        appendLine("Catalog Info: None provided yet. Be helpful and qualify the customer's needs clearly without inventing unverified prices, discounts, stock, or delivery dates.")
      }
      appendLine("Confirmation Rule: Never invent unverified prices, discounts, turnaround promises, or confirm bookings/orders without catalog backing or owner approval. Identify intent clearly and suggest a clean next step.")
    }.trim()
  }

  fun classifyHeuristic(
    conversation: ConversationEntity,
    messages: List<ChatMessageEntity>,
  ): BusinessAnalysisResult {
    if (messages.isEmpty()) {
      return BusinessAnalysisResult(
        category = if (conversation.unreadCount > 0) "Follow-Up Required" else "Potential Customer",
        leadScore = if (conversation.unreadCount > 0) 55 else 30,
        intent = conversation.lastMessagePreview.ifBlank { "No recent messages yet" }.take(140),
        nextAction = "Send an introductory check-in or review recent history.",
        analyzedByAi = false,
      )
    }

    val combinedLower =
      messages.takeLast(12).joinToString(" ") { m ->
        "${m.textContent} ${m.mediaTranscription.orEmpty()}"
      }.lowercase()

    val lastMsg = messages.last()
    val waitingOnUs = !lastMsg.fromMe || conversation.unreadCount > 0

    val hasSales =
      listOf("price", "pricing", "cost", "how much", "buy", "order", "quote", "plan", "package", "book", "purchase", "pay", "invoice")
        .any { combinedLower.contains(it) }
    val hasOpportunity =
      listOf("partner", "wholesale", "bulk", "enterprise", "contract", "proposal", "demo", "budget", "deal", "collaborat")
        .any { combinedLower.contains(it) }
    val hasSupport =
      listOf("help", "issue", "problem", "error", "broken", "refund", "cancel", "late", "delay", "not working", "fix", "support")
        .any { combinedLower.contains(it) }
    val hasExisting =
      listOf("again", "renew", "upgrade", "last order", "my order", "receipt", "subscription")
        .any { combinedLower.contains(it) } || messages.count { it.fromMe } >= 4

    val category =
      when {
        hasOpportunity -> "Important Opportunity"
        hasSales -> "Sales Inquiry"
        hasSupport -> "Support Request"
        hasExisting && waitingOnUs -> "Follow-Up Required"
        hasExisting -> "Existing Customer"
        waitingOnUs -> "Follow-Up Required"
        else -> "Potential Customer"
      }

    var score =
      when (category) {
        "Important Opportunity" -> 86
        "Sales Inquiry" -> 80
        "Follow-Up Required" -> 68
        "Support Request" -> 62
        "Existing Customer" -> 58
        else -> 48
      }
    if (waitingOnUs) score += 8
    if (messages.any { !it.fromMe && !it.mediaTranscription.isNullOrBlank() }) score += 4
    score = score.coerceIn(10, 98)

    val latestInbound = messages.lastOrNull { !it.fromMe } ?: lastMsg
    val rawSnippet =
      latestInbound.mediaTranscription?.takeIf { it.isNotBlank() } ?: latestInbound.textContent
    val intent = rawSnippet.replace(Regex("\\s+"), " ").trim().take(160).ifBlank { "General conversation" }

    val nextAction =
      when {
        category == "Important Opportunity" && waitingOnUs -> "Respond promptly with concrete proposal details or schedule a call."
        category == "Sales Inquiry" && waitingOnUs -> "Share clear pricing/availability and ask a qualifying follow-up."
        category == "Support Request" && waitingOnUs -> "Acknowledge the issue and provide concrete troubleshooting or timeline."
        waitingOnUs -> "Reply to the unanswered message from ${latestInbound.senderName.ifBlank { conversation.displayName }}."
        else -> "Monitor for reply; follow up if silent for 24h."
      }

    return BusinessAnalysisResult(
      category = category,
      leadScore = score,
      intent = intent,
      nextAction = nextAction,
      analyzedByAi = false,
    )
  }

  suspend fun analyzeConversationWithAi(
    conversation: ConversationEntity,
    messages: List<ChatMessageEntity>,
    settings: AgentSettings,
    discoveredModels: List<NimModelInfo>,
    aiProviderManager: AiProviderManager,
    repository: ClawRepository,
  ): BusinessAnalysisResult {
    val heuristic = classifyHeuristic(conversation, messages)
    val targets = aiProviderManager.buildTargetsForChat(settings, discoveredModels)
    if (messages.isEmpty() || targets.isEmpty()) {
      repository.updateBusinessInsights(
        chatJid = conversation.chatJid,
        category = heuristic.category,
        leadScore = heuristic.leadScore,
        intent = heuristic.intent,
        nextAction = heuristic.nextAction,
      )
      return heuristic
    }

    val transcript =
      messages.takeLast(14).joinToString("\n") { m ->
        val speaker = if (m.fromMe) "Me" else m.senderName.ifBlank { "Contact" }
        val extra = m.mediaTranscription?.takeIf { it.isNotBlank() }?.let { " [Attachment: ${it.take(120)}]" }.orEmpty()
        "$speaker: ${m.textContent.take(220)}$extra"
      }

    val sysPrompt =
      """
      You are a precise CRM analyst for WhatsApp business conversations.
      Classify the conversation into EXACTLY ONE of these categories:
      ${BUSINESS_CATEGORIES.joinToString(", ")}
      Respond strictly in valid JSON with keys:
      {"category":"<one exact category>","lead_score":<integer 0-100>,"intent":"<short 1-sentence intent>","next_action":"<short concrete next step>"}
      """.trimIndent()

    val userPrompt =
      buildString {
        appendLine("Conversation: ${conversation.displayName} (isGroup=${conversation.isGroup}, unread=${conversation.unreadCount})")
        if (settings.businessName.isNotBlank()) appendLine("Business: ${settings.businessName}")
        appendLine("Recent messages:")
        append(transcript)
      }

    val exec =
      aiProviderManager.executeWithFallback(
        targets = targets,
        messages = listOf(
          NimChatMessage(role = "system", content = sysPrompt),
          NimChatMessage(role = "user", content = userPrompt),
        ),
        tools = emptyList(),
        temperature = 0.15f,
        maxTokens = 260,
        stream = false,
      )

    val parsed =
      exec.text?.let { raw ->
        runCatching {
          val start = raw.indexOf('{')
          val end = raw.lastIndexOf('}')
          if (start >= 0 && end > start) {
            val obj = JSONObject(raw.substring(start, end + 1))
            val rawCat = obj.optNonNullString("category").trim()
            val matchedCat =
              BUSINESS_CATEGORIES.firstOrNull { it.equals(rawCat, ignoreCase = true) }
                ?: heuristic.category
            val score = obj.optInt("lead_score", heuristic.leadScore).coerceIn(0, 100)
            val intent = obj.optNonNullString("intent").trim().ifBlank { heuristic.intent }
            val nextAction = obj.optNonNullString("next_action").trim().ifBlank { heuristic.nextAction }
            BusinessAnalysisResult(
              category = matchedCat,
              leadScore = score,
              intent = intent,
              nextAction = nextAction,
              analyzedByAi = true,
            )
          } else {
            null
          }
        }.getOrNull()
      } ?: heuristic

    repository.updateBusinessInsights(
      chatJid = conversation.chatJid,
      category = parsed.category,
      leadScore = parsed.leadScore,
      intent = parsed.intent,
      nextAction = parsed.nextAction,
    )
    return parsed
  }
}

package com.example.agent

data class SanitizedReplyResult(
  val cleanText: String,
  val containedAiGiveaway: Boolean,
  val hadPrefixLeak: Boolean,
  val giveawayReasons: List<String> = emptyList(),
  val containedThinkingLeak: Boolean = false,
)

/**
 * Deterministic outbound & history sanitizer that eliminates robotic & reasoning leaks:
 * - Strips <think>...</think>, <thought>...</thought>, <reasoning>...</reasoning>, and orphaned </think>
 * - Detects raw chain-of-thought monologues ("The user wants me to...", "Strict rules:", "Key constraints:")
 * - Removes [HH:MM], [11/21, 3:24 AM], "Me (Assistant):", "Assistant:", "[Replying to: ...]", "(replying to ...)"
 * - Strips accidental prompt-envelope/XML artifacts
 * - Detects AI self-disclosure ("I'm an AI", "ClawLink assistant", "I was told not to say", bulleted tool menus)
 *   so ClawAgentRuntime can self-correct weak-model slips before anything reaches WhatsApp.
 */
object HumanReplySanitizer {

  private val leadingTimestampBracket =
    Regex("""^\s*\[\d{1,2}[:/]\d{1,2}(?:[,\s]+\d{1,2}:\d{2}(?:\s*[APap][Mm])?)?]\s*""")

  private val leadingSpeakerLabel =
    Regex(
      """^\s*(?:Me\s*\((?:Assistant|AI|Bot)\)|ClawLink(?:\s+(?:Agent|Assistant|AI))?|Assistant|AI\s+Assistant|ChatGPT|Gemini|Bot)\s*:\s*""",
      RegexOption.IGNORE_CASE,
    )

  private val leadingReplyingTag =
    Regex(
      """^\s*(?:\[(?:Replying|Reply)\s+to\s*:?\s*"?[^\]"\n]*"?\]|\((?:replying|reply)\s+to\s+"?[^)"\n]*"?\)|Replying\s+to\s+"?[^:\n]{0,120}"?:\s*)\s*""",
      RegexOption.IGNORE_CASE,
    )

  private val xmlNoiseTag =
    Regex("""</?(?:untrusted_whatsapp_message|untrusted_whatsapp_attachment|replied_to_message|system|assistant|reply)[^>]*>""", RegexOption.IGNORE_CASE)

  private val closedThinkingBlock =
    Regex("""<(think|thought|reasoning|reflection)\b[^>]*>[\s\S]*?</\1>""", setOf(RegexOption.IGNORE_CASE))

  private val unclosedThinkingBlock =
    Regex("""<(think|thought|reasoning|reflection)\b[^>]*>[\s\S]*$""", setOf(RegexOption.IGNORE_CASE))

  private val orphanClosingThinkTag =
    Regex("""</(?:think|thought|reasoning|reflection)>""", RegexOption.IGNORE_CASE)

  private val explicitFinalAnswerMarker =
    Regex("""(?:^|\n)\s*(?:\*{0,2})(?:Final\s+(?:Answer|Reply|Response|Output|Message)|Best\s+Reply)\s*(?:\*{0,2})\s*:\s*(.+)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

  private val thinkingLeakDetectors: List<Pair<Regex, String>> =
    listOf(
      Regex("""(?:^|\n)\s*(?:thinking(?:\s+process)?|reasoning(?:\s+process)?|chain\s+of\s+thought|internal\s+monologue|thought\s+process)\s*:""", RegexOption.IGNORE_CASE) to
        "Explicit thinking header",
      Regex("""\bthe\s+user\s+(?:wants\s+me\s+to|is\s+asking|provided|instructed|says|mentioned)\b""", RegexOption.IGNORE_CASE) to
        "Third-person prompt analysis",
      Regex("""(?:^|\n)\s*(?:incoming\s+(?:whatsapp\s+)?message(?:\s+is)?|draft\s+to\s+rewrite|context/draft\s+provided|the\s+context\s+from\s+the\s+draft)\b""", RegexOption.IGNORE_CASE) to
        "Prompt draft meta-reference",
      Regex("""(?:^|\n)\s*(?:strict\s+rules|key\s+constraints|best\s+options|possible\s+replies|natural\s+human\s+replies\s+to)\s*:""", RegexOption.IGNORE_CASE) to
        "Reasoning outline header",
      Regex("""\b(?:i\s+need\s+to\s+ignore\s+all\s+that|meta-instruction\s+noise|not\s+actual\s+history\s+i\s+have\s+access\s+to|i\s+should\s+(?:just\s+give|probably\s+vary)|a\s+natural\s+human\s+response\s+would\s+be|likely\s+referencing\s+the\s+previous)\b""", RegexOption.IGNORE_CASE) to
        "Internal decision monologue",
      Regex("""\[Status:\s*YOU\s+ALREADY\s+REPLIED""", RegexOption.IGNORE_CASE) to
        "History status badge echo",
    )

  private val aiGiveawayDetectors: List<Pair<Regex, String>> =
    listOf(
      Regex("""\b(?:i'?m|i\s+am|yes,?\s+i'?m|yep,?\s+that'?s\s+me[,!.\s]*(?:your|an?)?)\s+(?:an?\s+)?(?:ai|artificial\s+intelligence|language\s+model|llm|bot|chatbot|virtual\s+assistant|whatsapp\s+assistant|clawlink(?:\s+assistant|\s+agent)?)\b""", RegexOption.IGNORE_CASE) to
        "Direct AI/assistant admission",
      Regex("""\byour\s+friendly\s+(?:clawlink|whatsapp|ai)?\s*assistant\b""", RegexOption.IGNORE_CASE) to
        "Assistant persona leak",
      Regex("""\bclawlink(?:\s+wa|\s+agent|\s+assistant|\s+ai)?\b""", RegexOption.IGNORE_CASE) to
        "App name leak",
      Regex("""\b(?:as\s+an\s+ai|as\s+a\s+language\s+model|i\s+was\s+(?:told|instructed|programmed)\s+(?:never|not)\s+to\s+say)\b""", RegexOption.IGNORE_CASE) to
        "System instruction / model disclosure",
      Regex("""\bhow\s+can\s+i\s+(?:help|assist)\s+you\s+today\b""", RegexOption.IGNORE_CASE) to
        "Generic customer-bot opener",
      Regex("""\bi\s+can\s+help\s+you\s+with\s*:?\s*(?:\n|\r\n)?\s*[-•*]""", RegexOption.IGNORE_CASE) to
        "Capability menu list",
      Regex("""\bgenerating\s+strong\s+passwords\b.*\bunit\s+conversions\b|\bmath\s+calculations\b.*\bunit\s+conversions\b""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) to
        "Tool advertisement leak",
      Regex("""\bdo\s+not\s+mention\s+(?:ai|assistant)\b|\bmy\s+system\s+prompt\b""", RegexOption.IGNORE_CASE) to
        "Prompt meta leak",
    )

  fun stripThinkingTags(raw: String): String {
    if (raw.isBlank()) return ""
    var text = raw.replace(closedThinkingBlock, "").trim()
    // If an orphaned closing </think> tag exists (opening tag was in template), keep only text after it
    val orphanMatch = orphanClosingThinkTag.findAll(text).lastOrNull()
    if (orphanMatch != null) {
      text = text.substring(orphanMatch.range.last + 1).trim()
    }
    text = text.replace(unclosedThinkingBlock, "").trim()
    // If model emitted reasoning outline followed by explicit "Final Answer:" / "Final Reply:"
    val finalMarker = explicitFinalAnswerMarker.find(text)
    if (finalMarker != null && thinkingLeakDetectors.any { (rx, _) -> rx.containsMatchIn(text.substring(0, finalMarker.range.first)) }) {
      val candidate = finalMarker.groupValues[1].trim()
      if (candidate.isNotBlank()) {
        text = candidate
      }
    }
    return text.trim()
  }

  fun isThinkingLeak(rawText: String): Boolean {
    if (rawText.isBlank()) return false
    return thinkingLeakDetectors.any { (rx, _) -> rx.containsMatchIn(rawText) }
  }

  fun sanitizeHistoryTurn(rawText: String): String {
    if (rawText.isBlank()) return ""
    var text = stripThinkingTags(rawText).trim()
    if (text.isBlank()) return ""
    // Never feed previously leaked chain-of-thought messages back into future chat prompts
    if (isThinkingLeak(text)) return ""
    text = text.replace(xmlNoiseTag, "")
    var changed: Boolean
    var iterations = 0
    do {
      changed = false
      val before = text
      text = text.replace(leadingTimestampBracket, "")
      text = text.replace(leadingSpeakerLabel, "")
      text = text.replace(leadingReplyingTag, "")
      text = text.trimStart()
      if (text != before) changed = true
      iterations++
    } while (changed && iterations < 5)
    return text.trim()
  }

  fun scrubOutbound(
    rawReply: String,
    enforceHumanStealth: Boolean = true,
  ): SanitizedReplyResult {
    var text = stripThinkingTags(rawReply).trim()
    if (text.isBlank()) {
      val hadAnyThinking = rawReply.isNotBlank()
      return SanitizedReplyResult(
        cleanText = "",
        containedAiGiveaway = false,
        hadPrefixLeak = hadAnyThinking,
        giveawayReasons = if (hadAnyThinking) listOf("Empty after stripping thinking tags") else emptyList(),
        containedThinkingLeak = hadAnyThinking,
      )
    }

    val beforePrefixStrip = text
    text = text.replace(xmlNoiseTag, "")

    // Strip markdown outer code fences if a weak model wrapped casual reply in ``` ... ```
    if (text.startsWith("```") && text.endsWith("```") && text.length > 8) {
      text = text.removePrefix("```").removeSuffix("```").trim()
    }

    // Iteratively remove stacked prefixes like: [03:27] Me (Assistant): (replying to "...") ...
    var guard = 0
    while (guard < 6) {
      val prev = text
      text = text.replace(leadingTimestampBracket, "")
      text = text.replace(leadingSpeakerLabel, "")
      text = text.replace(leadingReplyingTag, "")
      text = text.trimStart()
      if (text == prev) break
      guard++
    }

    // Also strip inline line-start "Me (Assistant):" or "[HH:MM] Assistant:" on multi-line chunks
    text =
      text.lines().joinToString("\n") { line ->
        line
          .replace(leadingTimestampBracket, "")
          .replace(leadingSpeakerLabel, "")
          .replace(leadingReplyingTag, "")
          .trimStart()
      }.trim()

    // Unwrap accidental surrounding quotes around the whole message ("...")
    if (text.length >= 4 &&
      ((text.startsWith('"') && text.endsWith('"')) || (text.startsWith('“') && text.endsWith('”')))
    ) {
      val inner = text.substring(1, text.length - 1).trim()
      if (inner.isNotBlank() && !inner.contains('\n')) {
        text = inner
      }
    }

    val hadPrefixLeak = text != beforePrefixStrip || text != rawReply.trim()

    val thinkingReasons =
      thinkingLeakDetectors.mapNotNull { (regex, label) ->
        if (regex.containsMatchIn(text)) label else null
      }

    val giveawayOnlyReasons =
      if (enforceHumanStealth) {
        aiGiveawayDetectors.mapNotNull { (regex, label) ->
          if (regex.containsMatchIn(text)) label else null
        }
      } else {
        emptyList()
      }

    return SanitizedReplyResult(
      cleanText = text,
      containedAiGiveaway = giveawayOnlyReasons.isNotEmpty(),
      hadPrefixLeak = hadPrefixLeak,
      giveawayReasons = giveawayOnlyReasons + thinkingReasons,
      containedThinkingLeak = thinkingReasons.isNotEmpty(),
    )
  }

  /**
   * Deterministic last-resort fallback if a weak/thinking LLM still leaks internal thoughts or AI identity after rewrite.
   */
  fun fallbackHumanizeIfLeaked(
    sanitizedText: String,
    incomingUserText: String,
  ): String {
    val scrubbed = scrubOutbound(sanitizedText, enforceHumanStealth = true)
    if (!scrubbed.containedAiGiveaway && !scrubbed.containedThinkingLeak && scrubbed.cleanText.isNotBlank()) {
      return scrubbed.cleanText
    }
    val lowerIn = incomingUserText.lowercase().trim()
    if (!scrubbed.containedThinkingLeak) {
      val cleanedLines =
        scrubbed.cleanText.lines().filterNot { line ->
          val l = line.trim()
          l.startsWith("- ") || l.startsWith("• ") ||
            aiGiveawayDetectors.any { (rx, _) -> rx.containsMatchIn(l) } ||
            thinkingLeakDetectors.any { (rx, _) -> rx.containsMatchIn(l) }
        }.joinToString("\n").trim()
      if (cleanedLines.isNotBlank() && !isThinkingLeak(cleanedLines)) {
        return cleanedLines
      }
    }
    return when {
      lowerIn in setOf("sup", "yo", "hey", "hi", "hello", "hola", "wyd", "wassup", "what's up", "whats up") ||
        lowerIn.startsWith("sup ") || lowerIn.startsWith("wyd ") ->
        "Not much, just taking it easy. What's good on your end?"
      lowerIn.contains("remember") ->
        "Depends what part you're asking about—bring it up and let's see."
      lowerIn.contains("ai") || lowerIn.contains("bot") || lowerIn.contains("robot") || lowerIn.contains("chatgpt") ->
        "Lol what made you jump to that? You're overthinking it 😂"
      lowerIn.contains("who is this") || lowerIn.contains("who are you") ->
        "Just me texting you back—what's up?"
      else ->
        "Wait say that again properly—what were you getting at?"
    }
  }
}

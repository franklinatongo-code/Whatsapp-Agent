package com.example.ai

import kotlinx.serialization.Serializable

@Serializable
data class NimModelInfo(
  val id: String,
  val ownedBy: String,
  val supportsStreaming: Boolean,
  val supportsVision: Boolean,
  val supportsToolCalling: Boolean,
  val supportsAudioAsr: Boolean,
  val isChatCapable: Boolean,
  val estimatedContextTokens: Int,
) {
  val shortName: String
    get() = id.substringAfter('/', id)

  val providerBadge: String
    get() = id.substringBefore('/', ownedBy).uppercase()
}

/**
 * Infers capability metadata dynamically from discovered GET /v1/models identifiers
 * and ownership metadata without hardcoding a static model catalogue.
 */
object NvidiaModelCapabilityDetector {

  fun inferFromDiscoveredModel(id: String, ownedBy: String?): NimModelInfo {
    val lower = id.lowercase()
    val owner = (ownedBy ?: id.substringBefore('/', "nvidia")).ifBlank { "nvidia" }

    val isAsr =
      lower.contains("whisper") ||
        lower.contains("parakeet") ||
        lower.contains("canary") ||
        lower.contains("transcribe") ||
        lower.contains("voxtral") ||
        lower.contains("sensevoice") ||
        lower.contains("asr") ||
        lower.contains("stt") ||
        lower.contains("phi-4-multimodal") ||
        lower.contains("qwen2-audio") ||
        lower.contains("qwen-audio") ||
        lower.contains("flamingo")

    val isEmbeddingOrRerank =
      lower.contains("embed") ||
        lower.contains("rerank") ||
        lower.contains("nv-embed") ||
        lower.contains("bge-") ||
        lower.contains("clip") ||
        lower.contains("guard") ||
        lower.contains("reward") ||
        lower.contains("parse") ||
        lower.contains("ocr")

    val isImageGen =
      lower.contains("flux") ||
        lower.contains("stable-diffusion") ||
        lower.contains("sdxl") ||
        lower.contains("imagen")

    val isVision =
      !isImageGen &&
        (lower.contains("vision") ||
          lower.contains("-vl") ||
          lower.contains("_vl") ||
          lower.contains("vlm") ||
          lower.contains("neva") ||
          lower.contains("kosmos") ||
          lower.contains("fuyu") ||
          lower.contains("paligemma") ||
          lower.contains("phi-3.5-vision") ||
          lower.contains("phi-4-multimodal") ||
          lower.contains("pixtral") ||
          lower.contains("gemma-3") ||
          lower.contains("mistral-small-3") ||
          lower.contains("mistral-medium-3") ||
          lower.contains("kimi-k2.5") ||
          lower.contains("internvl") ||
          lower.contains("llama-4") ||
          lower.contains("gpt-4o") ||
          lower.contains("gpt-4.1") ||
          lower.contains("gpt-4.5") ||
          lower.contains("claude-3") ||
          lower.contains("claude-4") ||
          lower.contains("gemini-") ||
          lower.contains("llava") ||
          lower.contains("minicpm-v") ||
          lower.contains("grok-2-vision"))

    val isChat =
      (!isAsr || lower.contains("phi-4-multimodal")) &&
        !isEmbeddingOrRerank &&
        !isImageGen

    val supportsTools =
      isChat &&
        (lower.contains("instruct") ||
          lower.contains("chat") ||
          lower.contains("nemotron") ||
          lower.contains("llama-3.1") ||
          lower.contains("llama-3.2") ||
          lower.contains("llama-3.3") ||
          lower.contains("llama-4") ||
          lower.contains("mistral") ||
          lower.contains("qwen") ||
          lower.contains("deepseek") ||
          lower.contains("gpt") ||
          lower.contains("claude") ||
          lower.contains("gemini")) &&
        !lower.contains("8b-base") &&
        !lower.contains("fuyu") &&
        !lower.contains("paligemma") &&
        !lower.contains("kosmos") &&
        !lower.contains("neva")

    val contextEst =
      when {
        lower.contains("1m") || lower.contains("gemini-2") || lower.contains("gemini-1.5") -> 1_000_000
        lower.contains("256k") -> 256_000
        lower.contains("128k") || lower.contains("llama-3.1") || lower.contains("llama-3.3") || lower.contains("nemotron") || lower.contains("gpt-4o") ->
          128_000
        lower.contains("32k") || lower.contains("qwen") || lower.contains("mistral") -> 32_768
        else -> 16_384
      }

    return NimModelInfo(
      id = id,
      ownedBy = owner,
      supportsStreaming = isChat,
      supportsVision = isVision,
      supportsToolCalling = supportsTools,
      supportsAudioAsr = isAsr,
      isChatCapable = isChat,
      estimatedContextTokens = contextEst,
    )
  }

  /**
   * Detects when a model falsely refuses to analyze an attached image (e.g., text-only model alias
   * or safety/capability hallucination) so fallback vision routing can try another model/provider.
   */
  fun detectVisionRefusal(text: String): Boolean {
    val clean = text.trim().lowercase()
    if (clean.isEmpty()) return true
    val refusalPhrases =
      listOf(
        "i cannot see images",
        "i can't see images",
        "cannot view images",
        "can't view images",
        "unable to view images",
        "unable to process images",
        "cannot process images",
        "do not have the ability to view",
        "don't have the ability to view",
        "do not have vision",
        "as a text-based",
        "as an ai text model",
        "cannot see the picture",
        "can't see the picture",
        "cannot see the image",
        "can't see the image",
        "image is not clear enough to see anything",
        "no image was provided",
        "didn't receive an image",
      )
    return refusalPhrases.any { clean.contains(it) }
  }

  /**
   * Ranks discovered vision models so modern chat-capable VL models (Llama 3.2 Vision,
   * Nemotron VL, Qwen VL, Gemma 3, Phi-4 Multimodal, Pixtral, Llama 4, GPT-4o, Gemini) are chosen over
   * legacy single-turn captioners (fuyu, kosmos, paligemma, neva).
   */
  fun rankVisionModels(models: List<NimModelInfo>, preferredIds: List<String> = emptyList()): List<NimModelInfo> {
    val vision = models.filter { it.supportsVision && it.isChatCapable }
    val preferredSet = preferredIds.filter { it.isNotBlank() }

    fun score(m: NimModelInfo): Int {
      val id = m.id.lowercase()
      val prefBonus = preferredSet.indexOf(m.id).let { idx -> if (idx >= 0) 1000 - idx * 50 else 0 }
      val tier =
        when {
          id.contains("gpt-4o") || id.contains("gpt-4.1") -> 520
          id.contains("nemotron") && id.contains("vl") -> 500
          id.contains("llama-3.2-90b-vision") -> 480
          id.contains("llama-3.2-11b-vision") -> 460
          id.contains("llama-4") -> 450
          id.contains("qwen2.5-vl") || (id.contains("qwen3") && id.contains("vl")) -> 440
          id.contains("-vl") -> 420
          id.contains("phi-4-multimodal") -> 410
          id.contains("gemma-3-27b") -> 400
          id.contains("gemma-3-12b") -> 390
          id.contains("pixtral") || id.contains("mistral-medium-3") || id.contains("mistral-small-3") -> 380
          id.contains("gemini-") -> 375
          id.contains("vision") -> 350
          id.contains("neva") -> 150
          id.contains("fuyu") || id.contains("kosmos") || id.contains("paligemma") -> 50
          else -> 200
        }
      return prefBonus + tier
    }

    return vision.sortedByDescending { score(it) }
  }

  fun isReasoningModel(id: String): Boolean {
    val lower = id.lowercase()
    return lower.contains("thinking") ||
      lower.contains("deepseek-r1") ||
      lower.contains("qwq") ||
      lower.contains("-r1") ||
      lower.contains("reasoner") ||
      lower.contains("gpt-oss") ||
      Regex("""(?:^|/)(?:o1|o3|o4)(?:-|$)""").containsMatchIn(lower)
  }

  fun pickBestVisionModel(models: List<NimModelInfo>, preferredIds: List<String> = emptyList()): NimModelInfo? =
    rankVisionModels(models, preferredIds).firstOrNull()

  fun rankVoiceModels(models: List<NimModelInfo>, preferredId: String = ""): List<NimModelInfo> {
    val audio = models.filter { it.supportsAudioAsr }
    fun score(m: NimModelInfo): Int {
      val id = m.id.lowercase()
      val prefBonus = if (preferredId.isNotBlank() && m.id == preferredId) 2000 else 0
      val tier =
        when {
          id.contains("parakeet-tdt") -> 620
          id.contains("whisper-large-v3-turbo") -> 610
          id.contains("gpt-4o-transcribe") -> 600
          id.contains("distil-whisper-large-v3-en") -> 590
          id.contains("canary-1b") -> 580
          id.contains("gpt-4o-mini-transcribe") -> 570
          id.contains("parakeet") -> 560
          id.contains("whisper-large-v3") -> 550
          id.contains("canary") -> 540
          id.contains("phi-4-multimodal") -> 520
          id.contains("whisper") -> 490
          else -> 400
        }
      return prefBonus + tier
    }
    return audio.sortedByDescending { score(it) }
  }

  fun pickBestVoiceModel(models: List<NimModelInfo>, preferredId: String = ""): NimModelInfo? =
    rankVoiceModels(models, preferredId).firstOrNull()

  data class VoiceModelPreset(
    val id: String,
    val label: String,
    val badge: String,
  )

  fun recommendedVoicePresetsForProvider(
    provider: com.example.data.prefs.AiProviderType,
    discovered: List<NimModelInfo> = emptyList(),
  ): List<VoiceModelPreset> {
    val curated =
      when (provider) {
        com.example.data.prefs.AiProviderType.NVIDIA_NIM ->
          listOf(
            VoiceModelPreset("nvidia/parakeet-tdt-0.6b-v2", "Parakeet TDT v2", "Best EN"),
            VoiceModelPreset("nvidia/canary-1b-asr", "Canary 1B ASR", "EN / Multi"),
            VoiceModelPreset("nvidia/parakeet-ctc-1.1b-asr", "Parakeet CTC 1.1B", "EN Fast"),
            VoiceModelPreset("openai/whisper-large-v3", "Whisper Large v3", "Multi"),
            VoiceModelPreset("microsoft/phi-4-multimodal-instruct", "Phi-4 Multimodal", "Audio+Chat"),
          )
        com.example.data.prefs.AiProviderType.GROQ ->
          listOf(
            VoiceModelPreset("whisper-large-v3-turbo", "Whisper v3 Turbo", "Fast EN"),
            VoiceModelPreset("distil-whisper-large-v3-en", "Distil-Whisper EN", "English Pro"),
            VoiceModelPreset("whisper-large-v3", "Whisper Large v3", "High Acc"),
          )
        com.example.data.prefs.AiProviderType.OPENAI ->
          listOf(
            VoiceModelPreset("gpt-4o-transcribe", "GPT-4o Transcribe", "Best EN"),
            VoiceModelPreset("gpt-4o-mini-transcribe", "GPT-4o Mini Transcribe", "Fast EN"),
            VoiceModelPreset("whisper-1", "Whisper-1", "Classic"),
          )
        com.example.data.prefs.AiProviderType.OPENROUTER,
        com.example.data.prefs.AiProviderType.CUSTOM ->
          listOf(
            VoiceModelPreset("whisper-large-v3-turbo", "Whisper v3 Turbo", "Fast EN"),
            VoiceModelPreset("whisper-1", "Whisper-1", "Standard"),
          )
      }
    val discoveredPresets =
      rankVoiceModels(discovered).map { m ->
        val badge =
          when {
            m.id.contains("en", true) || m.id.contains("parakeet", true) -> "English Pro"
            m.id.contains("turbo", true) -> "Fast"
            else -> "Cloud ASR"
          }
        VoiceModelPreset(m.id, m.shortName, badge)
      }
    return (curated + discoveredPresets).distinctBy { it.id }
  }
}

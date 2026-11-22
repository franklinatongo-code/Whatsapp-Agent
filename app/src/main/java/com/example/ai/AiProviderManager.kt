package com.example.ai

import com.example.agent.HumanReplySanitizer
import com.example.data.prefs.AgentSettings
import com.example.data.prefs.AiProviderType
import com.example.security.SecureCredentialStore
import kotlinx.coroutines.flow.Flow

data class AiRouteTarget(
  val provider: AiProviderType,
  val baseUrl: String,
  val apiKey: String?,
  val model: NimModelInfo,
  val requiresApiKey: Boolean,
)

enum class AiFailureKind {
  MISSING_CREDENTIALS,
  MODEL_INCOMPATIBILITY,
  VISION_REFUSAL,
  RATE_LIMITED,
  NETWORK_TIMEOUT,
  PROVIDER_ERROR,
  MALFORMED_RESPONSE,
}

data class AiExecutionResult(
  val text: String?,
  val toolCalls: List<NimToolCall> = emptyList(),
  val usage: NimUsage = NimUsage(),
  val routeUsed: AiRouteTarget? = null,
  val failureKind: AiFailureKind? = null,
  val errorMessage: String? = null,
)

/**
 * Multi-provider OpenAI-compatible AI router.
 *
 * Resolves the active provider (NVIDIA NIM, OpenAI, Groq, OpenRouter, or Custom/Ollama) plus
 * ordered fallback providers/models. Guarantees a usable model is resolved even if `/v1/models`
 * discovery has not completed yet (e.g. cold launch / flaky network) whenever a model ID or
 * provider default is configured.
 */
class AiProviderManager(
  private val credentialStore: SecureCredentialStore,
  private val nimClient: NvidiaNimClient,
) {

  fun isProviderConfigured(provider: AiProviderType, settings: AgentSettings): Boolean {
    if (!provider.requiresKey) {
      return settings.customProviderBaseUrl.isNotBlank()
    }
    return !credentialStore.getProviderApiKey(provider.name).isNullOrBlank()
  }

  fun resolveBaseUrl(provider: AiProviderType, settings: AgentSettings): String =
    when (provider) {
      AiProviderType.CUSTOM ->
        settings.customProviderBaseUrl.trim().ifBlank { provider.defaultBaseUrl }
      AiProviderType.NVIDIA_NIM -> nimClient.configuredBaseUrl
      else -> provider.defaultBaseUrl
    }

  fun resolvePrimaryChatTarget(
    settings: AgentSettings,
    discoveredModels: List<NimModelInfo>,
  ): AiRouteTarget? = buildTargetsForChat(settings, discoveredModels).firstOrNull()

  fun buildTargetsForChat(
    settings: AgentSettings,
    discoveredModels: List<NimModelInfo>,
  ): List<AiRouteTarget> {
    val targets = mutableListOf<AiRouteTarget>()

    fun addProviderTargets(provider: AiProviderType, preferredModelId: String) {
      if (!isProviderConfigured(provider, settings)) return
      val baseUrl = resolveBaseUrl(provider, settings)
      val apiKey = credentialStore.getProviderApiKey(provider.name)

      val candidateIds = buildList {
        if (preferredModelId.isNotBlank()) add(preferredModelId)
        if (provider == AiProviderType.NVIDIA_NIM) {
          if (settings.selectedModelId.isNotBlank()) add(settings.selectedModelId)
          discoveredModels
            .filter { it.isChatCapable }
            .sortedBy { NvidiaModelCapabilityDetector.isReasoningModel(it.id) }
            .take(3)
            .forEach { add(it.id) }
        } else if (provider == AiProviderType.CUSTOM) {
          if (settings.customProviderModelId.isNotBlank()) add(settings.customProviderModelId)
        } else {
          if (settings.fallbackProviderModelId.isNotBlank() && provider == settings.fallbackProvider) {
            add(settings.fallbackProviderModelId)
          }
          if (provider.defaultPlaceholderModel.isNotBlank()) add(provider.defaultPlaceholderModel)
        }
      }.distinct()

      for (id in candidateIds) {
        val info =
          discoveredModels.firstOrNull { it.id == id }
            ?: NvidiaModelCapabilityDetector.inferFromDiscoveredModel(id, provider.name)
        targets.add(
          AiRouteTarget(
            provider = provider,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = info,
            requiresApiKey = provider.requiresKey,
          )
        )
      }
    }

    val primaryPreferred =
      when (settings.activeProvider) {
        AiProviderType.NVIDIA_NIM -> settings.selectedModelId
        AiProviderType.CUSTOM -> settings.customProviderModelId.ifBlank { settings.selectedModelId }
        else -> settings.selectedModelId.ifBlank { settings.activeProvider.defaultPlaceholderModel }
      }
    addProviderTargets(settings.activeProvider, primaryPreferred)

    if (settings.fallbackProviderEnabled && settings.fallbackProvider != settings.activeProvider) {
      addProviderTargets(
        settings.fallbackProvider,
        settings.fallbackProviderModelId.ifBlank { settings.fallbackProvider.defaultPlaceholderModel },
      )
    }

    // Finally check NVIDIA NIM if another provider was active and failed
    if (settings.activeProvider != AiProviderType.NVIDIA_NIM) {
      addProviderTargets(AiProviderType.NVIDIA_NIM, settings.selectedModelId)
    }

    return targets.distinctBy { "${it.provider.name}:${it.model.id}" }
  }

  fun buildVisionTargets(
    settings: AgentSettings,
    discoveredModels: List<NimModelInfo>,
  ): List<AiRouteTarget> {
    val result = mutableListOf<AiRouteTarget>()

    val primaryVisionIds =
      listOfNotNull(
        settings.visionModelId.takeIf { it.isNotBlank() },
        settings.selectedModelId.takeIf { it.isNotBlank() }?.let {
          val info =
            discoveredModels.firstOrNull { m -> m.id == it }
              ?: NvidiaModelCapabilityDetector.inferFromDiscoveredModel(it, settings.activeProvider.name)
          if (info.supportsVision) it else null
        },
      )

    if (isProviderConfigured(settings.activeProvider, settings)) {
      val baseUrl = resolveBaseUrl(settings.activeProvider, settings)
      val apiKey = credentialStore.getProviderApiKey(settings.activeProvider.name)
      val ranked =
        NvidiaModelCapabilityDetector.rankVisionModels(
          discoveredModels,
          preferredIds = primaryVisionIds,
        )
      val ids =
        (primaryVisionIds + ranked.map { it.id } +
          listOfNotNull(
            settings.customProviderModelId.takeIf { settings.activeProvider == AiProviderType.CUSTOM && it.isNotBlank() }
          ))
          .distinct()
      for (id in ids) {
        val baseModel =
          discoveredModels.firstOrNull { it.id == id }
            ?: NvidiaModelCapabilityDetector.inferFromDiscoveredModel(id, settings.activeProvider.name)
        val model = if (baseModel.supportsVision) baseModel else baseModel.copy(supportsVision = true)
        result.add(AiRouteTarget(settings.activeProvider, baseUrl, apiKey, model, settings.activeProvider.requiresKey))
      }
    }

    if (settings.fallbackProviderEnabled && settings.fallbackProvider != settings.activeProvider &&
      isProviderConfigured(settings.fallbackProvider, settings)
    ) {
      val fb = settings.fallbackProvider
      val fbModelId =
        settings.fallbackProviderModelId.ifBlank {
          when (fb) {
            AiProviderType.OPENAI -> "gpt-4o-mini"
            AiProviderType.GROQ -> "meta-llama/llama-4-scout-17b-16e-instruct"
            AiProviderType.OPENROUTER -> "google/gemini-2.0-flash-001"
            else -> fb.defaultPlaceholderModel
          }
        }
      if (fbModelId.isNotBlank()) {
        val fbInfo = NvidiaModelCapabilityDetector.inferFromDiscoveredModel(fbModelId, fb.name).copy(supportsVision = true)
        result.add(
          AiRouteTarget(
            provider = fb,
            baseUrl = resolveBaseUrl(fb, settings),
            apiKey = credentialStore.getProviderApiKey(fb.name),
            model = fbInfo,
            requiresApiKey = fb.requiresKey,
          )
        )
      }
    }

    return result.distinctBy { "${it.provider.name}:${it.model.id}" }
  }

  fun buildAudioTargets(
    settings: AgentSettings,
    discoveredModels: List<NimModelInfo>,
  ): List<AiRouteTarget> {
    val result = mutableListOf<AiRouteTarget>()

    fun addProviderAudio(provider: AiProviderType, includeExplicitSetting: Boolean) {
      if (!isProviderConfigured(provider, settings)) return
      val baseUrl = resolveBaseUrl(provider, settings)
      val apiKey = credentialStore.getProviderApiKey(provider.name)

      val preferredExplicit =
        if (includeExplicitSetting && settings.audioModelId.isNotBlank()) listOf(settings.audioModelId)
        else emptyList()

      val rankedDiscovered =
        if (provider == settings.activeProvider) {
          NvidiaModelCapabilityDetector.rankVoiceModels(discoveredModels, preferredId = settings.audioModelId).map { it.id }
        } else {
          emptyList()
        }

      val presets =
        NvidiaModelCapabilityDetector.recommendedVoicePresetsForProvider(
          provider = provider,
          discovered = if (provider == settings.activeProvider) discoveredModels else emptyList(),
        ).map { it.id }

      val candidateIds = (preferredExplicit + rankedDiscovered + presets).distinct().take(4)
      for (id in candidateIds) {
        val baseInfo =
          discoveredModels.firstOrNull { it.id == id }
            ?: NvidiaModelCapabilityDetector.inferFromDiscoveredModel(id, provider.name)
        val info = if (baseInfo.supportsAudioAsr) baseInfo else baseInfo.copy(supportsAudioAsr = true)
        result.add(
          AiRouteTarget(
            provider = provider,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = info,
            requiresApiKey = provider.requiresKey,
          )
        )
      }
    }

    addProviderAudio(settings.activeProvider, includeExplicitSetting = true)

    if (settings.fallbackProviderEnabled && settings.fallbackProvider != settings.activeProvider) {
      addProviderAudio(settings.fallbackProvider, includeExplicitSetting = false)
    }

    if (settings.activeProvider != AiProviderType.NVIDIA_NIM) {
      addProviderAudio(AiProviderType.NVIDIA_NIM, includeExplicitSetting = false)
    }

    return result.distinctBy { "${it.provider.name}:${it.model.id}" }
  }

  fun streamOnTarget(
    target: AiRouteTarget,
    messages: List<NimChatMessage>,
    tools: List<NimToolDefinition>,
    temperature: Float,
    maxTokens: Int,
    stream: Boolean,
  ): Flow<NimStreamEvent> =
    nimClient.streamChatCompletion(
      model = target.model,
      messages = messages,
      tools = tools,
      temperature = temperature,
      maxTokens = maxTokens,
      stream = stream,
      baseUrlOverride = target.baseUrl,
      apiKeyOverride = target.apiKey,
      requiresApiKey = target.requiresApiKey,
    )

  suspend fun executeWithFallback(
    targets: List<AiRouteTarget>,
    messages: List<NimChatMessage>,
    tools: List<NimToolDefinition>,
    temperature: Float,
    maxTokens: Int,
    stream: Boolean,
    requireVisionAnswer: Boolean = false,
    onDelta: suspend (String) -> Unit = {},
  ): AiExecutionResult {
    if (targets.isEmpty()) {
      return AiExecutionResult(
        text = null,
        failureKind = AiFailureKind.MISSING_CREDENTIALS,
        errorMessage = "No AI provider key or model is configured yet. Open AI Studio settings to configure a provider.",
      )
    }

    var lastErrorMsg = "AI provider failed to respond."
    var lastKind = AiFailureKind.PROVIDER_ERROR

    val candidateTargets = targets.take(6)
    for (idx in candidateTargets.indices) {
      val target = candidateTargets[idx]
      val hasMoreTargets = idx < candidateTargets.lastIndex
      var completedText: String? = null
      var completedTools: List<NimToolCall> = emptyList()
      var completedUsage = NimUsage()
      var failedEvent: NimStreamEvent.Failed? = null

      streamOnTarget(
        target = target,
        messages = messages,
        tools = tools,
        temperature = temperature,
        maxTokens = maxTokens,
        stream = stream,
      ).collect { ev ->
        when (ev) {
          is NimStreamEvent.Delta -> onDelta(ev.text)
          is NimStreamEvent.Completed -> {
            completedText = ev.fullText
            completedTools = ev.toolCalls
            completedUsage = ev.usage
          }
          is NimStreamEvent.Failed -> failedEvent = ev
        }
      }

      val errEv = failedEvent
      if (errEv != null) {
        val code = errEv.httpCode
        lastErrorMsg = "${target.provider.label} (${target.model.shortName}): ${errEv.message}"
        lastKind =
          when {
            code == 401 || code == 403 -> AiFailureKind.MISSING_CREDENTIALS
            code == 429 -> AiFailureKind.RATE_LIMITED
            code == 400 || code == 404 || code == 422 -> AiFailureKind.MODEL_INCOMPATIBILITY
            errEv.message.contains("timeout", ignoreCase = true) -> AiFailureKind.NETWORK_TIMEOUT
            else -> AiFailureKind.PROVIDER_ERROR
          }
        continue
      }

      val text = HumanReplySanitizer.stripThinkingTags(completedText.orEmpty()).trim()
      if (text.isBlank() && completedTools.isEmpty()) {
        lastKind = AiFailureKind.MALFORMED_RESPONSE
        lastErrorMsg = "${target.provider.label} (${target.model.shortName}) returned an empty response."
        continue
      }

      if (hasMoreTargets && completedTools.isEmpty() && HumanReplySanitizer.isThinkingLeak(text)) {
        lastKind = AiFailureKind.MALFORMED_RESPONSE
        lastErrorMsg = "${target.model.shortName} leaked internal reasoning; routing to backup."
        continue
      }

      if (requireVisionAnswer && completedTools.isEmpty() && NvidiaModelCapabilityDetector.detectVisionRefusal(text)) {
        lastKind = AiFailureKind.VISION_REFUSAL
        lastErrorMsg = "${target.model.shortName} declined to process the visual input; routing to backup."
        continue
      }

      return AiExecutionResult(
        text = text,
        toolCalls = completedTools,
        usage = completedUsage,
        routeUsed = target,
      )
    }

    return AiExecutionResult(
      text = null,
      failureKind = lastKind,
      errorMessage = lastErrorMsg,
    )
  }

  suspend fun discoverModelsForProvider(
    provider: AiProviderType,
    settings: AgentSettings,
    overrideKey: String? = null,
  ): NimDiscoveryResult {
    val effectiveKey = overrideKey?.trim()?.takeIf { it.isNotBlank() } ?: credentialStore.getProviderApiKey(provider.name)
    return nimClient.discoverModels(
      overrideKey = effectiveKey,
      baseUrlOverride = resolveBaseUrl(provider, settings),
    )
  }
}

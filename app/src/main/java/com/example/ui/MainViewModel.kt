package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ClawLinkApplication
import com.example.agent.AgentRuntimeStatus
import com.example.agent.AutomationRulesEngine
import com.example.agent.ManualAiActionResult
import com.example.ai.AiProviderManager
import com.example.ai.NimChatMessage
import com.example.ai.NimModelInfo
import com.example.ai.NimStreamEvent
import com.example.ai.NvidiaModelCapabilityDetector
import com.example.data.BackupManager
import com.example.data.db.AgentLogEntity
import com.example.data.db.AssistantMessageEntity
import com.example.data.db.ChatMessageEntity
import com.example.data.db.ContactMemoryEntity
import com.example.data.db.ConversationEntity
import com.example.data.db.MessageProcessingJobEntity
import com.example.data.db.ScheduledMessageEntity
import com.example.data.prefs.AgentSettings
import com.example.data.prefs.AiProviderType
import com.example.data.prefs.LocalWhisperModel
import com.example.data.prefs.TranscriptionMode
import com.example.media.LocalModelDownloadState
import com.example.security.NvidiaKeyProvider
import com.example.security.PermissionTier
import com.example.service.AutoReplyTileService
import com.example.service.WhatsAppAgentService
import com.example.whatsapp.WASignalProtocolStore
import com.example.whatsapp.WhatsAppConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

data class NimDiscoveryUiState(
  val isLoading: Boolean = false,
  val isValidated: Boolean = false,
  val maskedKeyStatus: String = "Not configured",
  val statusMessage: String? = null,
  val errorMessage: String? = null,
)

data class DiagnosticTestResult(
  val isRunning: Boolean = false,
  val summary: String? = null,
)

data class ChatAiActionResult(
  val isLoading: Boolean = false,
  val title: String = "",
  val text: String = "",
  val canInsertIntoComposer: Boolean = false,
)

data class CatchUpDigestUiState(
  val isLoading: Boolean = false,
  val text: String? = null,
  val error: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
  private val app = application as ClawLinkApplication
  private val repository = app.repository
  private val sessionManager = app.sessionManager
  private val nimClient = app.nimClient
  private val mediaIntelligence = app.mediaIntelligence
  private val agentRuntime = app.agentRuntime
  private val aiProviderManager: AiProviderManager = app.aiProviderManager
  private val businessEngine = app.businessEngine

  val rulesEngine: AutomationRulesEngine = agentRuntime.rulesEngine
  val webEngine = sessionManager.webEngine

  val connectionState: StateFlow<WhatsAppConnectionState> = sessionManager.state
  val isSessionRegistered: StateFlow<Boolean> = sessionManager.isSessionRegistered
  val qrCountdownSeconds: StateFlow<Int> = sessionManager.qrCountdownSeconds
  val lastConnectionError: StateFlow<String?> = sessionManager.lastErrorMessage
  val runtimeStatus: StateFlow<AgentRuntimeStatus> = agentRuntime.runtimeStatus
  val discoveredModels: StateFlow<List<NimModelInfo>> = agentRuntime.discoveredModels
  val localModelState: StateFlow<LocalModelDownloadState> = mediaIntelligence.localModelState

  val settings: StateFlow<AgentSettings> =
    repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AgentSettings())

  val conversations: StateFlow<List<ConversationEntity>> =
    repository.conversations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val logs: StateFlow<List<AgentLogEntity>> =
    repository.recentLogs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val scheduledMessages: StateFlow<List<ScheduledMessageEntity>> =
    repository.scheduledMessages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val assistantMessages: StateFlow<List<AssistantMessageEntity>> =
    repository.assistantMessages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val assistantTyping: StateFlow<Boolean> = app.assistantOrchestrator.isTyping
  val assistantStreamingPreview: StateFlow<String> = app.assistantOrchestrator.streamingPreview
  val assistantToolNote: StateFlow<String?> = app.assistantOrchestrator.activeToolNote

  val totalMessageCount: StateFlow<Int> =
    repository.totalMessageCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

  val signalSessionCount: StateFlow<Int> =
    app.database.signalKeyDao().countByType(WASignalProtocolStore.TYPE_SESSION)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

  val signalPreKeyCount: StateFlow<Int> =
    app.database.signalKeyDao().countByType(WASignalProtocolStore.TYPE_PREKEY)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

  private val _selectedChatJid = MutableStateFlow<String?>(null)
  val selectedChatJid: StateFlow<String?> = _selectedChatJid.asStateFlow()

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  val selectedConversation: StateFlow<ConversationEntity?> =
    _selectedChatJid
      .flatMapLatest { jid -> if (jid == null) flowOf(null) else repository.observeConversation(jid) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  val selectedChatMessages: StateFlow<List<ChatMessageEntity>> =
    _selectedChatJid
      .flatMapLatest { jid -> if (jid == null) flowOf(emptyList()) else repository.observeMessages(jid) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  val selectedChatMemories: StateFlow<List<ContactMemoryEntity>> =
    _selectedChatJid
      .flatMapLatest { jid -> if (jid == null) flowOf(emptyList()) else repository.observeMemories(jid) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  val selectedChatJobs: StateFlow<List<MessageProcessingJobEntity>> =
    _selectedChatJid
      .flatMapLatest { jid -> if (jid == null) flowOf(emptyList()) else repository.observeProcessingJobsForChat(jid) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _nimUiState =
    MutableStateFlow(
      NimDiscoveryUiState(
        maskedKeyStatus = app.credentialStore.getMaskedNvidiaKeySummary(),
      )
    )
  val nimUiState: StateFlow<NimDiscoveryUiState> = _nimUiState.asStateFlow()

  private val _providerKeySummaries = MutableStateFlow(buildProviderKeySummaries())
  val providerKeySummaries: StateFlow<Map<AiProviderType, String>> = _providerKeySummaries.asStateFlow()

  private val _manualAiFeedback = MutableStateFlow<ManualAiActionResult?>(null)
  val manualAiFeedback: StateFlow<ManualAiActionResult?> = _manualAiFeedback.asStateFlow()

  private val _manualAiBusyAction = MutableStateFlow<String?>(null)
  val manualAiBusyAction: StateFlow<String?> = _manualAiBusyAction.asStateFlow()

  private val _isAnalyzingBusinessCrm = MutableStateFlow(false)
  val isAnalyzingBusinessCrm: StateFlow<Boolean> = _isAnalyzingBusinessCrm.asStateFlow()

  private val _diagnosticTestResult = MutableStateFlow(DiagnosticTestResult())
  val diagnosticTestResult: StateFlow<DiagnosticTestResult> = _diagnosticTestResult.asStateFlow()

  private val _chatAiActionResult = MutableStateFlow<ChatAiActionResult?>(null)
  val chatAiActionResult: StateFlow<ChatAiActionResult?> = _chatAiActionResult.asStateFlow()

  private val _backupStatusMessage = MutableStateFlow<String?>(null)
  val backupStatusMessage: StateFlow<String?> = _backupStatusMessage.asStateFlow()

  private val _broadcastStatusMessage = MutableStateFlow<String?>(null)
  val broadcastStatusMessage: StateFlow<String?> = _broadcastStatusMessage.asStateFlow()

  private val _webViewHealthSummary = MutableStateFlow<String?>(null)
  val webViewHealthSummary: StateFlow<String?> = _webViewHealthSummary.asStateFlow()

  private val _visionInspectorResult = MutableStateFlow<String?>(null)
  val visionInspectorResult: StateFlow<String?> = _visionInspectorResult.asStateFlow()

  private val _isDictating = MutableStateFlow(false)
  val isDictating: StateFlow<Boolean> = _isDictating.asStateFlow()

  private val _dictatedText = MutableStateFlow("")
  val dictatedText: StateFlow<String> = _dictatedText.asStateFlow()

  private val _catchUpDigest = MutableStateFlow(CatchUpDigestUiState())
  val catchUpDigest: StateFlow<CatchUpDigestUiState> = _catchUpDigest.asStateFlow()

  private val _tileStatusMessage = MutableStateFlow<String?>(null)
  val tileStatusMessage: StateFlow<String?> = _tileStatusMessage.asStateFlow()

  private var speechRecognizer: SpeechRecognizer? = null
  private var tts: TextToSpeech? = null
  private var ttsReady = false

  init {
    mediaIntelligence.refreshLocalModelState(settings.value.localWhisperModel)
    viewModelScope.launch {
      val s = repository.getCurrentSettings()
      if (s.autoConnectOnLaunch && app.credentialStore.getOrCreateCredentials().isRegistered) {
        WhatsAppAgentService.startAndConnect(app)
      }
      if (app.credentialStore.getNvidiaApiKey() != null) {
        validateAndDiscoverModels(null)
      }
    }
  }

  fun connectWhatsApp() {
    WhatsAppAgentService.startAndConnect(app)
  }

  fun disconnectWhatsApp() {
    WhatsAppAgentService.stopAndDisconnect(app)
  }

  fun reconnectWhatsApp() {
    sessionManager.reconnectNow()
  }

  fun relinkWhatsApp() {
    sessionManager.relinkDevice()
  }

  fun requestPhonePairingCode(phone: String) {
    sessionManager.requestPhonePairingCode(phone)
  }

  fun replenishPreKeys() {
    sessionManager.manualUploadPreKeys()
  }

  fun selectChat(chatJid: String?) {
    _selectedChatJid.value = chatJid
    _manualAiFeedback.value = null
    if (chatJid != null) {
      viewModelScope.launch { repository.clearUnread(chatJid) }
    }
  }

  private fun buildProviderKeySummaries(): Map<AiProviderType, String> =
    AiProviderType.entries.associateWith { provider ->
      if (!provider.requiresKey) "Local / Custom URL"
      else app.credentialStore.getMaskedProviderKeySummary(provider.name)
    }

  fun refreshProviderKeySummaries() {
    _providerKeySummaries.value = buildProviderKeySummaries()
  }

  fun saveProviderApiKey(provider: AiProviderType, rawKey: String) {
    if (provider == AiProviderType.NVIDIA_NIM) {
      saveAndValidateNvidiaKey(rawKey)
      return
    }
    val clean = rawKey.trim()
    if (clean.isBlank()) {
      _nimUiState.value = _nimUiState.value.copy(errorMessage = "Enter a valid ${provider.label} API key.")
      return
    }
    app.credentialStore.saveProviderApiKey(provider.name, clean)
    refreshProviderKeySummaries()
    testProviderConnection(provider, clean)
  }

  fun clearProviderApiKey(provider: AiProviderType) {
    if (provider == AiProviderType.NVIDIA_NIM) {
      clearNvidiaKey()
      return
    }
    app.credentialStore.saveProviderApiKey(provider.name, "")
    refreshProviderKeySummaries()
    _nimUiState.value =
      _nimUiState.value.copy(
        statusMessage = "${provider.label} key removed from encrypted storage.",
        errorMessage = null,
      )
  }

  fun testProviderConnection(provider: AiProviderType, overrideKey: String? = null) {
    if (provider == AiProviderType.NVIDIA_NIM) {
      validateAndDiscoverModels(overrideKey)
      return
    }
    viewModelScope.launch {
      val s = repository.getCurrentSettings()
      _nimUiState.value =
        _nimUiState.value.copy(
          isLoading = true,
          errorMessage = null,
          statusMessage = "Contacting ${provider.label} (${aiProviderManager.resolveBaseUrl(provider, s)})…",
        )
      val res = aiProviderManager.discoverModelsForProvider(provider, s, overrideKey)
      refreshProviderKeySummaries()
      if (res.validated) {
        if (s.activeProvider == provider && res.models.isNotEmpty()) {
          agentRuntime.updateDiscoveredModels(res.models)
        }
        _nimUiState.value =
          _nimUiState.value.copy(
            isLoading = false,
            isValidated = true,
            statusMessage = "${provider.label} connected! Found ${res.models.size} model(s).",
            errorMessage = null,
          )
      } else {
        _nimUiState.value =
          _nimUiState.value.copy(
            isLoading = false,
            errorMessage = res.errorMessage ?: "Could not connect to ${provider.label}",
            statusMessage = null,
          )
      }
    }
  }

  fun saveAndValidateNvidiaKey(rawKey: String) {
    val clean = rawKey.trim()
    if (clean.isBlank()) {
      _nimUiState.value = _nimUiState.value.copy(errorMessage = "Enter a valid NVIDIA API key.")
      return
    }
    app.credentialStore.saveNvidiaApiKey(clean)
    NvidiaKeyProvider.setRuntimeKeyHint(clean)
    refreshProviderKeySummaries()
    validateAndDiscoverModels(clean)
  }

  fun clearNvidiaKey() {
    app.credentialStore.clearNvidiaApiKey()
    NvidiaKeyProvider.setRuntimeKeyHint(null)
    agentRuntime.updateDiscoveredModels(emptyList())
    refreshProviderKeySummaries()
    _nimUiState.value =
      NimDiscoveryUiState(
        isLoading = false,
        isValidated = false,
        maskedKeyStatus = app.credentialStore.getMaskedNvidiaKeySummary(),
        statusMessage = "NVIDIA API key removed from encrypted storage.",
      )
    viewModelScope.launch { repository.log("INFO", "SECURITY", "Cleared stored NVIDIA API key") }
  }

  fun validateAndDiscoverModels(overrideKey: String? = null) {
    viewModelScope.launch {
      _nimUiState.value =
        _nimUiState.value.copy(
          isLoading = true,
          errorMessage = null,
          statusMessage = "Contacting integrate.api.nvidia.com/v1/models…",
          maskedKeyStatus = app.credentialStore.getMaskedNvidiaKeySummary(),
        )
      val res = nimClient.discoverModels(overrideKey)
      if (res.validated) {
        agentRuntime.updateDiscoveredModels(res.models)
        val current = repository.getCurrentSettings()
        val needsChat = current.selectedModelId.isBlank() || res.models.none { it.id == current.selectedModelId }
        val needsVision = current.visionModelId.isBlank() || res.models.none { it.id == current.visionModelId && it.supportsVision }
        val needsAudio = current.audioModelId.isBlank() || res.models.none { it.id == current.audioModelId && it.supportsAudioAsr }
        if (needsChat || needsVision || needsAudio) {
          val nonThinkingChat =
            res.models.filter { it.isChatCapable && !NvidiaModelCapabilityDetector.isReasoningModel(it.id) }
          val chatPool = nonThinkingChat.ifEmpty { res.models.filter { it.isChatCapable } }
          val bestChat =
            chatPool.firstOrNull { it.supportsToolCalling && it.id.contains("llama-3.3", true) }?.id
              ?: chatPool.firstOrNull { it.supportsVision }?.id
              ?: chatPool.firstOrNull { it.supportsToolCalling }?.id
              ?: chatPool.firstOrNull()?.id.orEmpty()
          val bestVision = NvidiaModelCapabilityDetector.pickBestVisionModel(res.models)?.id.orEmpty()
          val bestAsr = NvidiaModelCapabilityDetector.pickBestVoiceModel(res.models)?.id.orEmpty()
          repository.preferences.updateSettings {
            it.copy(
              selectedModelId = if (needsChat) bestChat else it.selectedModelId,
              visionModelId = if (needsVision) bestVision else it.visionModelId,
              audioModelId = if (needsAudio) bestAsr else it.audioModelId,
            )
          }
        }
        _nimUiState.value =
          NimDiscoveryUiState(
            isLoading = false,
            isValidated = true,
            maskedKeyStatus = app.credentialStore.getMaskedNvidiaKeySummary(),
            statusMessage = "Ready! Found ${res.models.size} NVIDIA AI models.",
          )
        repository.log("INFO", "NVIDIA_NIM", "Validated API key and discovered ${res.models.size} models")
      } else {
        _nimUiState.value =
          _nimUiState.value.copy(
            isLoading = false,
            isValidated = false,
            maskedKeyStatus = app.credentialStore.getMaskedNvidiaKeySummary(),
            errorMessage = res.errorMessage ?: "Failed to validate NVIDIA NIM credentials",
            statusMessage = null,
          )
        repository.log("ERROR", "NVIDIA_NIM", "Model discovery failed: ${res.errorMessage}")
      }
    }
  }

  fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
    viewModelScope.launch {
      val updated = transform(repository.getCurrentSettings())
      repository.preferences.updateSettings { updated }
      if (updated.transcriptionMode != TranscriptionMode.LOCAL && !updated.localSpeechEnabled) {
        releaseSpeechRecognizer()
      }
      mediaIntelligence.refreshLocalModelState(updated.localWhisperModel)
      AutoReplyTileService.requestRefresh(app)
      WhatsAppAgentService.refreshNotification(app)
    }
  }

  fun runCatchUpDigest() {
    viewModelScope.launch {
      _catchUpDigest.value = CatchUpDigestUiState(isLoading = true)
      val res = agentRuntime.generateUnreadCatchUpDigest()
      _catchUpDigest.value =
        res.fold(
          onSuccess = { CatchUpDigestUiState(isLoading = false, text = it) },
          onFailure = { CatchUpDigestUiState(isLoading = false, error = it.message ?: "Could not generate catch-up digest.") },
        )
    }
  }

  fun clearCatchUpDigest() {
    _catchUpDigest.value = CatchUpDigestUiState()
  }

  fun scheduleWhatsAppMessage(
    chatJid: String,
    displayName: String,
    messageText: String,
    delayMinutes: Long,
  ) {
    val cleanMsg = messageText.trim()
    if (chatJid.isBlank() || cleanMsg.isBlank()) return
    val clampedMinutes = delayMinutes.coerceIn(1L, 43_200L)
    val targetMs = System.currentTimeMillis() + clampedMinutes * 60_000L
    viewModelScope.launch {
      repository.scheduleWhatsAppMessage(
        chatJid = chatJid,
        displayName = displayName,
        messageText = cleanMsg,
        scheduledAtMs = targetMs,
      )
      repository.log("INFO", "WHATSAPP", "Scheduled WhatsApp message to $displayName in ${clampedMinutes}m")
    }
  }

  fun cancelScheduledMessage(id: String) {
    viewModelScope.launch {
      repository.deleteScheduledMessage(id)
    }
  }

  fun requestAddQuickTile(context: Context) {
    AutoReplyTileService.requestAddTileToQuickSettings(context) { message ->
      _tileStatusMessage.value = message
    }
  }

  fun clearTileStatusMessage() {
    _tileStatusMessage.value = null
  }

  fun downloadLocalWhisperModel(model: LocalWhisperModel) {
    viewModelScope.launch {
      repository.log("INFO", "MEDIA", "Starting optional local speech model download: ${model.displayName}")
      val res = mediaIntelligence.downloadLocalWhisperModel(model)
      if (res.isSuccess) {
        repository.log("INFO", "MEDIA", "Downloaded ${model.displayName} locally")
      } else {
        repository.log("ERROR", "MEDIA", "Download failed: ${res.exceptionOrNull()?.message}")
      }
    }
  }

  fun disableAndReleaseLocalTranscription() {
    releaseSpeechRecognizer()
    mediaIntelligence.releaseAndDeleteLocalModels()
    updateSettings {
      it.copy(
        transcriptionMode = TranscriptionMode.OFF,
        autoTranscribeVoiceNotes = false,
        localSpeechEnabled = false,
      )
    }
    viewModelScope.launch {
      repository.log("INFO", "MEDIA", "Removed offline speech model and set voice transcription OFF")
    }
  }

  fun speakText(text: String) {
    if (text.isBlank()) return
    if (tts == null) {
      tts =
        TextToSpeech(app) { status ->
          if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.language = Locale.getDefault()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "clawlink_tts")
          }
        }
    } else if (ttsReady) {
      tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "clawlink_tts")
    }
  }

  fun stopSpeaking() {
    runCatching { tts?.stop() }
  }

  fun triggerHapticPulse(durationMs: Long = 120L) {
    if (!settings.value.hapticFeedbackEnabled) return
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
      } else {
        @Suppress("DEPRECATION")
        val v = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        @Suppress("DEPRECATION")
        v?.vibrate(durationMs)
      }
    }
  }

  fun startVoiceDictation() {
    if (!SpeechRecognizer.isRecognitionAvailable(app)) {
      viewModelScope.launch { repository.log("WARN", "MEDIA", "On-device Android SpeechRecognizer unavailable") }
      return
    }
    releaseSpeechRecognizer()
    val recognizer = SpeechRecognizer.createSpeechRecognizer(app)
    speechRecognizer = recognizer
    _isDictating.value = true
    recognizer.setRecognitionListener(
      object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { _isDictating.value = false }
        override fun onError(error: Int) {
          _isDictating.value = false
          releaseSpeechRecognizer()
        }
        override fun onResults(results: Bundle?) {
          _isDictating.value = false
          val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
          val top = matches?.firstOrNull().orEmpty()
          if (top.isNotBlank()) {
            _dictatedText.value = top
          }
          releaseSpeechRecognizer()
        }
        override fun onPartialResults(partialResults: Bundle?) {
          val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
          val partial = matches?.firstOrNull().orEmpty()
          if (partial.isNotBlank()) _dictatedText.value = partial
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
      }
    )
    val intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      }
    recognizer.startListening(intent)
  }

  fun consumeDictatedText() {
    _dictatedText.value = ""
  }

  private fun releaseSpeechRecognizer() {
    runCatching {
      speechRecognizer?.stopListening()
      speechRecognizer?.destroy()
    }
    speechRecognizer = null
    _isDictating.value = false
  }

  fun sendManualMessage(
    chatJid: String,
    text: String,
    quotedMessageId: String? = null,
    quotedPreview: String? = null,
  ) {
    val clean = text.trim()
    if (clean.isBlank()) return
    viewModelScope.launch(Dispatchers.IO) {
      val meJid = app.credentialStore.getOrCreateCredentials().meJid
      val cleanQuoteId = quotedMessageId?.takeIf { it.isNotBlank() }
      val cleanQuotePreview = quotedPreview?.takeIf { it.isNotBlank() }?.take(140)
      val sendRes =
        sessionManager.messaging.sendTextMessageDetailed(
          chatJid = chatJid,
          text = clean,
          meJid = meJid,
          quotedMessageId = cleanQuoteId,
          quotedText = cleanQuotePreview,
        )
      val sentId = sendRes.sentIdOrNull()
      val msgId = sentId ?: "manual_${System.nanoTime()}"
      val status =
        when (sendRes) {
          is com.example.whatsapp.WhatsAppSendResult.Confirmed -> "SENT"
          is com.example.whatsapp.WhatsAppSendResult.SentUnconfirmed -> "SENT_UNCONFIRMED"
          is com.example.whatsapp.WhatsAppSendResult.UnknownTimeout -> "UNKNOWN"
          is com.example.whatsapp.WhatsAppSendResult.Failed -> "FAILED"
        }
      repository.recordMessage(
        ChatMessageEntity(
          id = msgId,
          chatJid = chatJid,
          senderJid = meJid ?: "me@local",
          senderName = "Me",
          fromMe = true,
          timestamp = System.currentTimeMillis(),
          messageType = "TEXT",
          textContent = clean,
          quotedMessageId = cleanQuoteId,
          quotedPreview = cleanQuotePreview,
          deliveryStatus = status,
        )
      )
      if (cleanQuoteId != null) {
        val job = repository.getJobForIncomingMessage(cleanQuoteId)
        if (job != null && job.stage in setOf("DETECTED", "PERSISTED", "PARSED", "CONTEXT_LOADED", "FAILED_RETRYABLE")) {
          repository.updateProcessingJobStage(
            incomingMessageId = cleanQuoteId,
            chatJid = chatJid,
            stage = "CONFIRMED",
            attemptCount = job.attemptCount,
            outgoingMessageId = msgId,
            errorMessage = "Owner replied directly in-app",
          )
        }
      }
      if (sentId == null) {
        repository.log("WARN", "WHATSAPP", "Message saved locally; WhatsApp session/Signal channel not ready for live send to $chatJid")
      }
    }
  }

  fun sendBroadcastMessage(targetJids: List<String>, text: String) {
    val clean = text.trim()
    if (clean.isBlank() || targetJids.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
      _broadcastStatusMessage.value = "Sending to ${targetJids.size} chat(s)…"
      val meJid = app.credentialStore.getOrCreateCredentials().meJid
      var sentOk = 0
      targetJids.forEach { jid ->
        val sentId = sessionManager.messaging.sendTextMessage(jid, clean, meJid)
        if (sentId != null) sentOk++
        repository.recordMessage(
          ChatMessageEntity(
            id = sentId ?: "bc_${System.nanoTime()}",
            chatJid = jid,
            senderJid = meJid ?: "me@local",
            senderName = "You",
            fromMe = true,
            timestamp = System.currentTimeMillis(),
            messageType = "TEXT",
            textContent = clean,
            deliveryStatus = if (sentId != null) "SENT" else "FAILED",
          )
        )
      }
      _broadcastStatusMessage.value = "Broadcast finished: $sentOk/${targetJids.size} sent over Signal."
      repository.log("INFO", "WHATSAPP", "Broadcast to ${targetJids.size} chats ($sentOk live sent)")
    }
  }

  fun summarizeChat(chatJid: String) {
    viewModelScope.launch {
      _chatAiActionResult.value = ChatAiActionResult(isLoading = true, title = "AI Chat Summary")
      val res = agentRuntime.summarizeConversation(chatJid)
      _chatAiActionResult.value =
        res.fold(
          onSuccess = { ChatAiActionResult(false, "AI Chat Summary", it, canInsertIntoComposer = false) },
          onFailure = { ChatAiActionResult(false, "Summary Unavailable", it.message ?: "Error", false) },
        )
    }
  }

  fun draftSmartReply(chatJid: String, instruction: String = "") {
    viewModelScope.launch {
      _chatAiActionResult.value = ChatAiActionResult(isLoading = true, title = "AI Smart Reply Draft")
      val res = agentRuntime.draftSmartReply(chatJid, instruction)
      _chatAiActionResult.value =
        res.fold(
          onSuccess = { ChatAiActionResult(false, "AI Smart Reply Draft", it, canInsertIntoComposer = true) },
          onFailure = { ChatAiActionResult(false, "Draft Unavailable", it.message ?: "Error", false) },
        )
    }
  }

  fun translateChatText(text: String, targetLanguage: String) {
    viewModelScope.launch {
      _chatAiActionResult.value = ChatAiActionResult(isLoading = true, title = "Translation ($targetLanguage)")
      val res = agentRuntime.translateText(text, targetLanguage)
      _chatAiActionResult.value =
        res.fold(
          onSuccess = { ChatAiActionResult(false, "Translation ($targetLanguage)", it, canInsertIntoComposer = true) },
          onFailure = { ChatAiActionResult(false, "Translation Error", it.message ?: "Error", false) },
        )
    }
  }

  fun inspectChatMediaMessage(message: ChatMessageEntity) {
    val path = message.mediaLocalPath
    if (path.isNullOrBlank()) {
      _chatAiActionResult.value =
        ChatAiActionResult(false, "Media Not Saved", "No local image, sticker, or document file was attached to this message.", false)
      return
    }
    val file = File(path)
    if (!file.exists()) {
      _chatAiActionResult.value =
        ChatAiActionResult(false, "File Unavailable", "Attachment file could not be found on disk.", false)
      return
    }
    val isSticker = message.messageType == "STICKER"
    val title = if (isSticker) "AI Sticker Meaning" else "AI Media Insight"
    viewModelScope.launch {
      _chatAiActionResult.value = ChatAiActionResult(isLoading = true, title = title)
      val res = agentRuntime.inspectMediaFileWithAi(file, message.mediaMimeType, isSticker)
      _chatAiActionResult.value =
        res.fold(
          onSuccess = { ChatAiActionResult(false, title, it, canInsertIntoComposer = false) },
          onFailure = { ChatAiActionResult(false, title, it.message ?: "Error analyzing media", false) },
        )
    }
  }

  fun clearChatAiActionResult() {
    _chatAiActionResult.value = null
  }

  fun replyRight(chatJid: String) {
    if (_manualAiBusyAction.value != null) return
    _manualAiBusyAction.value = "REPLY_RIGHT"
    _manualAiFeedback.value = null
    viewModelScope.launch(Dispatchers.IO) {
      try {
        triggerHapticPulse(35L)
        val res = agentRuntime.replyRight(chatJid)
        _manualAiFeedback.value = res
        if (res.success) {
          triggerHapticPulse(65L)
        }
      } finally {
        _manualAiBusyAction.value = null
      }
    }
  }

  fun keepReplying(chatJid: String) {
    if (_manualAiBusyAction.value != null) return
    _manualAiBusyAction.value = "KEEP_REPLYING"
    _manualAiFeedback.value = null
    viewModelScope.launch(Dispatchers.IO) {
      try {
        triggerHapticPulse(35L)
        val res = agentRuntime.keepReplying(chatJid)
        _manualAiFeedback.value = res
        if (res.success) {
          triggerHapticPulse(65L)
        }
      } finally {
        _manualAiBusyAction.value = null
      }
    }
  }

  fun clearManualAiFeedback() {
    _manualAiFeedback.value = null
  }

  fun enableGroupChatting() {
    updateSettings { it.copy(autoReplyGroups = true) }
    _manualAiFeedback.value = null
  }

  fun refreshBusinessCrmInsights(targetChatJid: String? = null) {
    if (_isAnalyzingBusinessCrm.value) return
    _isAnalyzingBusinessCrm.value = true
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val s = repository.getCurrentSettings()
        val list =
          if (!targetChatJid.isNullOrBlank()) {
            listOfNotNull(repository.getConversation(targetChatJid))
          } else {
            repository.getAllConversations().take(20)
          }
        for (conv in list) {
          val msgs = repository.getRecentMessages(conv.chatJid, limit = 16)
          businessEngine.analyzeConversationWithAi(
            conversation = conv,
            messages = msgs,
            settings = s,
            discoveredModels = discoveredModels.value,
            aiProviderManager = aiProviderManager,
            repository = repository,
          )
        }
        repository.log("INFO", "AGENT", "Refreshed Business CRM insights for ${list.size} chat(s)")
      } catch (t: Throwable) {
        repository.log("ERROR", "AGENT", "CRM refresh failed: ${t.message}")
      } finally {
        _isAnalyzingBusinessCrm.value = false
      }
    }
  }

  fun shareChatTranscript(context: Context, conversation: ConversationEntity, messages: List<ChatMessageEntity>) {
    val body = BackupManager.formatChatAsReadableText(conversation.displayName, messages)
    val intent =
      Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "WhatsApp Chat — ${conversation.displayName}")
        putExtra(Intent.EXTRA_TEXT, body)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    context.startActivity(Intent.createChooser(intent, "Export Chat").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  fun exportBackupToUri(uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      _backupStatusMessage.value = "Exporting JSON backup…"
      val res = runCatching { BackupManager.writeBackupToUri(app, uri, repository) }
      _backupStatusMessage.value =
        res.fold(
          onSuccess = { "Backup saved: ${it.conversationsCount} chats, ${it.messagesCount} messages, ${it.memoriesCount} memories." },
          onFailure = { "Export failed: ${it.message}" },
        )
    }
  }

  fun importBackupFromUri(uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      _backupStatusMessage.value = "Importing JSON backup…"
      val res = runCatching { BackupManager.readBackupFromUri(app, uri, repository) }
      _backupStatusMessage.value =
        res.fold(
          onSuccess = { "Restored ${it.conversationsCount} chats, ${it.messagesCount} messages, ${it.memoriesCount} memories." },
          onFailure = { "Restore failed: ${it.message}" },
        )
    }
  }

  fun sendAttachmentFromUri(chatJid: String, uri: Uri, mimeTypeHint: String, displayLabel: String) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val bytes =
          app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@launch
        val dir = File(app.filesDir, "wa_outbound").apply { mkdirs() }
        val ext = if (mimeTypeHint.startsWith("image/")) "jpg" else "bin"
        val localFile = File(dir, "out_${System.currentTimeMillis()}.$ext").apply { writeBytes(bytes) }
        val kind = if (mimeTypeHint.startsWith("image/")) "IMAGE" else "DOCUMENT"
        val meJid = app.credentialStore.getOrCreateCredentials().meJid

        val sentId =
          sessionManager.messaging.sendMediaBytes(
            chatJid = chatJid,
            bytes = bytes,
            mimeType = mimeTypeHint,
            fileName = displayLabel,
            caption = displayLabel,
          )
        val msgId = sentId ?: "media_${System.nanoTime()}"
        val summary = "[$kind] $displayLabel (${bytes.size / 1024} KB)"
        repository.recordMessage(
          ChatMessageEntity(
            id = msgId,
            chatJid = chatJid,
            senderJid = meJid ?: "me@local",
            senderName = "You",
            fromMe = true,
            timestamp = System.currentTimeMillis(),
            messageType = kind,
            textContent = summary,
            mediaMimeType = mimeTypeHint,
            mediaLocalPath = localFile.absolutePath,
            deliveryStatus = if (sentId != null) "SENT" else "FAILED",
          )
        )
      } catch (t: Throwable) {
        repository.log("ERROR", "MEDIA", "Failed preparing attachment: ${t.message}")
      }
    }
  }

  fun updateConversationConfig(
    conversation: ConversationEntity,
    autoReply: Boolean = conversation.autoReplyEnabled,
    tier: PermissionTier = PermissionTier.fromName(conversation.permissionTier),
    customInstructions: String = conversation.customInstructions,
    preferredLanguage: String = conversation.preferredLanguage,
    responseStyle: String = conversation.responseStyle,
    recentContextCountOverride: Int? = conversation.recentContextCountOverride,
    contextModeOverride: String? = conversation.contextModeOverride,
  ) {
    viewModelScope.launch {
      repository.updateConversation(
        conversation.copy(
          autoReplyEnabled = autoReply,
          permissionTier = tier.name,
          customInstructions = customInstructions,
          preferredLanguage = preferredLanguage,
          responseStyle = responseStyle,
          recentContextCountOverride = recentContextCountOverride?.coerceIn(1, 50),
          contextModeOverride = contextModeOverride?.takeIf { it.isNotBlank() },
        )
      )
    }
  }

  fun addContactMemory(chatJid: String, key: String, value: String, category: String = "FACT") {
    viewModelScope.launch { repository.saveContactMemory(chatJid, key, value, category) }
  }

  fun deleteContactMemory(id: Long) {
    viewModelScope.launch { repository.deleteMemory(id) }
  }

  fun clearContactMemories(chatJid: String) {
    viewModelScope.launch {
      repository.clearMemoriesForChat(chatJid)
      repository.updateSummary(chatJid, "")
    }
  }

  fun runLiveAiDiagnosticTest() {
    viewModelScope.launch {
      val s = repository.getCurrentSettings()
      val targets = aiProviderManager.buildTargetsForChat(s, discoveredModels.value)
      if (targets.isEmpty()) {
        _diagnosticTestResult.value =
          DiagnosticTestResult(false, "No AI provider key or model configured yet. Set up a provider key in the AI tab first.")
        return@launch
      }
      val primary = targets.first()
      _diagnosticTestResult.value =
        DiagnosticTestResult(true, "Sending live inference check to ${primary.provider.label} (${primary.model.id})…")
      val start = System.currentTimeMillis()
      val exec =
        aiProviderManager.executeWithFallback(
          targets = targets,
          messages =
            listOf(
              NimChatMessage(
                role = "user",
                content = "Reply in one short sentence confirming ClawLink WA AI inference is online.",
              )
            ),
          tools = emptyList(),
          temperature = 0.2f,
          maxTokens = 64,
          stream = s.streamingEnabled,
        )
      val ms = System.currentTimeMillis() - start
      val summary =
        if (!exec.text.isNullOrBlank()) {
          val routeLabel = exec.routeUsed?.let { "${it.provider.label}/${it.model.shortName}" } ?: primary.model.shortName
          repository.log("INFO", "NVIDIA_NIM", "Live diagnostic passed on $routeLabel in ${ms}ms")
          "OK ($routeLabel, ${ms}ms, ${exec.usage.totalTokens} tokens): ${exec.text.trim()}"
        } else {
          val err = exec.errorMessage ?: "No response received."
          repository.log("ERROR", "NVIDIA_NIM", "Live diagnostic failed: $err")
          "Failed: $err"
        }
      _diagnosticTestResult.value = DiagnosticTestResult(false, summary)
    }
  }

  fun runWebViewDiagnostics() {
    viewModelScope.launch {
      _webViewHealthSummary.value = "Checking Chromium & WhatsApp Web modules…"
      val res = webEngine.runWebViewDiagnostics()
      _webViewHealthSummary.value = res
      repository.log("INFO", "WHATSAPP", "Health check: $res")
    }
  }

  fun inspectPickedMediaUri(uri: Uri, mimeHint: String, isSticker: Boolean = false) {
    viewModelScope.launch(Dispatchers.IO) {
      _visionInspectorResult.value = "Analyzing media with NVIDIA VL model…"
      try {
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null || bytes.isEmpty()) {
          _visionInspectorResult.value = "Could not read selected file."
          return@launch
        }
        val tmpDir = File(app.cacheDir, "vision_inspect").apply { mkdirs() }
        val ext =
          when {
            mimeHint.contains("pdf", true) -> "pdf"
            mimeHint.contains("webp", true) -> "webp"
            mimeHint.contains("png", true) -> "png"
            mimeHint.startsWith("text/", true) -> "txt"
            else -> "jpg"
          }
        val tmp = File(tmpDir, "inspect_${System.currentTimeMillis()}.$ext").apply { writeBytes(bytes) }
        val res = agentRuntime.inspectMediaFileWithAi(tmp, mimeHint, isSticker)
        _visionInspectorResult.value = res.getOrElse { "Error: ${it.message}" }
        tmp.delete()
      } catch (t: Throwable) {
        _visionInspectorResult.value = "Error: ${t.message}"
      }
    }
  }

  fun clearLogs() {
    viewModelScope.launch { repository.clearLogs() }
  }

  fun sendTextToAi(text: String, imageUri: Uri? = null, fileUri: Uri? = null) {
    if (text.isBlank() && imageUri == null && fileUri == null) return
    viewModelScope.launch(Dispatchers.IO) {
      val mediaDir = File(app.filesDir, "assistant_media").apply { mkdirs() }
      val copiedImage = imageUri?.let { copyUriToAssistantMedia(it, mediaDir, defaultPrefix = "img") }
      val copiedAttachment = fileUri?.let { copyUriToAssistantMedia(it, mediaDir, defaultPrefix = "file") }
      app.assistantOrchestrator.sendMessage(
        rawText = text,
        imageFile = copiedImage?.file,
        attachmentFile = copiedAttachment?.file,
        attachmentName = copiedAttachment?.displayName,
        attachmentMimeType = copiedAttachment?.mimeType,
      )
    }
  }

  fun toggleAssistantReaction(messageId: String, emoji: String) {
    viewModelScope.launch { repository.toggleAssistantReaction(messageId, emoji) }
  }

  fun deleteAssistantMessage(messageId: String) {
    viewModelScope.launch { repository.deleteAssistantMessage(messageId) }
  }

  fun clearAssistantChat() {
    viewModelScope.launch { repository.clearAssistantMessages() }
  }

  private data class CopiedAttachment(
    val file: File,
    val displayName: String,
    val mimeType: String?,
  )

  private fun copyUriToAssistantMedia(uri: Uri, targetDir: File, defaultPrefix: String): CopiedAttachment? {
    return runCatching {
      val mimeType = app.contentResolver.getType(uri)
      val displayName = resolveUriDisplayName(uri) ?: "${defaultPrefix}_${System.currentTimeMillis()}"
      val extFromName = displayName.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= 8 }
      val ext =
        extFromName
          ?: when {
            mimeType?.contains("png", true) == true -> "png"
            mimeType?.contains("webp", true) == true -> "webp"
            mimeType?.contains("pdf", true) == true -> "pdf"
            mimeType?.contains("audio", true) == true -> "m4a"
            mimeType?.startsWith("text/", true) == true -> "txt"
            mimeType?.startsWith("image/", true) == true -> "jpg"
            else -> "bin"
          }
      val safeBase = displayName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_").take(40).ifBlank { defaultPrefix }
      val destFile = File(targetDir, "${System.currentTimeMillis()}_${safeBase}.$ext")
      app.contentResolver.openInputStream(uri)?.use { input ->
        destFile.outputStream().use { output -> input.copyTo(output) }
      } ?: return null
      CopiedAttachment(destFile, displayName, mimeType)
    }.getOrNull()
  }

  private fun resolveUriDisplayName(uri: Uri): String? {
    return runCatching {
      app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (idx >= 0) cursor.getString(idx) else null
        } else {
          null
        }
      }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
  }

  override fun onCleared() {
    releaseSpeechRecognizer()
    runCatching { tts?.shutdown() }
    super.onCleared()
  }
}

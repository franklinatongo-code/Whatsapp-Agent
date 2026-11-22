package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ai.NimModelInfo
import com.example.ai.NvidiaModelCapabilityDetector
import com.example.data.prefs.AgentSettings
import com.example.data.prefs.AiProviderType
import com.example.data.prefs.LocalWhisperModel
import com.example.data.prefs.TranscriptionMode
import com.example.media.LocalModelDownloadState
import com.example.ui.NimDiscoveryUiState
import com.example.ui.components.BusinessModeStudioCard
import com.example.ui.components.ContextEngineCard
import com.example.ui.components.MultiProviderAiCard
import kotlin.math.roundToInt

private enum class ModelFilter(val label: String) {
  ALL("All"),
  VISION("Vision"),
  TOOLS("Tools"),
  AUDIO("Audio"),
}

private data class AiPersonaPreset(
  val title: String,
  val style: String,
  val instructions: String,
)

private val PRESETS =
  listOf(
    AiPersonaPreset(
      title = "Natural Human",
      style = AgentSettings.DEFAULT_RESPONSE_STYLE,
      instructions = AgentSettings.DEFAULT_HUMAN_SYSTEM_PROMPT,
    ),
    AiPersonaPreset(
      title = "Short & Crisp",
      style = "Ultra Brief",
      instructions = "Answer WhatsApp messages in 1-2 short sentences. No fluff.",
    ),
    AiPersonaPreset(
      title = "Business Formal",
      style = "Polite & Professional",
      instructions = "Reply professionally, politely, and clearly for client and work WhatsApp messages.",
    ),
    AiPersonaPreset(
      title = "Study Tutor",
      style = "Clear Teacher",
      instructions = "Explain concepts step by step in simple terms with short examples.",
    ),
    AiPersonaPreset(
      title = "Code Helper",
      style = "Developer",
      instructions = "Help debug code and technical questions concisely with clean snippets.",
    ),
    AiPersonaPreset(
      title = "Translator",
      style = "Bilingual Translator",
      instructions = "Detect the sender's language and help translate or reply naturally in their language.",
    ),
  )

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentStudioScreen(
  settings: AgentSettings,
  discoveredModels: List<NimModelInfo>,
  nimUiState: NimDiscoveryUiState,
  providerKeySummaries: Map<AiProviderType, String>,
  localModelState: LocalModelDownloadState,
  isAnalyzingBusinessCrm: Boolean,
  onSaveProviderKey: (AiProviderType, String) -> Unit,
  onTestProvider: (AiProviderType) -> Unit,
  onClearProviderKey: (AiProviderType) -> Unit,
  onUpdateSettings: ((AgentSettings) -> AgentSettings) -> Unit,
  onDownloadLocalWhisper: (LocalWhisperModel) -> Unit,
  onDisableAndReleaseLocal: () -> Unit,
  onScanBusinessCrm: () -> Unit,
) {
  var searchQuery by remember { mutableStateOf("") }
  var activeFilter by remember { mutableStateOf(ModelFilter.ALL) }
  var showAdvanced by remember { mutableStateOf(false) }

  var agentNameDraft by remember(settings.agentIdentityName) { mutableStateOf(settings.agentIdentityName) }
  var systemPromptDraft by remember(settings.systemInstructions) { mutableStateOf(settings.systemInstructions) }
  var styleDraft by remember(settings.responseStyle) { mutableStateOf(settings.responseStyle) }
  var langDraft by remember(settings.preferredLanguage) { mutableStateOf(settings.preferredLanguage) }

  val filteredModels =
    remember(discoveredModels, searchQuery, activeFilter) {
      discoveredModels.filter { model ->
        val matchesText =
          searchQuery.isBlank() ||
            model.id.contains(searchQuery, ignoreCase = true) ||
            model.ownedBy.contains(searchQuery, ignoreCase = true)
        val matchesCap =
          when (activeFilter) {
            ModelFilter.ALL -> true
            ModelFilter.VISION -> model.supportsVision
            ModelFilter.TOOLS -> model.supportsToolCalling
            ModelFilter.AUDIO -> model.supportsAudioAsr
          }
        matchesText && matchesCap
      }
    }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 14.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      // 1. Simple On/Off Switches
      StudioCard(
        title = "What AI Can See & Answer",
        subtitle = "Tap any switch ON or OFF — enforces permissions strictly",
      ) {
        SettingSwitchRow("Reply to Direct Chats", settings.autoReplyDirectChats) { v ->
          onUpdateSettings { it.copy(autoReplyDirectChats = v) }
        }
        SettingSwitchRow(
          label = "Group Chatting (Auto & Manual AI)",
          checked = settings.autoReplyGroups,
          testTag = "group_chat_toggle",
        ) { v ->
          onUpdateSettings { it.copy(autoReplyGroups = v) }
        }
        AnimatedVisibility(visible = settings.autoReplyGroups) {
          SettingSwitchRow(
            label = "Only Reply in Groups When @Mentioned",
            checked = settings.groupMentionOnly,
            testTag = "group_mention_only_toggle",
          ) { v ->
            onUpdateSettings { it.copy(groupMentionOnly = v) }
          }
        }
        SettingSwitchRow("See Pictures (VL Vision)", settings.visionAnalyzeImages) { v ->
          onUpdateSettings { it.copy(visionAnalyzeImages = v) }
        }
        SettingSwitchRow("Understand Stickers (VL Vision)", settings.visionAnalyzeStickers) { v ->
          onUpdateSettings { it.copy(visionAnalyzeStickers = v) }
        }
        SettingSwitchRow("Read Files & PDFs", settings.documentAnalysisEnabled) { v ->
          onUpdateSettings { it.copy(documentAnalysisEnabled = v) }
        }
        val voiceActive = settings.autoTranscribeVoiceNotes && settings.transcriptionMode != TranscriptionMode.OFF
        SettingSwitchRow("Transcribe Voice Notes", voiceActive) { enabled ->
          onUpdateSettings { s ->
            if (enabled) {
              s.copy(
                autoTranscribeVoiceNotes = true,
                transcriptionMode = if (s.localSpeechEnabled) TranscriptionMode.LOCAL else TranscriptionMode.CLOUD,
              )
            } else {
              s.copy(
                autoTranscribeVoiceNotes = false,
                transcriptionMode = TranscriptionMode.OFF,
                localSpeechEnabled = false,
              )
            }
          }
        }
        SettingSwitchRow(
          label = "Smart Human & Stealth Mode (No AI Giveaways)",
          checked = settings.humanStealthModeEnabled,
          testTag = "human_stealth_toggle",
        ) { v ->
          onUpdateSettings { it.copy(humanStealthModeEnabled = v) }
        }
        SettingSwitchRow(
          label = "Remember Person Info & Summaries",
          checked = settings.autoLearnContactMemory,
          testTag = "auto_learn_memory_toggle",
        ) { v ->
          onUpdateSettings { it.copy(autoLearnContactMemory = v) }
        }
        SettingSwitchRow(
          label = "Prevent Duplicate Replies",
          checked = settings.duplicatePreventionEnabled,
          testTag = "duplicate_prevention_toggle",
        ) { v ->
          onUpdateSettings { it.copy(duplicatePreventionEnabled = v) }
        }
        SettingSwitchRow("Incoming Message Alerts", settings.incomingNotificationsEnabled) { v ->
          onUpdateSettings { it.copy(incomingNotificationsEnabled = v) }
        }
      }

      // Conversation Context Engine & N-Message Reading Card
      ContextEngineCard(
        settings = settings,
        onUpdateSettings = onUpdateSettings,
      )

      // Business Mode & CRM Profile Card
      BusinessModeStudioCard(
        settings = settings,
        isAnalyzingCrm = isAnalyzingBusinessCrm,
        onUpdateSettings = onUpdateSettings,
        onScanBusinessCrm = onScanBusinessCrm,
      )

      // Dedicated Voice Transcription Card (Off / Cloud AI / Real Offline Vosk Model)
      val effectiveVoiceMode =
        if (!settings.autoTranscribeVoiceNotes) TranscriptionMode.OFF else settings.transcriptionMode
      val voiceSubtitle =
        when (effectiveVoiceMode) {
          TranscriptionMode.OFF -> "OFF — AI knows transcription is off and will inform senders"
          TranscriptionMode.CLOUD -> "ON — Using Cloud AI audio model"
          TranscriptionMode.LOCAL ->
            if (localModelState is LocalModelDownloadState.Downloaded) {
              "ON — Offline ${localModelState.model.displayName} ready (${localModelState.sizeMb} MB)"
            } else {
              "ON — Offline mode selected (download a language pack below)"
            }
        }
      StudioCard(
        title = "Voice Transcription",
        subtitle = voiceSubtitle,
      ) {
        SingleChoiceSegmentedButtonRow(
          modifier = Modifier.fillMaxWidth().testTag("voice_mode_segmented"),
        ) {
          TranscriptionMode.entries.forEachIndexed { idx, mode ->
            SegmentedButton(
              selected = effectiveVoiceMode == mode,
              onClick = {
                onUpdateSettings { s ->
                  when (mode) {
                    TranscriptionMode.OFF ->
                      s.copy(transcriptionMode = TranscriptionMode.OFF, autoTranscribeVoiceNotes = false, localSpeechEnabled = false)
                    TranscriptionMode.CLOUD ->
                      s.copy(transcriptionMode = TranscriptionMode.CLOUD, autoTranscribeVoiceNotes = true, localSpeechEnabled = false)
                    TranscriptionMode.LOCAL ->
                      s.copy(transcriptionMode = TranscriptionMode.LOCAL, autoTranscribeVoiceNotes = true, localSpeechEnabled = true)
                  }
                }
              },
              shape = SegmentedButtonDefaults.itemShape(idx, TranscriptionMode.entries.size),
              modifier = Modifier.testTag("voice_mode_${mode.name.lowercase()}"),
            ) {
              Text(mode.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
        }

        AnimatedVisibility(visible = effectiveVoiceMode == TranscriptionMode.CLOUD) {
          val presets =
            remember(settings.activeProvider, discoveredModels) {
              NvidiaModelCapabilityDetector.recommendedVoicePresetsForProvider(settings.activeProvider, discoveredModels)
            }
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              text = "Cloud Voice Model (${settings.activeProvider.label} • English & Multi-Language)",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              FilterChip(
                selected = settings.audioModelId.isBlank(),
                onClick = { onUpdateSettings { it.copy(audioModelId = "") } },
                label = { Text("Auto (Best English)") },
                modifier = Modifier.testTag("cloud_asr_chip_auto"),
              )
              presets.forEach { preset ->
                FilterChip(
                  selected = settings.audioModelId == preset.id,
                  onClick = { onUpdateSettings { it.copy(audioModelId = preset.id) } },
                  label = { Text("${preset.label} · ${preset.badge}") },
                  modifier = Modifier.testTag("cloud_asr_chip_${preset.id.substringAfterLast('/')}"),
                )
              }
            }
          }
        }

        AnimatedVisibility(visible = effectiveVoiceMode == TranscriptionMode.LOCAL) {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              LocalWhisperModel.entries.forEach { m ->
                FilterChip(
                  selected = settings.localWhisperModel == m,
                  onClick = { onUpdateSettings { it.copy(localWhisperModel = m) } },
                  label = { Text("${m.displayName} (${m.sizeLabel}) · ${m.languageBadge}") },
                  modifier = Modifier.testTag("vosk_chip_${m.name.lowercase()}"),
                )
              }
            }
            if (settings.localWhisperModel.qualityHint.isNotBlank()) {
              Text(
                text = settings.localWhisperModel.qualityHint,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            when (localModelState) {
              is LocalModelDownloadState.Downloading -> {
                LinearProgressIndicator(progress = { localModelState.progressPercent / 100f }, modifier = Modifier.fillMaxWidth())
                Text(
                  text = "${localModelState.stageLabel}: ${localModelState.progressPercent}%",
                  style = MaterialTheme.typography.labelMedium,
                )
              }
              is LocalModelDownloadState.Downloaded -> {
                Text(
                  text = "Ready on device: ${localModelState.model.displayName} (${localModelState.sizeMb} MB)",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.primary,
                )
              }
              is LocalModelDownloadState.Error -> {
                Text(
                  text = localModelState.message,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.error,
                )
              }
              LocalModelDownloadState.Idle -> {
                Text(
                  text = "Download the offline speech pack once to transcribe voice notes with zero internet.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(
                onClick = { onDownloadLocalWhisper(settings.localWhisperModel) },
                enabled = localModelState !is LocalModelDownloadState.Downloading,
                modifier = Modifier.weight(1f).testTag("download_local_voice_button").minimumInteractiveComponentSize(),
              ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (localModelState is LocalModelDownloadState.Downloaded) "Reinstall" else "Download")
              }
              OutlinedButton(
                onClick = onDisableAndReleaseLocal,
                enabled = localModelState !is LocalModelDownloadState.Downloading,
                modifier = Modifier.weight(1f).testTag("remove_local_voice_button").minimumInteractiveComponentSize(),
              ) {
                Text("Remove & Turn Off")
              }
            }
          }
        }
      }

      // 2. 1-Tap Personality Presets
      StudioCard(title = "How Should AI Sound?", subtitle = "Tap any style to apply instantly") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          PRESETS.forEach { preset ->
            val active = settings.responseStyle == preset.style
            FilterChip(
              selected = active,
              onClick = {
                styleDraft = preset.style
                systemPromptDraft = preset.instructions
                onUpdateSettings {
                  it.copy(responseStyle = preset.style, systemInstructions = preset.instructions)
                }
              },
              label = { Text(preset.title) },
            )
          }
        }
      }

      // 3. Multi-Provider AI Key & Fallback Routing
      MultiProviderAiCard(
        settings = settings,
        nimUiState = nimUiState,
        providerKeySummaries = providerKeySummaries,
        onUpdateSettings = onUpdateSettings,
        onSaveProviderKey = onSaveProviderKey,
        onTestProvider = onTestProvider,
        onClearProviderKey = onClearProviderKey,
      )

      // 4. AI Model Picker
      if (discoveredModels.isNotEmpty()) {
        StudioCard(
          title = "Choose AI Brain (${discoveredModels.size})",
          subtitle = "Chat: ${settings.selectedModelId.substringAfterLast('/').ifBlank { "Auto" }} • Vision: ${settings.visionModelId.substringAfterLast('/').ifBlank { "Auto" }}",
        ) {
          OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search models…") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("model_search_input"),
          )

          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelFilter.entries.forEach { f ->
              FilterChip(
                selected = activeFilter == f,
                onClick = { activeFilter = f },
                label = { Text(f.label) },
              )
            }
          }

          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            filteredModels.take(20).forEach { model ->
              val isPrimary = settings.selectedModelId == model.id
              val isVisionFallback = settings.visionModelId == model.id
              val isAsr = settings.audioModelId == model.id
              Card(
                colors =
                  CardDefaults.cardColors(
                    containerColor =
                      if (isPrimary) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                      else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                  ),
                modifier =
                  Modifier.fillMaxWidth()
                    .clickable {
                      if (model.isChatCapable) {
                        onUpdateSettings { it.copy(selectedModelId = model.id) }
                      } else if (model.supportsAudioAsr) {
                        onUpdateSettings { it.copy(audioModelId = model.id) }
                      }
                    }
                    .testTag("model_card_${model.shortName}"),
              ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Text(
                      text = model.id,
                      style = MaterialTheme.typography.titleMedium,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      modifier = Modifier.weight(1f),
                    )
                    if (isPrimary) {
                      Icon(Icons.Rounded.CheckCircle, contentDescription = "Selected model", tint = MaterialTheme.colorScheme.primary)
                    }
                  }
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (model.isChatCapable) {
                      AssistChip(
                        onClick = { onUpdateSettings { it.copy(selectedModelId = model.id) } },
                        label = { Text(if (isPrimary) "Primary ✓" else "Use Chat") },
                      )
                    }
                    if (model.supportsVision) {
                      AssistChip(
                        onClick = { onUpdateSettings { it.copy(visionModelId = model.id) } },
                        label = { Text(if (isVisionFallback) "Vision ✓" else "Use Vision") },
                      )
                    }
                    if (model.supportsAudioAsr) {
                      AssistChip(
                        onClick = { onUpdateSettings { it.copy(audioModelId = model.id) } },
                        label = { Text(if (isAsr) "Voice ✓" else "Use Voice") },
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }

      // 5. Collapsible Advanced Tuning
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .clickable { showAdvanced = !showAdvanced }
                .testTag("toggle_advanced_ai_button"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column {
              Text("Advanced Tuning", style = MaterialTheme.typography.titleMedium)
              Text("Custom prompt, creativity, typing indicators", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
              imageVector = if (showAdvanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
              contentDescription = "Toggle advanced AI settings",
            )
          }

          AnimatedVisibility(visible = showAdvanced) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
              HorizontalDivider()
              Text("Creativity (Temperature): ${((settings.temperature * 100).roundToInt() / 100f)}", style = MaterialTheme.typography.labelMedium)
              Slider(
                value = settings.temperature,
                onValueChange = { v -> onUpdateSettings { it.copy(temperature = v) } },
                valueRange = 0f..1.2f,
              )

              Text("Max Reply Length: ${settings.maxTokens} tokens", style = MaterialTheme.typography.labelMedium)
              Slider(
                value = settings.maxTokens.toFloat(),
                onValueChange = { v -> onUpdateSettings { it.copy(maxTokens = v.roundToInt()) } },
                valueRange = 256f..4096f,
                steps = 14,
              )

              Text("Natural Delay: ${settings.responseDelayMs} ms", style = MaterialTheme.typography.labelMedium)
              Slider(
                value = settings.responseDelayMs.toFloat(),
                onValueChange = { v -> onUpdateSettings { it.copy(responseDelayMs = v.roundToInt()) } },
                valueRange = 0f..4000f,
                steps = 15,
              )

              SettingSwitchRow("Streaming Responses", settings.streamingEnabled) { v ->
                onUpdateSettings { it.copy(streamingEnabled = v) }
              }
              SettingSwitchRow("Send Typing Indicator", settings.sendTypingIndicators) { v ->
                onUpdateSettings { it.copy(sendTypingIndicators = v) }
              }
              SettingSwitchRow("Send Read Receipts", settings.sendReadReceipts) { v ->
                onUpdateSettings { it.copy(sendReadReceipts = v) }
              }
              SettingSwitchRow("Group Mention Only", settings.groupMentionOnly) { v ->
                onUpdateSettings { it.copy(groupMentionOnly = v) }
              }
              SettingSwitchRow("Quote Incoming Message", settings.quoteIncomingMessages) { v ->
                onUpdateSettings { it.copy(quoteIncomingMessages = v) }
              }
              SettingSwitchRow("Add AI Signature", settings.appendAiSignature) { v ->
                onUpdateSettings { it.copy(appendAiSignature = v) }
              }

              OutlinedTextField(
                value = agentNameDraft,
                onValueChange = { agentNameDraft = it },
                label = { Text("Assistant Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
              )
              OutlinedTextField(
                value = systemPromptDraft,
                onValueChange = { systemPromptDraft = it },
                label = { Text("Custom System Prompt") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
              )
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                  value = styleDraft,
                  onValueChange = { styleDraft = it },
                  label = { Text("Style") },
                  singleLine = true,
                  modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                  value = langDraft,
                  onValueChange = { langDraft = it },
                  label = { Text("Language") },
                  singleLine = true,
                  modifier = Modifier.weight(1f),
                )
              }
              Button(
                onClick = {
                  onUpdateSettings {
                    it.copy(
                      agentIdentityName = agentNameDraft,
                      systemInstructions = systemPromptDraft,
                      responseStyle = styleDraft,
                      preferredLanguage = langDraft,
                    )
                  }
                },
                modifier = Modifier.fillMaxWidth().testTag("save_agent_personality_button").minimumInteractiveComponentSize(),
              ) {
                Text("Save Custom Prompt")
              }
            }
          }
        }
      }

      Spacer(Modifier.height(12.dp))
    }
  }
}

@Composable
private fun StudioCard(
  title: String,
  subtitle: String,
  content: @Composable () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    shape = MaterialTheme.shapes.large,
    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Column {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      content()
    }
  }
}

@Composable
private fun SettingSwitchRow(
  label: String,
  checked: Boolean,
  testTag: String? = null,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
    )
  }
}

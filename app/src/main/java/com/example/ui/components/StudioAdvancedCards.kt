package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BusinessCenter
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.prefs.AgentSettings
import com.example.data.prefs.AiProviderType
import com.example.data.prefs.ContextStrategy
import com.example.ui.NimDiscoveryUiState
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContextEngineCard(
  settings: AgentSettings,
  onUpdateSettings: ((AgentSettings) -> AgentSettings) -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    shape = MaterialTheme.shapes.large,
    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Rounded.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        Column(modifier = Modifier.weight(1f)) {
          Text("Conversation Context Engine", style = MaterialTheme.typography.titleLarge)
          Text(
            "Controls how AI reads prior chats, summaries & N messages",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = settings.conversationContextEnabled,
          onCheckedChange = { v -> onUpdateSettings { it.copy(conversationContextEnabled = v) } },
          modifier = Modifier.testTag("context_engine_toggle"),
        )
      }

      AnimatedVisibility(visible = settings.conversationContextEnabled) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("Context Strategy", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ContextStrategy.entries.forEach { strat ->
              FilterChip(
                selected = settings.contextStrategy == strat,
                onClick = { onUpdateSettings { it.copy(contextStrategy = strat) } },
                label = { Text(strat.label) },
                modifier = Modifier.testTag("context_strategy_${strat.name.lowercase()}"),
              )
            }
          }
          Text(
            text = settings.contextStrategy.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          HorizontalDivider()

          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("Read Past Messages Before Replying", style = MaterialTheme.typography.bodyLarge)
              Text(
                if (settings.recentMessageReadingEnabled) {
                  "Reads up to ${settings.recentMessageCount} recent messages (including images, stickers & PDFs)"
                } else {
                  "OFF — Only answers the current incoming message"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = settings.recentMessageReadingEnabled,
              onCheckedChange = { v -> onUpdateSettings { it.copy(recentMessageReadingEnabled = v) } },
              modifier = Modifier.testTag("recent_reading_toggle"),
            )
          }

          AnimatedVisibility(visible = settings.recentMessageReadingEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 5, 10, 20, 30).forEach { count ->
                  FilterChip(
                    selected = settings.recentMessageCount == count,
                    onClick = { onUpdateSettings { it.copy(recentMessageCount = count) } },
                    label = { Text("$count msgs") },
                  )
                }
              }
              Slider(
                value = settings.recentMessageCount.toFloat(),
                onValueChange = { v -> onUpdateSettings { it.copy(recentMessageCount = v.roundToInt().coerceIn(1, 50)) } },
                valueRange = 1f..50f,
                modifier = Modifier.testTag("recent_count_slider"),
              )
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BusinessModeStudioCard(
  settings: AgentSettings,
  isAnalyzingCrm: Boolean,
  onUpdateSettings: ((AgentSettings) -> AgentSettings) -> Unit,
  onScanBusinessCrm: () -> Unit,
) {
  var nameDraft by remember(settings.businessName) { mutableStateOf(settings.businessName) }
  var toneDraft by remember(settings.businessTone) { mutableStateOf(settings.businessTone) }
  var catalogDraft by remember(settings.businessCatalogOrInfo) { mutableStateOf(settings.businessCatalogOrInfo) }

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    shape = MaterialTheme.shapes.large,
    modifier =
      Modifier.fillMaxWidth()
        .border(
          1.dp,
          if (settings.businessModeEnabled) MaterialTheme.colorScheme.secondary.copy(alpha = 0.65f)
          else MaterialTheme.colorScheme.outline,
          MaterialTheme.shapes.large,
        ),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Rounded.BusinessCenter, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        Column(modifier = Modifier.weight(1f)) {
          Text("Business Mode & Live CRM", style = MaterialTheme.typography.titleLarge)
          Text(
            "Lead scoring, sales/support classification & brand-aware AI replies",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = settings.businessModeEnabled,
          onCheckedChange = { v -> onUpdateSettings { it.copy(businessModeEnabled = v) } },
          modifier = Modifier.testTag("business_mode_toggle"),
        )
      }

      AnimatedVisibility(visible = settings.businessModeEnabled) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedTextField(
            value = nameDraft,
            onValueChange = { nameDraft = it },
            label = { Text("Business / Brand Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("business_name_input"),
          )
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Consultative & Executive", "Warm & Concise", "High-Tech Concierge", "Direct Sales").forEach { tone ->
              FilterChip(
                selected = toneDraft == tone,
                onClick = { toneDraft = tone },
                label = { Text(tone) },
              )
            }
          }
          OutlinedTextField(
            value = catalogDraft,
            onValueChange = { catalogDraft = it },
            label = { Text("Verified Products, Pricing & Policies (AI uses this strictly)") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth().testTag("business_catalog_input"),
          )
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
              onClick = {
                onUpdateSettings {
                  it.copy(
                    businessName = nameDraft.trim(),
                    businessTone = toneDraft.trim().ifBlank { "Consultative & Executive" },
                    businessCatalogOrInfo = catalogDraft.trim(),
                  )
                }
              },
              modifier = Modifier.weight(1f).testTag("save_business_profile_button").minimumInteractiveComponentSize(),
            ) {
              Text("Save Profile")
            }
            OutlinedButton(
              onClick = onScanBusinessCrm,
              enabled = !isAnalyzingCrm,
              modifier = Modifier.weight(1f).testTag("studio_scan_crm_button").minimumInteractiveComponentSize(),
            ) {
              if (isAnalyzingCrm) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
              }
              Text(if (isAnalyzingCrm) "Scanning…" else "Scan CRM Chats")
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiProviderAiCard(
  settings: AgentSettings,
  nimUiState: NimDiscoveryUiState,
  providerKeySummaries: Map<AiProviderType, String>,
  onUpdateSettings: ((AgentSettings) -> AgentSettings) -> Unit,
  onSaveProviderKey: (AiProviderType, String) -> Unit,
  onTestProvider: (AiProviderType) -> Unit,
  onClearProviderKey: (AiProviderType) -> Unit,
) {
  val active = settings.activeProvider
  var providerKeyDraft by remember(active) { mutableStateOf("") }
  var showSecret by remember { mutableStateOf(false) }
  var customUrlDraft by remember(settings.customProviderBaseUrl) { mutableStateOf(settings.customProviderBaseUrl) }
  var customModelDraft by remember(settings.customProviderModelId) { mutableStateOf(settings.customProviderModelId) }
  var fallbackModelDraft by remember(settings.fallbackProviderModelId) { mutableStateOf(settings.fallbackProviderModelId) }

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    shape = MaterialTheme.shapes.large,
    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Rounded.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
          Text("AI Provider & Fallbacks", style = MaterialTheme.typography.titleLarge)
          Text(
            "Active: ${active.label} • All keys encrypted on-device",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (nimUiState.isLoading) {
          CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
      }

      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AiProviderType.entries.forEach { provider ->
          FilterChip(
            selected = settings.activeProvider == provider,
            onClick = { onUpdateSettings { it.copy(activeProvider = provider) } },
            label = { Text(provider.label) },
            modifier = Modifier.testTag("provider_chip_${provider.name.lowercase()}"),
          )
        }
      }

      if (active == AiProviderType.CUSTOM) {
        OutlinedTextField(
          value = customUrlDraft,
          onValueChange = { customUrlDraft = it },
          label = { Text("OpenAI-Compatible Base URL (e.g. Ollama LAN)") },
          placeholder = { Text("http://192.168.1.20:11434/v1") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("custom_provider_url_input"),
        )
        OutlinedTextField(
          value = customModelDraft,
          onValueChange = { customModelDraft = it },
          label = { Text("Model ID (e.g. llama3.2-vision)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("custom_provider_model_input"),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = {
              onUpdateSettings {
                it.copy(
                  customProviderBaseUrl = customUrlDraft.trim(),
                  customProviderModelId = customModelDraft.trim(),
                )
              }
              onTestProvider(AiProviderType.CUSTOM)
            },
            modifier = Modifier.weight(1f).testTag("save_custom_provider_button").minimumInteractiveComponentSize(),
          ) {
            Text("Save & Test URL")
          }
        }
      } else {
        val keyStatus = providerKeySummaries[active] ?: "Not configured"
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = if (keyStatus != "Not configured") Icons.Rounded.CloudDone else Icons.Rounded.Key,
            contentDescription = null,
            tint = if (keyStatus != "Not configured") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text("${active.label} Key: $keyStatus", style = MaterialTheme.typography.labelMedium)
        }

        OutlinedTextField(
          value = providerKeyDraft,
          onValueChange = { providerKeyDraft = it },
          label = { Text("Paste ${active.label} API key") },
          placeholder = { Text(if (active == AiProviderType.NVIDIA_NIM) "nvapi-..." else "sk-...") },
          singleLine = true,
          visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
          trailingIcon = {
            IconButton(
              onClick = { showSecret = !showSecret },
              modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
              Icon(
                imageVector = if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                contentDescription = "Toggle API key visibility",
              )
            }
          },
          modifier = Modifier.fillMaxWidth().testTag("nvidia_key_input"),
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = {
              onSaveProviderKey(active, providerKeyDraft)
              providerKeyDraft = ""
            },
            enabled = providerKeyDraft.isNotBlank() && !nimUiState.isLoading,
            modifier = Modifier.weight(1f).testTag("validate_nvidia_key_button").minimumInteractiveComponentSize(),
          ) {
            Text("Save & Connect AI")
          }
          OutlinedButton(
            onClick = { onTestProvider(active) },
            enabled = !nimUiState.isLoading,
            modifier = Modifier.testTag("refresh_models_button").minimumInteractiveComponentSize(),
          ) {
            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh AI models", modifier = Modifier.size(18.dp))
          }
          OutlinedButton(
            onClick = { onClearProviderKey(active) },
            modifier = Modifier.testTag("clear_nvidia_key_button").minimumInteractiveComponentSize(),
          ) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove API key", modifier = Modifier.size(18.dp))
          }
        }
      }

      nimUiState.statusMessage?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
      }
      nimUiState.errorMessage?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
      }

      HorizontalDivider()

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Auto-Fallback on Error / Refusal", style = MaterialTheme.typography.bodyLarge)
          Text(
            "Automatically tries backup provider if primary fails",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = settings.fallbackProviderEnabled,
          onCheckedChange = { v -> onUpdateSettings { it.copy(fallbackProviderEnabled = v) } },
          modifier = Modifier.testTag("fallback_provider_toggle"),
        )
      }

      AnimatedVisibility(visible = settings.fallbackProviderEnabled) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AiProviderType.entries.filter { it != active }.forEach { fb ->
              FilterChip(
                selected = settings.fallbackProvider == fb,
                onClick = { onUpdateSettings { it.copy(fallbackProvider = fb) } },
                label = { Text(fb.label) },
              )
            }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
              value = fallbackModelDraft,
              onValueChange = { fallbackModelDraft = it },
              label = { Text("Backup Model ID (optional)") },
              placeholder = { Text(settings.fallbackProvider.defaultPlaceholderModel.ifBlank { "auto" }) },
              singleLine = true,
              modifier = Modifier.weight(1f),
            )
            OutlinedButton(
              onClick = { onUpdateSettings { it.copy(fallbackProviderModelId = fallbackModelDraft.trim()) } },
              modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
              Text("Apply")
            }
          }
        }
      }
    }
  }
}

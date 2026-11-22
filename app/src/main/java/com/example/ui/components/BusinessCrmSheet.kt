package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BusinessCenter
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.agent.BusinessIntelligenceEngine
import com.example.data.db.ContactMemoryEntity
import com.example.data.db.ConversationEntity
import com.example.data.prefs.AgentSettings
import com.example.data.prefs.ContextStrategy
import com.example.security.PermissionTier
import kotlin.math.roundToInt

/**
 * Futuristic, strictly Room-backed Business Mode CRM Dashboard sheet.
 * Every metric, category, intent, and lead score reflects persisted Room conversations only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessCrmSheet(
  conversations: List<ConversationEntity>,
  settings: AgentSettings,
  isAnalyzing: Boolean,
  onDismiss: () -> Unit,
  onAnalyzeAll: () -> Unit,
  onOpenChat: (String) -> Unit,
  onAnalyzeSingleChat: (String) -> Unit,
) {
  BackHandler(onBack = onDismiss)
  var selectedCategory by remember { mutableStateOf("All") }
  val categories = remember { listOf("All") + BusinessIntelligenceEngine.BUSINESS_CATEGORIES }

  val filteredConversations =
    remember(conversations, selectedCategory) {
      val base =
        if (selectedCategory == "All") conversations
        else conversations.filter { it.businessCategory.equals(selectedCategory, ignoreCase = true) }
      base.sortedWith(
        compareByDescending<ConversationEntity> { it.businessLeadScore }
          .thenByDescending { it.unreadCount }
          .thenByDescending { it.lastMessageTimestamp }
      )
    }

  val hotLeadsCount =
    remember(conversations) {
      conversations.count {
        it.businessLeadScore >= 70 ||
          it.businessCategory in setOf("Sales Inquiry", "Important Opportunity")
      }
    }
  val followUpsCount =
    remember(conversations) {
      conversations.count {
        it.unreadCount > 0 || it.businessCategory == "Follow-Up Required"
      }
    }
  val analyzedCount = remember(conversations) { conversations.count { it.businessCategory.isNotBlank() } }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .navigationBarsPadding()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Sci-Fi / Business Command Header
      Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier =
          Modifier.fillMaxWidth()
            .border(
              1.dp,
              MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f),
              MaterialTheme.shapes.large,
            ),
      ) {
        Box(
          modifier =
            Modifier.fillMaxWidth()
              .background(
                Brush.linearGradient(
                  listOf(
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                  )
                )
              )
              .padding(16.dp),
        ) {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
              ) {
                Box(
                  modifier =
                    Modifier.size(40.dp)
                      .clip(CircleShape)
                      .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)),
                  contentAlignment = Alignment.Center,
                ) {
                  Icon(
                    Icons.Rounded.BusinessCenter,
                    contentDescription = "Business CRM command center",
                    tint = MaterialTheme.colorScheme.secondary,
                  )
                }
                Column {
                  Text(
                    text = settings.businessName.ifBlank { "Business Intelligence" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                  )
                  Text(
                    text =
                      if (settings.businessModeEnabled) "Live Room CRM Pipeline • Tone: ${settings.businessTone}"
                      else "Business Mode OFF (showing synced chats)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              Button(
                onClick = onAnalyzeAll,
                enabled = !isAnalyzing && conversations.isNotEmpty(),
                modifier = Modifier.testTag("analyze_business_crm_button").minimumInteractiveComponentSize(),
              ) {
                if (isAnalyzing) {
                  CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                  Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (isAnalyzing) "Scanning…" else "Scan Chats")
              }
            }

            // Real Room-backed metrics row
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              CrmMetricPill("Synced Chats", conversations.size.toString(), Modifier.weight(1f))
              CrmMetricPill("Hot Leads", hotLeadsCount.toString(), Modifier.weight(1f), highlight = hotLeadsCount > 0)
              CrmMetricPill("Follow-Ups", followUpsCount.toString(), Modifier.weight(1f))
              CrmMetricPill("Analyzed", analyzedCount.toString(), Modifier.weight(1f))
            }
          }
        }
      }

      // Category Filter Bar
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        categories.forEach { cat ->
          FilterChip(
            selected = selectedCategory == cat,
            onClick = { selectedCategory = cat },
            label = { Text(cat) },
            modifier = Modifier.testTag("crm_filter_${cat.lowercase().replace(" ", "_")}"),
          )
        }
      }

      if (filteredConversations.isEmpty()) {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(Icons.Rounded.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Text("No matching conversations", style = MaterialTheme.typography.titleMedium)
            Text(
              "Once WhatsApp chats arrive in Room, tap 'Scan Chats' to classify real leads and next actions.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else {
        filteredConversations.forEach { conv ->
          AnimatedVisibility(
            visible = true,
            enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) + slideInVertically { it / 8 },
          ) {
            BusinessLeadCard(
              conversation = conv,
              isAnalyzing = isAnalyzing,
              onOpenChat = {
                onDismiss()
                onOpenChat(conv.chatJid)
              },
              onAnalyze = { onAnalyzeSingleChat(conv.chatJid) },
            )
          }
        }
      }

      Spacer(Modifier.height(20.dp))
    }
  }
}

@Composable
private fun CrmMetricPill(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
  highlight: Boolean = false,
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color =
      if (highlight) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
      else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
    modifier = modifier,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = value,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun BusinessLeadCard(
  conversation: ConversationEntity,
  isAnalyzing: Boolean,
  onOpenChat: () -> Unit,
  onAnalyze: () -> Unit,
) {
  val category = conversation.businessCategory.ifBlank { "Unclassified" }
  val score = conversation.businessLeadScore.coerceIn(0, 100)
  Card(
    shape = MaterialTheme.shapes.medium,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    modifier =
      Modifier.fillMaxWidth()
        .border(
          1.dp,
          if (score >= 75) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
          else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
          MaterialTheme.shapes.medium,
        )
        .clickable(onClick = onOpenChat)
        .testTag("crm_lead_card_${conversation.chatJid}"),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.weight(1f),
        ) {
          Icon(
            imageVector = if (conversation.isGroup) Icons.Rounded.Group else Icons.Rounded.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(20.dp),
          )
          Text(
            text = conversation.displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Surface(
          shape = CircleShape,
          color =
            when {
              score >= 75 -> MaterialTheme.colorScheme.primaryContainer
              score >= 50 -> MaterialTheme.colorScheme.secondaryContainer
              else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ) {
          Text(
            text = "$category • $score%",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
      }

      LinearProgressIndicator(
        progress = { score / 100f },
        color =
          if (score >= 75) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.secondary,
        modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
      )

      Text(
        text = "Intent: ${conversation.businessIntent.ifBlank { conversation.lastMessagePreview.ifBlank { "Tap Scan to analyze messages" } }}",
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      if (conversation.businessNextAction.isNotBlank()) {
        Text(
          text = "Next Step: ${conversation.businessNextAction}",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.secondary,
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(
          onClick = onAnalyze,
          enabled = !isAnalyzing,
          modifier = Modifier.minimumInteractiveComponentSize(),
        ) {
          Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Re-Analyze")
        }
        OutlinedButton(
          onClick = onOpenChat,
          modifier = Modifier.minimumInteractiveComponentSize(),
        ) {
          Text("Open Chat")
          Spacer(Modifier.width(4.dp))
          Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
        }
      }
    }
  }
}

/**
 * Modular Per-Chat Memory, Rules, Context Strategy & N-Message Override Sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContactMemorySheet(
  conversation: ConversationEntity,
  memories: List<ContactMemoryEntity>,
  globalSettings: AgentSettings,
  onDismiss: () -> Unit,
  onSaveConfig: (
    autoReply: Boolean,
    tier: PermissionTier,
    customInstr: String,
    lang: String,
    style: String,
    recentCountOverride: Int?,
    contextModeOverride: String?,
  ) -> Unit,
  onAddMemory: (String, String) -> Unit,
  onDeleteMemory: (Long) -> Unit,
  onClearMemory: () -> Unit,
) {
  BackHandler(onBack = onDismiss)
  var autoReply by remember(conversation.chatJid) { mutableStateOf(conversation.autoReplyEnabled) }
  var tier by remember(conversation.chatJid) { mutableStateOf(PermissionTier.fromName(conversation.permissionTier)) }
  var customInstr by remember(conversation.chatJid) { mutableStateOf(conversation.customInstructions) }
  var lang by remember(conversation.chatJid) { mutableStateOf(conversation.preferredLanguage) }
  var style by remember(conversation.chatJid) { mutableStateOf(conversation.responseStyle) }
  var contextModeOverride by remember(conversation.chatJid) { mutableStateOf(conversation.contextModeOverride ?: "") }
  var useCustomCount by remember(conversation.chatJid) { mutableStateOf(conversation.recentContextCountOverride != null) }
  var customCount by remember(conversation.chatJid) {
    mutableIntStateOf((conversation.recentContextCountOverride ?: globalSettings.recentMessageCount).coerceIn(1, 50))
  }
  var newKey by remember { mutableStateOf("") }
  var newVal by remember { mutableStateOf("") }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .navigationBarsPadding()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = if (conversation.isGroup) "Group Rules & Context • ${conversation.displayName}" else "Contact Memory & Rules • ${conversation.displayName}",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
      )

      if (globalSettings.businessModeEnabled && conversation.businessCategory.isNotBlank()) {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              text = "Business CRM: ${conversation.businessCategory} (${conversation.businessLeadScore}%)",
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.secondary,
              fontWeight = FontWeight.SemiBold,
            )
            if (conversation.businessIntent.isNotBlank()) {
              Text("Intent: ${conversation.businessIntent}", style = MaterialTheme.typography.bodySmall)
            }
            if (conversation.businessNextAction.isNotBlank()) {
              Text("Next Action: ${conversation.businessNextAction}", style = MaterialTheme.typography.bodySmall)
            }
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("Auto-Reply Enabled for This Chat", style = MaterialTheme.typography.titleMedium)
        Switch(checked = autoReply, onCheckedChange = { autoReply = it })
      }

      Text("Role & Access Tag", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PermissionTier.entries.forEach { t ->
          val tagIcon =
            when (t) {
              PermissionTier.ADMIN -> Icons.Rounded.AdminPanelSettings
              PermissionTier.OWNER, PermissionTier.TRUSTED -> Icons.Rounded.VerifiedUser
              PermissionTier.BLOCKED -> Icons.Rounded.Block
              PermissionTier.STANDARD -> null
            }
          FilterChip(
            selected = tier == t,
            onClick = { tier = t },
            leadingIcon =
              tagIcon?.let { iconVec ->
                { Icon(iconVec, contentDescription = null, modifier = Modifier.size(16.dp)) }
              },
            label = { Text(t.label) },
            modifier = Modifier.testTag("permission_tier_${t.name.lowercase()}"),
          )
        }
      }
      val tierSummary =
        when (tier) {
          PermissionTier.ADMIN ->
            "Admin Tag Active: This contact can command your AI right inside WhatsApp to summarize any chat, fetch unread briefings, read/search chats, reply or send messages, and schedule reminders."
          PermissionTier.OWNER ->
            "Owner Tag: Full WhatsApp command tools plus personal telemetry privileges."
          PermissionTier.TRUSTED ->
            "Trusted Contact: Allowed to use utilities, memory, and device status tools; bypasses Quiet Hours."
          PermissionTier.STANDARD ->
            "Standard Contact: Natural conversation and per-chat memory only (cannot trigger Admin WhatsApp commands)."
          PermissionTier.BLOCKED ->
            "Blocked: AI completely ignores incoming messages from this chat."
        }
      Surface(
        shape = RoundedCornerShape(12.dp),
        color =
          when (tier) {
            PermissionTier.ADMIN, PermissionTier.OWNER -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            PermissionTier.BLOCKED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
          },
        modifier = Modifier.fillMaxWidth().testTag("permission_tier_description"),
      ) {
        Text(
          text = tierSummary,
          style = MaterialTheme.typography.bodySmall,
          color =
            if (tier == PermissionTier.BLOCKED) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
      }

      HorizontalDivider()

      // Per-chat context strategy & N-message override
      Text("Context Reading Mode (Override)", style = MaterialTheme.typography.labelMedium)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
          selected = contextModeOverride.isBlank(),
          onClick = { contextModeOverride = "" },
          label = { Text("Global (${globalSettings.contextStrategy.label})") },
        )
        ContextStrategy.entries.forEach { strat ->
          FilterChip(
            selected = contextModeOverride == strat.name,
            onClick = { contextModeOverride = strat.name },
            label = { Text(strat.label) },
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Custom Message Window", style = MaterialTheme.typography.bodyLarge)
          Text(
            if (useCustomCount) "Reads last $customCount messages for this chat"
            else "Using global limit (${globalSettings.recentMessageCount} messages)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(checked = useCustomCount, onCheckedChange = { useCustomCount = it })
      }
      if (useCustomCount) {
        Slider(
          value = customCount.toFloat(),
          onValueChange = { customCount = it.roundToInt().coerceIn(1, 50) },
          valueRange = 1f..50f,
          modifier = Modifier.testTag("contact_custom_context_slider"),
        )
      }

      OutlinedTextField(
        value = customInstr,
        onValueChange = { customInstr = it },
        label = { Text("Special instructions for this contact / group") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = lang,
          onValueChange = { lang = it },
          label = { Text("Language") },
          singleLine = true,
          modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
          value = style,
          onValueChange = { style = it },
          label = { Text("Tone") },
          singleLine = true,
          modifier = Modifier.weight(1f),
        )
      }

      Button(
        onClick = {
          onSaveConfig(
            autoReply,
            tier,
            customInstr,
            lang,
            style,
            if (useCustomCount) customCount else null,
            contextModeOverride.takeIf { it.isNotBlank() },
          )
        },
        modifier = Modifier.fillMaxWidth().testTag("save_contact_config_button").minimumInteractiveComponentSize(),
      ) {
        Text("Save Contact Settings")
      }

      HorizontalDivider()

      val summaryHeader =
        if (conversation.summaryVersion > 0) "Chat Summary (v${conversation.summaryVersion})"
        else "Chat Summary"
      Text(summaryHeader, style = MaterialTheme.typography.titleMedium)
      Text(
        text = conversation.rollingSummary.ifBlank { "Generates automatically as you chat or tap Summarize." },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Text("Saved Memory Facts (${memories.size})", style = MaterialTheme.typography.titleMedium)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
          value = newKey,
          onValueChange = { newKey = it },
          label = { Text("Label") },
          singleLine = true,
          modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
          value = newVal,
          onValueChange = { newVal = it },
          label = { Text("Fact") },
          singleLine = true,
          modifier = Modifier.weight(1.4f),
        )
        IconButton(
          onClick = {
            if (newKey.isNotBlank() && newVal.isNotBlank()) {
              onAddMemory(newKey, newVal)
              newKey = ""
              newVal = ""
            }
          },
          modifier = Modifier.minimumInteractiveComponentSize(),
        ) {
          Icon(Icons.Rounded.Add, contentDescription = "Add memory fact")
        }
      }

      memories.forEach { mem ->
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(mem.factKey, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
              Text(mem.factValue, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = { onDeleteMemory(mem.id) }, modifier = Modifier.minimumInteractiveComponentSize()) {
              Icon(Icons.Rounded.Delete, contentDescription = "Delete memory fact")
            }
          }
        }
      }

      if (memories.isNotEmpty() || conversation.rollingSummary.isNotBlank()) {
        OutlinedButton(
          onClick = onClearMemory,
          modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
        ) {
          Text("Clear Memory for This Contact")
        }
      }
      Spacer(Modifier.height(24.dp))
    }
  }
}

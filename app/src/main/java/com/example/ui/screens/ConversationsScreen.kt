package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BusinessCenter
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.agent.AutomationRulesEngine
import com.example.agent.ManualAiActionResult
import com.example.data.db.ChatMessageEntity
import com.example.data.db.ContactMemoryEntity
import com.example.data.db.ConversationEntity
import com.example.data.db.MessageProcessingJobEntity
import com.example.data.prefs.AgentSettings
import com.example.security.PermissionTier
import com.example.ui.ChatAiActionResult
import com.example.ui.components.BusinessCrmSheet
import com.example.ui.components.ContactMemorySheet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ChatFilterTab(val label: String) {
  ALL("All"),
  UNREAD("Unread"),
  GROUPS("Groups"),
  BUSINESS("Business"),
}

@Composable
fun ConversationsScreen(
  conversations: List<ConversationEntity>,
  selectedChatJid: String?,
  selectedConversation: ConversationEntity?,
  messages: List<ChatMessageEntity>,
  memories: List<ContactMemoryEntity>,
  selectedChatJobs: List<MessageProcessingJobEntity>,
  settings: AgentSettings,
  isDictating: Boolean,
  dictatedText: String,
  aiActionResult: ChatAiActionResult?,
  manualAiBusyAction: String?,
  manualAiFeedback: ManualAiActionResult?,
  isAnalyzingBusinessCrm: Boolean,
  onSelectChat: (String?) -> Unit,
  onSendMessage: (String, String, String?, String?) -> Unit,
  onSendAttachment: (String, Uri, String, String) -> Unit,
  onStartVoiceDictation: () -> Unit,
  onConsumeDictatedText: () -> Unit,
  onSpeakText: (String) -> Unit,
  onSummarizeChat: (String) -> Unit,
  onDraftSmartReply: (String) -> Unit,
  onTranslateText: (String, String) -> Unit,
  onInspectMedia: (ChatMessageEntity) -> Unit,
  onDismissAiAction: () -> Unit,
  onExportChat: (ConversationEntity, List<ChatMessageEntity>) -> Unit,
  onUpdateContactConfig: (
    ConversationEntity,
    Boolean,
    PermissionTier,
    String,
    String,
    String,
    Int?,
    String?,
  ) -> Unit,
  onAddMemory: (String, String, String) -> Unit,
  onDeleteMemory: (Long) -> Unit,
  onClearMemory: (String) -> Unit,
  onReplyRight: (String) -> Unit,
  onKeepReplying: (String) -> Unit,
  onDismissManualFeedback: () -> Unit,
  onEnableGroupChatting: () -> Unit,
  onRefreshBusinessCrm: (String?) -> Unit,
) {
  val context = LocalContext.current
  var showMemorySheet by remember { mutableStateOf(false) }
  var showBusinessCrmSheet by remember { mutableStateOf(false) }
  var pendingComposerInsert by remember { mutableStateOf<String?>(null) }
  val quickReplies =
    remember(settings.quickRepliesJson) {
      AutomationRulesEngine().parseStringList(settings.quickRepliesJson)
    }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val isWide = maxWidth >= 720.dp

    if (isWide) {
      Row(modifier = Modifier.fillMaxSize()) {
        ConversationListPane(
          conversations = conversations,
          selectedChatJid = selectedChatJid,
          settings = settings,
          onSelectChat = onSelectChat,
          onOpenBusinessCrm = { showBusinessCrmSheet = true },
          modifier = Modifier.width(340.dp).fillMaxHeight(),
        )
        HorizontalDivider(modifier = Modifier.width(1.dp).fillMaxHeight())
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
          if (selectedConversation != null) {
            ChatDetailPane(
              conversation = selectedConversation,
              messages = messages,
              jobs = selectedChatJobs,
              settings = settings,
              quickReplies = quickReplies,
              isWide = true,
              isDictating = isDictating,
              dictatedText = dictatedText,
              pendingInsert = pendingComposerInsert,
              manualAiBusyAction = manualAiBusyAction,
              manualAiFeedback = manualAiFeedback,
              onConsumedInsert = { pendingComposerInsert = null },
              onBack = { onSelectChat(null) },
              onOpenMemorySheet = { showMemorySheet = true },
              onToggleAutoReply = { enabled ->
                onUpdateContactConfig(
                  selectedConversation,
                  enabled,
                  PermissionTier.fromName(selectedConversation.permissionTier),
                  selectedConversation.customInstructions,
                  selectedConversation.preferredLanguage,
                  selectedConversation.responseStyle,
                  selectedConversation.recentContextCountOverride,
                  selectedConversation.contextModeOverride,
                )
              },
              onToggleAdminTag = {
                val cur = PermissionTier.fromName(selectedConversation.permissionTier)
                val next = if (cur == PermissionTier.ADMIN) PermissionTier.STANDARD else PermissionTier.ADMIN
                onUpdateContactConfig(
                  selectedConversation,
                  selectedConversation.autoReplyEnabled,
                  next,
                  selectedConversation.customInstructions,
                  selectedConversation.preferredLanguage,
                  selectedConversation.responseStyle,
                  selectedConversation.recentContextCountOverride,
                  selectedConversation.contextModeOverride,
                )
              },
              onSendMessage = { text, quotedId, quotedPrev ->
                onSendMessage(selectedConversation.chatJid, text, quotedId, quotedPrev)
              },
              onSendAttachment = { uri, mime, name ->
                onSendAttachment(selectedConversation.chatJid, uri, mime, name)
              },
              onStartVoiceDictation = onStartVoiceDictation,
              onConsumeDictatedText = onConsumeDictatedText,
              onSpeakText = onSpeakText,
              onSummarizeChat = { onSummarizeChat(selectedConversation.chatJid) },
              onDraftSmartReply = { onDraftSmartReply(selectedConversation.chatJid) },
              onTranslateLast = {
                val last = messages.lastOrNull { it.textContent.isNotBlank() }?.textContent.orEmpty()
                if (last.isNotBlank()) onTranslateText(last, "English")
              },
              onInspectMedia = onInspectMedia,
              onExportChat = { onExportChat(selectedConversation, messages) },
              onReplyRight = { onReplyRight(selectedConversation.chatJid) },
              onKeepReplying = { onKeepReplying(selectedConversation.chatJid) },
              onDismissManualFeedback = onDismissManualFeedback,
              onEnableGroupChatting = onEnableGroupChatting,
              onAnalyzeCrm = { onRefreshBusinessCrm(selectedConversation.chatJid) },
            )
          } else {
            EmptyChatPlaceholder()
          }
        }
      }
    } else {
      if (selectedConversation != null) {
        BackHandler { onSelectChat(null) }
        ChatDetailPane(
          conversation = selectedConversation,
          messages = messages,
          jobs = selectedChatJobs,
          settings = settings,
          quickReplies = quickReplies,
          isWide = false,
          isDictating = isDictating,
          dictatedText = dictatedText,
          pendingInsert = pendingComposerInsert,
          manualAiBusyAction = manualAiBusyAction,
          manualAiFeedback = manualAiFeedback,
          onConsumedInsert = { pendingComposerInsert = null },
          onBack = { onSelectChat(null) },
          onOpenMemorySheet = { showMemorySheet = true },
          onToggleAutoReply = { enabled ->
            onUpdateContactConfig(
              selectedConversation,
              enabled,
              PermissionTier.fromName(selectedConversation.permissionTier),
              selectedConversation.customInstructions,
              selectedConversation.preferredLanguage,
              selectedConversation.responseStyle,
              selectedConversation.recentContextCountOverride,
              selectedConversation.contextModeOverride,
            )
          },
          onToggleAdminTag = {
            val cur = PermissionTier.fromName(selectedConversation.permissionTier)
            val next = if (cur == PermissionTier.ADMIN) PermissionTier.STANDARD else PermissionTier.ADMIN
            onUpdateContactConfig(
              selectedConversation,
              selectedConversation.autoReplyEnabled,
              next,
              selectedConversation.customInstructions,
              selectedConversation.preferredLanguage,
              selectedConversation.responseStyle,
              selectedConversation.recentContextCountOverride,
              selectedConversation.contextModeOverride,
            )
          },
          onSendMessage = { text, quotedId, quotedPrev ->
            onSendMessage(selectedConversation.chatJid, text, quotedId, quotedPrev)
          },
          onSendAttachment = { uri, mime, name ->
            onSendAttachment(selectedConversation.chatJid, uri, mime, name)
          },
          onStartVoiceDictation = onStartVoiceDictation,
          onConsumeDictatedText = onConsumeDictatedText,
          onSpeakText = onSpeakText,
          onSummarizeChat = { onSummarizeChat(selectedConversation.chatJid) },
          onDraftSmartReply = { onDraftSmartReply(selectedConversation.chatJid) },
          onTranslateLast = {
            val last = messages.lastOrNull { it.textContent.isNotBlank() }?.textContent.orEmpty()
            if (last.isNotBlank()) onTranslateText(last, "English")
          },
          onInspectMedia = onInspectMedia,
          onExportChat = { onExportChat(selectedConversation, messages) },
          onReplyRight = { onReplyRight(selectedConversation.chatJid) },
          onKeepReplying = { onKeepReplying(selectedConversation.chatJid) },
          onDismissManualFeedback = onDismissManualFeedback,
          onEnableGroupChatting = onEnableGroupChatting,
          onAnalyzeCrm = { onRefreshBusinessCrm(selectedConversation.chatJid) },
        )
      } else {
        ConversationListPane(
          conversations = conversations,
          selectedChatJid = null,
          settings = settings,
          onSelectChat = onSelectChat,
          onOpenBusinessCrm = { showBusinessCrmSheet = true },
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
  }

  // Scrollable, null-safe AI Summary / Smart Reply / Translation / Vision modal
  if (aiActionResult != null) {
    val cleanTitle = aiActionResult.title.ifBlank { "AI Insight" }
    val cleanBody = aiActionResult.text.takeIf { it.isNotBlank() && !it.equals("null", true) }.orEmpty()
    AlertDialog(
      onDismissRequest = onDismissAiAction,
      icon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
      title = { Text(cleanTitle) },
      text = {
        if (aiActionResult.isLoading) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(vertical = 8.dp),
          ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp))
            Text("Analyzing conversation…")
          }
        } else {
          Column(
            modifier =
              Modifier.fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
          ) {
            Text(
              text = cleanBody.ifBlank { "No insights available yet." },
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
      },
      confirmButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (!aiActionResult.isLoading && cleanBody.isNotBlank()) {
            OutlinedButton(
              onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText(cleanTitle, cleanBody))
              },
              modifier = Modifier.testTag("copy_ai_action_button"),
            ) {
              Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text("Copy")
            }
          }
          if (!aiActionResult.isLoading && aiActionResult.canInsertIntoComposer && cleanBody.isNotBlank()) {
            Button(
              onClick = {
                pendingComposerInsert = cleanBody
                onDismissAiAction()
              },
              modifier = Modifier.testTag("use_ai_draft_button"),
            ) {
              Text("Use in Reply")
            }
          }
        }
      },
      dismissButton = {
        TextButton(onClick = onDismissAiAction, modifier = Modifier.testTag("close_ai_action_button")) {
          Text("Close")
        }
      },
    )
  }

  if (showMemorySheet && selectedConversation != null) {
    ContactMemorySheet(
      conversation = selectedConversation,
      memories = memories,
      globalSettings = settings,
      onDismiss = { showMemorySheet = false },
      onSaveConfig = { autoReply, tier, customInstr, lang, style, countOverride, modeOverride ->
        onUpdateContactConfig(
          selectedConversation,
          autoReply,
          tier,
          customInstr,
          lang,
          style,
          countOverride,
          modeOverride,
        )
      },
      onAddMemory = { k, v -> onAddMemory(selectedConversation.chatJid, k, v) },
      onDeleteMemory = onDeleteMemory,
      onClearMemory = { onClearMemory(selectedConversation.chatJid) },
    )
  }

  if (showBusinessCrmSheet) {
    BusinessCrmSheet(
      conversations = conversations,
      settings = settings,
      isAnalyzing = isAnalyzingBusinessCrm,
      onDismiss = { showBusinessCrmSheet = false },
      onAnalyzeAll = { onRefreshBusinessCrm(null) },
      onOpenChat = { jid -> onSelectChat(jid) },
      onAnalyzeSingleChat = { jid -> onRefreshBusinessCrm(jid) },
    )
  }
}

@Composable
private fun ConversationListPane(
  conversations: List<ConversationEntity>,
  selectedChatJid: String?,
  settings: AgentSettings,
  onSelectChat: (String) -> Unit,
  onOpenBusinessCrm: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var searchQuery by remember { mutableStateOf("") }
  var activeTab by remember { mutableStateOf(ChatFilterTab.ALL) }

  val visibleTabs =
    remember(settings.businessModeEnabled) {
      if (settings.businessModeEnabled) ChatFilterTab.entries
      else listOf(ChatFilterTab.ALL, ChatFilterTab.UNREAD, ChatFilterTab.GROUPS)
    }

  val filtered =
    remember(conversations, searchQuery, activeTab) {
      conversations.filter { conv ->
        val matchesTab =
          when (activeTab) {
            ChatFilterTab.ALL -> true
            ChatFilterTab.UNREAD -> conv.unreadCount > 0
            ChatFilterTab.GROUPS -> conv.isGroup
            ChatFilterTab.BUSINESS ->
              conv.businessCategory.isNotBlank() || conv.businessLeadScore > 0 || conv.unreadCount > 0
          }
        val matchesSearch =
          searchQuery.isBlank() ||
            conv.displayName.contains(searchQuery, ignoreCase = true) ||
            conv.lastMessagePreview.contains(searchQuery, ignoreCase = true) ||
            conv.businessIntent.contains(searchQuery, ignoreCase = true) ||
            conv.chatJid.contains(searchQuery, ignoreCase = true)
        matchesTab && matchesSearch
      }
    }

  Column(
    modifier = modifier.background(MaterialTheme.colorScheme.background).padding(horizontal = 14.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    // Sleek Header Row
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column {
        Text(
          text = "Messages",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text =
            buildString {
              append("${conversations.size} active")
              if (!settings.autoReplyGroups) append(" • Group AI OFF")
              if (settings.businessModeEnabled) append(" • Business ON")
            },
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (settings.businessModeEnabled) {
        OutlinedButton(
          onClick = onOpenBusinessCrm,
          shape = CircleShape,
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
          modifier = Modifier.testTag("open_business_crm_button").minimumInteractiveComponentSize(),
        ) {
          Icon(
            Icons.Rounded.BusinessCenter,
            contentDescription = "Open Business CRM Dashboard",
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(16.dp),
          )
          Spacer(Modifier.width(6.dp))
          Text("CRM", fontWeight = FontWeight.SemiBold)
        }
      }
    }

    // Pill Search Bar
    OutlinedTextField(
      value = searchQuery,
      onValueChange = { searchQuery = it },
      placeholder = { Text("Search chats, groups, or intents…") },
      leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
      singleLine = true,
      shape = RoundedCornerShape(24.dp),
      colors =
        OutlinedTextFieldDefaults.colors(
          focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
          unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
        ),
      modifier = Modifier.fillMaxWidth().testTag("conversation_search_input"),
    )

    // Filter Pills
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      visibleTabs.forEach { tab ->
        FilterChip(
          selected = activeTab == tab,
          onClick = { activeTab = tab },
          label = { Text(tab.label) },
          modifier = Modifier.testTag("chat_filter_${tab.name.lowercase()}"),
        )
      }
    }

    if (filtered.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.padding(24.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.ChatBubbleOutline,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = if (conversations.isEmpty()) "No Chats Yet" else "No Matching Chats",
            style = MaterialTheme.typography.titleMedium,
          )
          Text(
            text =
              if (conversations.isEmpty()) {
                "Once your WhatsApp is linked on Home, incoming DMs and groups will appear here automatically."
              } else {
                "Try switching filters or clearing your search query."
              },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    } else {
      LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(filtered, key = { it.chatJid }) { conv ->
          val isSelected = conv.chatJid == selectedChatJid
          AnimatedVisibility(
            visible = true,
            enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) + slideInVertically { it / 10 },
          ) {
            SleekConversationCard(
              conv = conv,
              isSelected = isSelected,
              settings = settings,
              onClick = { onSelectChat(conv.chatJid) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SleekConversationCard(
  conv: ConversationEntity,
  isSelected: Boolean,
  settings: AgentSettings,
  onClick: () -> Unit,
) {
  val timeLabel =
    remember(conv.lastMessageTimestamp) {
      if (conv.lastMessageTimestamp > 0L) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(conv.lastMessageTimestamp))
      } else ""
    }

  val containerColor =
    if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
    else MaterialTheme.colorScheme.surface
  val borderColor =
    when {
      isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
      conv.unreadCount > 0 -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
      else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    }

  Surface(
    shape = RoundedCornerShape(20.dp),
    color = containerColor,
    tonalElevation = if (isSelected) 3.dp else 1.dp,
    modifier =
      Modifier.fillMaxWidth()
        .border(1.dp, borderColor, RoundedCornerShape(20.dp))
        .clickable(onClick = onClick)
        .testTag("conversation_item_${conv.chatJid}"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier.size(46.dp)
            .clip(CircleShape)
            .background(
              Brush.linearGradient(
                if (conv.isGroup) {
                  listOf(
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f),
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                  )
                } else {
                  listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                    MaterialTheme.colorScheme.surfaceVariant,
                  )
                }
              )
            ),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = if (conv.isGroup) Icons.Rounded.Group else Icons.Rounded.Person,
          contentDescription = if (conv.isGroup) "Group chat" else "Direct chat",
          tint = if (conv.isGroup) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
        )
      }

      Spacer(Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
          ) {
            Text(
              text = conv.displayName,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = if (conv.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false),
            )
            if (conv.isGroup) {
              Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
              ) {
                Text(
                  text = "Group",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSecondaryContainer,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
              }
            }
            val tier = PermissionTier.fromName(conv.permissionTier)
            if (tier != PermissionTier.STANDARD) {
              val tagColor =
                when (tier) {
                  PermissionTier.ADMIN, PermissionTier.OWNER -> MaterialTheme.colorScheme.primaryContainer
                  PermissionTier.BLOCKED -> MaterialTheme.colorScheme.errorContainer
                  else -> MaterialTheme.colorScheme.tertiaryContainer
                }
              val tagTextColor =
                when (tier) {
                  PermissionTier.ADMIN, PermissionTier.OWNER -> MaterialTheme.colorScheme.primary
                  PermissionTier.BLOCKED -> MaterialTheme.colorScheme.error
                  else -> MaterialTheme.colorScheme.onTertiaryContainer
                }
              Surface(
                shape = CircleShape,
                color = tagColor.copy(alpha = 0.85f),
                modifier = Modifier.testTag("chat_tag_badge_${conv.chatJid}_${tier.name.lowercase()}"),
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                  if (tier == PermissionTier.ADMIN) {
                    Icon(
                      Icons.Rounded.AdminPanelSettings,
                      contentDescription = null,
                      tint = tagTextColor,
                      modifier = Modifier.size(11.dp),
                    )
                  }
                  Text(
                    text = tier.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tagTextColor,
                  )
                }
              }
            }
          }
          if (timeLabel.isNotBlank()) {
            Text(
              text = timeLabel,
              style = MaterialTheme.typography.labelSmall,
              color =
                if (conv.unreadCount > 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        Text(
          text = conv.lastMessagePreview.ifBlank { conv.chatJid },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val aiActive = conv.autoReplyEnabled && (!conv.isGroup || settings.autoReplyGroups)
            Text(
              text =
                when {
                  conv.isGroup && !settings.autoReplyGroups -> "Group OFF"
                  aiActive -> "AI ON"
                  else -> "Paused"
                },
              style = MaterialTheme.typography.labelSmall,
              color = if (aiActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (settings.businessModeEnabled && conv.businessCategory.isNotBlank()) {
              Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
              ) {
                Text(
                  text = "${conv.businessCategory} • ${conv.businessLeadScore}%",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.secondary,
                  modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                )
              }
            }
          }

          if (conv.unreadCount > 0) {
            Box(
              modifier =
                Modifier.clip(CircleShape)
                  .background(MaterialTheme.colorScheme.primary)
                  .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
              Text(
                text = conv.unreadCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun EmptyChatPlaceholder() {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Icon(
        Icons.Rounded.ChatBubbleOutline,
        contentDescription = null,
        modifier = Modifier.size(48.dp),
        tint = MaterialTheme.colorScheme.secondary,
      )
      Text("Pick a conversation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(
        "Select any DM or WhatsApp Group to view AI context, summaries, or send a Reply Right.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

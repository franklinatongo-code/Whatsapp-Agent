package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.agent.ManualAiActionResult
import com.example.agent.ReplyStatusTracker
import com.example.data.db.ChatMessageEntity
import com.example.data.db.ConversationEntity
import com.example.data.db.MessageProcessingJobEntity
import com.example.data.prefs.AgentSettings
import com.example.security.PermissionTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun ChatDetailPane(
  conversation: ConversationEntity,
  messages: List<ChatMessageEntity>,
  jobs: List<MessageProcessingJobEntity>,
  settings: AgentSettings,
  quickReplies: List<String>,
  isWide: Boolean,
  isDictating: Boolean,
  dictatedText: String,
  pendingInsert: String?,
  manualAiBusyAction: String?,
  manualAiFeedback: ManualAiActionResult?,
  onConsumedInsert: () -> Unit,
  onBack: () -> Unit,
  onOpenMemorySheet: () -> Unit,
  onToggleAutoReply: (Boolean) -> Unit,
  onToggleAdminTag: () -> Unit,
  onSendMessage: (String, String?, String?) -> Unit,
  onSendAttachment: (Uri, String, String) -> Unit,
  onStartVoiceDictation: () -> Unit,
  onConsumeDictatedText: () -> Unit,
  onSpeakText: (String) -> Unit,
  onSummarizeChat: () -> Unit,
  onDraftSmartReply: () -> Unit,
  onTranslateLast: () -> Unit,
  onInspectMedia: (ChatMessageEntity) -> Unit,
  onExportChat: () -> Unit,
  onReplyRight: () -> Unit,
  onKeepReplying: () -> Unit,
  onDismissManualFeedback: () -> Unit,
  onEnableGroupChatting: () -> Unit,
  onAnalyzeCrm: () -> Unit,
) {
  val context = LocalContext.current
  var inputText by remember(conversation.chatJid) { mutableStateOf("") }
  var replyingToMessage by remember(conversation.chatJid) { mutableStateOf<ChatMessageEntity?>(null) }
  var showMoreMenu by remember { mutableStateOf(false) }
  val listState = rememberLazyListState()
  val tier = remember(conversation.permissionTier) { PermissionTier.fromName(conversation.permissionTier) }

  LaunchedEffect(dictatedText) {
    if (dictatedText.isNotBlank()) {
      inputText = if (inputText.isBlank()) dictatedText else "$inputText $dictatedText"
      onConsumeDictatedText()
    }
  }

  LaunchedEffect(pendingInsert) {
    if (!pendingInsert.isNullOrBlank()) {
      inputText = pendingInsert
      onConsumedInsert()
    }
  }

  val uniqueMessages = remember(messages) { messages.distinctBy { it.id } }
  val replyIndex = remember(uniqueMessages) { ReplyStatusTracker.analyze(uniqueMessages) }

  LaunchedEffect(uniqueMessages.size) {
    if (uniqueMessages.isNotEmpty()) {
      listState.animateScrollToItem(uniqueMessages.size - 1)
    }
  }

  val photoPicker =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      if (uri != null) {
        onSendAttachment(uri, "image/jpeg", "Photo attachment")
      }
    }

  val docPicker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        onSendAttachment(uri, "application/pdf", uri.lastPathSegment ?: "document")
      }
    }

  val micPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) onStartVoiceDictation()
    }

  val latestJob = jobs.firstOrNull()
  val activeStage = conversation.lastProcessingState.ifBlank { latestJob?.stage.orEmpty() }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .imePadding(),
  ) {
    // Sleek iOS + Subtle Sci-Fi Top Header
    Surface(
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
      tonalElevation = 2.dp,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (!isWide) {
            IconButton(
              onClick = onBack,
              modifier = Modifier.testTag("chat_back_button").minimumInteractiveComponentSize(),
            ) {
              Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to chats")
            }
          }

          Box(
            modifier =
              Modifier.size(38.dp)
                .clip(CircleShape)
                .background(
                  Brush.linearGradient(
                    listOf(
                      MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                      MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f),
                    )
                  )
                )
                .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = if (conversation.isGroup) Icons.Rounded.Group else Icons.Rounded.Person,
              contentDescription = if (conversation.isGroup) "Group chat" else "Direct chat",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(20.dp),
            )
          }

          Spacer(Modifier.width(10.dp))

          Column(modifier = Modifier.weight(1f)) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Text(
                text = conversation.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
              )
              if (conversation.isGroup) {
                Surface(
                  shape = CircleShape,
                  color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                ) {
                  Text(
                    text = "Group",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                  )
                }
              }
              if (tier != PermissionTier.STANDARD) {
                Surface(
                  shape = CircleShape,
                  color =
                    when (tier) {
                      PermissionTier.ADMIN, PermissionTier.OWNER -> MaterialTheme.colorScheme.primaryContainer
                      PermissionTier.BLOCKED -> MaterialTheme.colorScheme.errorContainer
                      else -> MaterialTheme.colorScheme.tertiaryContainer
                    },
                  modifier = Modifier.clickable(onClick = onOpenMemorySheet).testTag("header_permission_tag_pill"),
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                  ) {
                    if (tier == PermissionTier.ADMIN) {
                      Icon(
                        Icons.Rounded.AdminPanelSettings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp),
                      )
                    }
                    Text(
                      text = tier.label,
                      style = MaterialTheme.typography.labelSmall,
                      fontWeight = FontWeight.SemiBold,
                      color =
                        if (tier == PermissionTier.BLOCKED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                  }
                }
              }
            }
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              val statusColor =
                if (conversation.autoReplyEnabled && (!conversation.isGroup || settings.autoReplyGroups))
                  MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
              val statusLine =
                when {
                  conversation.isGroup && !settings.autoReplyGroups -> "Group AI OFF globally"
                  conversation.autoReplyEnabled -> "AI Auto-Reply ON"
                  else -> "Auto-Reply Paused"
                }
              Text(
                text = statusLine,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                maxLines = 1,
              )
              if (activeStage.isNotBlank()) {
                Text(
                  text = "• $activeStage",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.secondary,
                  maxLines = 1,
                )
              }
            }
          }

          IconButton(
            onClick = onSummarizeChat,
            modifier = Modifier.testTag("summarize_chat_button").minimumInteractiveComponentSize(),
          ) {
            Icon(
              Icons.Rounded.Summarize,
              contentDescription = "Summarize chat",
              tint = MaterialTheme.colorScheme.secondary,
            )
          }

          Switch(
            checked = conversation.autoReplyEnabled,
            onCheckedChange = onToggleAutoReply,
            modifier = Modifier.testTag("chat_header_autoreply_switch"),
          )

          Box {
            IconButton(
              onClick = { showMoreMenu = true },
              modifier = Modifier.testTag("chat_actions_menu_button").minimumInteractiveComponentSize(),
            ) {
              Icon(Icons.Rounded.MoreVert, contentDescription = "More chat actions")
            }
            DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
              DropdownMenuItem(
                text = { Text(if (tier == PermissionTier.ADMIN) "Remove Admin Tag" else "Tag as Admin") },
                leadingIcon = { Icon(Icons.Rounded.AdminPanelSettings, contentDescription = null) },
                onClick = {
                  showMoreMenu = false
                  onToggleAdminTag()
                },
                modifier = Modifier.testTag("toggle_admin_tag_menu_item"),
              )
              DropdownMenuItem(
                text = { Text("Memory, Tag & Rules") },
                leadingIcon = { Icon(Icons.Rounded.Psychology, contentDescription = null) },
                onClick = {
                  showMoreMenu = false
                  onOpenMemorySheet()
                },
                modifier = Modifier.testTag("contact_memory_button"),
              )
              DropdownMenuItem(
                text = { Text("AI Draft Smart Reply") },
                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
                onClick = {
                  showMoreMenu = false
                  onDraftSmartReply()
                },
              )
              DropdownMenuItem(
                text = { Text("Summarize This Chat") },
                leadingIcon = { Icon(Icons.Rounded.Summarize, contentDescription = null) },
                onClick = {
                  showMoreMenu = false
                  onSummarizeChat()
                },
              )
              if (settings.businessModeEnabled) {
                DropdownMenuItem(
                  text = { Text("Scan Business Lead CRM") },
                  leadingIcon = { Icon(Icons.Rounded.Bolt, contentDescription = null) },
                  onClick = {
                    showMoreMenu = false
                    onAnalyzeCrm()
                  },
                )
              }
              DropdownMenuItem(
                text = { Text("Translate Last Message") },
                leadingIcon = { Icon(Icons.Rounded.Translate, contentDescription = null) },
                onClick = {
                  showMoreMenu = false
                  onTranslateLast()
                },
              )
              DropdownMenuItem(
                text = { Text("Share / Export Chat") },
                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                onClick = {
                  showMoreMenu = false
                  onExportChat()
                },
              )
            }
          }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
      }
    }

    // Private Manual AI Action Banner (only visible to device owner)
    AnimatedVisibility(
      visible = manualAiFeedback != null,
      enter = fadeIn(spring(stiffness = Spring.StiffnessMedium)) + slideInVertically { -it / 2 },
      exit = fadeOut(),
    ) {
      manualAiFeedback?.let { fb ->
        val isError = !fb.success
        Surface(
          color =
            if (isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)
            else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
          modifier = Modifier.fillMaxWidth().testTag("manual_ai_feedback_banner"),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
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
                  imageVector = if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.Check,
                  contentDescription = null,
                  tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(18.dp),
                )
                Text(
                  text =
                    if (fb.success) "${fb.actionLabel} sent (${fb.deliveryState}): ${fb.replyPreview.orEmpty()}"
                    else fb.blockerReason ?: "${fb.actionLabel} could not complete.",
                  style = MaterialTheme.typography.bodySmall,
                  color =
                    if (isError) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                  maxLines = 3,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              IconButton(
                onClick = onDismissManualFeedback,
                modifier = Modifier.size(32.dp),
              ) {
                Icon(Icons.Rounded.Close, contentDescription = "Dismiss notification", modifier = Modifier.size(16.dp))
              }
            }
            if (fb.permissionBlockedGroup) {
              Button(
                onClick = onEnableGroupChatting,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("enable_group_chat_button").minimumInteractiveComponentSize(),
              ) {
                Text("Turn On Group Chatting")
              }
            }
          }
        }
      }
    }

    // Message Thread
    LazyColumn(
      state = listState,
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      modifier = Modifier.weight(1f).fillMaxWidth(),
    ) {
      items(uniqueMessages, key = { it.id }) { msg ->
        AnimatedVisibility(
          visible = true,
          enter = fadeIn(spring(stiffness = Spring.StiffnessMedium)) + slideInVertically { it / 6 },
        ) {
          SleekMessageBubble(
            msg = msg,
            isGroup = conversation.isGroup,
            isRepliedByOwner = !msg.fromMe && replyIndex.isReplied(msg.id),
            onSpeak = { onSpeakText(msg.textContent) },
            onInspectMedia = { onInspectMedia(msg) },
            onReplyToMessage = { replyingToMessage = msg },
          )
        }
      }
    }

    // Quick Reply Snippets Row
    if (quickReplies.isNotEmpty()) {
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        quickReplies.forEach { snippet ->
          AssistChip(
            onClick = { inputText = if (inputText.isBlank()) snippet else "$inputText $snippet" },
            label = { Text(snippet, maxLines = 1) },
          )
        }
      }
    }

    // Sleek Ergonomic AI Backup Dock (Reply Right & Keep Replying) + Composer
    Surface(
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 4.dp,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))

        // Dedicated Manual Backup Strip: Reply Right & Keep Replying
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .padding(horizontal = 10.dp, vertical = 6.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          val busyRight = manualAiBusyAction == "REPLY_RIGHT"
          val busyKeep = manualAiBusyAction == "KEEP_REPLYING"
          val anyBusy = manualAiBusyAction != null

          Button(
            onClick = onReplyRight,
            enabled = !anyBusy,
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            modifier = Modifier.weight(1f).testTag("reply_right_button").minimumInteractiveComponentSize(),
          ) {
            AnimatedContent(
              targetState = busyRight,
              transitionSpec = { fadeIn() togetherWith fadeOut() },
              label = "replyRightState",
            ) { loading ->
              if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                  Text("Replying…", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
              } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(17.dp))
                  Text("Reply Right", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
              }
            }
          }

          FilledTonalButton(
            onClick = onKeepReplying,
            enabled = !anyBusy,
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            modifier = Modifier.weight(1f).testTag("keep_replying_button").minimumInteractiveComponentSize(),
          ) {
            AnimatedContent(
              targetState = busyKeep,
              transitionSpec = { fadeIn() togetherWith fadeOut() },
              label = "keepReplyingState",
            ) { loading ->
              if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                  Text("Working…", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
              } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  Icon(Icons.Rounded.Replay, contentDescription = null, modifier = Modifier.size(17.dp))
                  Text("Keep Replying", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
              }
            }
          }

          IconButton(
            onClick = onDraftSmartReply,
            enabled = !anyBusy,
            modifier = Modifier.testTag("draft_smart_reply_button").minimumInteractiveComponentSize(),
          ) {
            Icon(
              Icons.Rounded.AutoAwesome,
              contentDescription = "Draft smart reply into box",
              tint = MaterialTheme.colorScheme.secondary,
            )
          }
        }

        // Replying-to message banner (targeted swipe/quote reply)
        AnimatedVisibility(visible = replyingToMessage != null) {
          replyingToMessage?.let { targetMsg ->
            Surface(
              color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
              shape = RoundedCornerShape(12.dp),
              modifier =
                Modifier.fillMaxWidth()
                  .padding(horizontal = 10.dp, vertical = 4.dp)
                  .testTag("replying_to_preview_bar"),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = "Replying to ${if (targetMsg.fromMe) "Me" else targetMsg.senderName.ifBlank { "message" }}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                  )
                  Text(
                    text = targetMsg.textContent.ifBlank { "[${targetMsg.messageType}]" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                IconButton(onClick = { replyingToMessage = null }, modifier = Modifier.size(32.dp)) {
                  Icon(Icons.Rounded.Close, contentDescription = "Cancel reply target", modifier = Modifier.size(16.dp))
                }
              }
            }
          }
        }

        // Composer Row
        Row(
          modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, bottom = 8.dp, top = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(
            onClick = {
              photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            modifier = Modifier.testTag("attach_image_button").minimumInteractiveComponentSize(),
          ) {
            Icon(Icons.Rounded.Image, contentDescription = stringResource(R.string.cd_attach_image))
          }
          IconButton(
            onClick = { docPicker.launch(arrayOf("application/pdf", "text/*")) },
            modifier = Modifier.testTag("attach_document_button").minimumInteractiveComponentSize(),
          ) {
            Icon(Icons.Rounded.AttachFile, contentDescription = stringResource(R.string.cd_attach_document))
          }
          IconButton(
            onClick = {
              if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
              ) {
                onStartVoiceDictation()
              } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
              }
            },
            modifier = Modifier.testTag("voice_dictate_button").minimumInteractiveComponentSize(),
          ) {
            Icon(
              Icons.Rounded.Mic,
              contentDescription = stringResource(R.string.cd_voice_dictate),
              tint = if (isDictating) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            placeholder = { Text("iMessage-style reply…") },
            maxLines = 4,
            shape = RoundedCornerShape(24.dp),
            colors =
              OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
              ),
            modifier = Modifier.weight(1f).testTag("message_input"),
          )
          Spacer(Modifier.width(6.dp))
          Box(
            modifier =
              Modifier.size(44.dp)
                .clip(CircleShape)
                .background(
                  if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.surfaceVariant
                )
                .clickable(enabled = inputText.isNotBlank()) {
                  onSendMessage(
                    inputText,
                    replyingToMessage?.id,
                    replyingToMessage?.textContent?.take(120),
                  )
                  inputText = ""
                  replyingToMessage = null
                }
                .testTag("send_message_button"),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              Icons.AutoMirrored.Rounded.Send,
              contentDescription = stringResource(R.string.cd_send_message),
              tint =
                if (inputText.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SleekMessageBubble(
  msg: ChatMessageEntity,
  isGroup: Boolean,
  isRepliedByOwner: Boolean,
  onSpeak: () -> Unit,
  onInspectMedia: () -> Unit,
  onReplyToMessage: () -> Unit,
) {
  val timeStr =
    remember(msg.timestamp) {
      SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
    }

  val localBitmap by
    produceState<ImageBitmap?>(initialValue = null, msg.mediaLocalPath, msg.messageType) {
      value =
        withContext(Dispatchers.IO) {
          val path = msg.mediaLocalPath
          if (!path.isNullOrBlank() && (msg.messageType == "IMAGE" || msg.messageType == "STICKER")) {
            runCatching {
              val file = File(path)
              if (!file.exists()) return@runCatching null
              val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
              BitmapFactory.decodeFile(file.absolutePath, bounds)
              var sample = 1
              while (bounds.outWidth / sample > 768 || bounds.outHeight / sample > 768) {
                sample *= 2
              }
              val opts = BitmapFactory.Options().apply { inSampleSize = sample }
              BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
            }.getOrNull()
          } else {
            null
          }
        }
    }

  var expandInsight by remember(msg.id) { mutableStateOf(false) }
  val hasInspectableMedia =
    !msg.mediaLocalPath.isNullOrBlank() &&
      msg.messageType in setOf("IMAGE", "STICKER", "DOCUMENT")
  val hasInsight = !msg.mediaTranscription.isNullOrBlank() && !msg.mediaTranscription.equals("null", true)

  val bubbleShape =
    RoundedCornerShape(
      topStart = 20.dp,
      topEnd = 20.dp,
      bottomStart = if (msg.fromMe) 20.dp else 6.dp,
      bottomEnd = if (msg.fromMe) 6.dp else 20.dp,
    )

  val bubbleColor =
    if (msg.fromMe) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)

  val borderColor =
    if (msg.fromMe) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (msg.fromMe) Arrangement.End else Arrangement.Start,
  ) {
    Surface(
      shape = bubbleShape,
      color = bubbleColor,
      modifier =
        Modifier.widthIn(max = 350.dp)
          .border(1.dp, borderColor, bubbleShape),
    ) {
      Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!msg.fromMe) {
          val senderColor =
            if (isGroup) {
              val palette =
                listOf(
                  MaterialTheme.colorScheme.primary,
                  MaterialTheme.colorScheme.secondary,
                  MaterialTheme.colorScheme.tertiary,
                )
              palette[abs(msg.senderName.hashCode()) % palette.size]
            } else {
              MaterialTheme.colorScheme.primary
            }
          Text(
            text = msg.senderName.ifBlank { "Sender" },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = senderColor,
          )
        }

        if (!msg.quotedPreview.isNullOrBlank()) {
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = if (msg.fromMe) "↩ Replied to: ${msg.quotedPreview}" else "↩ ${msg.quotedPreview}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            )
          }
        }

        if (localBitmap != null) {
          Image(
            bitmap = localBitmap!!,
            contentDescription = if (msg.messageType == "STICKER") "WhatsApp sticker" else "WhatsApp image",
            contentScale = if (msg.messageType == "STICKER") ContentScale.Fit else ContentScale.Crop,
            modifier =
              Modifier.fillMaxWidth()
                .height(if (msg.messageType == "STICKER") 132.dp else 188.dp)
                .clip(RoundedCornerShape(14.dp)),
          )
        } else if (msg.messageType == "DOCUMENT") {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(Icons.Rounded.Description, contentDescription = "Document", tint = MaterialTheme.colorScheme.secondary)
              Text(
                text = msg.mediaMimeType ?: "Document",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }

        if (msg.textContent.isNotBlank()) {
          Text(
            text = msg.textContent,
            style = MaterialTheme.typography.bodyLarge,
            color =
              if (msg.fromMe) MaterialTheme.colorScheme.onPrimaryContainer
              else MaterialTheme.colorScheme.onSurface,
          )
        }

        if (hasInsight) {
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth().clickable { expandInsight = !expandInsight },
          ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
              Text(
                text = if (expandInsight) "AI Vision / Transcript (tap to collapse)" else "AI Saw: ${msg.mediaTranscription!!.take(85)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = if (expandInsight) 12 else 2,
                overflow = TextOverflow.Ellipsis,
              )
              if (expandInsight) {
                Spacer(Modifier.height(2.dp))
                Text(
                  text = msg.mediaTranscription!!,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }
        }

        if (hasInspectableMedia) {
          AssistChip(
            onClick = onInspectMedia,
            leadingIcon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp)) },
            label = {
              Text(
                when (msg.messageType) {
                  "STICKER" -> "Explain Sticker"
                  "DOCUMENT" -> "Summarize File"
                  else -> "Inspect Image with AI"
                }
              )
            },
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (msg.textContent.isNotBlank()) {
              IconButton(onClick = onSpeak, modifier = Modifier.size(30.dp)) {
                Icon(
                  imageVector = Icons.Rounded.VolumeUp,
                  contentDescription = stringResource(R.string.cd_speak_aloud),
                  modifier = Modifier.size(15.dp),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            IconButton(
              onClick = onReplyToMessage,
              modifier = Modifier.size(30.dp).testTag("reply_to_msg_${msg.id}"),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Rounded.Reply,
                contentDescription = "Reply to this message",
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
          ) {
            if (isRepliedByOwner) {
              Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.testTag("replied_badge_${msg.id}"),
              ) {
                Text(
                  text = "✓ Replied",
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Medium,
                  color = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
              }
            }
            val statusColor =
              when (msg.deliveryStatus) {
                "FAILED" -> MaterialTheme.colorScheme.error
                "UNKNOWN" -> MaterialTheme.colorScheme.tertiary
                "SENT", "DELIVERED", "READ" -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
              }
            val meta =
              buildString {
                if (msg.isEdited) append("Edited • ")
                append(timeStr)
                if (msg.fromMe) append(" • ${msg.deliveryStatus}")
              }
            Text(meta, style = MaterialTheme.typography.labelSmall, color = statusColor)
            if (msg.fromMe && msg.deliveryStatus in setOf("SENT", "DELIVERED", "READ")) {
              Icon(
                imageVector = Icons.Rounded.DoneAll,
                contentDescription = "Delivered",
                tint = statusColor,
                modifier = Modifier.size(13.dp),
              )
            }
          }
        }
      }
    }
  }
}

package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.R
import com.example.ai.NimModelInfo
import com.example.data.db.AssistantMessageEntity
import com.example.data.db.ConversationEntity
import com.example.data.prefs.AgentSettings
import com.example.data.prefs.AppThemeMode
import com.example.whatsapp.WhatsAppConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val IMessageEmerald = Color(0xFF22A565)
private val IMessageEmeraldDark = Color(0xFF059669)
private val IMessageBubbleLight = Color(0xFFE9E9EB)
private val IMessageBubbleDark = Color(0xFF1F2937)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskAiScreen(
  messages: List<AssistantMessageEntity>,
  conversations: List<ConversationEntity>,
  settings: AgentSettings,
  connectionState: WhatsAppConnectionState,
  discoveredModels: List<NimModelInfo>,
  isTyping: Boolean,
  streamingPreview: String,
  activeToolNote: String?,
  isDictating: Boolean,
  dictatedText: String,
  onSendToAi: (text: String, imageUri: Uri?, fileUri: Uri?) -> Unit,
  onToggleReaction: (messageId: String, emoji: String) -> Unit,
  onDeleteMessage: (messageId: String) -> Unit,
  onClearChat: () -> Unit,
  onStartVoiceDictation: () -> Unit,
  onConsumeDictatedText: () -> Unit,
  onSpeakText: (String) -> Unit,
  onNavigateBackHome: () -> Unit,
  onNavigateToStudio: () -> Unit,
) {
  val context = LocalContext.current
  val isDark =
    when (settings.themeMode) {
      AppThemeMode.DARK, AppThemeMode.OLED -> true
      AppThemeMode.LIGHT -> false
      AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

  val incomingBubbleColor = if (isDark) IMessageBubbleDark else IMessageBubbleLight
  val incomingTextColor = if (isDark) Color(0xFFF1F5F9) else Color(0xFF111827)
  val outgoingBubbleColor = if (isDark) IMessageEmeraldDark else IMessageEmerald
  val chatSurfaceBg =
    when (settings.themeMode) {
      AppThemeMode.OLED -> Color.Black
      AppThemeMode.DARK -> MaterialTheme.colorScheme.background
      else -> Color(0xFFF6F7F8)
    }
  val headerBg =
    when (settings.themeMode) {
      AppThemeMode.OLED -> Color(0xFF0A0D10)
      AppThemeMode.DARK -> MaterialTheme.colorScheme.surface
      else -> Color(0xFFFCFCFD)
    }

  var draftText by remember { mutableStateOf("") }
  var pendingImageUri by remember { mutableStateOf<Uri?>(null) }
  var pendingFileUri by remember { mutableStateOf<Uri?>(null) }
  var pendingFileLabel by remember { mutableStateOf<String?>(null) }
  var showAttachMenu by remember { mutableStateOf(false) }
  var showMoreMenu by remember { mutableStateOf(false) }
  var showContactSheet by remember { mutableStateOf(false) }
  var showClearConfirm by remember { mutableStateOf(false) }
  var fullscreenImagePath by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(dictatedText) {
    if (dictatedText.isNotBlank()) {
      draftText = if (draftText.isBlank()) dictatedText else "$draftText $dictatedText"
      onConsumeDictatedText()
    }
  }

  val imagePicker =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      if (uri != null) {
        pendingImageUri = uri
      }
    }

  val filePicker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        pendingFileUri = uri
        pendingFileLabel = resolveDisplayName(context, uri) ?: "Document"
      }
    }

  val micPermLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) onStartVoiceDictation()
    }

  val listState = rememberLazyListState()
  LaunchedEffect(messages.size, isTyping, streamingPreview) {
    val totalItems = messages.size + (if (isTyping) 1 else 0) + (if (messages.isEmpty()) 1 else 0)
    if (totalItems > 0) {
      listState.animateScrollToItem((totalItems - 1).coerceAtLeast(0))
    }
  }

  val activeModelLabel =
    remember(settings.selectedModelId, discoveredModels) {
      val id =
        settings.selectedModelId.ifBlank {
          discoveredModels.firstOrNull { it.isChatCapable }?.id.orEmpty()
        }
      if (id.isBlank()) {
        "Set NVIDIA key in AI tab"
      } else {
        id.substringAfterLast('/').take(28)
      }
    }

  val subtitleText =
    when {
      isTyping && !activeToolNote.isNullOrBlank() -> "AI • $activeToolNote"
      isTyping -> "AI • typing…"
      connectionState is WhatsAppConnectionState.Connected -> "AI • $activeModelLabel • WA Live"
      else -> "AI • $activeModelLabel"
    }

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .background(chatSurfaceBg)
  ) {
    // iMessage-inspired Top Header Bar
    Surface(
      color = headerBg,
      shadowElevation = 0.dp,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .widthIn(max = 760.dp)
              .align(Alignment.CenterHorizontally)
              .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
          IconButton(
            onClick = onNavigateBackHome,
            modifier =
              Modifier
                .align(Alignment.TopStart)
                .minimumInteractiveComponentSize()
                .testTag("ask_ai_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
              contentDescription = "Back to Home",
              tint = MaterialTheme.colorScheme.onSurface,
            )
          }

          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
          ) {
            Box(
              modifier =
                Modifier
                  .size(46.dp)
                  .clip(CircleShape)
                  .background(outgoingBubbleColor),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                imageVector = Icons.Rounded.SmartToy,
                contentDescription = stringResource(R.string.cd_ai_avatar),
                tint = Color.White,
                modifier = Modifier.size(26.dp),
              )
            }
            Spacer(Modifier.height(6.dp))
            Text(
              text = "${settings.agentIdentityName} WhatsApp Agent",
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
              if (isTyping) {
                TypingTinyDot(color = outgoingBubbleColor)
              }
              Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isTyping) outgoingBubbleColor else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }

          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.TopEnd),
          ) {
            IconButton(
              onClick = { showContactSheet = true },
              modifier = Modifier.minimumInteractiveComponentSize().testTag("ask_ai_contacts_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.PersonSearch,
                contentDescription = "Pick contact for AI command",
                tint = MaterialTheme.colorScheme.onSurface,
              )
            }
            Box {
              IconButton(
                onClick = { showMoreMenu = true },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("ask_ai_more_button"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.MoreHoriz,
                  contentDescription = "More AI chat options",
                  tint = MaterialTheme.colorScheme.onSurface,
                )
              }
              DropdownMenu(
                expanded = showMoreMenu,
                onDismissRequest = { showMoreMenu = false },
              ) {
                DropdownMenuItem(
                  text = { Text("Pick Contact to Summarize / Text") },
                  leadingIcon = { Icon(Icons.Rounded.PersonSearch, contentDescription = null) },
                  onClick = {
                    showMoreMenu = false
                    showContactSheet = true
                  },
                )
                val lastAi = messages.lastOrNull { it.role != "user" }
                if (lastAi != null) {
                  DropdownMenuItem(
                    text = { Text("Read Last Reply Aloud") },
                    leadingIcon = { Icon(Icons.Rounded.VolumeUp, contentDescription = null) },
                    onClick = {
                      showMoreMenu = false
                      onSpeakText(lastAi.textContent)
                    },
                  )
                }
                DropdownMenuItem(
                  text = { Text("AI Model & Key Settings") },
                  leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                  onClick = {
                    showMoreMenu = false
                    onNavigateToStudio()
                  },
                )
                if (messages.isNotEmpty()) {
                  DropdownMenuItem(
                    text = { Text("Clear Conversation") },
                    leadingIcon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
                    onClick = {
                      showMoreMenu = false
                      showClearConfirm = true
                    },
                  )
                }
              }
            }
          }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
      }
    }

    // Chat Message Feed
    LazyColumn(
      state = listState,
      modifier =
        Modifier
          .weight(1f)
          .fillMaxWidth()
          .widthIn(max = 760.dp)
          .align(Alignment.CenterHorizontally)
          .testTag("ask_ai_message_list"),
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (messages.isEmpty()) {
        item(key = "welcome_intro") {
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IncomingAiBubble(
              text =
                "Hi! I'm ${settings.agentIdentityName}, your AI WhatsApp Agent.\n" +
                  "Ask me to summarize a conversation, text someone a certain way, or inspect pictures and files you send me.",
              timeLabel = formatTime(System.currentTimeMillis()),
              bubbleColor = incomingBubbleColor,
              textColor = incomingTextColor,
              toolSummary = null,
              reactionEmoji = null,
              onSpeak = {
                onSpeakText(
                  "Hi! I'm ${settings.agentIdentityName}, your AI WhatsApp Agent. How can I help you today?"
                )
              },
              onCopy = {},
              onReact = {},
              onDelete = null,
            )

            // Quick starter prompt pills
            Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              AssistChip(
                onClick = {
                  if (conversations.isNotEmpty()) showContactSheet = true
                  else draftText = "Summarize my unread WhatsApp chats"
                },
                label = { Text("Summarize a chat") },
                leadingIcon = { Icon(Icons.Rounded.Summarize, contentDescription = null, modifier = Modifier.size(16.dp)) },
              )
              AssistChip(
                onClick = {
                  if (conversations.isNotEmpty()) showContactSheet = true
                  else draftText = "Text John nicely that I am running 10 minutes late"
                },
                label = { Text("Text someone for me") },
                leadingIcon = { Icon(Icons.Rounded.SmartToy, contentDescription = null, modifier = Modifier.size(16.dp)) },
              )
              AssistChip(
                onClick = {
                  imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                label = { Text("Look at a picture") },
                leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp)) },
              )
              AssistChip(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                label = { Text("Inspect a file") },
                leadingIcon = { Icon(Icons.Rounded.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp)) },
              )
            }
          }
        }
      }

      items(messages, key = { it.id }) { msg ->
        val timeLabel = remember(msg.timestamp) { formatTime(msg.timestamp) }
        if (msg.role == "user") {
          OutgoingUserBubble(
            message = msg,
            timeLabel = timeLabel,
            bubbleColor = outgoingBubbleColor,
            onImageClick = { path -> fullscreenImagePath = path },
            onDelete = { onDeleteMessage(msg.id) },
          )
        } else {
          IncomingAiBubble(
            text = msg.textContent,
            timeLabel = timeLabel,
            bubbleColor = incomingBubbleColor,
            textColor = incomingTextColor,
            toolSummary = msg.toolActivitySummary,
            reactionEmoji = msg.reactionEmoji,
            onSpeak = { onSpeakText(msg.textContent) },
            onCopy = { copyToClipboard(context, msg.textContent) },
            onReact = { emoji -> onToggleReaction(msg.id, emoji) },
            onDelete = { onDeleteMessage(msg.id) },
          )
        }
      }

      if (isTyping) {
        item(key = "typing_bubble") {
          LiveTypingBubble(
            streamingText = streamingPreview,
            toolNote = activeToolNote,
            bubbleColor = incomingBubbleColor,
            textColor = incomingTextColor,
            accentColor = outgoingBubbleColor,
          )
        }
      }
    }

    // Queued attachment preview bar
    AnimatedVisibility(visible = pendingImageUri != null || pendingFileUri != null) {
      Surface(
        color = headerBg,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .widthIn(max = 760.dp)
              .padding(horizontal = 14.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          pendingImageUri?.let { imgUri ->
            QueuedImagePreviewChip(
              uri = imgUri,
              onRemove = { pendingImageUri = null },
            )
          }
          pendingFileUri?.let { _ ->
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Description,
                  contentDescription = null,
                  tint = outgoingBubbleColor,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                  text = pendingFileLabel ?: "Attached file",
                  style = MaterialTheme.typography.labelMedium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.widthIn(max = 160.dp),
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                  imageVector = Icons.Rounded.Close,
                  contentDescription = "Remove file attachment",
                  modifier =
                    Modifier
                      .size(18.dp)
                      .clickable {
                        pendingFileUri = null
                        pendingFileLabel = null
                      },
                )
              }
            }
          }
        }
      }
    }

    // iMessage-style Pill Composer
    Surface(
      color = headerBg,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .widthIn(max = 760.dp)
              .align(Alignment.CenterHorizontally)
              .padding(horizontal = 10.dp, vertical = 8.dp),
          verticalAlignment = Alignment.Bottom,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Box {
            IconButton(
              onClick = { showAttachMenu = true },
              modifier =
                Modifier
                  .size(48.dp)
                  .testTag("ask_ai_attach_button"),
            ) {
              Box(
                modifier =
                  Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(incomingBubbleColor),
                contentAlignment = Alignment.Center,
              ) {
                Icon(
                  imageVector = Icons.Rounded.Add,
                  contentDescription = stringResource(R.string.cd_attach_ai),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(20.dp),
                )
              }
            }

            DropdownMenu(
              expanded = showAttachMenu,
              onDismissRequest = { showAttachMenu = false },
            ) {
              DropdownMenuItem(
                text = { Text("Send Picture") },
                leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null) },
                onClick = {
                  showAttachMenu = false
                  imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
              )
              DropdownMenuItem(
                text = { Text("Attach File, PDF, or Audio") },
                leadingIcon = { Icon(Icons.Rounded.AttachFile, contentDescription = null) },
                onClick = {
                  showAttachMenu = false
                  filePicker.launch(arrayOf("*/*"))
                },
              )
              DropdownMenuItem(
                text = { Text(if (isDictating) "Listening…" else "Voice Dictate") },
                leadingIcon = { Icon(Icons.Rounded.Mic, contentDescription = null) },
                onClick = {
                  showAttachMenu = false
                  if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                  ) {
                    onStartVoiceDictation()
                  } else {
                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                  }
                },
              )
              if (conversations.isNotEmpty()) {
                DropdownMenuItem(
                  text = { Text("Pick Contact Action") },
                  leadingIcon = { Icon(Icons.Rounded.PersonSearch, contentDescription = null) },
                  onClick = {
                    showAttachMenu = false
                    showContactSheet = true
                  },
                )
              }
            }
          }

          // Pill text input
          Surface(
            shape = RoundedCornerShape(24.dp),
            color = if (isDark) Color(0xFF151C24) else Color.White,
            modifier =
              Modifier
                .weight(1f)
                .border(
                  width = 1.dp,
                  color = MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.5f else 0.35f),
                  shape = RoundedCornerShape(24.dp),
                ),
          ) {
            Box(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
              contentAlignment = Alignment.CenterStart,
            ) {
              if (draftText.isEmpty()) {
                Text(
                  text = if (isDictating) "Listening…" else "Text ${settings.agentIdentityName}…",
                  style = MaterialTheme.typography.bodyLarge,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
              }
              BasicTextField(
                value = draftText,
                onValueChange = { draftText = it },
                maxLines = 4,
                textStyle =
                  TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                  ),
                cursorBrush = SolidColor(outgoingBubbleColor),
                modifier = Modifier.fillMaxWidth().testTag("ask_ai_input"),
              )
            }
          }

          val canSend = draftText.isNotBlank() || pendingImageUri != null || pendingFileUri != null
          IconButton(
            onClick = {
              if (!canSend || isTyping) return@IconButton
              val textToSend = draftText.trim()
              val img = pendingImageUri
              val file = pendingFileUri
              draftText = ""
              pendingImageUri = null
              pendingFileUri = null
              pendingFileLabel = null
              onSendToAi(textToSend, img, file)
            },
            enabled = canSend && !isTyping,
            modifier =
              Modifier
                .size(48.dp)
                .testTag("ask_ai_send_button"),
          ) {
            Box(
              modifier =
                Modifier
                  .size(34.dp)
                  .clip(CircleShape)
                  .background(
                    if (canSend && !isTyping) outgoingBubbleColor
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                  ),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                imageVector = Icons.Rounded.ArrowUpward,
                contentDescription = stringResource(R.string.cd_send_ai),
                tint = Color.White,
                modifier = Modifier.size(20.dp),
              )
            }
          }
        }
      }
    }
  }

  // Contact Picker Quick-Command Bottom Sheet
  if (showContactSheet) {
    ModalBottomSheet(onDismissRequest = { showContactSheet = false }) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          text = "Quick WhatsApp AI Actions",
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        Text(
          text = "Pick a contact to pre-fill a summarize or send-message command for your AI.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (conversations.isEmpty()) {
          Text(
            text = "No WhatsApp chats synced yet. Link WhatsApp on Home or just type any person's name/phone number directly!",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 12.dp),
          )
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxWidth().height(320.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(conversations, key = { it.chatJid }) { conv ->
              Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text(
                    text = conv.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                      onClick = {
                        showContactSheet = false
                        onSendToAi("Summarize my conversation with ${conv.displayName}", null, null)
                      },
                      label = { Text("Summarize") },
                    )
                    AssistChip(
                      onClick = {
                        showContactSheet = false
                        draftText = "Text ${conv.displayName} in a friendly tone: "
                      },
                      label = { Text("Text them") },
                    )
                    AssistChip(
                      onClick = {
                        showContactSheet = false
                        draftText = "Reply to ${conv.displayName} with shorter, casual messages from now on"
                      },
                      label = { Text("Set AI style") },
                    )
                  }
                }
              }
            }
          }
        }
        Spacer(Modifier.height(16.dp))
      }
    }
  }

  if (showClearConfirm) {
    AlertDialog(
      onDismissRequest = { showClearConfirm = false },
      title = { Text("Clear Text AI Thread?") },
      text = { Text("This deletes the private chat history between you and ${settings.agentIdentityName}. WhatsApp chats and saved contact memories stay intact.") },
      confirmButton = {
        TextButton(
          onClick = {
            showClearConfirm = false
            onClearChat()
          }
        ) {
          Text("Clear")
        }
      },
      dismissButton = {
        TextButton(onClick = { showClearConfirm = false }) {
          Text("Cancel")
        }
      },
    )
  }

  fullscreenImagePath?.let { path ->
    Dialog(
      onDismissRequest = { fullscreenImagePath = null },
      properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
      Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
          val fullBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
            value =
              withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
              }
          }
          fullBitmap?.let { bmp ->
            Image(
              bitmap = bmp,
              contentDescription = "Full size picture",
              contentScale = ContentScale.Fit,
              modifier = Modifier.fillMaxSize().padding(16.dp),
            )
          }
          IconButton(
            onClick = { fullscreenImagePath = null },
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).minimumInteractiveComponentSize(),
          ) {
            Icon(Icons.Rounded.Close, contentDescription = "Close picture", tint = Color.White)
          }
        }
      }
    }
  }
}

@Composable
private fun IncomingAiBubble(
  text: String,
  timeLabel: String,
  bubbleColor: Color,
  textColor: Color,
  toolSummary: String?,
  reactionEmoji: String?,
  onSpeak: () -> Unit,
  onCopy: () -> Unit,
  onReact: (String) -> Unit,
  onDelete: (() -> Unit)?,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.Start,
  ) {
    Box(modifier = Modifier.widthIn(max = 310.dp)) {
      // Bottom-left iMessage tail
      Canvas(
        modifier =
          Modifier
            .align(Alignment.BottomStart)
            .offset(x = (-5).dp, y = (-1).dp)
            .size(14.dp, 14.dp)
      ) {
        val path =
          Path().apply {
            moveTo(size.width, 0f)
            lineTo(size.width, size.height)
            quadraticTo(size.width * 0.35f, size.height, 0f, size.height * 0.85f)
            quadraticTo(size.width * 0.55f, size.height * 0.5f, size.width * 0.5f, 0f)
            close()
          }
        drawPath(path, color = bubbleColor)
      }

      Surface(
        shape = RoundedCornerShape(18.dp),
        color = bubbleColor,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          if (!toolSummary.isNullOrBlank()) {
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Bolt,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                  text = toolSummary,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary,
                )
              }
            }
          }
          SelectionContainer {
            Text(
              text = text,
              style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
              color = textColor,
            )
          }
        }
      }

      if (!reactionEmoji.isNullOrBlank()) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.surface,
          shadowElevation = 2.dp,
          modifier = Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 8.dp),
        ) {
          Text(
            text = reactionEmoji,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          )
        }
      }
    }

    Spacer(Modifier.height(4.dp))
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      modifier = Modifier.padding(start = 6.dp),
    ) {
      Text(
        text = timeLabel,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Icon(
        imageVector = Icons.Rounded.VolumeUp,
        contentDescription = stringResource(R.string.cd_speak_aloud),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(16.dp).clickable { onSpeak() },
      )
      Icon(
        imageVector = Icons.Rounded.ContentCopy,
        contentDescription = "Copy reply",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(15.dp).clickable { onCopy() },
      )
      Text(
        text = "❤️",
        fontSize = 13.sp,
        modifier = Modifier.clickable { onReact("❤️") },
      )
      Text(
        text = "👍",
        fontSize = 13.sp,
        modifier = Modifier.clickable { onReact("👍") },
      )
      if (onDelete != null) {
        Icon(
          imageVector = Icons.Rounded.DeleteOutline,
          contentDescription = "Delete message",
          tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
          modifier = Modifier.size(15.dp).clickable { onDelete() },
        )
      }
    }
  }
}

@Composable
private fun OutgoingUserBubble(
  message: AssistantMessageEntity,
  timeLabel: String,
  bubbleColor: Color,
  onImageClick: (String) -> Unit,
  onDelete: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.End,
  ) {
    Box(modifier = Modifier.widthIn(max = 300.dp)) {
      // Bottom-right iMessage tail
      Canvas(
        modifier =
          Modifier
            .align(Alignment.BottomEnd)
            .offset(x = 5.dp, y = (-1).dp)
            .size(14.dp, 14.dp)
      ) {
        val path =
          Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, size.height)
            quadraticTo(size.width * 0.65f, size.height, size.width, size.height * 0.85f)
            quadraticTo(size.width * 0.45f, size.height * 0.5f, size.width * 0.5f, 0f)
            close()
          }
        drawPath(path, color = bubbleColor)
      }

      Surface(
        shape = RoundedCornerShape(18.dp),
        color = bubbleColor,
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          val imgPath = message.imageLocalPath
          if (!imgPath.isNullOrBlank()) {
            val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = imgPath) {
              value = withContext(Dispatchers.IO) { decodeSampledBitmap(imgPath, 720) }
            }
            bitmap?.let { bmp ->
              Image(
                bitmap = bmp,
                contentDescription = "Sent picture",
                contentScale = ContentScale.Crop,
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onImageClick(imgPath) },
              )
            }
          }

          if (!message.fileLocalPath.isNullOrBlank()) {
            val isAudio = message.fileMimeType?.startsWith("audio/", true) == true
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = Color.White.copy(alpha = 0.18f),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
              ) {
                Icon(
                  imageVector = if (isAudio) Icons.Rounded.AudioFile else Icons.Rounded.Description,
                  contentDescription = null,
                  tint = Color.White,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                  text = message.fileName ?: "Attached file",
                  style = MaterialTheme.typography.labelMedium,
                  color = Color.White,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }

          if (message.textContent.isNotBlank()) {
            Text(
              text = message.textContent,
              style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
              color = Color.White,
            )
          }

          Row(
            modifier = Modifier.align(Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text(
              text = timeLabel,
              style = MaterialTheme.typography.labelSmall,
              color = Color.White.copy(alpha = 0.85f),
            )
            Icon(
              imageVector = Icons.Rounded.DoneAll,
              contentDescription = "Delivered",
              tint = Color.White.copy(alpha = 0.9f),
              modifier = Modifier.size(14.dp),
            )
            Icon(
              imageVector = Icons.Rounded.DeleteOutline,
              contentDescription = "Delete message",
              tint = Color.White.copy(alpha = 0.65f),
              modifier = Modifier.size(13.dp).clickable { onDelete() },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun LiveTypingBubble(
  streamingText: String,
  toolNote: String?,
  bubbleColor: Color,
  textColor: Color,
  accentColor: Color,
) {
  Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
    Surface(
      shape = RoundedCornerShape(18.dp),
      color = bubbleColor,
      modifier = Modifier.widthIn(max = 310.dp),
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        if (!toolNote.isNullOrBlank()) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Rounded.Bolt,
              contentDescription = null,
              tint = accentColor,
              modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
              text = toolNote,
              style = MaterialTheme.typography.labelSmall,
              color = accentColor,
            )
          }
        }
        if (streamingText.isNotBlank()) {
          Text(
            text = streamingText,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
            color = textColor,
          )
        } else {
          Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp),
          ) {
            TypingTinyDot(accentColor, delayMs = 0)
            TypingTinyDot(accentColor, delayMs = 150)
            TypingTinyDot(accentColor, delayMs = 300)
          }
        }
      }
    }
  }
}

@Composable
private fun TypingTinyDot(color: Color, delayMs: Int = 0) {
  val inf = rememberInfiniteTransition(label = "typing_dot")
  val alpha by inf.animateFloat(
    initialValue = 0.25f,
    targetValue = 1f,
    animationSpec =
      infiniteRepeatable(
        animation = tween(durationMillis = 600, delayMillis = delayMs),
        repeatMode = RepeatMode.Reverse,
      ),
    label = "dot_alpha",
  )
  Box(
    modifier =
      Modifier
        .size(7.dp)
        .alpha(alpha)
        .clip(CircleShape)
        .background(color)
  )
}

@Composable
private fun QueuedImagePreviewChip(
  uri: Uri,
  onRemove: () -> Unit,
) {
  val context = LocalContext.current
  val thumb by produceState<ImageBitmap?>(initialValue = null, key1 = uri) {
    value =
      withContext(Dispatchers.IO) {
        runCatching {
          context.contentResolver.openInputStream(uri)?.use { input ->
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeStream(input, null, opts)?.asImageBitmap()
          }
        }.getOrNull()
      }
  }

  Box(modifier = Modifier.size(56.dp)) {
    if (thumb != null) {
      Image(
        bitmap = thumb!!,
        contentDescription = "Queued picture",
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
      )
    } else {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxSize(),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(Icons.Rounded.Image, contentDescription = null)
        }
      }
    }
    Surface(
      shape = CircleShape,
      color = Color.Black.copy(alpha = 0.7f),
      modifier =
        Modifier
          .align(Alignment.TopEnd)
          .offset(x = 4.dp, y = (-4).dp)
          .size(20.dp)
          .clickable { onRemove() },
    ) {
      Icon(
        imageVector = Icons.Rounded.Close,
        contentDescription = "Remove picture",
        tint = Color.White,
        modifier = Modifier.padding(3.dp),
      )
    }
  }
}

private fun decodeSampledBitmap(path: String, maxDim: Int): ImageBitmap? {
  return runCatching {
    val file = File(path)
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
      sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
  }.getOrNull()
}

private fun resolveDisplayName(context: Context, uri: Uri): String? {
  return runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
      if (c.moveToFirst()) {
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0) c.getString(idx) else null
      } else null
    }
  }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
}

private fun copyToClipboard(context: Context, text: String) {
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
  cm.setPrimaryClip(ClipData.newPlainText("ClawLink AI Reply", text))
}

private fun formatTime(millis: Long): String {
  return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
}

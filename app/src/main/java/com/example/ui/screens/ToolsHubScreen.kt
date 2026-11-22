package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ScheduleSend
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.agent.AutomationRulesEngine
import com.example.agent.HabitItem
import com.example.agent.KeywordMatchMode
import com.example.agent.KeywordRule
import com.example.agent.NoteItem
import com.example.agent.TodoItem
import com.example.data.db.AgentLogEntity
import com.example.data.db.ConversationEntity
import com.example.data.db.ScheduledMessageEntity
import com.example.data.prefs.AgentSettings
import com.example.data.prefs.AppThemeMode
import com.example.tools.UtilityToolkit
import com.example.ui.DiagnosticTestResult
import com.example.whatsapp.WhatsAppConnectionState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ToolCategory(val label: String) {
  ALL("All"),
  WHATSAPP("WhatsApp"),
  FOCUS("Notes & Focus"),
  TEXT_QR("Text & QR"),
  MATH_UNITS("Math & Money"),
  DEV_CRYPTO("Dev & Security"),
  SYSTEM("Device & Backup"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsHubScreen(
  connectionState: WhatsAppConnectionState,
  settings: AgentSettings,
  conversations: List<ConversationEntity>,
  scheduledMessages: List<ScheduledMessageEntity>,
  logs: List<AgentLogEntity>,
  signalSessionsCount: Int,
  signalPreKeysCount: Int,
  diagnosticResult: DiagnosticTestResult,
  webViewHealthSummary: String?,
  visionInspectorResult: String?,
  maskedKeyStatus: String,
  backupStatusMessage: String?,
  broadcastStatusMessage: String?,
  rulesEngine: AutomationRulesEngine,
  onUpdateSettings: ((AgentSettings) -> AgentSettings) -> Unit,
  onSendBroadcast: (List<String>, String) -> Unit,
  onScheduleMessage: (String, String, String, Long) -> Unit,
  onCancelScheduledMessage: (String) -> Unit,
  onInspectMediaUri: (android.net.Uri, String, Boolean) -> Unit,
  onSpeakText: (String) -> Unit,
  onTriggerHaptic: () -> Unit,
  onExportBackup: (android.net.Uri) -> Unit,
  onImportBackup: (android.net.Uri) -> Unit,
  onReplenishPreKeys: () -> Unit,
  onRunAiDiagnostic: () -> Unit,
  onRunWebViewHealthCheck: () -> Unit,
  onClearLogs: () -> Unit,
) {
  var searchQuery by remember { mutableStateOf("") }
  var selectedCategory by remember { mutableStateOf(ToolCategory.ALL) }

  fun shouldShow(category: ToolCategory, vararg keywords: String): Boolean {
    val catMatch = selectedCategory == ToolCategory.ALL || selectedCategory == category
    val qMatch =
      searchQuery.isBlank() ||
        category.label.contains(searchQuery, ignoreCase = true) ||
        keywords.any { it.contains(searchQuery, ignoreCase = true) }
    return catMatch && qMatch
  }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 14.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        placeholder = { Text("Search tools (QR, Pomodoro, Calculator, Rules, Backup…)") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("tools_search_input"),
      )

      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        ToolCategory.entries.forEach { cat ->
          FilterChip(
            selected = selectedCategory == cat,
            onClick = { selectedCategory = cat },
            label = { Text(cat.label) },
          )
        }
      }

      if (shouldShow(ToolCategory.WHATSAPP, "sticker", "vision", "picture", "pdf", "file")) {
        VisionLabCard(
          visionInspectorResult = visionInspectorResult,
          onInspectMediaUri = onInspectMediaUri,
        )
      }

      if (shouldShow(ToolCategory.WHATSAPP, "schedule", "reminder", "later", "timer", "send")) {
        ScheduledMessagesCard(
          conversations = conversations,
          scheduledMessages = scheduledMessages,
          onScheduleMessage = onScheduleMessage,
          onCancelScheduledMessage = onCancelScheduledMessage,
        )
      }

      if (shouldShow(ToolCategory.WHATSAPP, "keyword", "dnd", "away", "react", "welcome", "snippet", "broadcast", "analytics")) {
        WhatsAppAutomationCard(
          settings = settings,
          conversations = conversations,
          rulesEngine = rulesEngine,
          broadcastStatusMessage = broadcastStatusMessage,
          onUpdateSettings = onUpdateSettings,
          onSendBroadcast = onSendBroadcast,
        )
      }

      if (shouldShow(ToolCategory.FOCUS, "timer", "pomodoro", "stopwatch", "breathe", "notes", "todo", "habit", "scratchpad", "tts", "speak")) {
        FocusNotesHabitsCard(
          settings = settings,
          onUpdateSettings = onUpdateSettings,
          onSpeakText = onSpeakText,
          onTriggerHaptic = onTriggerHaptic,
        )
      }

      if (shouldShow(ToolCategory.TEXT_QR, "qr", "wifi", "wa.me", "link", "whatsapp format")) {
        QrStudioCard()
      }

      if (shouldShow(ToolCategory.TEXT_QR, "text", "word", "case", "slug", "extract", "sort", "dedupe")) {
        TextToolsCard()
      }

      if (shouldShow(ToolCategory.MATH_UNITS, "math", "calc", "unit", "convert", "tip", "loan", "emi", "bmi", "countdown")) {
        MathAndConvertersCard()
      }

      if (shouldShow(ToolCategory.DEV_CRYPTO, "json", "base64", "hash", "sha", "hmac", "jwt", "regex", "password", "uuid", "clock", "color", "dice")) {
        DevCryptoAndFunCard()
      }

      if (shouldShow(ToolCategory.SYSTEM, "device", "battery", "storage", "theme", "oled", "haptic", "backup", "restore", "logs", "signal", "ai test", "webview", "health")) {
        DeviceBackupAndDiagnosticsCard(
          connectionState = connectionState,
          settings = settings,
          logs = logs,
          signalSessionsCount = signalSessionsCount,
          signalPreKeysCount = signalPreKeysCount,
          diagnosticResult = diagnosticResult,
          webViewHealthSummary = webViewHealthSummary,
          maskedKeyStatus = maskedKeyStatus,
          backupStatusMessage = backupStatusMessage,
          onUpdateSettings = onUpdateSettings,
          onTriggerHaptic = onTriggerHaptic,
          onExportBackup = onExportBackup,
          onImportBackup = onImportBackup,
          onReplenishPreKeys = onReplenishPreKeys,
          onRunAiDiagnostic = onRunAiDiagnostic,
          onRunWebViewHealthCheck = onRunWebViewHealthCheck,
          onClearLogs = onClearLogs,
        )
      }

      Spacer(Modifier.height(12.dp))
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VisionLabCard(
  visionInspectorResult: String?,
  onInspectMediaUri: (android.net.Uri, String, Boolean) -> Unit,
) {
  val context = LocalContext.current

  val photoLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      if (uri != null) {
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        onInspectMediaUri(uri, mime, mime.contains("webp", ignoreCase = true))
      }
    }
  val docLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        val mime = context.contentResolver.getType(uri) ?: "application/pdf"
        onInspectMediaUri(uri, mime, false)
      }
    }

  ToolSectionCard("VL Vision & File Tester", Icons.Rounded.Tune) {
    Text(
      "Test how your NVIDIA Vision model understands stickers, photos, and PDFs/text files:",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(
        onClick = { photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        modifier = Modifier.weight(1f).testTag("tools_inspect_photo_button").minimumInteractiveComponentSize(),
      ) {
        Icon(Icons.Rounded.EmojiEmotions, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Photo / Sticker")
      }
      OutlinedButton(
        onClick = { docLauncher.launch(arrayOf("application/pdf", "text/*", "image/webp")) },
        modifier = Modifier.weight(1f).testTag("tools_inspect_doc_button").minimumInteractiveComponentSize(),
      ) {
        Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("File / PDF")
      }
    }
    visionInspectorResult?.let {
      Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduledMessagesCard(
  conversations: List<ConversationEntity>,
  scheduledMessages: List<ScheduledMessageEntity>,
  onScheduleMessage: (String, String, String, Long) -> Unit,
  onCancelScheduledMessage: (String) -> Unit,
) {
  var selectedJid by remember(conversations) {
    mutableStateOf(conversations.firstOrNull()?.chatJid.orEmpty())
  }
  var messageText by remember { mutableStateOf("") }
  var delayMinutesText by remember { mutableStateOf("60") }

  ToolSectionCard("Scheduled WhatsApp Messages (${scheduledMessages.count { it.status == "PENDING" }} pending)", Icons.Rounded.ScheduleSend) {
    if (conversations.isEmpty()) {
      Text(
        "Link WhatsApp and sync at least one chat first, then you can schedule messages to send automatically even while closed.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      val selectedConv = conversations.firstOrNull { it.chatJid == selectedJid } ?: conversations.first()
      selectedJid = selectedConv.chatJid
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        conversations.take(10).forEach { c ->
          FilterChip(
            selected = c.chatJid == selectedConv.chatJid,
            onClick = { selectedJid = c.chatJid },
            label = { Text(c.displayName) },
          )
        }
      }
      OutlinedTextField(
        value = messageText,
        onValueChange = { messageText = it },
        label = { Text("Message to ${selectedConv.displayName}") },
        modifier = Modifier.fillMaxWidth().testTag("schedule_message_input"),
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(15L to "15m", 60L to "1h", 180L to "3h", 720L to "12h", 1440L to "24h").forEach { (mins, label) ->
          FilterChip(
            selected = delayMinutesText == mins.toString(),
            onClick = { delayMinutesText = mins.toString() },
            label = { Text(label) },
          )
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
          value = delayMinutesText,
          onValueChange = { delayMinutesText = it.filter(Char::isDigit).take(5) },
          label = { Text("Minutes from now") },
          singleLine = true,
          modifier = Modifier.weight(1f),
        )
        Button(
          onClick = {
            val mins = delayMinutesText.toLongOrNull()?.coerceAtLeast(1L) ?: 15L
            onScheduleMessage(selectedConv.chatJid, selectedConv.displayName, messageText, mins)
            messageText = ""
          },
          enabled = messageText.isNotBlank(),
          modifier = Modifier.testTag("schedule_message_submit").minimumInteractiveComponentSize(),
        ) {
          Text("Schedule")
        }
      }
    }

    if (scheduledMessages.isNotEmpty()) {
      HorizontalDivider()
      val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
      scheduledMessages.take(10).forEach { item ->
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "${item.displayName} • ${fmt.format(Date(item.scheduledAtMs))} (${item.status})",
              style = MaterialTheme.typography.labelLarge,
              color =
                when (item.status) {
                  "SENT" -> MaterialTheme.colorScheme.primary
                  "FAILED" -> MaterialTheme.colorScheme.error
                  else -> MaterialTheme.colorScheme.secondary
                },
            )
            Text(item.messageText, style = MaterialTheme.typography.bodyMedium)
          }
          IconButton(
            onClick = { onCancelScheduledMessage(item.id) },
            modifier = Modifier.minimumInteractiveComponentSize(),
          ) {
            Icon(Icons.Rounded.Delete, contentDescription = "Remove scheduled message")
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhatsAppAutomationCard(
  settings: AgentSettings,
  conversations: List<ConversationEntity>,
  rulesEngine: AutomationRulesEngine,
  broadcastStatusMessage: String?,
  onUpdateSettings: ((AgentSettings) -> AgentSettings) -> Unit,
  onSendBroadcast: (List<String>, String) -> Unit,
) {
  val rules = remember(settings.keywordRulesJson) { rulesEngine.parseKeywordRules(settings.keywordRulesJson) }
  var newKeyword by remember { mutableStateOf("") }
  var newReply by remember { mutableStateOf("") }
  var newMode by remember { mutableStateOf(KeywordMatchMode.CONTAINS) }

  val snippets = remember(settings.quickRepliesJson) { rulesEngine.parseStringList(settings.quickRepliesJson) }
  var newSnippet by remember { mutableStateOf("") }

  var selectedBroadcastJids by remember { mutableStateOf(setOf<String>()) }
  var broadcastText by remember { mutableStateOf("") }

  ToolSectionCard("WhatsApp Automation & Broadcast", Icons.Rounded.AutoMode) {
    // DND + Away Mode
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Quiet Hours (DND ${settings.dndStartHour}:00 → ${settings.dndEndHour}:00)", style = MaterialTheme.typography.titleMedium)
        Text("Pauses auto-reply at night (VIP/Trusted contacts bypass)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Switch(checked = settings.dndEnabled, onCheckedChange = { v -> onUpdateSettings { it.copy(dndEnabled = v) } })
    }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Away / Vacation Auto-Reply", style = MaterialTheme.typography.titleMedium)
        Text("Sends instant away note instead of calling AI", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Switch(checked = settings.awayModeEnabled, onCheckedChange = { v -> onUpdateSettings { it.copy(awayModeEnabled = v) } })
    }
    if (settings.awayModeEnabled) {
      OutlinedTextField(
        value = settings.awayMessage,
        onValueChange = { v -> onUpdateSettings { it.copy(awayMessage = v) } },
        label = { Text("Away reply message") },
        modifier = Modifier.fillMaxWidth(),
      )
    }

    // Welcome + Auto-React
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
      Text("Welcome New Contacts Automatically", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      Switch(checked = settings.welcomeNewContacts, onCheckedChange = { v -> onUpdateSettings { it.copy(welcomeNewContacts = v) } })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(
        value = settings.autoReactEmoji,
        onValueChange = { v -> onUpdateSettings { it.copy(autoReactEmoji = v.take(4)) } },
        label = { Text("Auto-React Emoji (e.g. 👍)") },
        singleLine = true,
        modifier = Modifier.weight(1f),
      )
      OutlinedTextField(
        value = settings.replyCooldownSeconds.toString(),
        onValueChange = { raw -> onUpdateSettings { it.copy(replyCooldownSeconds = raw.toIntOrNull()?.coerceIn(0, 3600) ?: 0) } },
        label = { Text("Cooldown (sec)") },
        singleLine = true,
        modifier = Modifier.weight(1f),
      )
    }

    HorizontalDivider()
    // Keyword Triggers
    Text("Keyword Auto-Replies (${rules.size}) — Instant, 0 AI tokens", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      KeywordMatchMode.entries.forEach { m ->
        FilterChip(selected = newMode == m, onClick = { newMode = m }, label = { Text(m.label) })
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(value = newKeyword, onValueChange = { newKeyword = it }, label = { Text("Trigger") }, singleLine = true, modifier = Modifier.weight(1f))
      OutlinedTextField(value = newReply, onValueChange = { newReply = it }, label = { Text("Reply") }, singleLine = true, modifier = Modifier.weight(1.4f))
      IconButton(onClick = {
        if (newKeyword.isNotBlank() && newReply.isNotBlank()) {
          val updated = rules + KeywordRule("r_${System.nanoTime()}", newKeyword.trim(), newReply.trim(), newMode, true)
          onUpdateSettings { it.copy(keywordRulesJson = rulesEngine.serializeKeywordRules(updated)) }
          newKeyword = ""
          newReply = ""
        }
      }) { Icon(Icons.Rounded.Add, contentDescription = "Add keyword trigger") }
    }
    rules.forEach { rule ->
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("\"${rule.keyword}\" (${rule.matchMode.label}) → ${rule.reply}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = {
          val updated = rules.filterNot { it.id == rule.id }
          onUpdateSettings { it.copy(keywordRulesJson = rulesEngine.serializeKeywordRules(updated)) }
        }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete rule") }
      }
    }

    HorizontalDivider()
    // Quick Reply Snippets
    Text("Quick Reply Snippets (shown in Chats)", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(value = newSnippet, onValueChange = { newSnippet = it }, label = { Text("New quick snippet") }, singleLine = true, modifier = Modifier.weight(1f))
      IconButton(onClick = {
        if (newSnippet.isNotBlank()) {
          val updated = snippets + newSnippet.trim()
          onUpdateSettings { it.copy(quickRepliesJson = rulesEngine.serializeStringList(updated)) }
          newSnippet = ""
        }
      }) { Icon(Icons.Rounded.Add, contentDescription = "Add snippet") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      snippets.forEach { snip ->
        AssistChip(
          onClick = {
            val updated = snippets.filterNot { it == snip }
            onUpdateSettings { it.copy(quickRepliesJson = rulesEngine.serializeStringList(updated)) }
          },
          label = { Text("$snip ×") },
        )
      }
    }

    HorizontalDivider()
    // Broadcast & Chat Analytics
    Text("Multi-Chat Broadcast (${conversations.size} chats available)", style = MaterialTheme.typography.titleMedium)
    if (conversations.isEmpty()) {
      Text("Chats appear here after linking WhatsApp and receiving messages.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        conversations.take(12).forEach { c ->
          val picked = c.chatJid in selectedBroadcastJids
          FilterChip(
            selected = picked,
            onClick = {
              selectedBroadcastJids = if (picked) selectedBroadcastJids - c.chatJid else selectedBroadcastJids + c.chatJid
            },
            label = { Text(c.displayName) },
          )
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = broadcastText, onValueChange = { broadcastText = it }, label = { Text("Broadcast message") }, modifier = Modifier.weight(1f))
        Button(
          onClick = {
            onSendBroadcast(selectedBroadcastJids.toList(), broadcastText)
            broadcastText = ""
          },
          enabled = selectedBroadcastJids.isNotEmpty() && broadcastText.isNotBlank(),
        ) {
          Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send broadcast", modifier = Modifier.size(18.dp))
        }
      }
      broadcastStatusMessage?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
    }

    // Analytics leaderboard
    if (conversations.isNotEmpty()) {
      val topChats = conversations.sortedByDescending { it.totalTokensUsed }.take(5)
      Text("Top AI Token Usage: " + topChats.joinToString(" • ") { "${it.displayName} (${it.totalTokensUsed})" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun FocusNotesHabitsCard(
  settings: AgentSettings,
  onUpdateSettings: ((AgentSettings) -> AgentSettings) -> Unit,
  onSpeakText: (String) -> Unit,
  onTriggerHaptic: () -> Unit,
) {
  // Pomodoro + Stopwatch + Breathing + Notes + Todos + Habits + Scratchpad + TTS Pad
  var pomoRunning by remember { mutableStateOf(false) }
  var pomoRemainingSec by remember(settings.pomodoroMinutes) { mutableIntStateOf(settings.pomodoroMinutes * 60) }
  var swRunning by remember { mutableStateOf(false) }
  var swElapsedMs by remember { mutableLongStateOf(0L) }
  var breatheActive by remember { mutableStateOf(false) }
  var breathePhase by remember { mutableStateOf("Ready (4-4-4-4 Box Breathing)") }

  LaunchedEffect(pomoRunning) {
    while (pomoRunning && pomoRemainingSec > 0) {
      delay(1000L)
      pomoRemainingSec--
      if (pomoRemainingSec == 0) {
        pomoRunning = false
        onTriggerHaptic()
      }
    }
  }
  LaunchedEffect(swRunning) {
    while (swRunning) {
      delay(100L)
      swElapsedMs += 100L
    }
  }
  LaunchedEffect(breatheActive) {
    val phases = listOf("Inhale slowly… 4s", "Hold breath… 4s", "Exhale gently… 4s", "Hold empty… 4s")
    var idx = 0
    while (breatheActive) {
      breathePhase = phases[idx % phases.size]
      delay(4000L)
      idx++
    }
    breathePhase = "Ready (4-4-4-4 Box Breathing)"
  }

  val notes = remember(settings.notesJson) { AutomationRulesEngine.parseNotes(settings.notesJson) }
  val todos = remember(settings.todosJson) { AutomationRulesEngine.parseTodos(settings.todosJson) }
  val habits = remember(settings.habitsJson) { AutomationRulesEngine.parseHabits(settings.habitsJson) }

  var noteTitle by remember { mutableStateOf("") }
  var noteBody by remember { mutableStateOf("") }
  var todoInput by remember { mutableStateOf("") }
  var habitInput by remember { mutableStateOf("") }
  var ttsDraft by remember { mutableStateOf("") }

  ToolSectionCard("Focus, Notes, To-Dos, Habits & Voice Read-Aloud", Icons.Rounded.Timer) {
    // Pomodoro + Stopwatch
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      val mm = pomoRemainingSec / 60
      val ss = pomoRemainingSec % 60
      Text("Focus Timer: ${"%02d:%02d".format(mm, ss)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip(onClick = { pomoRunning = !pomoRunning }, label = { Text(if (pomoRunning) "Pause" else "Start") })
        AssistChip(onClick = { pomoRunning = false; pomoRemainingSec = settings.pomodoroMinutes * 60 }, label = { Text("Reset") })
      }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      val swSec = (swElapsedMs / 1000L).toInt()
      val swTenths = ((swElapsedMs % 1000L) / 100L).toInt()
      Text("Stopwatch: ${"%02d:%02d.%d".format(swSec / 60, swSec % 60, swTenths)}", style = MaterialTheme.typography.bodyLarge)
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip(onClick = { swRunning = !swRunning }, label = { Text(if (swRunning) "Stop" else "Start") })
        AssistChip(onClick = { swRunning = false; swElapsedMs = 0L }, label = { Text("Clear") })
      }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text(breathePhase, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
      AssistChip(onClick = { breatheActive = !breatheActive }, label = { Text(if (breatheActive) "Stop" else "Breathe") })
    }

    HorizontalDivider()
    // To-Do Checklist
    Text("To-Do Checklist (${todos.count { it.done }}/${todos.size})", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(value = todoInput, onValueChange = { todoInput = it }, label = { Text("Add task…") }, singleLine = true, modifier = Modifier.weight(1f))
      IconButton(onClick = {
        if (todoInput.isNotBlank()) {
          val next = todos + TodoItem("td_${System.nanoTime()}", todoInput.trim(), false)
          onUpdateSettings { it.copy(todosJson = AutomationRulesEngine.serializeTodos(next)) }
          todoInput = ""
        }
      }) { Icon(Icons.Rounded.Add, contentDescription = "Add task") }
    }
    todos.forEach { t ->
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
          val next = todos.map { if (it.id == t.id) it.copy(done = !it.done) else it }
          onUpdateSettings { it.copy(todosJson = AutomationRulesEngine.serializeTodos(next)) }
        }) {
          Icon(if (t.done) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, contentDescription = "Toggle task")
        }
        Text(t.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = {
          val next = todos.filterNot { it.id == t.id }
          onUpdateSettings { it.copy(todosJson = AutomationRulesEngine.serializeTodos(next)) }
        }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete task") }
      }
    }

    HorizontalDivider()
    // Habit Tracker
    Text("Daily Habits (${habits.size})", style = MaterialTheme.typography.titleMedium)
    val today = AutomationRulesEngine.dayKeyNow()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(value = habitInput, onValueChange = { habitInput = it }, label = { Text("New habit (e.g. Drink water)") }, singleLine = true, modifier = Modifier.weight(1f))
      IconButton(onClick = {
        if (habitInput.isNotBlank()) {
          val next = habits + HabitItem("hb_${System.nanoTime()}", habitInput.trim(), 0, "")
          onUpdateSettings { it.copy(habitsJson = AutomationRulesEngine.serializeHabits(next)) }
          habitInput = ""
        }
      }) { Icon(Icons.Rounded.Add, contentDescription = "Add habit") }
    }
    habits.forEach { h ->
      val checkedToday = h.lastCheckedDay == today
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Checkbox(
          checked = checkedToday,
          onCheckedChange = {
            if (!checkedToday) {
              val next = habits.map { if (it.id == h.id) it.copy(streak = it.streak + 1, lastCheckedDay = today) else it }
              onUpdateSettings { it.copy(habitsJson = AutomationRulesEngine.serializeHabits(next)) }
            }
          },
        )
        Text("${h.name} (🔥 ${h.streak})", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = {
          val next = habits.filterNot { it.id == h.id }
          onUpdateSettings { it.copy(habitsJson = AutomationRulesEngine.serializeHabits(next)) }
        }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete habit") }
      }
    }

    HorizontalDivider()
    // Quick Notes + Scratchpad + Speak Aloud
    Text("Quick Notes (${notes.size})", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(value = noteTitle, onValueChange = { noteTitle = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.weight(1f))
      OutlinedTextField(value = noteBody, onValueChange = { noteBody = it }, label = { Text("Note") }, singleLine = true, modifier = Modifier.weight(1.4f))
      IconButton(onClick = {
        if (noteTitle.isNotBlank() || noteBody.isNotBlank()) {
          val next = listOf(NoteItem("n_${System.nanoTime()}", noteTitle.ifBlank { "Note" }, noteBody)) + notes
          onUpdateSettings { it.copy(notesJson = AutomationRulesEngine.serializeNotes(next)) }
          noteTitle = ""
          noteBody = ""
        }
      }) { Icon(Icons.Rounded.Add, contentDescription = "Add note") }
    }
    notes.take(8).forEach { n ->
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${n.title}: ${n.body}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = {
          val next = notes.filterNot { it.id == n.id }
          onUpdateSettings { it.copy(notesJson = AutomationRulesEngine.serializeNotes(next)) }
        }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete note") }
      }
    }

    OutlinedTextField(
      value = settings.scratchpadText,
      onValueChange = { v -> onUpdateSettings { it.copy(scratchpadText = v) } },
      label = { Text("Auto-saved Scratchpad") },
      minLines = 2,
      modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(value = ttsDraft, onValueChange = { ttsDraft = it }, label = { Text("Type text to speak aloud") }, singleLine = true, modifier = Modifier.weight(1f))
      IconButton(onClick = { onSpeakText(ttsDraft) }, modifier = Modifier.minimumInteractiveComponentSize()) {
        Icon(Icons.Rounded.VolumeUp, contentDescription = "Speak aloud")
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceBackupAndDiagnosticsCard(
  connectionState: WhatsAppConnectionState,
  settings: AgentSettings,
  logs: List<AgentLogEntity>,
  signalSessionsCount: Int,
  signalPreKeysCount: Int,
  diagnosticResult: DiagnosticTestResult,
  webViewHealthSummary: String?,
  maskedKeyStatus: String,
  backupStatusMessage: String?,
  onUpdateSettings: ((AgentSettings) -> AgentSettings) -> Unit,
  onTriggerHaptic: () -> Unit,
  onExportBackup: (android.net.Uri) -> Unit,
  onImportBackup: (android.net.Uri) -> Unit,
  onReplenishPreKeys: () -> Unit,
  onRunAiDiagnostic: () -> Unit,
  onRunWebViewHealthCheck: () -> Unit,
  onClearLogs: () -> Unit,
) {
  val context = LocalContext.current
  var telemetryRefresh by remember { mutableIntStateOf(0) }
  val telemetry = remember(telemetryRefresh) { UtilityToolkit.readDeviceTelemetry(context) }

  val exportLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
      if (uri != null) onExportBackup(uri)
    }
  val importLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) onImportBackup(uri)
    }

  // Theme & Haptics
  ToolSectionCard("Appearance, Device Info & Backup", Icons.Rounded.Palette) {
    Text("Theme Mode", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      AppThemeMode.entries.forEach { mode ->
        FilterChip(
          selected = settings.themeMode == mode,
          onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
          label = { Text(mode.label) },
        )
      }
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
      Text("Haptic Feedback", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = onTriggerHaptic, label = { Text("Test Vibrate") })
        Switch(checked = settings.hapticFeedbackEnabled, onCheckedChange = { v -> onUpdateSettings { it.copy(hapticFeedbackEnabled = v) } })
      }
    }

    HorizontalDivider()
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Rounded.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        Text("Phone Health & Telemetry", style = MaterialTheme.typography.titleMedium)
      }
      AssistChip(onClick = { telemetryRefresh++ }, label = { Text("Refresh") })
    }
    Text(
      "• Model: ${telemetry.deviceModel} (${telemetry.androidVersion})\n" +
        "• Battery: ${telemetry.batteryPercent}% (${if (telemetry.isCharging) "Charging" else "On Battery"})\n" +
        "• Network: ${telemetry.networkTransport} • CPU: ${telemetry.cpuCores} cores\n" +
        "• RAM Free: ${telemetry.availableRamMb}/${telemetry.totalRamMb} MB • Disk Free: ${telemetry.availableStorageGb}/${telemetry.totalStorageGb} GB",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Icon(Icons.Rounded.Backup, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
      Text("JSON Backup & Restore (Keys Excluded)", style = MaterialTheme.typography.titleMedium)
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        onClick = { exportLauncher.launch("clawlink_backup_${System.currentTimeMillis()}.json") },
        modifier = Modifier.weight(1f).minimumInteractiveComponentSize(),
      ) {
        Text("Export Backup")
      }
      OutlinedButton(
        onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) },
        modifier = Modifier.weight(1f).minimumInteractiveComponentSize(),
      ) {
        Text("Restore Backup")
      }
    }
    backupStatusMessage?.let {
      Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
  }

  // Protocol Security, Live AI Probe, and Structured Logs
  var selectedLogCat by remember { mutableStateOf("ALL") }
  val logCategories = listOf("ALL", "WHATSAPP", "PROTOCOL", "AGENT", "NVIDIA_NIM", "MEDIA", "SECURITY")
  val filteredLogs = remember(logs, selectedLogCat) {
    if (selectedLogCat == "ALL") logs else logs.filter { it.category == selectedLogCat }
  }

  ToolSectionCard("Protocol Diagnostics & Logs", Icons.Rounded.Shield) {
    Text(
      "• WhatsApp State: ${connectionState::class.simpleName}\n" +
        "• AI Key: $maskedKeyStatus (always redacted in logs)\n" +
        "• Signal E2EE: $signalSessionsCount active sessions, $signalPreKeysCount pre-keys stored",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
      Text("Allow Tools in Group Chats", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      Switch(
        checked = settings.allowGroupTools,
        onCheckedChange = { v -> onUpdateSettings { it.copy(allowGroupTools = v) } },
        modifier = Modifier.testTag("allow_group_tools_switch"),
      )
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        onClick = onRunAiDiagnostic,
        enabled = !diagnosticResult.isRunning,
        modifier = Modifier.weight(1f).testTag("run_ai_diagnostic_button").minimumInteractiveComponentSize(),
      ) {
        if (diagnosticResult.isRunning) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        else Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Test AI")
      }
      OutlinedButton(
        onClick = onRunWebViewHealthCheck,
        modifier = Modifier.weight(1f).testTag("webview_health_button").minimumInteractiveComponentSize(),
      ) {
        Text("Check WhatsApp")
      }
    }
    diagnosticResult.summary?.let {
      Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
    }
    webViewHealthSummary?.let {
      Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }

    HorizontalDivider()
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Rounded.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text("Logs (${filteredLogs.size})", style = MaterialTheme.typography.titleMedium)
      }
      OutlinedButton(onClick = onClearLogs, modifier = Modifier.testTag("clear_logs_button").minimumInteractiveComponentSize()) {
        Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear logs", modifier = Modifier.size(18.dp))
      }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      logCategories.forEach { cat ->
        FilterChip(selected = selectedLogCat == cat, onClick = { selectedLogCat = cat }, label = { Text(cat) })
      }
    }
    if (filteredLogs.isEmpty()) {
      Text("No log entries yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
      val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        filteredLogs.take(60).forEach { log ->
          val c = when (log.level) {
            "ERROR" -> MaterialTheme.colorScheme.error
            "WARN" -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.secondary
          }
          Text(
            text = "[${fmt.format(Date(log.timestamp))}] ${log.level}/${log.category}: ${log.message}",
            style = MaterialTheme.typography.labelSmall,
            color = c,
          )
        }
      }
    }
  }
}

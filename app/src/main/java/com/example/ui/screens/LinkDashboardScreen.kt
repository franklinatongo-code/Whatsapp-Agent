package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.agent.AgentRuntimeStatus
import com.example.data.prefs.AgentSettings
import com.example.data.prefs.TranscriptionMode
import com.example.ui.CatchUpDigestUiState
import com.example.ui.NimDiscoveryUiState
import com.example.whatsapp.WhatsAppConnectionState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinkDashboardScreen(
  connectionState: WhatsAppConnectionState,
  isSessionRegistered: Boolean,
  qrCountdownSeconds: Int,
  lastErrorMessage: String?,
  runtimeStatus: AgentRuntimeStatus,
  settings: AgentSettings,
  nimUiState: NimDiscoveryUiState,
  activeChatsCount: Int,
  totalMessagesCount: Int,
  catchUpDigest: CatchUpDigestUiState,
  tileStatusMessage: String?,
  onConnect: () -> Unit,
  onDisconnect: () -> Unit,
  onReconnect: () -> Unit,
  onRelink: () -> Unit,
  onRequestPairingCode: (String) -> Unit,
  onToggleDirectAutoReply: (Boolean) -> Unit,
  onUpdateSettings: ((AgentSettings) -> AgentSettings) -> Unit,
  onRunCatchUpDigest: () -> Unit,
  onClearCatchUpDigest: () -> Unit,
  onRequestAddQuickTile: (android.content.Context) -> Unit,
  onClearTileStatus: () -> Unit,
  onNavigateToStudio: () -> Unit,
  onNavigateToChats: () -> Unit,
  onOpenLiveWebView: () -> Unit,
  onUnlockHiddenVault: () -> Unit,
) {
  val context = LocalContext.current
  var showSwitchConfirm by remember { mutableStateOf(false) }
  var usePhoneInstead by rememberSaveable { mutableStateOf(false) }
  var phoneInput by rememberSaveable { mutableStateOf("") }

  // 5-tap secret vault trigger on the header title badge
  var headerTapCount by remember { mutableIntStateOf(0) }
  var lastHeaderTapMs by remember { mutableLongStateOf(0L) }

  val lifecycleOwner = LocalLifecycleOwner.current
  var permRefreshTick by remember { mutableIntStateOf(0) }
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) permRefreshTick++
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }

  val notifLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      permRefreshTick++
      onConnect()
    }
  val micLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      permRefreshTick++
    }
  val batteryLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
      permRefreshTick++
    }

  val powerMgr = remember(context) { context.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager }
  val batteryUnrestricted =
    remember(permRefreshTick, powerMgr) {
      powerMgr?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
  val notificationsGranted =
    remember(permRefreshTick) {
      Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    }
  val micGranted =
    remember(permRefreshTick) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    }

  val launchConnectWithPermission = {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted) {
      notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      onConnect()
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
      // Top Minimal Header Bar (tap 5x quietly to open Hidden Key Vault)
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier =
            Modifier.clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
            ) {
              val now = System.currentTimeMillis()
              headerTapCount = if (now - lastHeaderTapMs < 1500L) headerTapCount + 1 else 1
              lastHeaderTapMs = now
              if (headerTapCount >= 5) {
                headerTapCount = 0
                onUnlockHiddenVault()
              }
            }
              .testTag("app_header_badge"),
        ) {
          Text(
            text = "ClawLink WA",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
          )
          Spacer(Modifier.width(8.dp))
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(8.dp),
          ) {
            Text(
              text = "v1.0",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
          }
        }

        val statusLabel =
          when {
            connectionState is WhatsAppConnectionState.Connected -> "Online"
            isSessionRegistered -> "Saved"
            connectionState is WhatsAppConnectionState.QrReady -> "Scan QR"
            else -> "Not Linked"
          }
        Card(
          colors =
            CardDefaults.cardColors(
              containerColor =
                if (connectionState is WhatsAppConnectionState.Connected)
                  MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
          shape = CircleShape,
        ) {
          Text(
            text = statusLabel,
            style = MaterialTheme.typography.labelMedium,
            color =
              if (connectionState is WhatsAppConnectionState.Connected)
                MaterialTheme.colorScheme.onPrimaryContainer
              else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
          )
        }
      }

      // MAIN HERO CARD — Baby-simple view based strictly on whether session is saved!
      if (isSessionRegistered || connectionState is WhatsAppConnectionState.Connected) {
        SavedSessionHomeCard(
          connectionState = connectionState,
          runtimeStatus = runtimeStatus,
          autoReplyEnabled = settings.autoReplyDirectChats,
          onToggleAutoReply = onToggleDirectAutoReply,
          onConnect = launchConnectWithPermission,
          onReconnect = onReconnect,
          onDisconnect = onDisconnect,
          onSwitchAccount = { showSwitchConfirm = true },
          onOpenChats = onNavigateToChats,
          onOpenLiveWebView = onOpenLiveWebView,
        )
      } else {
        FreshSetupQrCard(
          connectionState = connectionState,
          qrCountdownSeconds = qrCountdownSeconds,
          lastErrorMessage = lastErrorMessage,
          usePhoneInstead = usePhoneInstead,
          phoneInput = phoneInput,
          onPhoneInputChange = { phoneInput = it },
          onToggleMethod = { usePhoneInstead = !usePhoneInstead },
          onConnect = launchConnectWithPermission,
          onRequestNewQr = onRelink,
          onRequestPairingCode = onRequestPairingCode,
          onOpenLiveWebView = onOpenLiveWebView,
        )
      }

      // Simple 3-stat overview
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        SimpleStatPill(
          label = "Chats",
          value = activeChatsCount.toString(),
          modifier = Modifier.weight(1f),
        )
        SimpleStatPill(
          label = "Messages",
          value = totalMessagesCount.toString(),
          modifier = Modifier.weight(1f),
        )
        SimpleStatPill(
          label = "AI Replies",
          value = runtimeStatus.totalResponsesSent.toString(),
          accent = nimUiState.isValidated,
          modifier = Modifier.weight(1f),
        )
      }

      // Friendly AI Key setup banner if user hasn't set up their AI key yet
      if (!nimUiState.isValidated) {
        Card(
          colors =
            CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
            ),
          shape = MaterialTheme.shapes.large,
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
              )
              Spacer(Modifier.width(12.dp))
              Column {
                Text(
                  text = if (nimUiState.isLoading) "Checking AI Key…" else "Turn On AI Replies",
                  style = MaterialTheme.typography.titleMedium,
                )
                Text(
                  text = "Add your NVIDIA AI key in the AI tab so ClawLink can reply for you.",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            Spacer(Modifier.width(10.dp))
            Button(
              onClick = onNavigateToStudio,
              modifier = Modifier.testTag("open_studio_cta_button").minimumInteractiveComponentSize(),
            ) {
              Text("Set Up")
            }
          }
        }
      }

      // Baby-simple 1-Tap AI Vision & Quick Settings Card
      Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("What AI Can Understand", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Text(
            "Tap any pill to turn ON or OFF:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
              selected = settings.visionAnalyzeImages,
              onClick = { onUpdateSettings { it.copy(visionAnalyzeImages = !it.visionAnalyzeImages) } },
              leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp)) },
              label = { Text("Pictures") },
              modifier = Modifier.testTag("home_chip_pictures"),
            )
            FilterChip(
              selected = settings.visionAnalyzeStickers,
              onClick = { onUpdateSettings { it.copy(visionAnalyzeStickers = !it.visionAnalyzeStickers) } },
              leadingIcon = { Icon(Icons.Rounded.EmojiEmotions, contentDescription = null, modifier = Modifier.size(16.dp)) },
              label = { Text("Stickers") },
              modifier = Modifier.testTag("home_chip_stickers"),
            )
            FilterChip(
              selected = settings.documentAnalysisEnabled,
              onClick = { onUpdateSettings { it.copy(documentAnalysisEnabled = !it.documentAnalysisEnabled) } },
              leadingIcon = { Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(16.dp)) },
              label = { Text("Files & PDFs") },
              modifier = Modifier.testTag("home_chip_files"),
            )
            val voiceNotesActive = settings.autoTranscribeVoiceNotes && settings.transcriptionMode != TranscriptionMode.OFF
            FilterChip(
              selected = voiceNotesActive,
              onClick = {
                onUpdateSettings { s ->
                  val currentlyOn = s.autoTranscribeVoiceNotes && s.transcriptionMode != TranscriptionMode.OFF
                  if (currentlyOn) {
                    s.copy(
                      autoTranscribeVoiceNotes = false,
                      transcriptionMode = TranscriptionMode.OFF,
                      localSpeechEnabled = false,
                    )
                  } else {
                    s.copy(
                      autoTranscribeVoiceNotes = true,
                      transcriptionMode = if (s.localSpeechEnabled) TranscriptionMode.LOCAL else TranscriptionMode.CLOUD,
                    )
                  }
                }
              },
              leadingIcon = { Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(16.dp)) },
              label = { Text("Voice Notes") },
              modifier = Modifier.testTag("home_chip_voice"),
            )
            FilterChip(
              selected = settings.incomingNotificationsEnabled,
              onClick = { onUpdateSettings { it.copy(incomingNotificationsEnabled = !it.incomingNotificationsEnabled) } },
              leadingIcon = { Icon(Icons.Rounded.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp)) },
              label = { Text("Alerts") },
              modifier = Modifier.testTag("home_chip_alerts"),
            )
          }
        }
      }

      // 1-Tap Unread WhatsApp Catch-Up Briefing
      Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(
              modifier = Modifier.weight(1f),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(Icons.Rounded.Summarize, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Column {
                Text("WhatsApp Catch-Up", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                  "AI summarizes unread & recent chats in 1 tap",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            Spacer(Modifier.width(8.dp))
            Button(
              onClick = onRunCatchUpDigest,
              enabled = !catchUpDigest.isLoading,
              modifier = Modifier.testTag("home_catch_up_button").minimumInteractiveComponentSize(),
            ) {
              if (catchUpDigest.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
              } else {
                Text("Catch Me Up")
              }
            }
          }

          AnimatedVisibility(visible = catchUpDigest.text != null || catchUpDigest.error != null) {
            Card(
              colors =
                CardDefaults.cardColors(
                  containerColor =
                    if (catchUpDigest.error != null) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                ),
            ) {
              Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = if (catchUpDigest.error != null) "Briefing Unavailable" else "Recent Activity Briefing",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                  )
                  TextButton(onClick = onClearCatchUpDigest) { Text("Hide") }
                }
                Text(
                  text = catchUpDigest.text ?: catchUpDigest.error.orEmpty(),
                  style = MaterialTheme.typography.bodyMedium,
                )
              }
            }
          }
        }
      }

      // Always-On Background & Quick Tile Setup Card
      Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = "Always-On & Quick Settings Tile",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text = "Keep WhatsApp AI replying even when the app is closed, and toggle it anytime from Quick Settings:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          PermissionReadinessRow(
            icon = Icons.Rounded.BatteryChargingFull,
            title = "Unrestricted Battery",
            subtitle = if (batteryUnrestricted) "Ready — stays online when closed" else "Required so Android won't pause AI",
            isReady = batteryUnrestricted,
            actionLabel = if (batteryUnrestricted) "Done" else "Allow",
            testTag = "perm_battery_button",
            onClick = {
              @SuppressLint("BatteryLife")
              val reqIntent =
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                  data = Uri.parse("package:${context.packageName}")
                }
              val launched =
                runCatching {
                  batteryLauncher.launch(reqIntent)
                  true
                }.getOrDefault(false)
              if (!launched) {
                runCatching { batteryLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
              }
            },
          )

          PermissionReadinessRow(
            icon = Icons.Rounded.NotificationsActive,
            title = "Always-On Alerts",
            subtitle = if (notificationsGranted) "Allowed — live service & quick reply active" else "Tap Allow for background indicator",
            isReady = notificationsGranted,
            actionLabel = if (notificationsGranted) "Done" else "Allow",
            testTag = "perm_notifications_button",
            onClick = {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
              } else {
                val intent =
                  Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                  }
                runCatching { batteryLauncher.launch(intent) }
              }
            },
          )

          PermissionReadinessRow(
            icon = Icons.Rounded.Mic,
            title = "Voice Dictation Mic",
            subtitle = if (micGranted) "Allowed" else "Optional for voice input in Chats & AI",
            isReady = micGranted,
            actionLabel = if (micGranted) "Done" else "Allow",
            testTag = "perm_mic_button",
            onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
          )

          OutlinedButton(
            onClick = { onRequestAddQuickTile(context) },
            modifier = Modifier.fillMaxWidth().testTag("add_quick_tile_button").minimumInteractiveComponentSize(),
          ) {
            Icon(Icons.Rounded.GridView, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add 'WA Auto-Reply' to Quick Settings")
          }

          if (!tileStatusMessage.isNullOrBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text(
                  text = tileStatusMessage,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSecondaryContainer,
                  modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClearTileStatus) { Text("OK") }
              }
            }
          }
        }
      }

      Spacer(Modifier.height(10.dp))
    }
  }

  if (showSwitchConfirm) {
    AlertDialog(
      onDismissRequest = { showSwitchConfirm = false },
      icon = { Icon(Icons.Rounded.SwapHoriz, contentDescription = null) },
      title = { Text("Switch WhatsApp Account?") },
      text = {
        Text(
          "This will sign out of your current saved WhatsApp session and generate a fresh QR code to link a new phone."
        )
      },
      confirmButton = {
        Button(
          onClick = {
            showSwitchConfirm = false
            onRelink()
          },
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
          modifier = Modifier.testTag("confirm_switch_account_button"),
        ) {
          Text("Log Out & Show QR")
        }
      },
      dismissButton = {
        TextButton(onClick = { showSwitchConfirm = false }) {
          Text("Keep Login")
        }
      },
    )
  }
}

@Composable
private fun SavedSessionHomeCard(
  connectionState: WhatsAppConnectionState,
  runtimeStatus: AgentRuntimeStatus,
  autoReplyEnabled: Boolean,
  onToggleAutoReply: (Boolean) -> Unit,
  onConnect: () -> Unit,
  onReconnect: () -> Unit,
  onDisconnect: () -> Unit,
  onSwitchAccount: () -> Unit,
  onOpenChats: () -> Unit,
  onOpenLiveWebView: () -> Unit,
) {
  val isConnected = connectionState is WhatsAppConnectionState.Connected
  val dotColor by
    animateColorAsState(
      targetValue =
        when (connectionState) {
          is WhatsAppConnectionState.Connected -> MaterialTheme.colorScheme.primary
          is WhatsAppConnectionState.Error -> MaterialTheme.colorScheme.error
          else -> MaterialTheme.colorScheme.secondary
        },
      label = "homeDot",
    )

  val pulse by
    rememberInfiniteTransition(label = "homePulse").animateFloat(
      initialValue = 0.4f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
      label = "homePulseAlpha",
    )

  Card(
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large),
  ) {
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .background(
            Brush.linearGradient(
              listOf(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                MaterialTheme.colorScheme.surface,
              )
            )
          )
          .padding(20.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (isConnected) {
            Icon(
              imageVector = Icons.Rounded.CheckCircle,
              contentDescription = null,
              tint = dotColor.copy(alpha = pulse),
              modifier = Modifier.size(28.dp),
            )
          } else {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
          }
          Spacer(Modifier.width(10.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text =
                when (connectionState) {
                  is WhatsAppConnectionState.Connected -> "WhatsApp Connected"
                  is WhatsAppConnectionState.Connecting -> "Restoring Saved Login…"
                  is WhatsAppConnectionState.Reconnecting ->
                    "Reconnecting in ${connectionState.nextRetrySeconds}s…"
                  is WhatsAppConnectionState.Error -> "Connection Paused"
                  else -> "Saved Session Ready"
                },
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.SemiBold,
            )
            val subtitle =
              when (connectionState) {
                is WhatsAppConnectionState.Connected ->
                  connectionState.meName?.takeIf { it.isNotBlank() } ?: connectionState.meJid
                is WhatsAppConnectionState.Error -> connectionState.message
                else -> "Your login is saved on this phone — no QR needed."
              }
            Text(
              text = subtitle,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        if (runtimeStatus.isProcessing) {
          Card(
            colors =
              CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
              ),
          ) {
            Text(
              text = "AI is replying: ${runtimeStatus.streamingPreview.ifBlank { "Thinking…" }}",
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(10.dp),
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        // Big 1-Tap AI Auto-Reply Switch right on Home
        Card(
          colors =
            CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
          shape = MaterialTheme.shapes.medium,
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("Auto-Reply to Messages", style = MaterialTheme.typography.titleMedium)
              Text(
                if (autoReplyEnabled) "AI answers incoming chats automatically"
                else "Paused — you can still read & reply in Chats",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = autoReplyEnabled,
              onCheckedChange = onToggleAutoReply,
              modifier = Modifier.testTag("home_auto_reply_switch"),
            )
          }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (connectionState is WhatsAppConnectionState.Disconnected ||
            connectionState is WhatsAppConnectionState.Error
          ) {
            Button(
              onClick = onConnect,
              modifier = Modifier.weight(1f).testTag("connect_whatsapp_button").minimumInteractiveComponentSize(),
            ) {
              Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("Go Online")
            }
          } else {
            Button(
              onClick = onOpenChats,
              modifier = Modifier.weight(1f).testTag("open_chats_cta_button").minimumInteractiveComponentSize(),
            ) {
              Text("Open Chats")
            }
            OutlinedButton(
              onClick = onReconnect,
              modifier = Modifier.testTag("reconnect_whatsapp_button").minimumInteractiveComponentSize(),
            ) {
              Icon(Icons.Rounded.Refresh, contentDescription = "Reconnect", modifier = Modifier.size(18.dp))
            }
            OutlinedButton(
              onClick = onDisconnect,
              colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
              modifier = Modifier.testTag("disconnect_whatsapp_button").minimumInteractiveComponentSize(),
            ) {
              Icon(Icons.Rounded.LinkOff, contentDescription = "Pause connection", modifier = Modifier.size(18.dp))
            }
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(
            onClick = onOpenLiveWebView,
            modifier = Modifier.testTag("open_live_webview_button"),
          ) {
            Text("Live WhatsApp Web")
          }
          TextButton(
            onClick = onSwitchAccount,
            modifier = Modifier.testTag("relink_whatsapp_button"),
          ) {
            Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Switch Account")
          }
        }
      }
    }
  }
}

@Composable
private fun FreshSetupQrCard(
  connectionState: WhatsAppConnectionState,
  qrCountdownSeconds: Int,
  lastErrorMessage: String?,
  usePhoneInstead: Boolean,
  phoneInput: String,
  onPhoneInputChange: (String) -> Unit,
  onToggleMethod: () -> Unit,
  onConnect: () -> Unit,
  onRequestNewQr: () -> Unit,
  onRequestPairingCode: (String) -> Unit,
  onOpenLiveWebView: () -> Unit,
) {
  Card(
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Icon(
        imageVector = if (usePhoneInstead) Icons.Rounded.PhoneAndroid else Icons.Rounded.QrCode2,
        contentDescription = stringResource(R.string.cd_brand_emblem),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(36.dp),
      )

      Text(
        text = if (usePhoneInstead) "Link With Phone Number" else "Link Your WhatsApp",
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
      )

      if (!lastErrorMessage.isNullOrBlank()) {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(
              text = lastErrorMessage,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
        }
      }

      if (!usePhoneInstead) {
        when (connectionState) {
          is WhatsAppConnectionState.Disconnected -> {
            Text(
              text = "Tap the button below to get a fast QR code.\n1. Open WhatsApp on your phone\n2. Tap Linked Devices → Link a Device\n3. Scan the QR code",
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
            )
            Button(
              onClick = onConnect,
              modifier = Modifier.fillMaxWidth().height(52.dp).testTag("connect_whatsapp_button"),
            ) {
              Icon(Icons.Rounded.Link, contentDescription = null)
              Spacer(Modifier.width(8.dp))
              Text("Link WhatsApp", style = MaterialTheme.typography.titleMedium)
            }
          }
          is WhatsAppConnectionState.Connecting -> {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            Text(
              text = connectionState.statusNote,
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          is WhatsAppConnectionState.Reconnecting -> {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            Text(
              text = "Connecting in ${connectionState.nextRetrySeconds}s…",
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          is WhatsAppConnectionState.Error -> {
            Text(
              text = connectionState.message,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
              textAlign = TextAlign.Center,
            )
            Button(
              onClick = onConnect,
              modifier = Modifier.fillMaxWidth().height(50.dp).testTag("connect_whatsapp_button"),
            ) {
              Text("Try Again")
            }
          }
          is WhatsAppConnectionState.QrReady -> {
            val bmp = connectionState.qrBitmap
            if (bmp != null) {
              Box(
                modifier =
                  Modifier.size(250.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White)
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
              ) {
                Image(
                  bitmap = bmp.asImageBitmap(),
                  contentDescription = stringResource(R.string.cd_qr_code),
                  modifier = Modifier.fillMaxSize(),
                )
              }
            } else {
              CircularProgressIndicator(modifier = Modifier.padding(20.dp))
            }

            if (qrCountdownSeconds > 0) {
              LinearProgressIndicator(
                progress = { (qrCountdownSeconds / 60f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
              )
              Text(
                text = "Scan in WhatsApp → Linked Devices → Link a Device\nNew code in ${qrCountdownSeconds}s",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
            }

            OutlinedButton(
              onClick = onRequestNewQr,
              modifier = Modifier.testTag("refresh_qr_button").minimumInteractiveComponentSize(),
            ) {
              Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("Refresh QR Code")
            }
          }
          is WhatsAppConnectionState.Connected -> Unit
        }
      } else {
        Text(
          text = "Type your WhatsApp number with country code (no spaces or + sign).",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
        OutlinedTextField(
          value = phoneInput,
          onValueChange = onPhoneInputChange,
          label = { Text("Phone number (e.g. 15551234567)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("phone_pairing_input"),
        )
        Button(
          onClick = { onRequestPairingCode(phoneInput) },
          enabled = phoneInput.count { it.isDigit() } >= 7,
          modifier = Modifier.fillMaxWidth().height(50.dp).testTag("request_pairing_code_button"),
        ) {
          Text("Get Pairing Code")
        }
        val code = (connectionState as? WhatsAppConnectionState.QrReady)?.pairingCode
        if (!code.isNullOrBlank()) {
          Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Text(
              text = code.chunked(4).joinToString("-"),
              style = MaterialTheme.typography.headlineLarge,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
          }
          Text(
            text = "In WhatsApp: Linked Devices → Link a Device → Link with phone number instead",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(
          onClick = onOpenLiveWebView,
          modifier = Modifier.testTag("open_live_webview_button"),
        ) {
          Text("Live WhatsApp Web")
        }
        TextButton(
          onClick = onToggleMethod,
          modifier = Modifier.testTag("toggle_pairing_method_button"),
        ) {
          Text(if (usePhoneInstead) "Use QR Code Instead" else "Use Phone Instead")
        }
      }
    }
  }
}

@Composable
private fun SimpleStatPill(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
  accent: Boolean = false,
) {
  Card(
    modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    shape = MaterialTheme.shapes.medium,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 10.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = value,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun PermissionReadinessRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  subtitle: String,
  isReady: Boolean,
  actionLabel: String,
  testTag: String,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Row(
      modifier = Modifier.weight(1f),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
        modifier = Modifier.size(20.dp),
      )
      Column {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Spacer(Modifier.width(8.dp))
    if (isReady) {
      Icon(
        imageVector = Icons.Rounded.CheckCircle,
        contentDescription = "$title ready",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(24.dp),
      )
    } else {
      OutlinedButton(
        onClick = onClick,
        modifier = Modifier.testTag(testTag).minimumInteractiveComponentSize(),
      ) {
        Text(actionLabel)
      }
    }
  }
}

package com.example

import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.agent.ClawAgentRuntime
import com.example.ui.MainViewModel
import com.example.ui.components.HiddenVaultDialog
import com.example.ui.screens.AgentStudioScreen
import com.example.ui.screens.AskAiScreen
import com.example.ui.screens.ConversationsScreen
import com.example.ui.screens.LinkDashboardScreen
import com.example.ui.screens.ToolsHubScreen
import com.example.ui.theme.ClawLinkTheme
import kotlinx.serialization.Serializable

@Serializable object LinkRoute

@Serializable object ChatsRoute

@Serializable object AskAiRoute

@Serializable object StudioRoute

@Serializable object LogsRoute

private data class NavTab(
  val route: Any,
  val labelRes: Int,
  val activeIcon: ImageVector,
  val inactiveIcon: ImageVector,
  val testTag: String,
)

class MainActivity : ComponentActivity() {

  private val pendingDeepLinkChatJid = mutableStateOf<String?>(null)

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent.getStringExtra(ClawAgentRuntime.EXTRA_OPEN_CHAT_JID)?.let {
      pendingDeepLinkChatJid.value = it
    }
  }

  @OptIn(ExperimentalMaterial3Api::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    pendingDeepLinkChatJid.value = intent?.getStringExtra(ClawAgentRuntime.EXTRA_OPEN_CHAT_JID)
    (application as ClawLinkApplication).sessionManager.webEngine.updateBaseContext(this)
    setContent {
      val vm: MainViewModel = viewModel()
      val settings by vm.settings.collectAsStateWithLifecycle()

      ClawLinkTheme(themeMode = settings.themeMode) {
        val context = LocalContext.current
        val connectionState by vm.connectionState.collectAsStateWithLifecycle()
        val isSessionRegistered by vm.isSessionRegistered.collectAsStateWithLifecycle()
        val qrCountdownSeconds by vm.qrCountdownSeconds.collectAsStateWithLifecycle()
        val lastConnectionError by vm.lastConnectionError.collectAsStateWithLifecycle()
        val runtimeStatus by vm.runtimeStatus.collectAsStateWithLifecycle()
        val discoveredModels by vm.discoveredModels.collectAsStateWithLifecycle()
        val nimUiState by vm.nimUiState.collectAsStateWithLifecycle()
        val localModelState by vm.localModelState.collectAsStateWithLifecycle()
        val conversations by vm.conversations.collectAsStateWithLifecycle()
        val selectedChatJid by vm.selectedChatJid.collectAsStateWithLifecycle()
        val selectedConversation by vm.selectedConversation.collectAsStateWithLifecycle()
        val selectedMessages by vm.selectedChatMessages.collectAsStateWithLifecycle()
        val selectedMemories by vm.selectedChatMemories.collectAsStateWithLifecycle()
        val selectedChatJobs by vm.selectedChatJobs.collectAsStateWithLifecycle()
        val providerKeySummaries by vm.providerKeySummaries.collectAsStateWithLifecycle()
        val manualAiFeedback by vm.manualAiFeedback.collectAsStateWithLifecycle()
        val manualAiBusyAction by vm.manualAiBusyAction.collectAsStateWithLifecycle()
        val isAnalyzingBusinessCrm by vm.isAnalyzingBusinessCrm.collectAsStateWithLifecycle()
        val logs by vm.logs.collectAsStateWithLifecycle()
        val scheduledMessages by vm.scheduledMessages.collectAsStateWithLifecycle()
        val assistantMessages by vm.assistantMessages.collectAsStateWithLifecycle()
        val assistantTyping by vm.assistantTyping.collectAsStateWithLifecycle()
        val assistantStreamingPreview by vm.assistantStreamingPreview.collectAsStateWithLifecycle()
        val assistantToolNote by vm.assistantToolNote.collectAsStateWithLifecycle()
        val totalMessages by vm.totalMessageCount.collectAsStateWithLifecycle()
        val signalSessions by vm.signalSessionCount.collectAsStateWithLifecycle()
        val signalPreKeys by vm.signalPreKeyCount.collectAsStateWithLifecycle()
        val diagnosticResult by vm.diagnosticTestResult.collectAsStateWithLifecycle()
        val chatAiActionResult by vm.chatAiActionResult.collectAsStateWithLifecycle()
        val catchUpDigest by vm.catchUpDigest.collectAsStateWithLifecycle()
        val tileStatusMessage by vm.tileStatusMessage.collectAsStateWithLifecycle()
        val backupStatusMessage by vm.backupStatusMessage.collectAsStateWithLifecycle()
        val broadcastStatusMessage by vm.broadcastStatusMessage.collectAsStateWithLifecycle()
        val webViewHealthSummary by vm.webViewHealthSummary.collectAsStateWithLifecycle()
        val visionInspectorResult by vm.visionInspectorResult.collectAsStateWithLifecycle()
        val isDictating by vm.isDictating.collectAsStateWithLifecycle()
        val dictatedText by vm.dictatedText.collectAsStateWithLifecycle()

        var showHiddenVault by remember { mutableStateOf(false) }
        var showLiveWhatsAppWeb by remember { mutableStateOf(false) }

        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDest = navBackStackEntry?.destination

        val tabs =
          listOf(
            NavTab(LinkRoute, R.string.nav_link, Icons.Rounded.Home, Icons.Outlined.Home, "nav_link"),
            NavTab(ChatsRoute, R.string.nav_chats, Icons.Rounded.ChatBubble, Icons.Outlined.ChatBubbleOutline, "nav_chats"),
            NavTab(AskAiRoute, R.string.nav_ask_ai, Icons.Rounded.SmartToy, Icons.Outlined.SmartToy, "nav_ask_ai"),
            NavTab(StudioRoute, R.string.nav_agent, Icons.Rounded.AutoAwesome, Icons.Outlined.AutoAwesome, "nav_studio"),
            NavTab(LogsRoute, R.string.nav_logs, Icons.Rounded.Widgets, Icons.Outlined.Widgets, "nav_logs"),
          )

        fun navigateToTab(route: Any) {
          navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
          }
        }

        val deepLinkChat by pendingDeepLinkChatJid
        LaunchedEffect(deepLinkChat) {
          val jid = deepLinkChat
          if (!jid.isNullOrBlank()) {
            vm.selectChat(jid)
            navigateToTab(ChatsRoute)
            pendingDeepLinkChatJid.value = null
          }
        }

        Box(modifier = Modifier.fillMaxSize()) {
          // Persistent background WebView host keeps Chromium alive across tab switches
          if (!showLiveWhatsAppWeb) {
            AndroidView(
              factory = { ctx ->
                FrameLayout(ctx).also { vm.webEngine.attachToContainer(it) }
              },
              update = { container ->
                vm.webEngine.attachToContainer(container)
              },
              modifier = Modifier.size(1.dp).alpha(0.01f),
            )
          }

          BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp

            Scaffold(
              contentWindowInsets = WindowInsets.safeDrawing,
              bottomBar = {
                if (!useRail) {
                  NavigationBar {
                    tabs.forEach { tab ->
                      val selected = currentDest?.hasRoute(tab.route::class) == true
                      val label = stringResource(tab.labelRes)
                      NavigationBarItem(
                        selected = selected,
                        onClick = { navigateToTab(tab.route) },
                        icon = {
                          Icon(
                            imageVector = if (selected) tab.activeIcon else tab.inactiveIcon,
                            contentDescription = label,
                          )
                        },
                        label = { Text(label) },
                        modifier = Modifier.testTag(tab.testTag),
                      )
                    }
                  }
                }
              },
            ) { innerPadding ->
              Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                if (useRail) {
                  NavigationRail {
                    tabs.forEach { tab ->
                      val selected = currentDest?.hasRoute(tab.route::class) == true
                      val label = stringResource(tab.labelRes)
                      NavigationRailItem(
                        selected = selected,
                        onClick = { navigateToTab(tab.route) },
                        icon = {
                          Icon(
                            imageVector = if (selected) tab.activeIcon else tab.inactiveIcon,
                            contentDescription = label,
                          )
                        },
                        label = { Text(label) },
                        modifier = Modifier.testTag(tab.testTag),
                      )
                    }
                  }
                }

                NavHost(
                  navController = navController,
                  startDestination = LinkRoute,
                  modifier = Modifier.weight(1f).fillMaxSize(),
                ) {
                  composable<LinkRoute> {
                    LinkDashboardScreen(
                      connectionState = connectionState,
                      isSessionRegistered = isSessionRegistered,
                      qrCountdownSeconds = qrCountdownSeconds,
                      lastErrorMessage = lastConnectionError,
                      runtimeStatus = runtimeStatus,
                      settings = settings,
                      nimUiState = nimUiState,
                      activeChatsCount = conversations.size,
                      totalMessagesCount = totalMessages,
                      catchUpDigest = catchUpDigest,
                      tileStatusMessage = tileStatusMessage,
                      onConnect = vm::connectWhatsApp,
                      onDisconnect = vm::disconnectWhatsApp,
                      onReconnect = vm::reconnectWhatsApp,
                      onRelink = vm::relinkWhatsApp,
                      onRequestPairingCode = vm::requestPhonePairingCode,
                      onToggleDirectAutoReply = { enabled ->
                        vm.updateSettings { it.copy(autoReplyDirectChats = enabled) }
                      },
                      onUpdateSettings = vm::updateSettings,
                      onRunCatchUpDigest = vm::runCatchUpDigest,
                      onClearCatchUpDigest = vm::clearCatchUpDigest,
                      onRequestAddQuickTile = vm::requestAddQuickTile,
                      onClearTileStatus = vm::clearTileStatusMessage,
                      onNavigateToStudio = { navigateToTab(StudioRoute) },
                      onNavigateToChats = { navigateToTab(ChatsRoute) },
                      onOpenLiveWebView = {
                        vm.connectWhatsApp()
                        showLiveWhatsAppWeb = true
                      },
                      onUnlockHiddenVault = { showHiddenVault = true },
                    )
                  }
                  composable<ChatsRoute> {
                    ConversationsScreen(
                      conversations = conversations,
                      selectedChatJid = selectedChatJid,
                      selectedConversation = selectedConversation,
                      messages = selectedMessages,
                      memories = selectedMemories,
                      selectedChatJobs = selectedChatJobs,
                      settings = settings,
                      isDictating = isDictating,
                      dictatedText = dictatedText,
                      aiActionResult = chatAiActionResult,
                      manualAiBusyAction = manualAiBusyAction,
                      manualAiFeedback = manualAiFeedback,
                      isAnalyzingBusinessCrm = isAnalyzingBusinessCrm,
                      onSelectChat = vm::selectChat,
                      onSendMessage = vm::sendManualMessage,
                      onSendAttachment = vm::sendAttachmentFromUri,
                      onStartVoiceDictation = vm::startVoiceDictation,
                      onConsumeDictatedText = vm::consumeDictatedText,
                      onSpeakText = vm::speakText,
                      onSummarizeChat = vm::summarizeChat,
                      onDraftSmartReply = { jid -> vm.draftSmartReply(jid) },
                      onTranslateText = vm::translateChatText,
                      onInspectMedia = vm::inspectChatMediaMessage,
                      onDismissAiAction = vm::clearChatAiActionResult,
                      onExportChat = { conv, msgs -> vm.shareChatTranscript(context, conv, msgs) },
                      onUpdateContactConfig = vm::updateConversationConfig,
                      onAddMemory = vm::addContactMemory,
                      onDeleteMemory = vm::deleteContactMemory,
                      onClearMemory = vm::clearContactMemories,
                      onReplyRight = vm::replyRight,
                      onKeepReplying = vm::keepReplying,
                      onDismissManualFeedback = vm::clearManualAiFeedback,
                      onEnableGroupChatting = vm::enableGroupChatting,
                      onRefreshBusinessCrm = vm::refreshBusinessCrmInsights,
                    )
                  }
                  composable<AskAiRoute> {
                    AskAiScreen(
                      messages = assistantMessages,
                      conversations = conversations,
                      settings = settings,
                      connectionState = connectionState,
                      discoveredModels = discoveredModels,
                      isTyping = assistantTyping,
                      streamingPreview = assistantStreamingPreview,
                      activeToolNote = assistantToolNote,
                      isDictating = isDictating,
                      dictatedText = dictatedText,
                      onSendToAi = vm::sendTextToAi,
                      onToggleReaction = vm::toggleAssistantReaction,
                      onDeleteMessage = vm::deleteAssistantMessage,
                      onClearChat = vm::clearAssistantChat,
                      onStartVoiceDictation = vm::startVoiceDictation,
                      onConsumeDictatedText = vm::consumeDictatedText,
                      onSpeakText = vm::speakText,
                      onNavigateBackHome = { navigateToTab(LinkRoute) },
                      onNavigateToStudio = { navigateToTab(StudioRoute) },
                    )
                  }
                  composable<StudioRoute> {
                    AgentStudioScreen(
                      settings = settings,
                      discoveredModels = discoveredModels,
                      nimUiState = nimUiState,
                      providerKeySummaries = providerKeySummaries,
                      localModelState = localModelState,
                      isAnalyzingBusinessCrm = isAnalyzingBusinessCrm,
                      onSaveProviderKey = vm::saveProviderApiKey,
                      onTestProvider = { provider -> vm.testProviderConnection(provider) },
                      onClearProviderKey = vm::clearProviderApiKey,
                      onUpdateSettings = vm::updateSettings,
                      onDownloadLocalWhisper = vm::downloadLocalWhisperModel,
                      onDisableAndReleaseLocal = vm::disableAndReleaseLocalTranscription,
                      onScanBusinessCrm = { vm.refreshBusinessCrmInsights(null) },
                    )
                  }
                  composable<LogsRoute> {
                    ToolsHubScreen(
                      connectionState = connectionState,
                      settings = settings,
                      conversations = conversations,
                      scheduledMessages = scheduledMessages,
                      logs = logs,
                      signalSessionsCount = signalSessions,
                      signalPreKeysCount = signalPreKeys,
                      diagnosticResult = diagnosticResult,
                      webViewHealthSummary = webViewHealthSummary,
                      visionInspectorResult = visionInspectorResult,
                      maskedKeyStatus = nimUiState.maskedKeyStatus,
                      backupStatusMessage = backupStatusMessage,
                      broadcastStatusMessage = broadcastStatusMessage,
                      rulesEngine = vm.rulesEngine,
                      onUpdateSettings = vm::updateSettings,
                      onSendBroadcast = vm::sendBroadcastMessage,
                      onScheduleMessage = vm::scheduleWhatsAppMessage,
                      onCancelScheduledMessage = vm::cancelScheduledMessage,
                      onInspectMediaUri = vm::inspectPickedMediaUri,
                      onSpeakText = vm::speakText,
                      onTriggerHaptic = { vm.triggerHapticPulse() },
                      onExportBackup = vm::exportBackupToUri,
                      onImportBackup = vm::importBackupFromUri,
                      onReplenishPreKeys = vm::replenishPreKeys,
                      onRunAiDiagnostic = vm::runLiveAiDiagnosticTest,
                      onRunWebViewHealthCheck = vm::runWebViewDiagnostics,
                      onClearLogs = vm::clearLogs,
                    )
                  }
                }
              }
            }
          }
        }

        if (showLiveWhatsAppWeb) {
          Dialog(
            onDismissRequest = { showLiveWhatsAppWeb = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
          ) {
            Surface(
              modifier = Modifier.fillMaxSize(),
              color = MaterialTheme.colorScheme.background,
            ) {
              Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                  title = { Text("Live WhatsApp Web") },
                  navigationIcon = {
                    IconButton(onClick = { showLiveWhatsAppWeb = false }) {
                      Icon(Icons.Rounded.Close, contentDescription = "Close")
                    }
                  },
                  actions = {
                    IconButton(onClick = { vm.webEngine.reload() }) {
                      Icon(Icons.Rounded.Refresh, contentDescription = "Reload WhatsApp Web")
                    }
                    Spacer(Modifier.width(4.dp))
                  },
                )
                AndroidView(
                  factory = { ctx ->
                    FrameLayout(ctx).also { vm.webEngine.attachToContainer(it) }
                  },
                  update = { container ->
                    vm.webEngine.attachToContainer(container)
                  },
                  modifier = Modifier.weight(1f).fillMaxWidth(),
                )
              }
            }
          }
        }

        if (showHiddenVault) {
          HiddenVaultDialog(
            onDismiss = { showHiddenVault = false },
            onUseApiKey = vm::saveAndValidateNvidiaKey,
          )
        }
      }
    }
  }

  override fun onPause() {
    super.onPause()
    (application as ClawLinkApplication).sessionManager.webEngine.flushCookies()
  }

  override fun onResume() {
    super.onResume()
    val engine = (application as ClawLinkApplication).sessionManager.webEngine
    engine.updateBaseContext(this)
    engine.resumeWebView()
  }

  override fun onDestroy() {
    (application as ClawLinkApplication).sessionManager.webEngine.detachToBackground()
    super.onDestroy()
  }
}

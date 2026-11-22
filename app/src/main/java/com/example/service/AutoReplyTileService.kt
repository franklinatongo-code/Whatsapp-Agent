package com.example.service

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.ClawLinkApplication
import com.example.R
import com.example.whatsapp.WhatsAppConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Android Quick Settings Tile ("WA Auto-Reply") allowing instant 1-tap toggling of
 * AI auto-replies from the system Quick Settings shade without opening the app.
 */
class AutoReplyTileService : TileService() {

  private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  override fun onTileAdded() {
    super.onTileAdded()
    refreshTileAsync()
  }

  override fun onStartListening() {
    super.onStartListening()
    refreshTileAsync()
  }

  override fun onClick() {
    super.onClick()
    val app = application as? ClawLinkApplication ?: return
    tileScope.launch {
      var nextEnabled = true
      app.preferences.updateSettings { current ->
        nextEnabled = !current.autoReplyDirectChats
        current.copy(autoReplyDirectChats = nextEnabled)
      }
      app.repository.log(
        "INFO",
        "AGENT",
        "Quick Settings Tile toggled Auto-Reply ${if (nextEnabled) "ON" else "OFF"}",
      )
      if (nextEnabled &&
        app.credentialStore.getOrCreateCredentials().isRegistered &&
        app.sessionManager.state.value is WhatsAppConnectionState.Disconnected
      ) {
        runCatching { WhatsAppAgentService.startAndConnect(this@AutoReplyTileService) }
      }
      applyTileState(nextEnabled, app.sessionManager.state.value)
      WhatsAppAgentService.refreshNotification(this@AutoReplyTileService)
    }
  }

  override fun onDestroy() {
    tileScope.cancel()
    super.onDestroy()
  }

  private fun refreshTileAsync() {
    val app = application as? ClawLinkApplication ?: return
    tileScope.launch {
      val settings = runCatching { app.preferences.settingsFlow.first() }.getOrNull() ?: return@launch
      applyTileState(settings.autoReplyDirectChats, app.sessionManager.state.value)
    }
  }

  private fun applyTileState(
    enabled: Boolean,
    connectionState: WhatsAppConnectionState,
  ) {
    val tile = qsTile ?: return
    tile.label = getString(R.string.tile_auto_reply_label)
    tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val connLabel =
        when (connectionState) {
          is WhatsAppConnectionState.Connected -> "Online"
          is WhatsAppConnectionState.Connecting, is WhatsAppConnectionState.Reconnecting -> "Connecting"
          is WhatsAppConnectionState.QrReady -> "Pair QR"
          else -> "Saved"
        }
      tile.subtitle = if (enabled) "ON • $connLabel" else "Paused"
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      tile.stateDescription = if (enabled) "Auto-reply active" else "Auto-reply paused"
    }
    tile.updateTile()
  }

  companion object {
    fun requestRefresh(context: Context) {
      runCatching {
        requestListeningState(
          context.applicationContext,
          ComponentName(context.applicationContext, AutoReplyTileService::class.java),
        )
      }
    }

    /**
     * Prompts the user via Android 13+ StatusBarManager system dialog to add the tile directly,
     * returning a status note for older Android versions or completion callbacks.
     */
    fun requestAddTileToQuickSettings(
      context: Context,
      onResult: (String) -> Unit,
    ) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val sbm = context.getSystemService(StatusBarManager::class.java)
        if (sbm != null) {
          sbm.requestAddTileService(
            ComponentName(context, AutoReplyTileService::class.java),
            context.getString(R.string.tile_auto_reply_label),
            Icon.createWithResource(context, R.drawable.ic_tile_auto_reply),
            context.mainExecutor,
          ) { resultCode ->
            val msg =
              when (resultCode) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                  "Added 'WA Auto-Reply' to your Quick Settings tiles!"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                  "'WA Auto-Reply' is already in your Quick Settings tiles!"
                else ->
                  "Swipe down twice → tap Edit (pencil) → drag 'WA Auto-Reply' into Quick Settings."
              }
            onResult(msg)
          }
          return
        }
      }
      onResult("Swipe down twice from top of screen → tap Edit (pencil) → add 'WA Auto-Reply'.")
    }
  }
}

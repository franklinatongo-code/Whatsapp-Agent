package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ClawLinkApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restarts the persistent WhatsAppAgentService on device reboot, app update, or
 * OEM-kill watchdog alarm so incoming messages & scheduled sends continue even when closed.
 */
class BootCompletedReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action ?: return
    if (action != Intent.ACTION_BOOT_COMPLETED &&
      action != Intent.ACTION_MY_PACKAGE_REPLACED &&
      action != ACTION_KEEPALIVE_ALARM
    ) {
      return
    }
    val app = context.applicationContext as? ClawLinkApplication ?: return
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val creds = app.credentialStore.getOrCreateCredentials()
        val settings = runCatching { app.preferences.settingsFlow.first() }.getOrNull()
        val shouldRun = creds.isRegistered && (settings?.autoConnectOnLaunch ?: true)
        if (shouldRun) {
          runCatching { WhatsAppAgentService.startAndConnect(app) }
        }
        AutoReplyTileService.requestRefresh(app)
      } finally {
        pending.finish()
      }
    }
  }

  companion object {
    const val ACTION_KEEPALIVE_ALARM = "com.example.action.KEEPALIVE_ALARM"
  }
}

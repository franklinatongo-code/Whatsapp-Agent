package com.example.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.ClawLinkApplication
import com.example.MainActivity
import com.example.R
import com.example.agent.GroupPermissionPolicy
import com.example.agent.PermissionDecision
import com.example.agent.TriggerSource
import com.example.data.db.ChatMessageEntity
import com.example.whatsapp.WhatsAppConnectionState
import com.example.whatsapp.WhatsAppSendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Persistent foreground service keeping WhatsApp Web, WebView timers, WakeLock/WifiLock,
 * scheduled message delivery, and autonomous ClawAgentRuntime execution alive even when closed.
 */
class WhatsAppAgentService : Service() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var stateJob: Job? = null
  private var heartbeatJob: Job? = null
  private var networkCallback: ConnectivityManager.NetworkCallback? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private var wifiLock: WifiManager.WifiLock? = null
  @Volatile private var manuallyStopped = false

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    promoteToForeground("Initializing WhatsApp channel…", autoReplyOn = true)

    val app = application as ClawLinkApplication
    app.agentRuntime.start()
    acquireKeepAliveLocks()
    registerNetworkMonitor(app)

    stateJob =
      serviceScope.launch {
        var lastKey: String? = null
        kotlinx.coroutines.flow.combine(app.sessionManager.state, app.preferences.settingsFlow) { state, settings ->
          state to settings.autoReplyDirectChats
        }.collectLatest { (state, autoReplyOn) ->
          val statusPrefix = if (autoReplyOn) "AI Auto-Reply ON" else "AI Auto-Reply PAUSED"
          val connText =
            when (state) {
              is WhatsAppConnectionState.Disconnected -> "WhatsApp disconnected"
              is WhatsAppConnectionState.Connecting -> "Connecting (attempt ${state.attempt})…"
              is WhatsAppConnectionState.QrReady -> "QR ready — scan via Linked Devices"
              is WhatsAppConnectionState.Connected -> "Online (${state.meName ?: state.meJid})"
              is WhatsAppConnectionState.Reconnecting -> "Reconnecting in ${state.nextRetrySeconds}s…"
              is WhatsAppConnectionState.Error -> "Error: ${state.message}"
            }
          val combined = "$statusPrefix • $connText"
          if (combined != lastKey) {
            lastKey = combined
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(combined, autoReplyOn))
            AutoReplyTileService.requestRefresh(this@WhatsAppAgentService)
          }
        }
      }

    heartbeatJob =
      serviceScope.launch(Dispatchers.IO) {
        while (isActive) {
          delay(20_000L)
          app.sessionManager.webEngine.keepAliveTick()
          dispatchDueScheduledMessages(app)
        }
      }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val app = application as ClawLinkApplication
    when (intent?.action) {
      ACTION_CONNECT -> {
        manuallyStopped = false
        val st = app.sessionManager.state.value
        if (st is WhatsAppConnectionState.Disconnected ||
          st is WhatsAppConnectionState.Error ||
          st is WhatsAppConnectionState.Reconnecting
        ) {
          app.sessionManager.connect(force = true)
        }
      }
      ACTION_RECONNECT -> {
        manuallyStopped = false
        app.sessionManager.reconnectNow()
      }
      ACTION_TOGGLE_AUTO_REPLY -> {
        serviceScope.launch {
          var nextOn = true
          app.preferences.updateSettings { cur ->
            nextOn = !cur.autoReplyDirectChats
            cur.copy(autoReplyDirectChats = nextOn)
          }
          app.repository.log("INFO", "AGENT", "Notification toggled Auto-Reply ${if (nextOn) "ON" else "OFF"}")
          AutoReplyTileService.requestRefresh(this@WhatsAppAgentService)
        }
      }
      ACTION_REFRESH_NOTIF -> {
        serviceScope.launch {
          val s = app.repository.getCurrentSettings()
          val st = app.sessionManager.state.value
          val prefix = if (s.autoReplyDirectChats) "AI Auto-Reply ON" else "AI Auto-Reply PAUSED"
          val conn = if (st is WhatsAppConnectionState.Connected) "Online (${st.meName ?: st.meJid})" else "Background ready"
          val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
          nm.notify(NOTIFICATION_ID, buildNotification("$prefix • $conn", s.autoReplyDirectChats))
        }
      }
      ACTION_DISCONNECT -> {
        manuallyStopped = true
        cancelKeepAliveWatchdog()
        app.sessionManager.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
      }
      else -> {
        if (app.sessionManager.state.value is WhatsAppConnectionState.Disconnected) {
          app.sessionManager.connect()
        }
      }
    }
    return START_STICKY
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    val app = application as ClawLinkApplication
    app.sessionManager.webEngine.detachToBackground()
    if (!manuallyStopped && app.credentialStore.getOrCreateCredentials().isRegistered) {
      scheduleKeepAliveWatchdog(delayMs = 8_000L)
    }
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    unregisterNetworkMonitor()
    releaseKeepAliveLocks()
    heartbeatJob?.cancel()
    stateJob?.cancel()
    serviceScope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun promoteToForeground(statusText: String, autoReplyOn: Boolean) {
    val notif = buildNotification(statusText, autoReplyOn)
    val fgsType =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
      } else {
        0
      }
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notif, fgsType)
  }

  private suspend fun dispatchDueScheduledMessages(app: ClawLinkApplication) {
    val st = app.sessionManager.state.value
    if (st !is WhatsAppConnectionState.Connected) return
    val due = app.repository.getDueScheduledMessages(System.currentTimeMillis())
    if (due.isEmpty()) return
    val settings = app.repository.getCurrentSettings()
    val meJid = app.credentialStore.getOrCreateCredentials().meJid
    for (item in due) {
      val conv = app.repository.getConversation(item.chatJid)
      when (val perm = GroupPermissionPolicy.evaluateOutgoingPermission(item.chatJid, settings, conv, TriggerSource.SCHEDULED_JOB)) {
        is PermissionDecision.Blocked -> {
          app.repository.updateScheduledStatus(item.id, "BLOCKED", System.currentTimeMillis())
          app.repository.log("WARN", "WHATSAPP", "Blocked scheduled message to ${item.displayName}: ${perm.reason}")
          continue
        }
        PermissionDecision.Allowed -> Unit
      }
      val sendRes =
        runCatching {
          app.sessionManager.messaging.sendTextMessageDetailed(item.chatJid, item.messageText, meJid)
        }.getOrElse { WhatsAppSendResult.Failed(it.message ?: "Scheduled dispatch error") }
      val sentId = sendRes.sentIdOrNull()
      if (sentId != null) {
        app.repository.updateScheduledStatus(item.id, "SENT", System.currentTimeMillis())
        app.repository.recordMessage(
          ChatMessageEntity(
            id = sentId,
            chatJid = item.chatJid,
            senderJid = meJid ?: "me@local",
            senderName = "You (Scheduled)",
            fromMe = true,
            timestamp = System.currentTimeMillis(),
            messageType = "TEXT",
            textContent = item.messageText,
            deliveryStatus = if (sendRes is WhatsAppSendResult.Confirmed) "SENT" else "SENT_UNCONFIRMED",
          )
        )
        app.repository.log("INFO", "WHATSAPP", "Delivered scheduled message to ${item.displayName}")
      } else {
        app.repository.updateScheduledStatus(item.id, "FAILED", System.currentTimeMillis())
        app.repository.log("WARN", "WHATSAPP", "Scheduled send failed for ${item.displayName}")
      }
    }
  }

  private fun acquireKeepAliveLocks() {
    runCatching {
      val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
      if (pm != null && wakeLock == null) {
        wakeLock =
          pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClawLinkWA::AgentKeepAlive").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
          }
      }
    }
    runCatching {
      val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
      if (wm != null && wifiLock == null) {
        val mode =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
          else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock =
          wm.createWifiLock(mode, "ClawLinkWA::WifiKeepAlive").apply {
            setReferenceCounted(false)
            acquire()
          }
      }
    }
  }

  private fun releaseKeepAliveLocks() {
    runCatching {
      if (wakeLock?.isHeld == true) wakeLock?.release()
      wakeLock = null
    }
    runCatching {
      if (wifiLock?.isHeld == true) wifiLock?.release()
      wifiLock = null
    }
  }

  private fun scheduleKeepAliveWatchdog(delayMs: Long) {
    val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    val intent = Intent(this, BootCompletedReceiver::class.java).setAction(BootCompletedReceiver.ACTION_KEEPALIVE_ALARM)
    val pi =
      PendingIntent.getBroadcast(
        this,
        7101,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val triggerAt = SystemClock.elapsedRealtime() + delayMs
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
      } else {
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
      }
    }
  }

  private fun cancelKeepAliveWatchdog() {
    val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    val intent = Intent(this, BootCompletedReceiver::class.java).setAction(BootCompletedReceiver.ACTION_KEEPALIVE_ALARM)
    val pi =
      PendingIntent.getBroadcast(
        this,
        7101,
        intent,
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
      )
    if (pi != null) {
      am.cancel(pi)
    }
  }

  private fun registerNetworkMonitor(app: ClawLinkApplication) {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    val request =
      NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
    val cb =
      object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          val st = app.sessionManager.state.value
          if (st is WhatsAppConnectionState.Reconnecting || st is WhatsAppConnectionState.Error) {
            app.sessionManager.connect()
          }
        }
      }
    runCatching { cm.registerNetworkCallback(request, cb) }
    networkCallback = cb
  }

  private fun unregisterNetworkMonitor() {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
    networkCallback = null
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          getString(R.string.notif_channel_name),
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = getString(R.string.notif_channel_desc)
        }
      val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      nm.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(statusText: String, autoReplyOn: Boolean): Notification {
    val openIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val toggleIntent =
      PendingIntent.getService(
        this,
        2,
        Intent(this, WhatsAppAgentService::class.java).setAction(ACTION_TOGGLE_AUTO_REPLY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val stopIntent =
      PendingIntent.getService(
        this,
        1,
        Intent(this, WhatsAppAgentService::class.java).setAction(ACTION_DISCONNECT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_tile_auto_reply)
      .setContentTitle(getString(R.string.app_name))
      .setContentText(statusText)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(openIntent)
      .addAction(0, if (autoReplyOn) "Pause AI" else "Resume AI", toggleIntent)
      .addAction(0, "Disconnect", stopIntent)
      .build()
  }

  companion object {
    const val CHANNEL_ID = "clawlink_wa_channel"
    const val NOTIFICATION_ID = 4201
    const val ACTION_CONNECT = "com.example.action.CONNECT"
    const val ACTION_RECONNECT = "com.example.action.RECONNECT"
    const val ACTION_DISCONNECT = "com.example.action.DISCONNECT"
    const val ACTION_TOGGLE_AUTO_REPLY = "com.example.action.TOGGLE_AUTO_REPLY"
    const val ACTION_REFRESH_NOTIF = "com.example.action.REFRESH_NOTIF"

    fun startAndConnect(context: Context) {
      val intent = Intent(context, WhatsAppAgentService::class.java).setAction(ACTION_CONNECT)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stopAndDisconnect(context: Context) {
      val intent = Intent(context, WhatsAppAgentService::class.java).setAction(ACTION_DISCONNECT)
      context.startService(intent)
    }

    fun refreshNotification(context: Context) {
      runCatching {
        val intent = Intent(context, WhatsAppAgentService::class.java).setAction(ACTION_REFRESH_NOTIF)
        context.startService(intent)
      }
    }
  }
}

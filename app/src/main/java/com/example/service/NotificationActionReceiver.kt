package com.example.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.example.ClawLinkApplication
import com.example.data.db.ChatMessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles direct notification replies (RemoteInput) and Mark-Read actions from incoming
 * WhatsApp notifications without needing to open the app.
 */
class NotificationActionReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action ?: return
    val chatJid = intent.getStringExtra(EXTRA_CHAT_JID) ?: return
    val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
    val app = context.applicationContext as? ClawLinkApplication ?: return
    val pending = goAsync()

    CoroutineScope(Dispatchers.IO).launch {
      try {
        when (action) {
          ACTION_DIRECT_REPLY -> {
            val replyText =
              RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(KEY_REMOTE_REPLY)
                ?.toString()
                ?.trim()
                .orEmpty()
            if (replyText.isNotBlank()) {
              val meJid = app.credentialStore.getOrCreateCredentials().meJid
              val sentId = app.sessionManager.messaging.sendTextMessage(chatJid, replyText, meJid)
              app.repository.recordMessage(
                ChatMessageEntity(
                  id = sentId ?: "notif_${System.nanoTime()}",
                  chatJid = chatJid,
                  senderJid = meJid ?: "me@local",
                  senderName = "You",
                  fromMe = true,
                  timestamp = System.currentTimeMillis(),
                  messageType = "TEXT",
                  textContent = replyText,
                  deliveryStatus = if (sentId != null) "SENT" else "FAILED",
                )
              )
              app.repository.clearUnread(chatJid)
              app.sessionManager.webEngine.sendSeen(chatJid)
            }
          }
          ACTION_MARK_READ -> {
            app.repository.clearUnread(chatJid)
            app.sessionManager.webEngine.sendSeen(chatJid)
          }
        }
        if (notifId > 0) {
          val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
          nm?.cancel(notifId)
        }
      } finally {
        pending.finish()
      }
    }
  }

  companion object {
    const val ACTION_DIRECT_REPLY = "com.example.action.NOTIF_DIRECT_REPLY"
    const val ACTION_MARK_READ = "com.example.action.NOTIF_MARK_READ"
    const val EXTRA_CHAT_JID = "extra_chat_jid"
    const val EXTRA_NOTIF_ID = "extra_notif_id"
    const val KEY_REMOTE_REPLY = "key_remote_reply"
  }
}

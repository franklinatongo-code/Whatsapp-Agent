package com.example.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
  @PrimaryKey val chatJid: String,
  val displayName: String,
  val isGroup: Boolean,
  val lastMessagePreview: String = "",
  val lastMessageTimestamp: Long = 0L,
  val unreadCount: Int = 0,
  val rollingSummary: String = "",
  val autoReplyEnabled: Boolean = true,
  val permissionTier: String = "STANDARD",
  val customInstructions: String = "",
  val preferredLanguage: String = "",
  val responseStyle: String = "",
  val totalTokensUsed: Int = 0,
  val recentContextCountOverride: Int? = null,
  val contextModeOverride: String? = null,
  val businessCategory: String = "",
  val businessLeadScore: Int = 0,
  val businessIntent: String = "",
  val businessNextAction: String = "",
  val businessUpdatedAtMs: Long = 0L,
  val lastProcessingState: String = "",
  val lastSummarizedMessageId: String = "",
  val summaryUpdatedAtMs: Long = 0L,
  val summaryMessageCount: Int = 0,
  val summaryVersion: Int = 0,
  val chatType: String = if (isGroup) "GROUP" else "INDIVIDUAL",
  val perGroupPermissionOverride: String? = null,
)

@Entity(
  tableName = "chat_messages",
  indices = [Index(value = ["chatJid", "timestamp"])],
)
data class ChatMessageEntity(
  @PrimaryKey val id: String,
  val chatJid: String,
  val senderJid: String,
  val senderName: String,
  val fromMe: Boolean,
  val timestamp: Long,
  val messageType: String, // TEXT, IMAGE, AUDIO, PTT, VIDEO, DOCUMENT, STICKER, LOCATION, CONTACT, REACTION, SYSTEM
  val textContent: String,
  val mediaMimeType: String? = null,
  val mediaLocalPath: String? = null,
  val mediaTranscription: String? = null,
  val quotedMessageId: String? = null,
  val quotedPreview: String? = null,
  val isEdited: Boolean = false,
  val isRevoked: Boolean = false,
  val deliveryStatus: String = "RECEIVED", // RECEIVED, PENDING, SENT, SENT_UNCONFIRMED, UNKNOWN, DELIVERED, READ, FAILED
  val aiTokensUsed: Int = 0,
  val reactionEmoji: String = "",
  val mediaSha256: String? = null,
)

@Entity(tableName = "assistant_messages")
data class AssistantMessageEntity(
  @PrimaryKey val id: String,
  val role: String, // "user" or "assistant"
  val textContent: String,
  val imageLocalPath: String? = null,
  val fileLocalPath: String? = null,
  val fileName: String? = null,
  val fileMimeType: String? = null,
  val reactionEmoji: String = "",
  val quotedPreview: String? = null,
  val toolActivitySummary: String? = null,
  val timestamp: Long = System.currentTimeMillis(),
)

@Entity(
  tableName = "contact_memories",
  indices = [Index(value = ["chatJid"])],
)
data class ContactMemoryEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val chatJid: String,
  val factKey: String,
  val factValue: String,
  val category: String = "GENERAL",
  val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
  tableName = "signal_keys",
  indices = [Index(value = ["keyType"])],
)
data class SignalKeyEntity(
  @PrimaryKey val compositeKey: String,
  val keyType: String, // SESSION, PREKEY, SIGNED_PREKEY, IDENTITY, SENDER_KEY
  val keyId: String,
  val dataBase64: String,
  val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "agent_logs")
data class AgentLogEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val timestamp: Long = System.currentTimeMillis(),
  val level: String, // INFO, WARN, ERROR, DEBUG
  val category: String, // WHATSAPP, PROTOCOL, AGENT, NVIDIA_NIM, MEDIA, SECURITY
  val message: String,
)

@Entity(
  tableName = "scheduled_messages",
  indices = [Index(value = ["status", "scheduledAtMs"])],
)
data class ScheduledMessageEntity(
  @PrimaryKey val id: String,
  val chatJid: String,
  val displayName: String,
  val messageText: String,
  val scheduledAtMs: Long,
  val status: String = "PENDING", // PENDING, SENT, FAILED, CANCELLED, BLOCKED
  val createdAtMs: Long = System.currentTimeMillis(),
  val sentAtMs: Long? = null,
)

@Entity(
  tableName = "processing_jobs",
  indices =
    [
      Index(value = ["incomingMessageId"], unique = true),
      Index(value = ["chatJid", "updatedAtMs"]),
    ],
)
data class MessageProcessingJobEntity(
  @PrimaryKey val jobId: String,
  val incomingMessageId: String,
  val chatJid: String,
  val triggerType: String = "AUTO", // AUTO, REPLY_RIGHT, KEEP_REPLYING
  val stage: String = "DETECTED", // DETECTED, PERSISTED, QUEUED, PARSED, CONTEXT_LOADED, AI_PROCESSING, VALIDATED, SENDING, CONFIRMED, SENT_UNCONFIRMED, UNKNOWN, FAILED_RETRYABLE, FAILED, BLOCKED
  val attemptCount: Int = 1,
  val errorMessage: String = "",
  val outgoingMessageId: String? = null,
  val replyHash: String = "",
  val createdAtMs: Long = System.currentTimeMillis(),
  val updatedAtMs: Long = System.currentTimeMillis(),
  val sendCorrelationId: String = "",
  val nextRetryAtMs: Long = 0L,
)

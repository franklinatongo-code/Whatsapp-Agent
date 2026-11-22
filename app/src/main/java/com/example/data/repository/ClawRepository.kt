package com.example.data.repository

import com.example.data.db.AgentLogEntity
import com.example.data.db.AssistantMessageEntity
import com.example.data.db.ChatMessageEntity
import com.example.data.db.ClawDatabase
import com.example.data.db.ContactMemoryEntity
import com.example.data.db.ConversationEntity
import com.example.data.db.MessageProcessingJobEntity
import com.example.data.db.ScheduledMessageEntity
import com.example.data.prefs.AgentSettings
import com.example.data.prefs.AppPreferences
import com.example.security.NvidiaKeyProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

class ClawRepository(
  private val db: ClawDatabase,
  val preferences: AppPreferences,
) {
  private val conversationDao = db.conversationDao()
  private val messageDao = db.chatMessageDao()
  private val assistantDao = db.assistantMessageDao()
  private val memoryDao = db.contactMemoryDao()
  private val logDao = db.agentLogDao()
  private val scheduledDao = db.scheduledMessageDao()
  private val jobDao = db.messageProcessingJobDao()

  val conversations: Flow<List<ConversationEntity>> = conversationDao.observeAll()
  val assistantMessages: Flow<List<AssistantMessageEntity>> = assistantDao.observeAll()
  val recentLogs: Flow<List<AgentLogEntity>> = logDao.observeRecent()
  val scheduledMessages: Flow<List<ScheduledMessageEntity>> = scheduledDao.observeAll()
  val totalMessageCount: Flow<Int> = messageDao.observeTotalCount()
  val settings: Flow<AgentSettings> = preferences.settingsFlow

  fun observeConversation(chatJid: String): Flow<ConversationEntity?> =
    conversationDao.observeByJid(chatJid)

  fun observeMessages(chatJid: String): Flow<List<ChatMessageEntity>> =
    messageDao.observeForChat(chatJid)

  fun observeMemories(chatJid: String): Flow<List<ContactMemoryEntity>> =
    memoryDao.observeForChat(chatJid)

  fun observeProcessingJobsForChat(chatJid: String): Flow<List<MessageProcessingJobEntity>> =
    jobDao.observeRecentForChat(chatJid)

  suspend fun getCurrentSettings(): AgentSettings = preferences.settingsFlow.first()

  suspend fun getConversation(chatJid: String): ConversationEntity? =
    conversationDao.getByJid(chatJid)

  suspend fun ensureConversation(
    chatJid: String,
    displayName: String,
    isGroup: Boolean,
    chatTitle: String = "",
  ): ConversationEntity {
    val cleanTitle = chatTitle.trim().takeIf { it.isNotBlank() && !it.equals("null", true) }.orEmpty()
    val cleanSender = displayName.trim().takeIf { it.isNotBlank() && !it.equals("null", true) }.orEmpty()
    val fallbackLabel =
      if (isGroup) {
        cleanTitle.ifBlank { "Group ${chatJid.substringBefore("@").takeLast(6)}" }
      } else {
        cleanTitle.ifBlank { cleanSender }.ifBlank { chatJid.substringBefore("@") }
      }

    val existing = conversationDao.getByJid(chatJid)
    if (existing != null) {
      val jidPrefix = existing.chatJid.substringBefore("@")
      val betterName =
        if (isGroup) {
          when {
            cleanTitle.isNotBlank() &&
              (existing.displayName == jidPrefix ||
                existing.displayName.startsWith("Group ") ||
                existing.displayName.isBlank()) -> cleanTitle
            cleanTitle.isNotBlank() && cleanTitle != existing.displayName -> cleanTitle
            else -> existing.displayName
          }
        } else {
          if (fallbackLabel.isNotBlank() &&
            (existing.displayName == jidPrefix || existing.displayName.isBlank())
          ) {
            fallbackLabel
          } else {
            existing.displayName
          }
        }
      return if (betterName != existing.displayName || existing.isGroup != isGroup) {
        existing.copy(displayName = betterName, isGroup = isGroup).also { conversationDao.upsert(it) }
      } else {
        existing
      }
    }
    val created =
      ConversationEntity(
        chatJid = chatJid,
        displayName = fallbackLabel,
        isGroup = isGroup,
      )
    conversationDao.upsert(created)
    return created
  }

  suspend fun updateConversation(conversation: ConversationEntity) {
    conversationDao.upsert(conversation)
  }

  suspend fun clearUnread(chatJid: String) {
    conversationDao.clearUnread(chatJid)
  }

  suspend fun isDuplicateMessage(messageId: String): Boolean {
    return messageDao.existsById(messageId)
  }

  suspend fun recordMessage(
    message: ChatMessageEntity,
    previewOverride: String? = null,
    incrementUnread: Boolean = !message.fromMe,
    chatTitle: String = "",
  ): Boolean {
    if (message.fromMe && message.textContent.isNotBlank()) {
      val existingEcho =
        messageDao.findMatchingOutgoing(
          chatJid = message.chatJid,
          textContent = message.textContent.trim(),
          minTs = message.timestamp - 35_000L,
          maxTs = message.timestamp + 35_000L,
        )
      if (existingEcho != null) {
        if (existingEcho.deliveryStatus != "SENT" && message.deliveryStatus == "SENT") {
          messageDao.updateDeliveryStatus(existingEcho.id, "SENT")
        }
        return false
      }
    }

    val insertedRow = messageDao.insertIgnore(message)
    if (insertedRow == -1L) return false
    val isGroup = message.chatJid.endsWith("@g.us")
    val conv =
      ensureConversation(
        chatJid = message.chatJid,
        displayName = if (isGroup) "" else message.senderName,
        isGroup = isGroup,
        chatTitle = chatTitle,
      )
    val preview =
      previewOverride
        ?: if (isGroup && !message.fromMe && message.senderName.isNotBlank()) {
          "${message.senderName}: ${message.textContent.ifBlank { "[${message.messageType}]" }}"
        } else {
          message.textContent.ifBlank { "[${message.messageType}]" }
        }
    conversationDao.upsert(
      conv.copy(
        lastMessagePreview = preview.take(120),
        lastMessageTimestamp = maxOf(conv.lastMessageTimestamp, message.timestamp),
        unreadCount = if (incrementUnread) conv.unreadCount + 1 else conv.unreadCount,
        totalTokensUsed = conv.totalTokensUsed + message.aiTokensUsed,
      )
    )
    return true
  }

  suspend fun updateMessageStatus(messageId: String, status: String) {
    messageDao.updateDeliveryStatus(messageId, status)
  }

  suspend fun markMessageEdited(messageId: String, newText: String) {
    messageDao.markEdited(messageId, newText)
  }

  suspend fun markMessageRevoked(messageId: String) {
    messageDao.markRevoked(messageId)
  }

  suspend fun updateMediaTranscription(messageId: String, transcription: String, sha256: String? = null) {
    if (transcription.isBlank()) return
    messageDao.updateMediaTranscription(messageId, transcription.trim(), sha256)
  }

  suspend fun updateMediaLocalPath(messageId: String, localPath: String, mimeType: String? = null) {
    if (localPath.isBlank()) return
    messageDao.updateMediaLocalPath(messageId, localPath, mimeType)
  }

  suspend fun findCachedMediaTranscription(sha256: String): String? {
    if (sha256.isBlank()) return null
    return messageDao.findCachedTranscriptionBySha(sha256)
  }

  suspend fun getRecentMessages(chatJid: String, limit: Int = 16): List<ChatMessageEntity> {
    return messageDao.getRecentForChat(chatJid, limit.coerceIn(1, 100)).reversed()
  }

  suspend fun searchChatHistory(chatJid: String, query: String): List<ChatMessageEntity> {
    return messageDao.searchInChat(chatJid, query)
  }

  suspend fun findQuotedMessage(chatJid: String, quotedId: String): ChatMessageEntity? {
    val cleanId = quotedId.trim()
    if (cleanId.isBlank()) return null
    return messageDao.findQuotedMessage(chatJid, cleanId)
  }

  /**
   * Loads the messages immediately before & after a quoted message so the AI remembers
   * the exact conversational reason WHY that earlier reply was sent.
   */
  suspend fun getSurroundingThreadContext(
    chatJid: String,
    anchor: ChatMessageEntity,
    beforeCount: Int = 3,
    afterCount: Int = 2,
  ): List<ChatMessageEntity> {
    val before =
      if (beforeCount > 0) {
        messageDao.getMessagesBeforeTimestamp(chatJid, anchor.timestamp, anchor.id, beforeCount).reversed()
      } else {
        emptyList()
      }
    val after =
      if (afterCount > 0) {
        messageDao.getMessagesAfterTimestamp(chatJid, anchor.timestamp, anchor.id, afterCount)
      } else {
        emptyList()
      }
    return (before + anchor + after).distinctBy { it.id }.sortedBy { it.timestamp }
  }

  /**
   * Fast SQLite keyword recall across older messages in the same chat when the user
   * references past events, promises, or earlier questions outside the recent window.
   */
  suspend fun recallOlderContextForQuery(
    chatJid: String,
    queryText: String,
    excludeIds: Set<String>,
    maxResults: Int = 3,
  ): List<ChatMessageEntity> {
    val stopWords =
      setOf(
        "what", "when", "where", "which", "that", "this", "with", "from", "have", "your",
        "about", "remember", "earlier", "before", "said", "mean", "why", "you", "they", "them",
      )
    val keywords =
      Regex("""[A-Za-z0-9]{4,}""")
        .findAll(queryText.lowercase())
        .map { it.value }
        .filter { it !in stopWords }
        .distinct()
        .take(3)
        .toList()
    if (keywords.isEmpty()) return emptyList()
    val hits = linkedMapOf<String, ChatMessageEntity>()
    for (kw in keywords) {
      messageDao.searchInChat(chatJid, kw).forEach { msg ->
        if (msg.id !in excludeIds && !msg.isRevoked) {
          hits.putIfAbsent(msg.id, msg)
        }
      }
      if (hits.size >= maxResults) break
    }
    return hits.values.sortedBy { it.timestamp }.takeLast(maxResults)
  }

  suspend fun claimOrCreateProcessingJob(
    incomingMessageId: String,
    chatJid: String,
    triggerType: String = "AUTO",
  ): Pair<Boolean, MessageProcessingJobEntity> {
    val existing = jobDao.getByIncomingMessageId(incomingMessageId)
    if (existing != null) {
      return false to existing
    }
    val job =
      MessageProcessingJobEntity(
        jobId = "job_${UUID.randomUUID().toString().take(12)}",
        incomingMessageId = incomingMessageId,
        chatJid = chatJid,
        triggerType = triggerType,
        stage = "DETECTED",
      )
    val inserted = jobDao.insertIgnore(job)
    return if (inserted == -1L) {
      false to (jobDao.getByIncomingMessageId(incomingMessageId) ?: job)
    } else {
      conversationDao.updateProcessingState(chatJid, "DETECTED")
      true to job
    }
  }

  suspend fun getJobForIncomingMessage(incomingMessageId: String): MessageProcessingJobEntity? =
    jobDao.getByIncomingMessageId(incomingMessageId)

  suspend fun getRecoverableJobs(): List<MessageProcessingJobEntity> =
    jobDao.getRecoverableJobs()

  suspend fun hasRecentReplyWithHash(chatJid: String, replyHash: String, windowMs: Long = 120_000L): Boolean {
    if (replyHash.isBlank()) return false
    return jobDao.hasRecentReplyWithHash(chatJid, replyHash, System.currentTimeMillis() - windowMs)
  }

  suspend fun updateProcessingJobStage(
    incomingMessageId: String,
    chatJid: String,
    stage: String,
    attemptCount: Int = 1,
    errorMessage: String = "",
    outgoingMessageId: String? = null,
    replyHash: String = "",
    sendCorrelationId: String = "",
    nextRetryAtMs: Long = 0L,
  ) {
    jobDao.updateStage(
      incomingMessageId = incomingMessageId,
      stage = stage,
      attemptCount = attemptCount,
      errorMessage = errorMessage.take(300),
      outgoingMessageId = outgoingMessageId,
      replyHash = replyHash,
      sendCorrelationId = sendCorrelationId,
      nextRetryAtMs = nextRetryAtMs,
      updatedAtMs = System.currentTimeMillis(),
    )
    conversationDao.updateProcessingState(chatJid, stage)
  }

  suspend fun updateBusinessInsights(
    chatJid: String,
    category: String,
    leadScore: Int,
    intent: String,
    nextAction: String,
  ) {
    conversationDao.updateBusinessInsights(
      chatJid = chatJid,
      category = category.take(48),
      leadScore = leadScore.coerceIn(0, 100),
      intent = intent.take(240),
      nextAction = nextAction.take(240),
      updatedAtMs = System.currentTimeMillis(),
    )
  }

  suspend fun getMemories(chatJid: String): List<ContactMemoryEntity> =
    memoryDao.getForChat(chatJid)

  suspend fun saveContactMemory(
    chatJid: String,
    key: String,
    value: String,
    category: String = "FACT",
  ) {
    val cleanKey = key.trim().take(64)
    val cleanVal = value.trim().take(500)
    if (cleanKey.isBlank() || cleanVal.isBlank()) return
    val existing = memoryDao.findByKey(chatJid, cleanKey)
    if (existing != null) {
      memoryDao.upsert(
        existing.copy(
          factValue = cleanVal,
          category = category,
          updatedAt = System.currentTimeMillis(),
        )
      )
    } else {
      memoryDao.upsert(
        ContactMemoryEntity(
          chatJid = chatJid,
          factKey = cleanKey,
          factValue = cleanVal,
          category = category,
        )
      )
    }
  }

  suspend fun deleteMemory(id: Long) = memoryDao.deleteById(id)

  suspend fun deleteMemoryByKey(chatJid: String, key: String) {
    val cleanKey = key.trim()
    if (cleanKey.isNotBlank()) {
      memoryDao.deleteByKey(chatJid, cleanKey)
    }
  }

  suspend fun clearMemoriesForChat(chatJid: String) = memoryDao.clearForChat(chatJid)

  suspend fun updateSummary(chatJid: String, summary: String) =
    conversationDao.updateSummary(chatJid, summary.take(1500))

  suspend fun updateSummaryWithMetadata(
    chatJid: String,
    summary: String,
    lastSummarizedMessageId: String,
    summaryMessageCount: Int,
  ) {
    conversationDao.updateSummaryWithMetadata(
      chatJid = chatJid,
      summary = summary.take(1500),
      lastSummarizedMessageId = lastSummarizedMessageId,
      summaryMessageCount = summaryMessageCount,
      updatedAtMs = System.currentTimeMillis(),
    )
  }

  suspend fun addTokens(chatJid: String, delta: Int) {
    if (delta > 0) conversationDao.addTokens(chatJid, delta)
  }

  suspend fun log(level: String, category: String, rawMessage: String) {
    val safeMessage = NvidiaKeyProvider.redactSecrets(rawMessage).take(800)
    logDao.insert(
      AgentLogEntity(
        timestamp = System.currentTimeMillis(),
        level = level,
        category = category,
        message = safeMessage,
      )
    )
    logDao.trimOld()
  }

  suspend fun getAllConversations(): List<ConversationEntity> = conversationDao.getAll()

  suspend fun getAllMessages(): List<ChatMessageEntity> = messageDao.getAll()

  suspend fun getAllMemories(): List<ContactMemoryEntity> = memoryDao.getAll()

  suspend fun importBackupEntities(
    conversations: List<ConversationEntity>,
    messages: List<ChatMessageEntity>,
    memories: List<ContactMemoryEntity>,
  ) {
    if (conversations.isNotEmpty()) conversationDao.upsertAll(conversations)
    if (messages.isNotEmpty()) messageDao.insertAllIgnore(messages)
    if (memories.isNotEmpty()) memoryDao.upsertAll(memories)
  }

  suspend fun toggleWhatsAppReaction(messageId: String, emoji: String): String {
    val existing = messageDao.getById(messageId) ?: return ""
    val next = if (existing.reactionEmoji == emoji) "" else emoji
    messageDao.updateReaction(messageId, next)
    return next
  }

  suspend fun getRecentAssistantMessages(limit: Int = 20): List<AssistantMessageEntity> =
    assistantDao.getRecent(limit).reversed()

  suspend fun saveAssistantMessage(message: AssistantMessageEntity) {
    assistantDao.upsert(message)
  }

  suspend fun toggleAssistantReaction(id: String, emoji: String): String {
    val existing = assistantDao.getById(id) ?: return ""
    val next = if (existing.reactionEmoji == emoji) "" else emoji
    assistantDao.updateReaction(id, next)
    return next
  }

  suspend fun deleteAssistantMessage(id: String) = assistantDao.deleteById(id)

  suspend fun clearAssistantMessages() = assistantDao.clearAll()

  suspend fun scheduleWhatsAppMessage(
    chatJid: String,
    displayName: String,
    messageText: String,
    scheduledAtMs: Long,
  ): ScheduledMessageEntity {
    val item =
      ScheduledMessageEntity(
        id = "sched_${UUID.randomUUID().toString().take(12)}",
        chatJid = chatJid,
        displayName = displayName.ifBlank { chatJid.substringBefore("@") },
        messageText = messageText.trim(),
        scheduledAtMs = scheduledAtMs,
        status = "PENDING",
      )
    scheduledDao.upsert(item)
    return item
  }

  suspend fun getDueScheduledMessages(nowMs: Long = System.currentTimeMillis()): List<ScheduledMessageEntity> =
    scheduledDao.getDue(nowMs)

  suspend fun updateScheduledStatus(id: String, status: String, sentAtMs: Long? = null) =
    scheduledDao.updateStatus(id, status, sentAtMs)

  suspend fun deleteScheduledMessage(id: String) = scheduledDao.deleteById(id)

  suspend fun clearLogs() = logDao.clearAll()
}

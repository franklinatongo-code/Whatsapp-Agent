package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
  @Query("SELECT * FROM conversations ORDER BY lastMessageTimestamp DESC")
  fun observeAll(): Flow<List<ConversationEntity>>

  @Query("SELECT * FROM conversations WHERE chatJid = :chatJid LIMIT 1")
  fun observeByJid(chatJid: String): Flow<ConversationEntity?>

  @Query("SELECT * FROM conversations WHERE chatJid = :chatJid LIMIT 1")
  suspend fun getByJid(chatJid: String): ConversationEntity?

  @Query("SELECT * FROM conversations ORDER BY lastMessageTimestamp DESC")
  suspend fun getAll(): List<ConversationEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(conversation: ConversationEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertAll(conversations: List<ConversationEntity>)

  @Update
  suspend fun update(conversation: ConversationEntity)

  @Query("UPDATE conversations SET unreadCount = 0 WHERE chatJid = :chatJid")
  suspend fun clearUnread(chatJid: String)

  @Query("UPDATE conversations SET rollingSummary = :summary WHERE chatJid = :chatJid")
  suspend fun updateSummary(chatJid: String, summary: String)

  @Query(
    """
    UPDATE conversations
    SET rollingSummary = :summary,
        lastSummarizedMessageId = :lastSummarizedMessageId,
        summaryUpdatedAtMs = :updatedAtMs,
        summaryMessageCount = :summaryMessageCount,
        summaryVersion = summaryVersion + 1
    WHERE chatJid = :chatJid
    """
  )
  suspend fun updateSummaryWithMetadata(
    chatJid: String,
    summary: String,
    lastSummarizedMessageId: String,
    summaryMessageCount: Int,
    updatedAtMs: Long,
  )

  @Query("UPDATE conversations SET totalTokensUsed = totalTokensUsed + :delta WHERE chatJid = :chatJid")
  suspend fun addTokens(chatJid: String, delta: Int)

  @Query("UPDATE conversations SET lastProcessingState = :state WHERE chatJid = :chatJid")
  suspend fun updateProcessingState(chatJid: String, state: String)

  @Query(
    """
    UPDATE conversations
    SET businessCategory = :category,
        businessLeadScore = :leadScore,
        businessIntent = :intent,
        businessNextAction = :nextAction,
        businessUpdatedAtMs = :updatedAtMs
    WHERE chatJid = :chatJid
    """
  )
  suspend fun updateBusinessInsights(
    chatJid: String,
    category: String,
    leadScore: Int,
    intent: String,
    nextAction: String,
    updatedAtMs: Long,
  )
}

@Dao
interface ChatMessageDao {
  @Query("SELECT * FROM chat_messages WHERE chatJid = :chatJid ORDER BY timestamp ASC")
  fun observeForChat(chatJid: String): Flow<List<ChatMessageEntity>>

  @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
  suspend fun getAll(): List<ChatMessageEntity>

  @Query("SELECT * FROM chat_messages WHERE chatJid = :chatJid ORDER BY timestamp DESC LIMIT :limit")
  suspend fun getRecentForChat(chatJid: String, limit: Int): List<ChatMessageEntity>

  @Query("SELECT * FROM chat_messages WHERE id = :id LIMIT 1")
  suspend fun getById(id: String): ChatMessageEntity?

  @Query("SELECT EXISTS(SELECT 1 FROM chat_messages WHERE id = :id)")
  suspend fun existsById(id: String): Boolean

  @Query(
    "SELECT * FROM chat_messages WHERE chatJid = :chatJid AND fromMe = 1 AND textContent = :textContent AND timestamp BETWEEN :minTs AND :maxTs LIMIT 1"
  )
  suspend fun findMatchingOutgoing(
    chatJid: String,
    textContent: String,
    minTs: Long,
    maxTs: Long,
  ): ChatMessageEntity?

  @Query("SELECT * FROM chat_messages WHERE chatJid = :chatJid AND (textContent LIKE '%' || :query || '%' OR COALESCE(mediaTranscription, '') LIKE '%' || :query || '%' OR COALESCE(quotedPreview, '') LIKE '%' || :query || '%') ORDER BY timestamp DESC LIMIT 10")
  suspend fun searchInChat(chatJid: String, query: String): List<ChatMessageEntity>

  @Query("SELECT * FROM chat_messages WHERE chatJid = :chatJid AND (id = :quotedId OR id LIKE '%_' || :quotedId) ORDER BY timestamp DESC LIMIT 1")
  suspend fun findQuotedMessage(chatJid: String, quotedId: String): ChatMessageEntity?

  @Query("SELECT * FROM chat_messages WHERE chatJid = :chatJid AND timestamp <= :targetTs AND id != :excludeId ORDER BY timestamp DESC LIMIT :limit")
  suspend fun getMessagesBeforeTimestamp(chatJid: String, targetTs: Long, excludeId: String, limit: Int): List<ChatMessageEntity>

  @Query("SELECT * FROM chat_messages WHERE chatJid = :chatJid AND timestamp >= :targetTs AND id != :excludeId ORDER BY timestamp ASC LIMIT :limit")
  suspend fun getMessagesAfterTimestamp(chatJid: String, targetTs: Long, excludeId: String, limit: Int): List<ChatMessageEntity>

  @Query("SELECT mediaTranscription FROM chat_messages WHERE mediaSha256 = :sha AND mediaTranscription IS NOT NULL AND mediaTranscription != '' LIMIT 1")
  suspend fun findCachedTranscriptionBySha(sha: String): String?

  @Query("SELECT COUNT(*) FROM chat_messages")
  fun observeTotalCount(): Flow<Int>

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertIgnore(message: ChatMessageEntity): Long

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertAllIgnore(messages: List<ChatMessageEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(message: ChatMessageEntity)

  @Query("UPDATE chat_messages SET textContent = :newText, isEdited = 1 WHERE id = :id")
  suspend fun markEdited(id: String, newText: String)

  @Query("UPDATE chat_messages SET isRevoked = 1, textContent = '[Message deleted]' WHERE id = :id")
  suspend fun markRevoked(id: String)

  @Query("UPDATE chat_messages SET deliveryStatus = :status WHERE id = :id")
  suspend fun updateDeliveryStatus(id: String, status: String)

  @Query("UPDATE chat_messages SET reactionEmoji = :emoji WHERE id = :id")
  suspend fun updateReaction(id: String, emoji: String)

  @Query("UPDATE chat_messages SET mediaTranscription = :transcription, mediaSha256 = COALESCE(:sha256, mediaSha256) WHERE id = :id")
  suspend fun updateMediaTranscription(id: String, transcription: String, sha256: String? = null)

  @Query("UPDATE chat_messages SET mediaLocalPath = :localPath, mediaMimeType = COALESCE(:mimeType, mediaMimeType) WHERE id = :id")
  suspend fun updateMediaLocalPath(id: String, localPath: String, mimeType: String? = null)
}

@Dao
interface AssistantMessageDao {
  @Query("SELECT * FROM assistant_messages ORDER BY timestamp ASC")
  fun observeAll(): Flow<List<AssistantMessageEntity>>

  @Query("SELECT * FROM assistant_messages ORDER BY timestamp DESC LIMIT :limit")
  suspend fun getRecent(limit: Int): List<AssistantMessageEntity>

  @Query("SELECT * FROM assistant_messages WHERE id = :id LIMIT 1")
  suspend fun getById(id: String): AssistantMessageEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(message: AssistantMessageEntity)

  @Query("UPDATE assistant_messages SET reactionEmoji = :emoji WHERE id = :id")
  suspend fun updateReaction(id: String, emoji: String)

  @Query("DELETE FROM assistant_messages WHERE id = :id")
  suspend fun deleteById(id: String)

  @Query("DELETE FROM assistant_messages")
  suspend fun clearAll()
}

@Dao
interface ContactMemoryDao {
  @Query("SELECT * FROM contact_memories WHERE chatJid = :chatJid ORDER BY updatedAt DESC")
  fun observeForChat(chatJid: String): Flow<List<ContactMemoryEntity>>

  @Query("SELECT * FROM contact_memories ORDER BY updatedAt DESC")
  suspend fun getAll(): List<ContactMemoryEntity>

  @Query("SELECT * FROM contact_memories WHERE chatJid = :chatJid ORDER BY updatedAt DESC")
  suspend fun getForChat(chatJid: String): List<ContactMemoryEntity>

  @Query("SELECT * FROM contact_memories WHERE chatJid = :chatJid AND factKey = :factKey LIMIT 1")
  suspend fun findByKey(chatJid: String, factKey: String): ContactMemoryEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(memory: ContactMemoryEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertAll(memories: List<ContactMemoryEntity>)

  @Query("DELETE FROM contact_memories WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("DELETE FROM contact_memories WHERE chatJid = :chatJid AND factKey = :factKey")
  suspend fun deleteByKey(chatJid: String, factKey: String)

  @Query("DELETE FROM contact_memories WHERE chatJid = :chatJid")
  suspend fun clearForChat(chatJid: String)
}

@Dao
interface SignalKeyDao {
  @Query("SELECT * FROM signal_keys WHERE compositeKey = :compositeKey LIMIT 1")
  fun getByCompositeSync(compositeKey: String): SignalKeyEntity?

  @Query("SELECT * FROM signal_keys WHERE keyType = :keyType")
  fun getAllByTypeSync(keyType: String): List<SignalKeyEntity>

  @Query("SELECT COUNT(*) FROM signal_keys WHERE keyType = :keyType")
  fun countByType(keyType: String): Flow<Int>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun upsertSync(entity: SignalKeyEntity)

  @Query("DELETE FROM signal_keys WHERE compositeKey = :compositeKey")
  fun deleteByCompositeSync(compositeKey: String)

  @Query("DELETE FROM signal_keys")
  fun clearAllSync()
}

@Dao
interface AgentLogDao {
  @Query("SELECT * FROM agent_logs ORDER BY timestamp DESC, id DESC LIMIT 300")
  fun observeRecent(): Flow<List<AgentLogEntity>>

  @Insert
  suspend fun insert(log: AgentLogEntity)

  @Query("DELETE FROM agent_logs WHERE id NOT IN (SELECT id FROM agent_logs ORDER BY timestamp DESC, id DESC LIMIT 400)")
  suspend fun trimOld()

  @Query("DELETE FROM agent_logs")
  suspend fun clearAll()
}

@Dao
interface ScheduledMessageDao {
  @Query("SELECT * FROM scheduled_messages ORDER BY scheduledAtMs ASC")
  fun observeAll(): Flow<List<ScheduledMessageEntity>>

  @Query("SELECT * FROM scheduled_messages WHERE status = 'PENDING' AND scheduledAtMs <= :nowMs ORDER BY scheduledAtMs ASC")
  suspend fun getDue(nowMs: Long): List<ScheduledMessageEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(item: ScheduledMessageEntity)

  @Query("UPDATE scheduled_messages SET status = :status, sentAtMs = :sentAtMs WHERE id = :id")
  suspend fun updateStatus(id: String, status: String, sentAtMs: Long?)

  @Query("DELETE FROM scheduled_messages WHERE id = :id")
  suspend fun deleteById(id: String)
}

@Dao
interface MessageProcessingJobDao {
  @Query("SELECT * FROM processing_jobs WHERE incomingMessageId = :incomingMessageId LIMIT 1")
  suspend fun getByIncomingMessageId(incomingMessageId: String): MessageProcessingJobEntity?

  @Query("SELECT * FROM processing_jobs WHERE chatJid = :chatJid ORDER BY updatedAtMs DESC LIMIT 20")
  fun observeRecentForChat(chatJid: String): Flow<List<MessageProcessingJobEntity>>

  @Query(
    """
    SELECT * FROM processing_jobs
    WHERE stage IN ('DETECTED', 'PERSISTED', 'QUEUED', 'PARSED', 'CONTEXT_LOADED', 'AI_PROCESSING', 'VALIDATED', 'FAILED_RETRYABLE', 'UNKNOWN', 'SENDING')
    ORDER BY createdAtMs ASC LIMIT 40
    """
  )
  suspend fun getRecoverableJobs(): List<MessageProcessingJobEntity>

  @Query("SELECT EXISTS(SELECT 1 FROM processing_jobs WHERE chatJid = :chatJid AND replyHash = :replyHash AND replyHash != '' AND stage IN ('CONFIRMED', 'SENT_UNCONFIRMED', 'SENDING', 'UNKNOWN') AND updatedAtMs >= :sinceMs)")
  suspend fun hasRecentReplyWithHash(chatJid: String, replyHash: String, sinceMs: Long): Boolean

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertIgnore(job: MessageProcessingJobEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(job: MessageProcessingJobEntity)

  @Query(
    """
    UPDATE processing_jobs
    SET stage = :stage,
        attemptCount = :attemptCount,
        errorMessage = :errorMessage,
        outgoingMessageId = COALESCE(:outgoingMessageId, outgoingMessageId),
        replyHash = CASE WHEN :replyHash != '' THEN :replyHash ELSE replyHash END,
        sendCorrelationId = CASE WHEN :sendCorrelationId != '' THEN :sendCorrelationId ELSE sendCorrelationId END,
        nextRetryAtMs = :nextRetryAtMs,
        updatedAtMs = :updatedAtMs
    WHERE incomingMessageId = :incomingMessageId
    """
  )
  suspend fun updateStage(
    incomingMessageId: String,
    stage: String,
    attemptCount: Int,
    errorMessage: String = "",
    outgoingMessageId: String? = null,
    replyHash: String = "",
    sendCorrelationId: String = "",
    nextRetryAtMs: Long = 0L,
    updatedAtMs: Long = System.currentTimeMillis(),
  )
}

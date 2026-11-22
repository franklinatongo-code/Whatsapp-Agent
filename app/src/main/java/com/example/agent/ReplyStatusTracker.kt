package com.example.agent

import com.example.data.db.ChatMessageEntity

data class ReplyStatusIndex(
  val ownerReplyByIncomingId: Map<String, ChatMessageEntity>,
  val directQuotedTargetByMessageId: Map<String, ChatMessageEntity>,
) {
  fun isReplied(incomingMessageId: String): Boolean = ownerReplyByIncomingId.containsKey(incomingMessageId)

  fun ownerReplyFor(incomingMessageId: String): ChatMessageEntity? = ownerReplyByIncomingId[incomingMessageId]

  fun quotedTargetFor(messageId: String): ChatMessageEntity? = directQuotedTargetByMessageId[messageId]
}

/**
 * Tracks which specific incoming WhatsApp messages have already been replied to by "Me" (owner or AI)
 * versus which remain unanswered—distinguishing targeted swipe-replies from sequential replies.
 */
object ReplyStatusTracker {

  fun matchesMessageId(storedId: String, quotedId: String?): Boolean {
    val cleanQuoted = quotedId?.trim().orEmpty()
    if (cleanQuoted.isBlank()) return false
    return storedId == cleanQuoted || storedId.endsWith("_$cleanQuoted")
  }

  fun findQuotedTarget(
    messages: List<ChatMessageEntity>,
    quotedMessageId: String?,
  ): ChatMessageEntity? {
    val cleanQuoted = quotedMessageId?.trim().orEmpty()
    if (cleanQuoted.isBlank()) return null
    return messages.firstOrNull { it.id == cleanQuoted }
      ?: messages.firstOrNull { it.id.endsWith("_$cleanQuoted") }
  }

  fun analyze(chronologicalMessages: List<ChatMessageEntity>): ReplyStatusIndex {
    val valid = chronologicalMessages.filter { !it.isRevoked }
    val ownerReplyByIncomingId = linkedMapOf<String, ChatMessageEntity>()
    val directQuotedTargetByMessageId = linkedMapOf<String, ChatMessageEntity>()

    // Pass 1: resolve explicit swipe/quote references for every message in the window
    for (msg in valid) {
      val qid = msg.quotedMessageId?.trim()
      if (!qid.isNullOrBlank()) {
        val target = findQuotedTarget(valid, qid)
        if (target != null) {
          directQuotedTargetByMessageId[msg.id] = target
          if (msg.fromMe && !target.fromMe) {
            ownerReplyByIncomingId[target.id] = msg
          }
        }
      }
    }

    // Pass 2: sequential general (non-quoted) fromMe messages cover unquoted pending incoming messages before them,
    // WITHOUT falsely marking unaddressed messages when the owner specifically swipe-replied to a different message.
    val pendingIncoming = mutableListOf<ChatMessageEntity>()
    for (msg in valid) {
      if (!msg.fromMe) {
        if (!ownerReplyByIncomingId.containsKey(msg.id)) {
          pendingIncoming.add(msg)
        }
      } else {
        val explicitTarget = directQuotedTargetByMessageId[msg.id]
        if (explicitTarget != null) {
          pendingIncoming.removeAll { it.id == explicitTarget.id }
        } else if (msg.quotedMessageId.isNullOrBlank()) {
          for (inc in pendingIncoming) {
            ownerReplyByIncomingId.putIfAbsent(inc.id, msg)
          }
          pendingIncoming.clear()
        }
      }
    }

    return ReplyStatusIndex(
      ownerReplyByIncomingId = ownerReplyByIncomingId,
      directQuotedTargetByMessageId = directQuotedTargetByMessageId,
    )
  }
}

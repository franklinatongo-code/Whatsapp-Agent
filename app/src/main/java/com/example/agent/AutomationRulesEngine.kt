package com.example.agent

import com.example.util.optNonNullString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

enum class KeywordMatchMode(val label: String) {
  CONTAINS("Contains"),
  EXACT("Exact Match"),
  STARTS_WITH("Starts With"),
}

data class KeywordRule(
  val id: String,
  val keyword: String,
  val reply: String,
  val matchMode: KeywordMatchMode = KeywordMatchMode.CONTAINS,
  val enabled: Boolean = true,
)

data class NoteItem(
  val id: String,
  val title: String,
  val body: String,
  val updatedAtMs: Long = System.currentTimeMillis(),
)

data class TodoItem(
  val id: String,
  val text: String,
  val done: Boolean = false,
)

data class HabitItem(
  val id: String,
  val name: String,
  val streak: Int = 0,
  val lastCheckedDay: String = "",
)

/**
 * Pure Kotlin automation rules engine for WhatsApp auto-replies:
 * - Quiet Hours (Do Not Disturb, midnight-safe wraparound)
 * - Away Mode instant reply
 * - Keyword trigger matching (zero AI tokens used)
 * - Per-contact cooldown throttling
 * - Daily auto-reply quota guard
 */
class AutomationRulesEngine {

  private val lastReplyAtByChat = ConcurrentHashMap<String, Long>()
  private var currentDayKey: String = dayKeyNow()
  private var dailyReplyCount: Int = 0

  fun parseKeywordRules(json: String): List<KeywordRule> {
    if (json.isBlank()) return emptyList()
    return runCatching {
      val arr = JSONArray(json)
      buildList {
        for (i in 0 until arr.length()) {
          val o = arr.optJSONObject(i) ?: continue
          val kw = o.optNonNullString("keyword").trim()
          val rep = o.optNonNullString("reply").trim()
          if (kw.isNotEmpty() && rep.isNotEmpty()) {
            add(
              KeywordRule(
                id = o.optNonNullString("id").ifBlank { "rule_$i" },
                keyword = kw,
                reply = rep,
                matchMode = KeywordMatchMode.entries.firstOrNull { it.name == o.optNonNullString("mode") }
                  ?: KeywordMatchMode.CONTAINS,
                enabled = o.optBoolean("enabled", true),
              )
            )
          }
        }
      }
    }.getOrDefault(emptyList())
  }

  fun serializeKeywordRules(rules: List<KeywordRule>): String {
    val arr = JSONArray()
    rules.forEach { r ->
      arr.put(
        JSONObject()
          .put("id", r.id)
          .put("keyword", r.keyword)
          .put("reply", r.reply)
          .put("mode", r.matchMode.name)
          .put("enabled", r.enabled)
      )
    }
    return arr.toString()
  }

  fun parseStringList(json: String): List<String> {
    if (json.isBlank()) return emptyList()
    return runCatching {
      val arr = JSONArray(json)
      buildList {
        for (i in 0 until arr.length()) {
          val v = arr.optNonNullString(i).trim()
          if (v.isNotEmpty()) add(v)
        }
      }
    }.getOrDefault(emptyList())
  }

  fun serializeStringList(items: List<String>): String {
    val arr = JSONArray()
    items.forEach { if (it.isNotBlank()) arr.put(it.trim()) }
    return arr.toString()
  }

  fun matchKeywordRule(incomingText: String, rulesJson: String): KeywordRule? {
    val trimmed = incomingText.trim()
    if (trimmed.isBlank()) return null
    val rules = parseKeywordRules(rulesJson)
    return rules.firstOrNull { rule ->
      if (!rule.enabled) return@firstOrNull false
      val kw = rule.keyword.trim()
      when (rule.matchMode) {
        KeywordMatchMode.EXACT -> trimmed.equals(kw, ignoreCase = true)
        KeywordMatchMode.STARTS_WITH -> trimmed.startsWith(kw, ignoreCase = true)
        KeywordMatchMode.CONTAINS -> trimmed.contains(kw, ignoreCase = true)
      }
    }
  }

  fun isWithinQuietHours(currentHour: Int, startHour: Int, endHour: Int): Boolean {
    val h = currentHour.coerceIn(0, 23)
    val s = startHour.coerceIn(0, 23)
    val e = endHour.coerceIn(0, 23)
    return when {
      s == e -> false
      s < e -> h in s until e
      else -> h >= s || h < e // spans midnight, e.g. 23 -> 7
    }
  }

  fun isCurrentTimeQuiet(startHour: Int, endHour: Int): Boolean {
    val cal = Calendar.getInstance()
    return isWithinQuietHours(cal.get(Calendar.HOUR_OF_DAY), startHour, endHour)
  }

  fun isCooldownActive(chatJid: String, cooldownSeconds: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
    if (cooldownSeconds <= 0) return false
    val last = lastReplyAtByChat[chatJid] ?: return false
    return (nowMs - last) < (cooldownSeconds * 1000L)
  }

  @Synchronized
  fun isDailyQuotaExceeded(dailyLimit: Int, dayKey: String = dayKeyNow()): Boolean {
    if (dailyLimit <= 0) return false
    if (dayKey != currentDayKey) {
      currentDayKey = dayKey
      dailyReplyCount = 0
    }
    return dailyReplyCount >= dailyLimit
  }

  @Synchronized
  fun recordReplySent(chatJid: String, nowMs: Long = System.currentTimeMillis(), dayKey: String = dayKeyNow()) {
    lastReplyAtByChat[chatJid] = nowMs
    if (dayKey != currentDayKey) {
      currentDayKey = dayKey
      dailyReplyCount = 0
    }
    dailyReplyCount++
  }

  @Synchronized
  fun getTodayReplyCount(dayKey: String = dayKeyNow()): Int {
    if (dayKey != currentDayKey) {
      currentDayKey = dayKey
      dailyReplyCount = 0
    }
    return dailyReplyCount
  }

  companion object {
    fun dayKeyNow(): String {
      val cal = Calendar.getInstance()
      return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
    }

    fun parseNotes(json: String): List<NoteItem> =
      runCatching {
        val arr = JSONArray(json)
        buildList {
          for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
              NoteItem(
                id = o.optNonNullString("id", "n_$i"),
                title = o.optNonNullString("title"),
                body = o.optNonNullString("body"),
                updatedAtMs = o.optLong("updatedAt", System.currentTimeMillis()),
              )
            )
          }
        }
      }.getOrDefault(emptyList())

    fun serializeNotes(list: List<NoteItem>): String {
      val arr = JSONArray()
      list.forEach {
        arr.put(
          JSONObject()
            .put("id", it.id)
            .put("title", it.title)
            .put("body", it.body)
            .put("updatedAt", it.updatedAtMs)
        )
      }
      return arr.toString()
    }

    fun parseTodos(json: String): List<TodoItem> =
      runCatching {
        val arr = JSONArray(json)
        buildList {
          for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
              TodoItem(
                id = o.optNonNullString("id", "t_$i"),
                text = o.optNonNullString("text"),
                done = o.optBoolean("done", false),
              )
            )
          }
        }
      }.getOrDefault(emptyList())

    fun serializeTodos(list: List<TodoItem>): String {
      val arr = JSONArray()
      list.forEach {
        arr.put(JSONObject().put("id", it.id).put("text", it.text).put("done", it.done))
      }
      return arr.toString()
    }

    fun parseHabits(json: String): List<HabitItem> =
      runCatching {
        val arr = JSONArray(json)
        buildList {
          for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
              HabitItem(
                id = o.optNonNullString("id", "h_$i"),
                name = o.optNonNullString("name"),
                streak = o.optInt("streak", 0),
                lastCheckedDay = o.optNonNullString("lastCheckedDay", ""),
              )
            )
          }
        }
      }.getOrDefault(emptyList())

    fun serializeHabits(list: List<HabitItem>): String {
      val arr = JSONArray()
      list.forEach {
        arr.put(
          JSONObject()
            .put("id", it.id)
            .put("name", it.name)
            .put("streak", it.streak)
            .put("lastCheckedDay", it.lastCheckedDay)
        )
      }
      return arr.toString()
    }
  }
}

package com.example.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * Android's [JSONObject.optString] returns the literal string `"null"` when a key is present
 * with JSON `null` (`JSONObject.NULL`). This helper safely treats JSON `null` as absent.
 */
fun JSONObject.optNonNullString(key: String, fallback: String = ""): String {
  if (!has(key) || isNull(key)) return fallback
  val raw = optString(key, fallback)
  return if (raw.equals("null", ignoreCase = true) && opt(key) === JSONObject.NULL) fallback else raw
}

fun JSONObject.optNullableString(key: String): String? {
  if (!has(key) || isNull(key)) return null
  val raw = optString(key, "")
  if (raw.equals("null", ignoreCase = true) && opt(key) === JSONObject.NULL) return null
  return raw.ifBlank { null }
}

fun JSONArray.optNonNullString(index: Int, fallback: String = ""): String {
  if (index < 0 || index >= length() || isNull(index)) return fallback
  val raw = optString(index, fallback)
  return if (raw.equals("null", ignoreCase = true) && opt(index) === JSONObject.NULL) fallback else raw
}

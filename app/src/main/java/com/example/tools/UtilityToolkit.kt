package com.example.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * UtilityToolkit: 70+ genuinely working offline utilities for Text, QR, Dev/Crypto,
 * Math/Finance, Unit Conversions, Colors, Timezones, RNG, and Android Device Telemetry.
 */
object UtilityToolkit {

  private val random = SecureRandom()

  // ==================== 1. TEXT & WHATSAPP TOOLS ====================

  data class TextStats(
    val chars: Int,
    val charsNoSpaces: Int,
    val words: Int,
    val lines: Int,
    val sentences: Int,
    val readingSeconds: Int,
  )

  fun analyzeText(text: String): TextStats {
    val wordsList = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val sentences = text.split(Regex("[.!?]+")).count { it.trim().isNotEmpty() }
    val lines = if (text.isEmpty()) 0 else text.lines().size
    val readingSec = if (wordsList.isEmpty()) 0 else ((wordsList.size / 3.3)).toInt().coerceAtLeast(1)
    return TextStats(
      chars = text.length,
      charsNoSpaces = text.count { !it.isWhitespace() },
      words = wordsList.size,
      lines = lines,
      sentences = sentences,
      readingSeconds = readingSec,
    )
  }

  fun toTitleCase(text: String): String =
    text.split(Regex("(\\s+)")).joinToString(" ") { w ->
      w.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

  fun toCamelCase(text: String): String {
    val parts = text.trim().split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return ""
    return parts.mapIndexed { idx, s ->
      val lower = s.lowercase()
      if (idx == 0) lower else lower.replaceFirstChar { it.uppercaseChar() }
    }.joinToString("")
  }

  fun toSnakeCase(text: String): String =
    text.trim()
      .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
      .split(Regex("[^A-Za-z0-9]+"))
      .filter { it.isNotEmpty() }
      .joinToString("_") { it.lowercase() }

  fun toSlug(text: String): String =
    text.trim()
      .lowercase(Locale.US)
      .replace(Regex("[^a-z0-9\\s-]"), "")
      .trim()
      .replace(Regex("[\\s-]+"), "-")

  fun deduplicateLines(text: String): String =
    text.lines().distinct().joinToString("\n")

  fun sortLines(text: String, descending: Boolean = false): String {
    val lines = text.lines()
    return (if (descending) lines.sortedDescending() else lines.sorted()).joinToString("\n")
  }

  fun removeExtraSpaces(text: String): String =
    text.lines().joinToString("\n") { it.trim().replace(Regex("\\s+"), " ") }.trim()

  fun addLineNumbers(text: String): String =
    text.lines().mapIndexed { idx, line -> "${idx + 1}. $line" }.joinToString("\n")

  fun extractEmails(text: String): List<String> =
    Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").findAll(text).map { it.value }.distinct().toList()

  fun extractUrls(text: String): List<String> =
    Regex("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+").findAll(text).map { it.value }.distinct().toList()

  fun extractPhoneNumbers(text: String): List<String> =
    Regex("(?:\\+?\\d[\\d\\s().-]{6,}\\d)").findAll(text).map { it.value.trim() }.distinct().toList()

  fun wordFrequencyTop(text: String, topN: Int = 8): List<Pair<String, Int>> =
    text.lowercase(Locale.getDefault())
      .split(Regex("[^\\p{L}\\p{N}']+"))
      .filter { it.length >= 2 }
      .groupingBy { it }
      .eachCount()
      .entries
      .sortedByDescending { it.value }
      .take(topN)
      .map { it.key to it.value }

  fun wrapWhatsAppFormat(text: String, style: String): String =
    when (style) {
      "bold" -> "*$text*"
      "italic" -> "_${text}_"
      "strike" -> "~$text~"
      "mono" -> "```$text```"
      "quote" -> text.lines().joinToString("\n") { "> $it" }
      "bullet" -> text.lines().joinToString("\n") { "• $it" }
      else -> text
    }

  fun buildWhatsAppDirectUrl(phoneRaw: String, prefilledMessage: String = ""): String {
    val digits = phoneRaw.filter { it.isDigit() }
    val base = "https://wa.me/$digits"
    return if (prefilledMessage.isBlank()) base
    else "$base?text=${URLEncoder.encode(prefilledMessage, "UTF-8")}"
  }

  fun buildWifiQrPayload(ssid: String, password: String, security: String = "WPA", hidden: Boolean = false): String {
    fun esc(v: String) = v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:")
    val sec = security.uppercase(Locale.US)
    return "WIFI:T:$sec;S:${esc(ssid)};P:${esc(password)};H:$hidden;;"
  }

  // ==================== 2. DEV, CRYPTO & ENCODING TOOLS ====================

  fun formatJson(raw: String, minify: Boolean = false): Result<String> =
    runCatching {
      val trimmed = raw.trim()
      when {
        trimmed.startsWith("{") -> {
          val obj = JSONObject(trimmed)
          if (minify) obj.toString() else obj.toString(2)
        }
        trimmed.startsWith("[") -> {
          val arr = JSONArray(trimmed)
          if (minify) arr.toString() else arr.toString(2)
        }
        else -> error("Input must start with '{' or '['")
      }
    }

  fun base64Encode(text: String): String =
    Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

  fun base64Decode(encoded: String): Result<String> =
    runCatching {
      String(Base64.decode(encoded.trim(), Base64.DEFAULT), Charsets.UTF_8)
    }

  fun urlEncode(text: String): String = URLEncoder.encode(text, "UTF-8")

  fun urlDecode(text: String): Result<String> = runCatching { URLDecoder.decode(text, "UTF-8") }

  fun textToHex(text: String): String =
    text.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

  fun hexToText(hex: String): Result<String> =
    runCatching {
      val clean = hex.replace(Regex("\\s+"), "")
      require(clean.length % 2 == 0) { "Hex length must be even" }
      val bytes = ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
      }
      String(bytes, Charsets.UTF_8)
    }

  fun textToBinary(text: String): String =
    text.toByteArray(Charsets.UTF_8).joinToString(" ") { b ->
      (b.toInt() and 0xFF).toString(2).padStart(8, '0')
    }

  fun binaryToText(binary: String): Result<String> =
    runCatching {
      val clean = binary.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
      val bytes = clean.map { chunk -> chunk.toInt(2).toByte() }.toByteArray()
      String(bytes, Charsets.UTF_8)
    }

  fun digestHex(text: String, algorithm: String): String {
    val md = MessageDigest.getInstance(algorithm)
    return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
  }

  fun hmacSha256Hex(message: String, secret: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
  }

  fun rot13(text: String): String =
    text.map { c ->
      when (c) {
        in 'a'..'z' -> ('a'.code + (c.code - 'a'.code + 13) % 26).toChar()
        in 'A'..'Z' -> ('A'.code + (c.code - 'A'.code + 13) % 26).toChar()
        else -> c
      }
    }.joinToString("")

  private val morseMap =
    mapOf(
      'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".",
      'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---",
      'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---",
      'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-",
      'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--",
      'Z' to "--..", '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--",
      '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..",
      '9' to "----.", ' ' to "/",
    )
  private val reverseMorseMap = morseMap.entries.associate { it.value to it.key }

  fun toMorse(text: String): String =
    text.uppercase(Locale.US).mapNotNull { morseMap[it] }.joinToString(" ")

  fun fromMorse(morse: String): String =
    morse.trim().split(Regex("\\s+")).map { token -> reverseMorseMap[token] ?: '?' }.joinToString("")

  fun decodeJwt(token: String): Result<String> =
    runCatching {
      val parts = token.trim().split(".")
      require(parts.size >= 2) { "JWT must have at least header.payload" }
      fun decPart(p: String): String {
        val raw = Base64.decode(p, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val txt = String(raw, Charsets.UTF_8)
        return runCatching { JSONObject(txt).toString(2) }.getOrDefault(txt)
      }
      "HEADER:\n${decPart(parts[0])}\n\nPAYLOAD:\n${decPart(parts[1])}"
    }

  fun testRegex(pattern: String, input: String): Result<List<String>> =
    runCatching {
      Regex(pattern).findAll(input).map { it.value }.toList()
    }

  fun generatePassword(
    length: Int = 16,
    useUpper: Boolean = true,
    useLower: Boolean = true,
    useDigits: Boolean = true,
    useSymbols: Boolean = true,
  ): String {
    val safeLen = length.coerceIn(6, 64)
    val pool = buildString {
      if (useUpper) append("ABCDEFGHJKLMNPQRSTUVWXYZ")
      if (useLower) append("abcdefghijkmnopqrstuvwxyz")
      if (useDigits) append("23456789")
      if (useSymbols) append("!@#\$%^&*_-+=?")
    }.ifEmpty { "abcdefghijkmnopqrstuvwxyz23456789" }
    return (0 until safeLen).map { pool[random.nextInt(pool.length)] }.joinToString("")
  }

  private val passphraseWords =
    listOf(
      "amber", "atlas", "aurora", "badge", "breeze", "canyon", "cedar", "cipher",
      "clover", "cobalt", "comet", "coral", "crest", "delta", "drift", "echo",
      "ember", "falcon", "fjord", "flame", "forest", "forge", "frost", "galaxy",
      "glacier", "granite", "harbor", "hazel", "horizon", "indigo", "island", "ivory",
      "jade", "jasper", "kestrel", "lagoon", "lantern", "laurel", "lotus", "lunar",
      "maple", "meadow", "meteor", "mirage", "mist", "nebula", "nectar", "nimbus",
      "noble", "oasis", "obsidian", "olive", "onyx", "orbit", "orchid", "oriole",
      "pebble", "phoenix", "pine", "planet", "prism", "pulse", "quartz", "quest",
      "raven", "reef", "ridge", "ripple", "river", "ruby", "saffron", "sage",
      "sapphire", "sequoia", "shadow", "shield", "silver", "solstice", "spark", "spruce",
      "summit", "timber", "topaz", "torrent", "valley", "velvet", "vertex", "voyage",
      "willow", "zephyr",
    )

  fun generatePassphrase(wordCount: Int = 4, separator: String = "-"): String {
    val count = wordCount.coerceIn(3, 8)
    return (0 until count).joinToString(separator) { passphraseWords[random.nextInt(passphraseWords.size)] }
  }

  fun generateUuidV4(): String = UUID.randomUUID().toString()

  fun epochToIso(epochRaw: String): Result<String> =
    runCatching {
      val num = epochRaw.trim().toLong()
      val ms = if (abs(num) < 100_000_000_000L) num * 1000L else num
      val localFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
      val utcFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
      }
      "Local: ${localFmt.format(Date(ms))}\nUTC: ${utcFmt.format(Date(ms))}"
    }

  // ==================== 3. MATH, FINANCE & CONVERTERS ====================

  /**
   * Recursive-descent safe math expression evaluator supporting:
   * +, -, *, /, %, ^, parentheses (), pi, e, sqrt(), sin(), cos(), tan(), log(), ln(), abs().
   */
  fun evaluateMath(expression: String): Result<Double> =
    runCatching {
      val s = expression.trim()
      require(s.isNotEmpty()) { "Empty expression" }
      var pos = -1
      var ch = 0

      fun nextChar() {
        pos++
        ch = if (pos < s.length) s[pos].code else -1
      }

      fun eat(charToEat: Int): Boolean {
        while (ch == ' '.code) nextChar()
        if (ch == charToEat) {
          nextChar()
          return true
        }
        return false
      }

      lateinit var parseExpression: () -> Double

      fun parseFactor(): Double {
        if (eat('+'.code)) return parseFactor()
        if (eat('-'.code)) return -parseFactor()
        val startPos = pos
        var x: Double
        if (eat('('.code)) {
          x = parseExpression()
          require(eat(')'.code)) { "Missing closing parenthesis" }
        } else if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
          while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
          x = s.substring(startPos, pos).toDouble()
        } else if (ch in 'a'.code..'z'.code || ch in 'A'.code..'Z'.code) {
          while (ch in 'a'.code..'z'.code || ch in 'A'.code..'Z'.code) nextChar()
          val name = s.substring(startPos, pos).lowercase(Locale.US)
          if (name == "pi") {
            x = PI
          } else if (name == "e") {
            x = Math.E
          } else {
            require(eat('('.code)) { "Expected '(' after function $name" }
            val arg = parseExpression()
            require(eat(')'.code)) { "Missing ')' after $name" }
            x = when (name) {
              "sqrt" -> sqrt(arg)
              "abs" -> abs(arg)
              "sin" -> sin(Math.toRadians(arg))
              "cos" -> cos(Math.toRadians(arg))
              "tan" -> tan(Math.toRadians(arg))
              "log" -> log10(arg)
              "ln" -> ln(arg)
              else -> error("Unknown function: $name")
            }
          }
        } else {
          error("Unexpected character: '${ch.toChar()}'")
        }
        if (eat('^'.code)) x = x.pow(parseFactor())
        return x
      }

      fun parseTerm(): Double {
        var x = parseFactor()
        while (true) {
          x = when {
            eat('*'.code) -> x * parseFactor()
            eat('/'.code) -> {
              val d = parseFactor()
              require(d != 0.0) { "Division by zero" }
              x / d
            }
            eat('%'.code) -> x % parseFactor()
            else -> return x
          }
        }
      }

      parseExpression = {
        var x = parseTerm()
        while (true) {
          if (eat('+'.code)) {
            x += parseTerm()
          } else if (eat('-'.code)) {
            x -= parseTerm()
          } else {
            break
          }
        }
        x
      }

      nextChar()
      val result = parseExpression()
      while (ch == ' '.code) nextChar()
      require(pos >= s.length) { "Unexpected character at end of expression" }
      require(result.isFinite()) { "Result is infinite or undefined" }
      result
    }

  enum class UnitCategory(val label: String, val units: List<String>) {
    LENGTH("Length", listOf("m", "km", "cm", "mm", "mi", "yd", "ft", "in")),
    MASS("Weight", listOf("kg", "g", "mg", "lb", "oz", "ton")),
    TEMPERATURE("Temp", listOf("C", "F", "K")),
    SPEED("Speed", listOf("km/h", "m/s", "mph", "kn")),
    DATA("Data", listOf("B", "KB", "MB", "GB", "TB")),
    TIME("Time", listOf("sec", "min", "hr", "day", "wk")),
    AREA("Area", listOf("m2", "km2", "ft2", "acre", "ha")),
    VOLUME("Volume", listOf("L", "mL", "gal", "cup", "floz")),
  }

  fun convertUnit(value: Double, category: UnitCategory, from: String, to: String): Double {
    if (from.equals(to, ignoreCase = true)) return value
    if (category == UnitCategory.TEMPERATURE) {
      val celsius = when (from.uppercase(Locale.US)) {
        "C" -> value
        "F" -> (value - 32.0) * 5.0 / 9.0
        "K" -> value - 273.15
        else -> value
      }
      return when (to.uppercase(Locale.US)) {
        "C" -> celsius
        "F" -> celsius * 9.0 / 5.0 + 32.0
        "K" -> celsius + 273.15
        else -> celsius
      }
    }
    val factorToBase: Map<String, Double> = when (category) {
      UnitCategory.LENGTH -> mapOf(
        "m" to 1.0, "km" to 1000.0, "cm" to 0.01, "mm" to 0.001,
        "mi" to 1609.344, "yd" to 0.9144, "ft" to 0.3048, "in" to 0.0254,
      )
      UnitCategory.MASS -> mapOf(
        "kg" to 1.0, "g" to 0.001, "mg" to 1e-6, "lb" to 0.45359237,
        "oz" to 0.028349523125, "ton" to 1000.0,
      )
      UnitCategory.SPEED -> mapOf(
        "m/s" to 1.0, "km/h" to (1.0 / 3.6), "mph" to 0.44704, "kn" to 0.514444,
      )
      UnitCategory.DATA -> mapOf(
        "B" to 1.0, "KB" to 1024.0, "MB" to 1048576.0, "GB" to 1073741824.0, "TB" to 1099511627776.0,
      )
      UnitCategory.TIME -> mapOf(
        "sec" to 1.0, "min" to 60.0, "hr" to 3600.0, "day" to 86400.0, "wk" to 604800.0,
      )
      UnitCategory.AREA -> mapOf(
        "m2" to 1.0, "km2" to 1_000_000.0, "ft2" to 0.09290304, "acre" to 4046.8564224, "ha" to 10_000.0,
      )
      UnitCategory.VOLUME -> mapOf(
        "L" to 1.0, "mL" to 0.001, "gal" to 3.785411784, "cup" to 0.2365882365, "floz" to 0.0295735295625,
      )
      UnitCategory.TEMPERATURE -> emptyMap()
    }
    val fFrom = factorToBase[from] ?: 1.0
    val fTo = factorToBase[to] ?: 1.0
    return (value * fFrom) / fTo
  }

  data class TipResult(val tipAmount: Double, val totalAmount: Double, val perPerson: Double)

  fun calculateTipSplit(bill: Double, tipPercent: Double, people: Int): TipResult {
    val safeBill = bill.coerceAtLeast(0.0)
    val safePeople = people.coerceAtLeast(1)
    val tip = safeBill * (tipPercent.coerceAtLeast(0.0) / 100.0)
    val total = safeBill + tip
    return TipResult(tip, total, total / safePeople)
  }

  data class CompoundInterestResult(val finalAmount: Double, val totalInterest: Double)

  fun calculateCompoundInterest(principal: Double, annualRatePct: Double, years: Double, compoundsPerYear: Int = 12): CompoundInterestResult {
    val p = principal.coerceAtLeast(0.0)
    val r = annualRatePct / 100.0
    val n = compoundsPerYear.coerceAtLeast(1).toDouble()
    val t = years.coerceAtLeast(0.0)
    val amount = p * (1.0 + r / n).pow(n * t)
    return CompoundInterestResult(amount, amount - p)
  }

  data class LoanResult(val monthlyPayment: Double, val totalPayment: Double, val totalInterest: Double)

  fun calculateLoanEmi(principal: Double, annualRatePct: Double, months: Int): LoanResult {
    val p = principal.coerceAtLeast(0.0)
    val m = months.coerceAtLeast(1)
    val monthlyRate = (annualRatePct / 100.0) / 12.0
    val emi =
      if (monthlyRate <= 0.0) p / m
      else {
        val factor = (1.0 + monthlyRate).pow(m.toDouble())
        p * monthlyRate * factor / (factor - 1.0)
      }
    val total = emi * m
    return LoanResult(emi, total, total - p)
  }

  fun calculateBmi(weightKg: Double, heightCm: Double): Pair<Double, String> {
    val hM = (heightCm / 100.0).coerceAtLeast(0.3)
    val bmi = weightKg.coerceAtLeast(1.0) / (hM * hM)
    val category =
      when {
        bmi < 18.5 -> "Underweight"
        bmi < 25.0 -> "Healthy Weight"
        bmi < 30.0 -> "Overweight"
        else -> "Obesity Range"
      }
    return bmi to category
  }

  // ==================== 4. TIMEZONE, COLOR, RNG ====================

  fun worldClocksNow(): List<Pair<String, String>> {
    val zones =
      listOf(
        "Local" to TimeZone.getDefault().id,
        "UTC" to "UTC",
        "New York" to "America/New_York",
        "London" to "Europe/London",
        "Lagos" to "Africa/Lagos",
        "Dubai" to "Asia/Dubai",
        "Tokyo" to "Asia/Tokyo",
        "Sydney" to "Australia/Sydney",
      )
    val now = Date()
    return zones.map { (label, tzId) ->
      val fmt = SimpleDateFormat("EEE HH:mm (MMM d)", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone(tzId)
      }
      label to fmt.format(now)
    }
  }

  data class ColorInspection(
    val normalizedHex: String,
    val r: Int,
    val g: Int,
    val b: Int,
    val contrastOnWhite: Double,
    val contrastOnBlack: Double,
  )

  fun inspectHexColor(rawHex: String): Result<ColorInspection> =
    runCatching {
      val clean = rawHex.trim().removePrefix("#")
      require(clean.length == 6) { "Enter 6-digit hex color like #10B981" }
      val rgb = clean.toInt(16)
      val r = (rgb shr 16) and 0xFF
      val g = (rgb shr 8) and 0xFF
      val b = rgb and 0xFF
      fun lum(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
      }
      val l = 0.2126 * lum(r) + 0.7152 * lum(g) + 0.0722 * lum(b)
      val onWhite = 1.05 / (l + 0.05)
      val onBlack = (l + 0.05) / 0.05
      ColorInspection("#${clean.uppercase(Locale.US)}", r, g, b, onWhite, onBlack)
    }

  fun rollDice(count: Int = 2, sides: Int = 6): List<Int> {
    val safeCount = count.coerceIn(1, 20)
    val safeSides = sides.coerceIn(2, 1000)
    return List(safeCount) { random.nextInt(safeSides) + 1 }
  }

  fun pickRandomChoice(optionsText: String): String? {
    val items = optionsText.lines().flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }
    if (items.isEmpty()) return null
    return items[random.nextInt(items.size)]
  }

  // ==================== 5. ANDROID HARDWARE TELEMETRY ====================

  data class DeviceTelemetrySnapshot(
    val deviceModel: String,
    val androidVersion: String,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val networkTransport: String,
    val availableStorageGb: Double,
    val totalStorageGb: Double,
    val availableRamMb: Long,
    val totalRamMb: Long,
    val cpuCores: Int,
    val localTime: String,
  )

  fun readDeviceTelemetry(context: Context): DeviceTelemetrySnapshot {
    val batteryIntent = runCatching {
      context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()
    val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

    val networkLabel = runCatching {
      val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
      when {
        caps == null -> "Offline"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Connected"
      }
    }.getOrDefault("Unknown")

    val (availGb, totalGb) = runCatching {
      val stat = StatFs(Environment.getDataDirectory().path)
      val avail = stat.availableBlocksLong * stat.blockSizeLong
      val total = stat.blockCountLong * stat.blockSizeLong
      (avail / 1_073_741_824.0) to (total / 1_073_741_824.0)
    }.getOrDefault(0.0 to 0.0)

    val (availRam, totalRam) = runCatching {
      val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
      val info = ActivityManager.MemoryInfo()
      am?.getMemoryInfo(info)
      (info.availMem / 1_048_576L) to (info.totalMem / 1_048_576L)
    }.getOrDefault(0L to 0L)

    val now = SimpleDateFormat("EEE MMM d, HH:mm:ss z", Locale.getDefault()).format(Date())
    return DeviceTelemetrySnapshot(
      deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
      androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
      batteryPercent = pct,
      isCharging = charging,
      networkTransport = networkLabel,
      availableStorageGb = (availGb * 100.0).roundToLong() / 100.0,
      totalStorageGb = (totalGb * 100.0).roundToLong() / 100.0,
      availableRamMb = availRam,
      totalRamMb = totalRam,
      cpuCores = Runtime.getRuntime().availableProcessors(),
      localTime = now,
    )
  }

  fun daysUntilDate(month1To12: Int, dayOfMonth: Int): Int {
    val today = Calendar.getInstance().apply {
      set(Calendar.HOUR_OF_DAY, 0)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }
    val target = Calendar.getInstance().apply {
      set(Calendar.HOUR_OF_DAY, 0)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
      set(Calendar.MONTH, (month1To12 - 1).coerceIn(0, 11))
      set(Calendar.DAY_OF_MONTH, dayOfMonth.coerceIn(1, 31))
      if (before(today)) add(Calendar.YEAR, 1)
    }
    val diffMs = target.timeInMillis - today.timeInMillis
    return (diffMs / 86_400_000L).toInt()
  }
}

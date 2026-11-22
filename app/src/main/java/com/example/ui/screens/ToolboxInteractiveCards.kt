package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.tools.UtilityToolkit
import com.example.whatsapp.QrBitmapGenerator
import java.util.Locale

private fun copyToClipboard(context: Context, label: String, text: String) {
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  cm.setPrimaryClip(ClipData.newPlainText(label, text))
  Toast.makeText(context, "Copied $label", Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QrStudioCard() {
  val context = LocalContext.current
  var qrMode by remember { mutableIntStateOf(0) } // 0=Text/URL, 1=WhatsApp Chat Link, 2=Wi-Fi QR
  var textPayload by remember { mutableStateOf("https://wa.me/") }
  var waPhone by remember { mutableStateOf("") }
  var waMsg by remember { mutableStateOf("") }
  var wifiSsid by remember { mutableStateOf("") }
  var wifiPass by remember { mutableStateOf("") }
  var wifiSec by remember { mutableStateOf("WPA") }

  val effectivePayload =
    remember(qrMode, textPayload, waPhone, waMsg, wifiSsid, wifiPass, wifiSec) {
      when (qrMode) {
        1 -> if (waPhone.any { it.isDigit() }) UtilityToolkit.buildWhatsAppDirectUrl(waPhone, waMsg) else ""
        2 -> if (wifiSsid.isNotBlank()) UtilityToolkit.buildWifiQrPayload(wifiSsid, wifiPass, wifiSec) else ""
        else -> textPayload
      }
    }
  val bitmap = remember(effectivePayload) {
    if (effectivePayload.isNotBlank()) QrBitmapGenerator.generateQrBitmap(effectivePayload, 420) else null
  }

  ToolSectionCard("QR & WhatsApp Link Maker", Icons.Rounded.QrCode2) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilterChip(selected = qrMode == 0, onClick = { qrMode = 0 }, label = { Text("Text / URL") })
      FilterChip(selected = qrMode == 1, onClick = { qrMode = 1 }, label = { Text("wa.me Chat Link") })
      FilterChip(selected = qrMode == 2, onClick = { qrMode = 2 }, label = { Text("Wi-Fi Share QR") })
    }
    when (qrMode) {
      0 -> {
        OutlinedTextField(
          value = textPayload,
          onValueChange = { textPayload = it },
          label = { Text("Any text, phone, or link") },
          modifier = Modifier.fillMaxWidth(),
        )
      }
      1 -> {
        OutlinedTextField(
          value = waPhone,
          onValueChange = { waPhone = it },
          label = { Text("WhatsApp Phone (country code + number)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = waMsg,
          onValueChange = { waMsg = it },
          label = { Text("Pre-filled message (optional)") },
          modifier = Modifier.fillMaxWidth(),
        )
      }
      2 -> {
        OutlinedTextField(
          value = wifiSsid,
          onValueChange = { wifiSsid = it },
          label = { Text("Wi-Fi Network Name (SSID)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = wifiPass,
          onValueChange = { wifiPass = it },
          label = { Text("Wi-Fi Password") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf("WPA", "WEP", "nopass").forEach { sec ->
            FilterChip(selected = wifiSec == sec, onClick = { wifiSec = sec }, label = { Text(sec) })
          }
        }
      }
    }
    if (effectivePayload.isNotBlank()) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = effectivePayload,
          style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { copyToClipboard(context, "QR Link", effectivePayload) }) {
          Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy link")
        }
      }
    }
    if (bitmap != null) {
      Box(
        modifier =
          Modifier.size(200.dp)
            .align(Alignment.CenterHorizontally)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(10.dp),
      ) {
        Image(
          bitmap = bitmap.asImageBitmap(),
          contentDescription = stringResource(R.string.cd_custom_qr),
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextToolsCard() {
  val context = LocalContext.current
  var input by remember { mutableStateOf("") }
  var output by remember { mutableStateOf("") }
  val stats = remember(input) { UtilityToolkit.analyzeText(input) }

  ToolSectionCard("Text Workbench & WhatsApp Formatter", Icons.Rounded.TextFields) {
    OutlinedTextField(
      value = input,
      onValueChange = { input = it },
      label = { Text("Paste or type text") },
      minLines = 3,
      modifier = Modifier.fillMaxWidth(),
    )
    Text(
      text = "${stats.words} words • ${stats.chars} chars • ${stats.lines} lines • ~${stats.readingSeconds}s read",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.primary,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      AssistChip(onClick = { output = UtilityToolkit.wrapWhatsAppFormat(input, "bold") }, label = { Text("*Bold*") })
      AssistChip(onClick = { output = UtilityToolkit.wrapWhatsAppFormat(input, "italic") }, label = { Text("_Italic_") })
      AssistChip(onClick = { output = UtilityToolkit.wrapWhatsAppFormat(input, "strike") }, label = { Text("~Strike~") })
      AssistChip(onClick = { output = UtilityToolkit.wrapWhatsAppFormat(input, "mono") }, label = { Text("```Mono```") })
      AssistChip(onClick = { output = UtilityToolkit.wrapWhatsAppFormat(input, "bullet") }, label = { Text("• Bullets") })
      AssistChip(onClick = { output = input.uppercase(Locale.getDefault()) }, label = { Text("UPPER") })
      AssistChip(onClick = { output = input.lowercase(Locale.getDefault()) }, label = { Text("lower") })
      AssistChip(onClick = { output = UtilityToolkit.toTitleCase(input) }, label = { Text("Title Case") })
      AssistChip(onClick = { output = UtilityToolkit.toCamelCase(input) }, label = { Text("camelCase") })
      AssistChip(onClick = { output = UtilityToolkit.toSnakeCase(input) }, label = { Text("snake_case") })
      AssistChip(onClick = { output = UtilityToolkit.toSlug(input) }, label = { Text("url-slug") })
      AssistChip(onClick = { output = input.reversed() }, label = { Text("Reverse") })
      AssistChip(onClick = { output = UtilityToolkit.sortLines(input) }, label = { Text("Sort A-Z") })
      AssistChip(onClick = { output = UtilityToolkit.deduplicateLines(input) }, label = { Text("Dedupe Lines") })
      AssistChip(onClick = { output = UtilityToolkit.removeExtraSpaces(input) }, label = { Text("Trim Spaces") })
      AssistChip(onClick = { output = UtilityToolkit.addLineNumbers(input) }, label = { Text("Number Lines") })
      AssistChip(onClick = {
        val emails = UtilityToolkit.extractEmails(input)
        val urls = UtilityToolkit.extractUrls(input)
        val phones = UtilityToolkit.extractPhoneNumbers(input)
        output = "Emails: $emails\nURLs: $urls\nPhones: $phones"
      }, label = { Text("Extract Links/Emails") })
      AssistChip(onClick = {
        output = UtilityToolkit.wordFrequencyTop(input).joinToString("\n") { "${it.first}: ${it.second}" }
      }, label = { Text("Top Words") })
    }
    if (output.isNotEmpty()) {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Top) {
          Text(output, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
          IconButton(onClick = { copyToClipboard(context, "Text Result", output) }) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy result")
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MathAndConvertersCard() {
  var expr by remember { mutableStateOf("(125 * 1.15) + sqrt(81)") }
  var calcOut by remember { mutableStateOf("") }

  var selectedCat by remember { mutableStateOf(UtilityToolkit.UnitCategory.LENGTH) }
  var unitVal by remember { mutableStateOf("10") }
  var fromUnit by remember(selectedCat) { mutableStateOf(selectedCat.units.first()) }
  var toUnit by remember(selectedCat) { mutableStateOf(selectedCat.units.getOrElse(1) { selectedCat.units.first() }) }

  var billAmt by remember { mutableStateOf("45") }
  var tipPct by remember { mutableStateOf("15") }
  var peopleCnt by remember { mutableStateOf("2") }

  var weightKg by remember { mutableStateOf("70") }
  var heightCm by remember { mutableStateOf("175") }

  var loanAmt by remember { mutableStateOf("10000") }
  var loanRate by remember { mutableStateOf("6.5") }
  var loanMonths by remember { mutableStateOf("24") }

  var bdayMonth by remember { mutableStateOf("12") }
  var bdayDay by remember { mutableStateOf("25") }

  ToolSectionCard("Calculator, Units, Finance & Health", Icons.Rounded.Calculate) {
    // 1. Safe Math Evaluator
    OutlinedTextField(
      value = expr,
      onValueChange = { expr = it },
      label = { Text("Math (+ - * / ^ % sqrt sin cos log pi)") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().testTag("toolbox_math_input"),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      Button(onClick = {
        calcOut = UtilityToolkit.evaluateMath(expr).fold(
          onSuccess = { "= $it" },
          onFailure = { "Error: ${it.message}" },
        )
      }) { Text("Evaluate") }
      if (calcOut.isNotEmpty()) Text(calcOut, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }

    HorizontalDivider()
    // 2. Unit Converter (8 categories)
    Text("Unit Converter", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      UtilityToolkit.UnitCategory.entries.forEach { cat ->
        FilterChip(selected = selectedCat == cat, onClick = { selectedCat = cat }, label = { Text(cat.label) })
      }
    }
    OutlinedTextField(value = unitVal, onValueChange = { unitVal = it }, label = { Text("Value") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Column(modifier = Modifier.weight(1f)) {
        Text("From", style = MaterialTheme.typography.labelSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          selectedCat.units.forEach { u ->
            FilterChip(selected = fromUnit == u, onClick = { fromUnit = u }, label = { Text(u) })
          }
        }
      }
      Column(modifier = Modifier.weight(1f)) {
        Text("To", style = MaterialTheme.typography.labelSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          selectedCat.units.forEach { u ->
            FilterChip(selected = toUnit == u, onClick = { toUnit = u }, label = { Text(u) })
          }
        }
      }
    }
    val converted = remember(unitVal, selectedCat, fromUnit, toUnit) {
      val v = unitVal.toDoubleOrNull() ?: 0.0
      UtilityToolkit.convertUnit(v, selectedCat, fromUnit, toUnit)
    }
    Text(
      text = "${unitVal.ifBlank { "0" }} $fromUnit = ${"%.4f".format(Locale.US, converted)} $toUnit",
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.secondary,
    )

    HorizontalDivider()
    // 3. Tip & Split
    Text("Tip & Bill Splitter", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(value = billAmt, onValueChange = { billAmt = it }, label = { Text("Bill") }, modifier = Modifier.weight(1f), singleLine = true)
      OutlinedTextField(value = tipPct, onValueChange = { tipPct = it }, label = { Text("Tip %") }, modifier = Modifier.weight(1f), singleLine = true)
      OutlinedTextField(value = peopleCnt, onValueChange = { peopleCnt = it }, label = { Text("People") }, modifier = Modifier.weight(1f), singleLine = true)
    }
    val tipRes = UtilityToolkit.calculateTipSplit(billAmt.toDoubleOrNull() ?: 0.0, tipPct.toDoubleOrNull() ?: 0.0, peopleCnt.toIntOrNull() ?: 1)
    Text(
      text = "Tip: ${"%.2f".format(tipRes.tipAmount)} • Total: ${"%.2f".format(tipRes.totalAmount)} • Per Person: ${"%.2f".format(tipRes.perPerson)}",
      style = MaterialTheme.typography.bodyMedium,
    )

    HorizontalDivider()
    // 4. Loan EMI + BMI + Event Countdown
    Text("Loan EMI & Compound Estimator", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(value = loanAmt, onValueChange = { loanAmt = it }, label = { Text("Amount") }, modifier = Modifier.weight(1f), singleLine = true)
      OutlinedTextField(value = loanRate, onValueChange = { loanRate = it }, label = { Text("APR %") }, modifier = Modifier.weight(1f), singleLine = true)
      OutlinedTextField(value = loanMonths, onValueChange = { loanMonths = it }, label = { Text("Months") }, modifier = Modifier.weight(1f), singleLine = true)
    }
    val emi = UtilityToolkit.calculateLoanEmi(loanAmt.toDoubleOrNull() ?: 0.0, loanRate.toDoubleOrNull() ?: 0.0, loanMonths.toIntOrNull() ?: 12)
    Text("Monthly EMI: ${"%.2f".format(emi.monthlyPayment)} • Total Interest: ${"%.2f".format(emi.totalInterest)}", style = MaterialTheme.typography.bodyMedium)

    HorizontalDivider()
    Text("BMI & Event Countdown", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(value = weightKg, onValueChange = { weightKg = it }, label = { Text("Weight kg") }, modifier = Modifier.weight(1f), singleLine = true)
      OutlinedTextField(value = heightCm, onValueChange = { heightCm = it }, label = { Text("Height cm") }, modifier = Modifier.weight(1f), singleLine = true)
    }
    val (bmi, bmiCat) = UtilityToolkit.calculateBmi(weightKg.toDoubleOrNull() ?: 70.0, heightCm.toDoubleOrNull() ?: 175.0)
    Text("BMI: ${"%.1f".format(bmi)} ($bmiCat)", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(value = bdayMonth, onValueChange = { bdayMonth = it }, label = { Text("Month (1-12)") }, modifier = Modifier.weight(1f), singleLine = true)
      OutlinedTextField(value = bdayDay, onValueChange = { bdayDay = it }, label = { Text("Day") }, modifier = Modifier.weight(1f), singleLine = true)
      val days = UtilityToolkit.daysUntilDate(bdayMonth.toIntOrNull() ?: 1, bdayDay.toIntOrNull() ?: 1)
      Text("$days days left", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DevCryptoAndFunCard() {
  val context = LocalContext.current
  var devInput by remember { mutableStateOf("") }
  var devSecret by remember { mutableStateOf("my-secret") }
  var devPattern by remember { mutableStateOf("\\d+") }
  var devOutput by remember { mutableStateOf("") }
  var generatedSecret by remember { mutableStateOf(UtilityToolkit.generatePassword()) }
  var hexColor by remember { mutableStateOf("#10B981") }
  var diceOut by remember { mutableStateOf("") }
  var choicePool by remember { mutableStateOf("Pizza, Tacos, Sushi, Burgers") }

  ToolSectionCard("Dev, Crypto, Passwords, Clocks & RNG", Icons.Rounded.Code) {
    // Passwords & UUID
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Icon(Icons.Rounded.Password, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
      Text(
        text = generatedSecret,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.weight(1f),
      )
      IconButton(onClick = { copyToClipboard(context, "Generated Secret", generatedSecret) }) {
        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy generated secret")
      }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      AssistChip(onClick = { generatedSecret = UtilityToolkit.generatePassword(16) }, label = { Text("16-char Password") })
      AssistChip(onClick = { generatedSecret = UtilityToolkit.generatePassword(24) }, label = { Text("24-char Strong") })
      AssistChip(onClick = { generatedSecret = UtilityToolkit.generatePassphrase(4) }, label = { Text("4-Word Passphrase") })
      AssistChip(onClick = { generatedSecret = UtilityToolkit.generateUuidV4() }, label = { Text("UUID v4") })
    }

    HorizontalDivider()
    OutlinedTextField(
      value = devInput,
      onValueChange = { devInput = it },
      label = { Text("JSON, Base64, JWT, Epoch, Regex input, or text to hash") },
      minLines = 2,
      modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(value = devPattern, onValueChange = { devPattern = it }, label = { Text("Regex") }, singleLine = true, modifier = Modifier.weight(1f))
      OutlinedTextField(value = devSecret, onValueChange = { devSecret = it }, label = { Text("HMAC Secret") }, singleLine = true, modifier = Modifier.weight(1f))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      AssistChip(onClick = { devOutput = UtilityToolkit.formatJson(devInput, false).getOrElse { it.message ?: "Error" } }, label = { Text("JSON Pretty") })
      AssistChip(onClick = { devOutput = UtilityToolkit.formatJson(devInput, true).getOrElse { it.message ?: "Error" } }, label = { Text("JSON Minify") })
      AssistChip(onClick = { devOutput = UtilityToolkit.base64Encode(devInput) }, label = { Text("B64 Enc") })
      AssistChip(onClick = { devOutput = UtilityToolkit.base64Decode(devInput).getOrElse { "Invalid Base64" } }, label = { Text("B64 Dec") })
      AssistChip(onClick = { devOutput = UtilityToolkit.urlEncode(devInput) }, label = { Text("URL Enc") })
      AssistChip(onClick = { devOutput = UtilityToolkit.urlDecode(devInput).getOrElse { "Invalid URL" } }, label = { Text("URL Dec") })
      AssistChip(onClick = { devOutput = UtilityToolkit.textToHex(devInput) }, label = { Text("To Hex") })
      AssistChip(onClick = { devOutput = UtilityToolkit.hexToText(devInput).getOrElse { it.message ?: "Error" } }, label = { Text("From Hex") })
      AssistChip(onClick = { devOutput = UtilityToolkit.textToBinary(devInput) }, label = { Text("To Binary") })
      AssistChip(onClick = { devOutput = UtilityToolkit.binaryToText(devInput).getOrElse { "Invalid Binary" } }, label = { Text("From Binary") })
      AssistChip(onClick = { devOutput = UtilityToolkit.digestHex(devInput, "SHA-256") }, label = { Text("SHA-256") })
      AssistChip(onClick = { devOutput = UtilityToolkit.digestHex(devInput, "SHA-512") }, label = { Text("SHA-512") })
      AssistChip(onClick = { devOutput = UtilityToolkit.digestHex(devInput, "MD5") }, label = { Text("MD5") })
      AssistChip(onClick = { devOutput = UtilityToolkit.hmacSha256Hex(devInput, devSecret) }, label = { Text("HMAC-SHA256") })
      AssistChip(onClick = { devOutput = UtilityToolkit.decodeJwt(devInput).getOrElse { it.message ?: "Invalid JWT" } }, label = { Text("Decode JWT") })
      AssistChip(onClick = { devOutput = UtilityToolkit.testRegex(devPattern, devInput).fold({ "Matches (${it.size}): $it" }, { it.message ?: "Bad Regex" }) }, label = { Text("Run Regex") })
      AssistChip(onClick = { devOutput = UtilityToolkit.epochToIso(devInput).getOrElse { "Enter Unix seconds/ms" } }, label = { Text("Epoch → Date") })
      AssistChip(onClick = { devOutput = UtilityToolkit.rot13(devInput) }, label = { Text("ROT13") })
      AssistChip(onClick = { devOutput = UtilityToolkit.toMorse(devInput) }, label = { Text("To Morse") })
      AssistChip(onClick = { devOutput = UtilityToolkit.fromMorse(devInput) }, label = { Text("From Morse") })
    }
    if (devOutput.isNotEmpty()) {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
          Text(devOutput, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1f))
          IconButton(onClick = { copyToClipboard(context, "Dev Output", devOutput) }) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy dev output")
          }
        }
      }
    }

    HorizontalDivider()
    // World Clocks
    var clockTick by remember { mutableIntStateOf(0) }
    val clocks = remember(clockTick) { UtilityToolkit.worldClocksNow() }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text("World Clocks", style = MaterialTheme.typography.titleMedium)
      IconButton(onClick = { clockTick++ }) {
        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh world clocks")
      }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      clocks.forEach { (city, time) ->
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
          Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(city, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(time, style = MaterialTheme.typography.labelMedium)
          }
        }
      }
    }

    HorizontalDivider()
    // Color & Contrast Checker
    OutlinedTextField(value = hexColor, onValueChange = { hexColor = it }, label = { Text("Hex Color (#RRGGBB)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val colorInfo = remember(hexColor) { UtilityToolkit.inspectHexColor(hexColor).getOrNull() }
    if (colorInfo != null) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
          modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Color(colorInfo.r, colorInfo.g, colorInfo.b)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
        )
        Text(
          "RGB(${colorInfo.r}, ${colorInfo.g}, ${colorInfo.b}) • White contrast ${"%.1f".format(colorInfo.contrastOnWhite)}:1 • Black ${"%.1f".format(colorInfo.contrastOnBlack)}:1",
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }

    HorizontalDivider()
    // Dice & Random Choice Picker
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick = {
        val d = UtilityToolkit.rollDice(2, 6)
        diceOut = "Rolled 2d6: ${d.joinToString(" + ")} = ${d.sum()}"
      }) { Text("Roll 2d6") }
      OutlinedButton(onClick = {
        diceOut = "Coin Flip: ${if (UtilityToolkit.rollDice(1, 2).first() == 1) "HEADS" else "TAILS"}"
      }) { Text("Flip Coin") }
      OutlinedButton(onClick = {
        val pick = UtilityToolkit.pickRandomChoice(choicePool) ?: "Add choices"
        diceOut = "Picked: $pick"
      }) { Text("Pick Random") }
    }
    OutlinedTextField(value = choicePool, onValueChange = { choicePool = it }, label = { Text("Comma-separated options to pick from") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (diceOut.isNotEmpty()) {
      Text(diceOut, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
  }
}

@Composable
fun ToolSectionCard(
  title: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
  Card(
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
      }
      content()
    }
  }
}

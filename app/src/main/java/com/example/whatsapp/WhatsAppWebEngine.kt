package com.example.whatsapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.util.optNonNullString
import com.example.util.optNullableString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class WebChatSnapshot(
  val chatJid: String,
  val displayName: String,
  val isGroup: Boolean,
  val lastMessagePreview: String,
  val lastTimestampMs: Long,
  val unreadCount: Int,
)

/**
 * WhatsAppWebEngine: Long-lived, self-contained Chromium WebView host for https://web.whatsapp.com/.
 *
 * Persists WhatsApp Web's cookies, localStorage, and IndexedDB in the app's private data directory
 * so a single QR/phone scan stays logged in across restarts. Injects assets/wa_utils.js and
 * assets/wa_boot.js over AndroidWA @JavascriptInterface for real-time message reading, media
 * decryption, typing/seen status, and outbound sending.
 */
class WhatsAppWebEngine(
  private val appContext: Context,
) {

  interface Listener {
    fun onQrRef(ref: String)
    fun onQrBitmap(bitmap: Bitmap)
    fun onConnectionState(rawState: String)
    fun onConnected(meJid: String, meName: String)
    fun onLoggedOut()
    fun onPairingCode(code: String)
    fun onIncomingEvent(event: IncomingWhatsAppEvent)
    fun onChatSnapshots(snapshots: List<WebChatSnapshot>)
    fun onEngineLog(level: String, tag: String, message: String)
    fun onEngineError(message: String)
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  private val mutableContext = MutableContextWrapper(appContext.applicationContext)
  private val pendingSends = ConcurrentHashMap<String, CompletableDeferred<WhatsAppSendResult>>()
  private val pendingMediaFetches = ConcurrentHashMap<String, CompletableDeferred<String?>>()

  @Volatile private var webView: WebView? = null
  @Volatile private var loadedOnce = false
  @Volatile var listener: Listener? = null

  fun updateBaseContext(context: Context) {
    mainHandler.post {
      mutableContext.baseContext = context
    }
  }

  fun start() {
    mainHandler.post {
      val web = ensureWebViewOnMain()
      if (!loadedOnce || web.url.isNullOrBlank() || !web.url.orEmpty().contains("whatsapp.com")) {
        loadedOnce = true
        web.loadUrl(WA_WEB_URL)
      } else {
        injectBridgeScripts(web)
      }
    }
  }

  fun reload() {
    mainHandler.post {
      val web = ensureWebViewOnMain()
      loadedOnce = true
      web.loadUrl(WA_WEB_URL)
    }
  }

  fun flushCookies() {
    mainHandler.post {
      runCatching { CookieManager.getInstance().flush() }
    }
  }

  fun pauseWebView() {
    mainHandler.post {
      runCatching { CookieManager.getInstance().flush() }
      webView?.onPause()
    }
  }

  fun resumeWebView() {
    mainHandler.post {
      webView?.onResume()
      webView?.resumeTimers()
    }
  }

  /**
   * Detaches WebView cleanly from a closing/destroyed Activity so it keeps running
   * against applicationContext without leaking the Activity window.
   */
  fun detachToBackground() {
    mainHandler.post {
      runCatching { CookieManager.getInstance().flush() }
      mutableContext.baseContext = appContext.applicationContext
      val web = webView ?: return@post
      (web.parent as? ViewGroup)?.removeView(web)
      web.onResume()
      web.resumeTimers()
    }
  }

  /**
   * Lightweight keep-alive nudge invoked by WhatsAppAgentService while running closed/in background.
   */
  fun keepAliveTick() {
    mainHandler.post {
      val web = webView ?: return@post
      web.onResume()
      web.resumeTimers()
      web.evaluateJavascript("window.__waHeartbeat && window.__waHeartbeat()", null)
    }
  }

  fun attachToContainer(container: ViewGroup) {
    mainHandler.post {
      val web = ensureWebViewOnMain()
      val currentParent = web.parent as? ViewGroup
      if (currentParent === container) return@post
      currentParent?.removeView(web)
      container.removeAllViews()
      container.addView(
        web,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
      )
      web.onResume()
      web.resumeTimers()
    }
  }

  fun resetSessionAndReload() {
    mainHandler.post {
      pendingSends.values.forEach {
        it.complete(WhatsAppSendResult.Failed("WhatsApp session reset while sending"))
      }
      pendingSends.clear()
      pendingMediaFetches.values.forEach { it.complete(null) }
      pendingMediaFetches.clear()
      val cm = CookieManager.getInstance()
      cm.removeAllCookies {
        cm.flush()
      }
      runCatching { WebStorage.getInstance().deleteAllData() }
      val web = ensureWebViewOnMain()
      web.clearCache(true)
      web.clearHistory()
      loadedOnce = true
      web.loadUrl(WA_WEB_URL)
    }
  }

  suspend fun sendTextDetailed(
    chatJid: String,
    text: String,
    quotedMessageId: String? = null,
  ): WhatsAppSendResult {
    val web = webView ?: return WhatsAppSendResult.Failed("WhatsApp Web is not active yet.")
    val reqId = "tx_${UUID.randomUUID().toString().take(8)}"
    val deferred = CompletableDeferred<WhatsAppSendResult>()
    pendingSends[reqId] = deferred

    val js =
      "window.__waReply && window.__waReply(${JSONObject.quote(reqId)}, ${JSONObject.quote(chatJid)}, ${JSONObject.quote(text)}, " +
        (quotedMessageId?.let { JSONObject.quote(it) } ?: "null") + ", 4000)"
    mainHandler.post {
      web.evaluateJavascript(js, null)
    }

    val result = withTimeoutOrNull(22_000L) { deferred.await() }
    pendingSends.remove(reqId)
    return result
      ?: WhatsAppSendResult.UnknownTimeout("Timed out waiting for WhatsApp Web send confirmation (message may already be in flight).")
  }

  suspend fun sendText(chatJid: String, text: String, quotedMessageId: String? = null): String? =
    sendTextDetailed(chatJid, text, quotedMessageId).sentIdOrNull()

  fun sendPresence(available: Boolean) {
    val web = webView ?: return
    val js = "window.__waPresence && window.__waPresence($available)"
    mainHandler.post { web.evaluateJavascript(js, null) }
  }

  suspend fun sendMedia(
    chatJid: String,
    bytes: ByteArray,
    mimeType: String,
    fileName: String,
    caption: String,
  ): String? {
    val web = webView ?: return null
    val reqId = "txm_${UUID.randomUUID().toString().take(8)}"
    val deferred = CompletableDeferred<WhatsAppSendResult>()
    pendingSends[reqId] = deferred

    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    val js =
      "window.__waSendMedia && window.__waSendMedia(" +
        "${JSONObject.quote(reqId)}, " +
        "${JSONObject.quote(chatJid)}, " +
        "${JSONObject.quote(b64)}, " +
        "${JSONObject.quote(mimeType)}, " +
        "${JSONObject.quote(fileName)}, " +
        "${JSONObject.quote(caption)})"
    mainHandler.post {
      web.evaluateJavascript(js, null)
    }

    val result = withTimeoutOrNull(25_000L) { deferred.await() }
    pendingSends.remove(reqId)
    return result?.sentIdOrNull()
  }

  fun sendReaction(messageId: String, emoji: String) {
    val web = webView ?: return
    val js = "window.__waSendReaction && window.__waSendReaction(${JSONObject.quote(messageId)}, ${JSONObject.quote(emoji)})"
    mainHandler.post { web.evaluateJavascript(js, null) }
  }

  fun sendChatState(chatJid: String, composing: Boolean) {
    val web = webView ?: return
    val js = "window.__waSendChatState && window.__waSendChatState(${JSONObject.quote(chatJid)}, $composing)"
    mainHandler.post { web.evaluateJavascript(js, null) }
  }

  fun sendSeen(chatJid: String) {
    val web = webView ?: return
    val js = "window.__waSendSeen && window.__waSendSeen(${JSONObject.quote(chatJid)})"
    mainHandler.post { web.evaluateJavascript(js, null) }
  }

  suspend fun fetchMediaFromWebView(messageId: String): Pair<String, String>? {
    val web = webView ?: return null
    val deferred = CompletableDeferred<String?>()
    val js =
      "(function(){try{" +
        "var fn=window.__waFetchMediaBase64;" +
        "if(typeof fn!=='function')return null;" +
        "var r=null;var done=false;" +
        "fn(${JSONObject.quote(messageId)}).then(function(v){AndroidWA._onMediaFetched&&AndroidWA._onMediaFetched(${JSONObject.quote(messageId)},v||'');}).catch(function(){AndroidWA._onMediaFetched&&AndroidWA._onMediaFetched(${JSONObject.quote(messageId)},'');});" +
        "return 'pending';" +
        "}catch(e){return null;}})();"
    pendingMediaFetches[messageId] = deferred
    mainHandler.post {
      web.evaluateJavascript(js) { raw ->
        if (raw == null || raw == "null") {
          pendingMediaFetches.remove(messageId)?.complete(null)
        }
      }
    }
    val jsonStr = withTimeoutOrNull(5_000L) { deferred.await() }
    pendingMediaFetches.remove(messageId)
    if (jsonStr.isNullOrBlank()) return null
    return runCatching {
      val obj = JSONObject(jsonStr)
      val b64 = obj.optNonNullString("data").trim()
      val mime = obj.optNonNullString("mimetype")
      if (b64.isNotBlank()) b64 to mime else null
    }.getOrNull()
  }

  fun requestPairingCode(phoneDigits: String) {
    mainHandler.post {
      val web = ensureWebViewOnMain()
      val js = "window.__waRequestPhoneCode && window.__waRequestPhoneCode(${JSONObject.quote(phoneDigits)})"
      web.evaluateJavascript(js, null)
    }
  }

  suspend fun runWebViewDiagnostics(): String {
    val web = webView ?: return "WebView not initialized yet. Tap Connect WhatsApp on Home first."
    val deferred = CompletableDeferred<String>()
    val probeJs =
      "(function(){try{" +
        "var req=typeof window.require==='function';" +
        "var ww=!!window.WWebJS;" +
        "var rep=!!window.__waReply;" +
        "var boot=(window.__waBootState&&window.__waBootState.state)||'unknown';" +
        "var st='';try{st=window.require('WAWebSocketModel').Socket.state||'';}catch(e){}" +
        "return JSON.stringify({url:location.href,require:req,wwebjs:ww,replyHook:rep,bootState:boot,sockState:st});" +
        "}catch(e){return JSON.stringify({error:String(e)});}})();"
    mainHandler.post {
      web.evaluateJavascript(probeJs) { raw ->
        deferred.complete(raw ?: "null")
      }
    }
    val raw = withTimeoutOrNull(4_000L) { deferred.await() } ?: return "WebView JS probe timed out (page still loading)."
    return runCatching {
      val unquoted =
        if (raw.startsWith("\"") && raw.endsWith("\"")) {
          org.json.JSONTokener(raw).nextValue()?.toString().orEmpty()
        } else {
          raw
        }
      val obj = JSONObject(unquoted)
      if (obj.has("error")) {
        "Probe error: ${obj.optNonNullString("error")}"
      } else {
        "URL=${obj.optNonNullString("url")} | boot=${obj.optNonNullString("bootState")} | require=${obj.optBoolean("require")} | WWebJS=${obj.optBoolean("wwebjs")} | replyHook=${obj.optBoolean("replyHook")} | socket=${obj.optNonNullString("sockState").ifBlank { "unknown" }}"
      }
    }.getOrDefault("Probe raw: $raw")
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun ensureWebViewOnMain(): WebView {
    webView?.let { return it }

    val web = WebView(mutableContext)
    CookieManager.getInstance().apply {
      setAcceptCookie(true)
      setAcceptThirdPartyCookies(web, true)
    }

    web.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
      databaseEnabled = true
      mediaPlaybackRequiresUserGesture = false
      useWideViewPort = true
      loadWithOverviewMode = true
      builtInZoomControls = true
      displayZoomControls = false
      mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
      // Desktop Chrome UA is required so web.whatsapp.com renders the real WhatsApp Web QR/client
      // instead of redirecting Android browsers to the mobile download landing page.
      userAgentString = DESKTOP_CHROME_UA
    }

    web.addJavascriptInterface(JsBridge(), "AndroidWA")

    web.webViewClient =
      object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
          val url = request?.url?.toString().orEmpty()
          return !(url.startsWith("https://web.whatsapp.com") || url.startsWith("about:blank"))
        }

        override fun onPageFinished(view: WebView?, url: String?) {
          super.onPageFinished(view, url)
          runCatching { CookieManager.getInstance().flush() }
          if (view != null && url.orEmpty().contains("web.whatsapp.com")) {
            injectBridgeScripts(view)
          }
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
          listener?.onEngineLog("WARN", "WEBJS", "WebView renderer stopped in background; auto-recovering saved session")
          mainHandler.post {
            val dead = webView
            webView = null
            loadedOnce = false
            runCatching {
              (dead?.parent as? ViewGroup)?.removeView(dead)
              dead?.destroy()
            }
            val fresh = ensureWebViewOnMain()
            loadedOnce = true
            fresh.loadUrl(WA_WEB_URL)
          }
          return true
        }
      }

    web.webChromeClient =
      object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
          if (consoleMessage != null && consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
            listener?.onEngineLog("DEBUG", "WEBJS", "${consoleMessage.message()} (${consoleMessage.lineNumber()})")
          }
          return true
        }
      }

    webView = web
    return web
  }

  private fun injectBridgeScripts(view: WebView) {
    runCatching {
      val utilsJs = appContext.assets.open("wa_utils.js").bufferedReader().use { it.readText() }
      val bootJs = appContext.assets.open("wa_boot.js").bufferedReader().use { it.readText() }
      view.evaluateJavascript(utilsJs) {
        view.evaluateJavascript(bootJs, null)
      }
    }.onFailure { err ->
      listener?.onEngineLog("ERROR", "WEBJS", "Failed injecting bridge scripts: ${err.message}")
    }
  }

  private inner class JsBridge {
    @JavascriptInterface
    fun onQr(ref: String) {
      if (ref.isNotBlank()) {
        listener?.onQrRef(ref)
      }
    }

    @JavascriptInterface
    fun onQrImage(dataUrl: String) {
      val b64 = dataUrl.substringAfter("base64,", "")
      if (b64.isBlank()) return
      runCatching {
        val raw = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(raw, 0, raw.size)
      }.getOrNull()?.let { bmp ->
        listener?.onQrBitmap(bmp)
      }
    }

    @JavascriptInterface
    fun onState(json: String) {
      val st = runCatching { JSONObject(json).optNonNullString("state") }.getOrDefault(json)
      if (st.isNotBlank()) {
        listener?.onConnectionState(st)
      }
    }

    @JavascriptInterface
    fun onConnected(json: String) {
      mainHandler.post { runCatching { CookieManager.getInstance().flush() } }
      val obj = runCatching { JSONObject(json) }.getOrNull()
      val meJid = obj?.optNullableString("meJid") ?: "linked@s.whatsapp.net"
      val meName = obj?.optNonNullString("meName").orEmpty()
      listener?.onConnected(meJid, meName)
    }

    @JavascriptInterface
    fun onLoggedOut(reason: String) {
      listener?.onLoggedOut()
    }

    @JavascriptInterface
    fun onPairingCode(code: String) {
      if (code.isNotBlank()) {
        listener?.onPairingCode(code)
      }
    }

    @JavascriptInterface
    fun onMessage(json: String) {
      val mediaDir = File(appContext.filesDir, "wa_media")
      val event = parseIncomingMessageJson(json, mediaDir) ?: return
      listener?.onIncomingEvent(event)
    }

    @JavascriptInterface
    fun onChatsSnapshot(json: String) {
      val items = parseChatSnapshotsJson(json)
      if (items.isNotEmpty()) {
        listener?.onChatSnapshots(items)
      }
    }

    @JavascriptInterface
    fun onSent(json: String) {
      runCatching {
        val obj = JSONObject(json)
        val reqId = obj.optNonNullString("requestId")
        val id = obj.optNonNullString("id").ifBlank { "wa_${System.currentTimeMillis()}" }
        if (reqId.isNotBlank()) {
          pendingSends.remove(reqId)?.complete(WhatsAppSendResult.Confirmed(id))
        }
      }
    }

    @JavascriptInterface
    fun onError(json: String) {
      runCatching {
        val obj = JSONObject(json)
        val reqId = obj.optNonNullString("requestId")
        val error = obj.optNonNullString("error", "Send failed").ifBlank { "Send failed" }
        if (reqId.isNotBlank()) {
          pendingSends.remove(reqId)?.complete(WhatsAppSendResult.Failed(error))
        }
        listener?.onEngineError(error)
      }
    }

    @JavascriptInterface
    fun _onMediaFetched(messageId: String, jsonOrEmpty: String) {
      pendingMediaFetches.remove(messageId)?.complete(jsonOrEmpty.takeIf { it.isNotBlank() })
    }

    @JavascriptInterface
    fun log(msg: String) {
      listener?.onEngineLog("INFO", "WEBJS", msg)
    }
  }

  companion object {
    const val WA_WEB_URL = "https://web.whatsapp.com/"
    private const val DESKTOP_CHROME_UA =
      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

    fun parseIncomingMessageJson(json: String, mediaDir: File?): IncomingWhatsAppEvent? {
      return runCatching {
        val m = JSONObject(json)
        val idObj = m.optJSONObject("id") ?: return null
        val serializedId = idObj.optNonNullString("_serialized").ifEmpty { idObj.optNonNullString("\$1") }
        if (serializedId.isBlank()) return null

        val fromMe = idObj.optBoolean("fromMe", false)
        val rawRemote = idObj.optNonNullString("remote").ifBlank { m.optNonNullString("from") }
        if (rawRemote.isBlank() || rawRemote == "status@broadcast") return null

        val chatJid = WAJid.normalizeUser(rawRemote)
        val isGroup = chatJid.endsWith("@g.us")
        val rawSender = m.optNonNullString("author").ifBlank { m.optNonNullString("from") }.ifBlank { chatJid }
        val senderJid = WAJid.normalizeUser(rawSender)
        val chatTitle = m.optNonNullString("chatTitle").ifBlank {
          if (isGroup) m.optNonNullString("chatName") else ""
        }
        val senderName =
          m.optNonNullString("notifyName")
            .ifBlank { if (!isGroup) chatTitle else "" }
            .ifBlank { WAJid.parse(senderJid).user }

        val rawType = m.optNonNullString("type", "chat").lowercase()
        val mappedType =
          when (rawType) {
            "chat" -> "TEXT"
            "ptt" -> "PTT"
            "audio" -> "AUDIO"
            "image" -> "IMAGE"
            "video" -> "VIDEO"
            "document" -> "DOCUMENT"
            "sticker" -> "STICKER"
            "location" -> "LOCATION"
            "vcard", "multi_vcard" -> "CONTACT"
            "edited" -> "EDIT"
            "revoked" -> "REVOKE"
            else -> "SYSTEM"
          }

        val mime = m.optNullableString("mimetype")
        val fileName = m.optNullableString("filename")
        val durationSec = m.optInt("duration", 0)
        val body = m.optNonNullString("body")
        val caption = m.optNonNullString("caption")

        var mediaFile: File? = null
        val rawB64 = m.optNonNullString("mediaBase64").trim()
        val bodyThumb =
          if (rawB64.isBlank() && mappedType in setOf("IMAGE", "STICKER")) {
            val cleaned = body.replace(Regex("\\s+"), "")
            if (cleaned.length >= 64 &&
              (cleaned.startsWith("/9j/") || cleaned.startsWith("iVBOR") || cleaned.startsWith("UklGR") || cleaned.startsWith("R0lGOD"))
            ) {
              cleaned
            } else {
              ""
            }
          } else {
            ""
          }
        val b64 = rawB64.ifBlank { bodyThumb }
        val resolvedMime =
          when {
            !mime.isNullOrBlank() -> mime
            bodyThumb.startsWith("UklGR") -> "image/webp"
            bodyThumb.startsWith("iVBOR") -> "image/png"
            bodyThumb.startsWith("/9j/") -> "image/jpeg"
            else -> mime
          }
        if (b64.isNotBlank() && mediaDir != null) {
          runCatching {
            mediaDir.mkdirs()
            val ext = extensionForMimeOrType(resolvedMime.orEmpty(), mappedType)
            val safeName = serializedId.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(48)
            val out = File(mediaDir, "${safeName}.$ext")
            out.writeBytes(Base64.decode(b64, Base64.DEFAULT))
            mediaFile = out
          }
        }

        val textContent =
          when (mappedType) {
            "TEXT" -> body
            "EDIT" -> body
            "REVOKE" -> "[Message deleted]"
            "LOCATION" -> {
              val loc = m.optNonNullString("loc")
              val lat = if (m.has("lat") && !m.isNull("lat")) m.optDouble("lat") else 0.0
              val lng = if (m.has("lng") && !m.isNull("lng")) m.optDouble("lng") else 0.0
              "Location: $loc ($lat, $lng)".trim()
            }
            "CONTACT" -> {
              val name = m.optNonNullString("vcardFormattedName").ifBlank { "Contact" }
              "Shared contact: $name\n${body.take(400)}"
            }
            "IMAGE", "PTT", "AUDIO", "VIDEO", "DOCUMENT", "STICKER" -> {
              buildString {
                append("[$mappedType]")
                if (!fileName.isNullOrBlank()) append(" $fileName")
                if (durationSec > 0) append(" (${durationSec}s)")
                val bodyNonBase64 =
                  if (rawType != "chat" && !body.startsWith("/9j/") && !body.startsWith("iVBOR") && !body.startsWith("UklGR") && !body.startsWith("R0lGOD")) {
                    body
                  } else {
                    ""
                  }
                val cap = caption.ifBlank { bodyNonBase64 }
                if (cap.isNotBlank()) append(" $cap")
              }
            }
            else -> body.ifBlank { "[$rawType]" }
          }

        val isThumbnail =
          m.optBoolean("mediaIsThumbnail", false) ||
            (rawB64.isBlank() && bodyThumb.isNotBlank())

        val mentionedArr = m.optJSONArray("mentionedJidList")
        val mentionedJids =
          if (mentionedArr != null) {
            buildList {
              for (i in 0 until mentionedArr.length()) {
                val rawMention = mentionedArr.optNonNullString(i).trim()
                if (rawMention.isNotBlank()) add(WAJid.normalizeUser(rawMention))
              }
            }
          } else {
            emptyList()
          }
        val quotedParticipantRaw = m.optNullableString("quotedParticipant")
        val quotedParticipantJid = quotedParticipantRaw?.let { WAJid.normalizeUser(it) }

        val tsSec = m.optLong("timestamp", System.currentTimeMillis() / 1000L)
        val tsMs = if (tsSec > 10_000_000_000L) tsSec else tsSec * 1000L

        val resolvedChatType =
          when {
            chatJid == "status@broadcast" -> ChatType.STATUS
            chatJid.endsWith("@newsletter") -> ChatType.CHANNEL
            isGroup -> ChatType.GROUP
            chatJid.endsWith("@s.whatsapp.net") || chatJid.endsWith("@c.us") || chatJid.endsWith("@lid") ->
              ChatType.INDIVIDUAL
            else -> ChatType.UNKNOWN
          }

        val rawQuotedText = m.optNullableString("quotedText")?.takeIf { qt ->
          val trimmed = qt.trim()
          !(trimmed.length >= 64 &&
            (trimmed.startsWith("/9j/") || trimmed.startsWith("iVBOR") || trimmed.startsWith("UklGR") || trimmed.startsWith("R0lGOD")))
        }

        IncomingWhatsAppEvent(
          messageId = serializedId,
          chatJid = chatJid,
          senderJid = senderJid,
          senderName = senderName,
          isGroup = isGroup,
          fromMe = fromMe,
          timestampMs = tsMs,
          messageType = mappedType,
          textContent = textContent,
          mediaMimeType = resolvedMime,
          mediaLocalFile = mediaFile,
          quotedMessageId = m.optNullableString("quotedMsgId"),
          quotedText = rawQuotedText,
          targetMessageId = m.optNullableString("targetMessageId"),
          isBacklog = m.optBoolean("isBacklog", false),
          mediaDirectPath = m.optNullableString("directPath"),
          mediaDownloadUrl = m.optNullableString("deprecatedMms3Url"),
          mediaStaticUrl = m.optNullableString("staticUrl"),
          mediaKeyBase64 = m.optNullableString("mediaKey"),
          mediaIsThumbnail = isThumbnail,
          chatTitle = chatTitle,
          mentionedJids = mentionedJids,
          quotedParticipantJid = quotedParticipantJid,
          chatType = resolvedChatType,
          quotedFromMe = m.optBoolean("quotedFromMe", false),
        )
      }.getOrNull()
    }

    fun parseChatSnapshotsJson(json: String): List<WebChatSnapshot> {
      return runCatching {
        val arr = JSONArray(json)
        buildList {
          for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val jid = obj.optNonNullString("chatJid")
            if (jid.isBlank() || jid == "status@broadcast") continue
            val isGroup = obj.optBoolean("isGroup", jid.endsWith("@g.us"))
            val rawName = obj.optNonNullString("displayName")
            val fallbackName =
              if (isGroup) "Group ${WAJid.parse(jid).user.takeLast(6)}" else WAJid.parse(jid).user
            add(
              WebChatSnapshot(
                chatJid = WAJid.normalizeUser(jid),
                displayName = rawName.ifBlank { fallbackName },
                isGroup = isGroup,
                lastMessagePreview = obj.optNonNullString("lastMessagePreview"),
                lastTimestampMs = obj.optLong("lastTimestampMs", System.currentTimeMillis()),
                unreadCount = obj.optInt("unreadCount", 0),
              )
            )
          }
        }
      }.getOrDefault(emptyList())
    }

    private fun extensionForMimeOrType(mime: String, kind: String): String {
      val clean = mime.substringBefore(';').trim().lowercase()
      return when {
        clean.contains("jpeg") || clean.contains("jpg") -> "jpg"
        clean.contains("png") -> "png"
        clean.contains("webp") -> "webp"
        clean.contains("pdf") -> "pdf"
        clean.contains("ogg") || clean.contains("opus") -> "ogg"
        clean.contains("mp4") -> "mp4"
        clean.contains("mpeg") || clean.contains("mp3") -> "mp3"
        kind == "IMAGE" -> "jpg"
        kind == "PTT" || kind == "AUDIO" -> "ogg"
        kind == "VIDEO" -> "mp4"
        kind == "STICKER" -> "webp"
        else -> "bin"
      }
    }
  }
}

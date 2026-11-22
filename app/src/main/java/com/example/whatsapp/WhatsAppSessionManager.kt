package com.example.whatsapp

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.example.data.db.ChatMessageEntity
import com.example.data.repository.ClawRepository
import com.example.security.SecureCredentialStore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class WhatsAppConnectionState {
  data object Disconnected : WhatsAppConnectionState()
  data class Connecting(val attempt: Int = 1, val statusNote: String = "Connecting to WhatsApp…") :
    WhatsAppConnectionState()
  data class QrReady(
    val qrString: String,
    val qrBitmap: Bitmap?,
    val pairingCode: String? = null,
    val qrIndex: Int = 1,
    val qrTotal: Int = 1,
  ) : WhatsAppConnectionState()
  data class Connected(
    val meJid: String,
    val meName: String?,
    val platform: String?,
    val connectedAtMs: Long,
  ) : WhatsAppConnectionState()
  data class Reconnecting(val attempt: Int, val nextRetrySeconds: Int, val reason: String) :
    WhatsAppConnectionState()
  data class Error(val message: String, val code: Int? = null) : WhatsAppConnectionState()
}

/**
 * WhatsAppSessionManager: Coordinates the persistent Chromium WebView WhatsApp Web runtime
 * (`WhatsAppWebEngine` + `wa_utils.js` + `wa_boot.js`), persistent cookie/IndexedDB session
 * restoration, real-time message ingestion, and outbound sends for ClawLink WA.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WhatsAppSessionManager(
  private val context: Context,
  val credentialStore: SecureCredentialStore,
  private val repository: ClawRepository,
  val crypto: WhatsAppCrypto,
  wsUrl: String = WhatsAppTransport.DEFAULT_WS_URL,
) {
  private val appContext = context.applicationContext
  private val cdnHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(20, TimeUnit.SECONDS)
      .build()
  private val sessionDispatcher = Dispatchers.IO.limitedParallelism(6)
  private val scope = CoroutineScope(SupervisorJob() + sessionDispatcher)
  private val stateMutex = Mutex()

  val transport = WhatsAppTransport(wsUrl = wsUrl)
  val auth = WhatsAppAuthentication(credentialStore, crypto, context)
  val webEngine = WhatsAppWebEngine(context.applicationContext)
  val messaging = WhatsAppMessaging(context, transport, crypto, repository, webEngine = webEngine)

  private val _state = MutableStateFlow<WhatsAppConnectionState>(WhatsAppConnectionState.Disconnected)
  val state: StateFlow<WhatsAppConnectionState> = _state.asStateFlow()

  private val _isSessionRegistered = MutableStateFlow(credentialStore.getOrCreateCredentials().isRegistered)
  val isSessionRegistered: StateFlow<Boolean> = _isSessionRegistered.asStateFlow()

  private val _qrCountdownSeconds = MutableStateFlow(0)
  val qrCountdownSeconds: StateFlow<Int> = _qrCountdownSeconds.asStateFlow()

  private val _incomingEvents = MutableSharedFlow<IncomingWhatsAppEvent>(extraBufferCapacity = 128)
  val incomingEvents: SharedFlow<IncomingWhatsAppEvent> = _incomingEvents.asSharedFlow()

  private val _lastErrorMessage = MutableStateFlow<String?>(null)
  val lastErrorMessage: StateFlow<String?> = _lastErrorMessage.asStateFlow()

  private var qrTickerJob: Job? = null
  private var manualDisconnect = false
  private var activePairingCode: String? = null
  private var qrBatchCounter = 0
  private val sessionStartedAtMs = System.currentTimeMillis()
  @Volatile private var liveWindowAnchorMs = sessionStartedAtMs

  init {
    webEngine.listener =
      object : WhatsAppWebEngine.Listener {
        override fun onQrRef(ref: String) {
          scope.launch {
            stateMutex.withLock {
              if (manualDisconnect) return@launch
              qrBatchCounter++
              val bmp = QrBitmapGenerator.generateQrBitmap(ref, 540)
              _lastErrorMessage.value = null
              _state.value =
                WhatsAppConnectionState.QrReady(
                  qrString = ref,
                  qrBitmap = bmp,
                  pairingCode = activePairingCode,
                  qrIndex = qrBatchCounter,
                  qrTotal = qrBatchCounter,
                )
              startQrCountdown(20)
            }
            repository.log("INFO", "WHATSAPP", "Live QR #$qrBatchCounter generated from WhatsApp Web")
          }
        }

        override fun onQrBitmap(bitmap: Bitmap) {
          scope.launch {
            stateMutex.withLock {
              if (manualDisconnect) return@launch
              qrBatchCounter++
              _lastErrorMessage.value = null
              _state.value =
                WhatsAppConnectionState.QrReady(
                  qrString = "webview-qr-$qrBatchCounter",
                  qrBitmap = bitmap,
                  pairingCode = activePairingCode,
                  qrIndex = qrBatchCounter,
                  qrTotal = qrBatchCounter,
                )
              startQrCountdown(20)
            }
            repository.log("INFO", "WHATSAPP", "Live QR #$qrBatchCounter captured from WhatsApp Web canvas")
          }
        }

        override fun onConnectionState(rawState: String) {
          scope.launch {
            repository.log("DEBUG", "PROTOCOL", "WhatsApp Web socket state: $rawState")
            if (rawState == "TIMEOUT" || rawState == "PROXYBLOCK" || rawState == "SMB_TOS_BLOCK") {
              _lastErrorMessage.value = "WhatsApp Web state: $rawState"
            }
          }
        }

        override fun onConnected(meJid: String, meName: String) {
          scope.launch {
            val updatedMeName: String?
            val normalizedMe: String
            stateMutex.withLock {
              qrTickerJob?.cancel()
              _qrCountdownSeconds.value = 0
              activePairingCode = null
              _lastErrorMessage.value = null

              val curCreds = credentialStore.getOrCreateCredentials()
              normalizedMe =
                if (meJid.contains("@")) WAJid.normalizeUser(meJid) else "linked@s.whatsapp.net"
              val updated =
                curCreds.copy(
                  meJid = normalizedMe,
                  meName = meName.ifBlank { curCreds.meName },
                  platform = "WhatsApp Web",
                )
              credentialStore.saveCredentials(updated)
              updatedMeName = updated.meName
              _isSessionRegistered.value = true
              val connectedNow = System.currentTimeMillis()
              // Include messages that landed within 2 minutes prior to link/reconnect completion
              liveWindowAnchorMs = minOf(liveWindowAnchorMs, connectedNow - 120_000L)
              _state.value =
                WhatsAppConnectionState.Connected(
                  meJid = normalizedMe,
                  meName = updatedMeName,
                  platform = "WhatsApp Web",
                  connectedAtMs = connectedNow,
                )
            }
            repository.log("INFO", "WHATSAPP", "Connected & session saved locally (${updatedMeName ?: normalizedMe})")
          }
        }

        override fun onLoggedOut() {
          scope.launch {
            stateMutex.withLock {
              qrTickerJob?.cancel()
              _qrCountdownSeconds.value = 0
              activePairingCode = null
              credentialStore.clearSessionKeepIdentity()
              _isSessionRegistered.value = false
              _state.value =
                WhatsAppConnectionState.Connecting(
                  attempt = 1,
                  statusNote = "Signed out from phone; loading fresh QR…",
                )
            }
            repository.log("WARN", "WHATSAPP", "WhatsApp session logged out on phone; cleared saved state")
          }
        }

        override fun onPairingCode(code: String) {
          scope.launch {
            stateMutex.withLock {
              activePairingCode = code
              val cur = _state.value
              _state.value =
                if (cur is WhatsAppConnectionState.QrReady) {
                  cur.copy(pairingCode = code)
                } else {
                  WhatsAppConnectionState.QrReady(qrString = "", qrBitmap = null, pairingCode = code)
                }
            }
            repository.log("INFO", "WHATSAPP", "Received WhatsApp pairing code")
          }
        }

        override fun onIncomingEvent(event: IncomingWhatsAppEvent) {
          scope.launch {
            handleNormalizedEvent(event)
          }
        }

        override fun onChatSnapshots(snapshots: List<WebChatSnapshot>) {
          scope.launch {
            snapshots.forEach { snap ->
              val conv =
                repository.ensureConversation(
                  chatJid = snap.chatJid,
                  displayName = if (snap.isGroup) "" else snap.displayName,
                  isGroup = snap.isGroup,
                  chatTitle = snap.displayName,
                )
              if (snap.lastMessagePreview.isNotBlank() && conv.lastMessagePreview.isBlank()) {
                repository.updateConversation(
                  conv.copy(
                    displayName = if (snap.isGroup && snap.displayName.isNotBlank()) snap.displayName else conv.displayName,
                    lastMessagePreview = snap.lastMessagePreview.take(180),
                    lastMessageTimestamp = maxOf(conv.lastMessageTimestamp, snap.lastTimestampMs),
                    unreadCount = maxOf(conv.unreadCount, snap.unreadCount),
                  )
                )
              }
            }
            repository.log("INFO", "WHATSAPP", "Synced ${snapshots.size} recent chats from saved session")
          }
        }

        override fun onEngineLog(level: String, tag: String, message: String) {
          scope.launch {
            repository.log(level, tag, message)
          }
        }

        override fun onEngineError(message: String) {
          scope.launch {
            _lastErrorMessage.value = message
            repository.log("ERROR", "WHATSAPP", message)
          }
        }
      }
  }

  fun connect(isRestartAfter515: Boolean = false, force: Boolean = false) {
    scope.launch {
      val cur = _state.value
      if (!force && !isRestartAfter515 &&
        (cur is WhatsAppConnectionState.Connecting ||
          cur is WhatsAppConnectionState.QrReady ||
          cur is WhatsAppConnectionState.Connected)
      ) {
        return@launch
      }

      manualDisconnect = false
      _lastErrorMessage.value = null
      val creds = credentialStore.getOrCreateCredentials()
      _isSessionRegistered.value = creds.isRegistered

      _state.value =
        WhatsAppConnectionState.Connecting(
          attempt = 1,
          statusNote =
            if (creds.isRegistered) "Restoring saved WhatsApp login…"
            else "Loading WhatsApp QR code…",
        )

      repository.log(
        "INFO",
        "WHATSAPP",
        if (creds.isRegistered) "Restoring saved WhatsApp Web session (${creds.meName ?: creds.meJid})"
        else "Starting WhatsApp Web engine for QR pairing",
      )

      webEngine.start()
    }
  }

  fun requestPhonePairingCode(phoneNumber: String) {
    scope.launch {
      val cleanPhone = phoneNumber.filter { it.isDigit() }
      if (cleanPhone.length < 7) {
        _lastErrorMessage.value = "Enter full phone number with country code."
        return@launch
      }
      _lastErrorMessage.value = null
      webEngine.start()
      webEngine.requestPairingCode(cleanPhone)
      repository.log("INFO", "WHATSAPP", "Requested phone pairing code for $cleanPhone")
    }
  }

  fun disconnect() {
    scope.launch {
      manualDisconnect = true
      qrTickerJob?.cancel()
      _qrCountdownSeconds.value = 0
      webEngine.pauseWebView()
      _state.value = WhatsAppConnectionState.Disconnected
      repository.log("INFO", "WHATSAPP", "Paused WhatsApp Web connection (saved session kept)")
    }
  }

  fun reconnectNow() {
    scope.launch {
      manualDisconnect = false
      qrTickerJob?.cancel()
      _qrCountdownSeconds.value = 0
      _lastErrorMessage.value = null
      val creds = credentialStore.getOrCreateCredentials()
      _isSessionRegistered.value = creds.isRegistered
      _state.value =
        WhatsAppConnectionState.Connecting(
          attempt = 1,
          statusNote = if (creds.isRegistered) "Reconnecting saved login…" else "Refreshing QR code…",
        )
      webEngine.reload()
      repository.log("INFO", "WHATSAPP", "Reloaded WhatsApp Web session")
    }
  }

  fun relinkDevice() {
    scope.launch {
      manualDisconnect = false
      qrTickerJob?.cancel()
      _qrCountdownSeconds.value = 0
      activePairingCode = null
      qrBatchCounter = 0
      _lastErrorMessage.value = null
      credentialStore.clearSessionKeepIdentity()
      crypto.signalStore.clearAllSignalState()
      _isSessionRegistered.value = false
      _state.value =
        WhatsAppConnectionState.Connecting(
          attempt = 1,
          statusNote = "Clearing old login & loading fresh QR…",
        )
      webEngine.resetSessionAndReload()
      repository.log("WARN", "WHATSAPP", "Cleared saved cookies/session; generating fresh pairing QR")
    }
  }

  fun manualUploadPreKeys() {
    scope.launch {
      repository.log("INFO", "PROTOCOL", "Keys managed locally inside WhatsApp Web IndexedDB session")
    }
  }

  private fun startQrCountdown(seconds: Int) {
    qrTickerJob?.cancel()
    qrTickerJob =
      scope.launch {
        for (sec in seconds downTo 1) {
          if (!isActive || _state.value is WhatsAppConnectionState.Connected || _isSessionRegistered.value) {
            _qrCountdownSeconds.value = 0
            return@launch
          }
          _qrCountdownSeconds.value = sec
          delay(1000)
        }
        _qrCountdownSeconds.value = 0
      }
  }

  private suspend fun handleNormalizedEvent(rawEvent: IncomingWhatsAppEvent) {
    when (rawEvent.messageType) {
      "EDIT" -> rawEvent.targetMessageId?.let { repository.markMessageEdited(it, rawEvent.textContent) }
      "REVOKE" -> rawEvent.targetMessageId?.let { repository.markMessageRevoked(it) }
      else -> {
        val resolvedQuotedPreview =
          rawEvent.quotedText?.takeIf { it.isNotBlank() && !it.equals("null", true) }
            ?: rawEvent.quotedMessageId?.takeIf { it.isNotBlank() }?.let { qid ->
              repository.findQuotedMessage(rawEvent.chatJid, qid)?.let { qMsg ->
                qMsg.textContent.ifBlank {
                  qMsg.mediaTranscription?.takeUnless {
                    com.example.agent.PersonMemoryExtractor.isPlaceholderOrBlankMediaNote(it)
                  }.orEmpty()
                }.takeIf { it.isNotBlank() }?.take(140)
              }
            }
        // Persist incoming message durably BEFORE attempting network/CDN media upgrades
        val inserted =
          repository.recordMessage(
            message =
              ChatMessageEntity(
                id = rawEvent.messageId,
                chatJid = rawEvent.chatJid,
                senderJid = rawEvent.senderJid,
                senderName = rawEvent.senderName,
                fromMe = rawEvent.fromMe,
                timestamp = rawEvent.timestampMs,
                messageType = rawEvent.messageType,
                textContent = rawEvent.textContent,
                mediaMimeType = rawEvent.mediaMimeType,
                mediaLocalPath = rawEvent.mediaLocalFile?.absolutePath,
                quotedMessageId = rawEvent.quotedMessageId,
                quotedPreview = resolvedQuotedPreview,
                deliveryStatus = if (rawEvent.fromMe) "SENT" else "RECEIVED",
              ),
            chatTitle = rawEvent.chatTitle,
          )
        val upgradedEvent =
          runCatching { ensureMediaFileAvailable(rawEvent) }.getOrDefault(rawEvent)
        if (upgradedEvent.mediaLocalFile != null &&
          upgradedEvent.mediaLocalFile.absolutePath != rawEvent.mediaLocalFile?.absolutePath
        ) {
          repository.updateMediaLocalPath(
            messageId = upgradedEvent.messageId,
            localPath = upgradedEvent.mediaLocalFile.absolutePath,
            mimeType = upgradedEvent.mediaMimeType,
          )
        }
        if (inserted) {
          val now = System.currentTimeMillis()
          val isRecent =
            !upgradedEvent.isBacklog &&
              (upgradedEvent.timestampMs >= (liveWindowAnchorMs - 60_000L) || (now - upgradedEvent.timestampMs) <= 300_000L)
          if (isRecent) {
            if (!upgradedEvent.fromMe) {
              webEngine.sendPresence(true)
            }
            _incomingEvents.emit(upgradedEvent)
          } else {
            repository.log("DEBUG", "WHATSAPP", "Saved history sync message ${upgradedEvent.messageId} without triggering AI reply")
          }
        }
      }
    }
  }

  private suspend fun ensureMediaFileAvailable(event: IncomingWhatsAppEvent): IncomingWhatsAppEvent {
    if (event.fromMe) return event
    if (event.messageType !in setOf("IMAGE", "STICKER", "PTT", "AUDIO", "DOCUMENT")) return event

    val existing = event.mediaLocalFile
    val needsUpgrade =
      existing == null ||
        !existing.exists() ||
        event.mediaIsThumbnail ||
        (event.messageType == "IMAGE" && isLowResThumbnailFile(existing))
    if (!needsUpgrade) return event

    val ext =
      when {
        event.mediaMimeType?.contains("webp", true) == true || event.messageType == "STICKER" -> "webp"
        event.mediaMimeType?.contains("png", true) == true -> "png"
        event.mediaMimeType?.contains("pdf", true) == true -> "pdf"
        event.messageType == "PTT" || event.messageType == "AUDIO" -> "ogg"
        else -> "jpg"
      }

    // Fallback A: Encrypted WhatsApp CDN download + HKDF/AES-CBC decrypt (full original resolution)
    val keyB64 = event.mediaKeyBase64
    val dPath = event.mediaDirectPath.orEmpty()
    val dUrl = event.mediaDownloadUrl.orEmpty()
    if (!keyB64.isNullOrBlank() && (dPath.isNotBlank() || dUrl.isNotBlank())) {
      val decryptedFile =
        runCatching {
          val mediaKey = Base64.decode(keyB64, Base64.DEFAULT)
          WhatsAppMediaCrypto.downloadAndSaveDecryptedMedia(
            context = appContext,
            httpClient = cdnHttpClient,
            url = dUrl,
            directPath = dPath,
            mediaKey = mediaKey,
            mediaKind = event.messageType,
            fileExtension = ext,
            messageId = event.messageId,
          )
        }.getOrNull()
      if (decryptedFile != null && decryptedFile.exists() && decryptedFile.length() > (existing?.length() ?: 0L)) {
        repository.log("INFO", "MEDIA", "Downloaded full-res ${event.messageType} via native CDN decrypt (${decryptedFile.length()}B)")
        return event.copy(mediaLocalFile = decryptedFile, mediaIsThumbnail = false)
      }
    }

    // Fallback B: Unencrypted static HTTPS sticker/media URL
    val staticUrl = event.mediaStaticUrl
    if (existing == null && !staticUrl.isNullOrBlank() && staticUrl.startsWith("https://")) {
      val staticFile =
        runCatching {
          val req = Request.Builder().url(staticUrl).get().build()
          cdnHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val bytes = resp.body?.bytes() ?: return@use null
            if (bytes.isEmpty()) return@use null
            val dir = File(appContext.filesDir, "wa_media").apply { mkdirs() }
            val safeId = event.messageId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)
            File(dir, "$safeId.$ext").apply { writeBytes(bytes) }
          }
        }.getOrNull()
      if (staticFile != null && staticFile.exists() && staticFile.length() > 0L) {
        repository.log("INFO", "MEDIA", "Recovered ${event.messageType} via static HTTPS URL (${staticFile.length()}B)")
        return event.copy(mediaLocalFile = staticFile, mediaIsThumbnail = false)
      }
    }

    // Fallback C: Delayed WebView media decryption if CDN was unavailable or thumbnail-only
    val webMedia =
      runCatching {
        kotlinx.coroutines.delay(350L)
        webEngine.fetchMediaFromWebView(event.messageId)
      }.getOrNull()
    if (webMedia != null) {
      val (b64, resolvedMime) = webMedia
      val savedFile =
        runCatching {
          val bytes = Base64.decode(b64, Base64.DEFAULT)
          if (bytes.isEmpty()) null
          else {
            val dir = File(appContext.filesDir, "wa_media").apply { mkdirs() }
            val safeId = event.messageId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)
            val resolvedExt =
              when {
                resolvedMime.contains("webp", true) -> "webp"
                resolvedMime.contains("png", true) -> "png"
                resolvedMime.contains("pdf", true) -> "pdf"
                resolvedMime.contains("ogg", true) -> "ogg"
                else -> ext
              }
            File(dir, "$safeId.$resolvedExt").apply { writeBytes(bytes) }
          }
        }.getOrNull()
      if (savedFile != null && savedFile.exists() && savedFile.length() > (existing?.length() ?: 0L)) {
        repository.log("INFO", "MEDIA", "Recovered ${event.messageType} via delayed WebView fetch (${savedFile.length()}B)")
        return event.copy(
          mediaLocalFile = savedFile,
          mediaMimeType = resolvedMime.ifBlank { event.mediaMimeType },
          mediaIsThumbnail = false,
        )
      }
    }

    return event
  }

  private fun isLowResThumbnailFile(file: File): Boolean {
    if (!file.exists()) return true
    if (file.length() < 32_000L) return true
    return runCatching {
      val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
      android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
      opts.outWidth in 1..399 && opts.outHeight in 1..399
    }.getOrDefault(false)
  }
}

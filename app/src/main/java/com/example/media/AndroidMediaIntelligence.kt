package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.example.ai.AiRouteTarget
import com.example.ai.NimChatMessage
import com.example.ai.NimModelInfo
import com.example.ai.NimStreamEvent
import com.example.ai.NvidiaNimClient
import com.example.ai.NvidiaModelCapabilityDetector
import com.example.data.prefs.AgentSettings
import com.example.data.prefs.LocalWhisperModel
import com.example.data.prefs.TranscriptionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

sealed class LocalModelDownloadState {
  data object Idle : LocalModelDownloadState()
  data class Downloading(val model: LocalWhisperModel, val progressPercent: Int, val stageLabel: String = "Downloading") :
    LocalModelDownloadState()
  data class Downloaded(val model: LocalWhisperModel, val sizeMb: Long) : LocalModelDownloadState()
  data class Error(val message: String) : LocalModelDownloadState()
}

/**
 * AndroidMediaIntelligence:
 * - High-resolution vision image & PDF encoding (up to 1920px @ 92% JPEG so receipts/tables/UI text stay sharp)
 * - Real 16kHz 16-bit mono PCM resampling via Android MediaExtractor + MediaCodec
 * - Real Cloud NVIDIA NIM ASR + Real Offline Kaldi/Vosk speech recognition from downloaded ZIP models
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidMediaIntelligence(
  private val context: Context,
  private val nimClient: NvidiaNimClient,
  private val httpClient: OkHttpClient = OkHttpClient(),
) {

  private val _localModelState = MutableStateFlow<LocalModelDownloadState>(LocalModelDownloadState.Idle)
  val localModelState: StateFlow<LocalModelDownloadState> = _localModelState.asStateFlow()

  // Vosk C++ Model/Recognizer calls must be serialized on a single thread
  private val voskDispatcher = Dispatchers.IO.limitedParallelism(1)
  private var loadedVoskModelId: String? = null
  private var loadedVoskModel: Model? = null

  // Fast bounded in-memory extraction cache keyed by "<sha256>:<kind>"
  private val extractionCache = java.util.concurrent.ConcurrentHashMap<String, String>()
  private val base64Cache = java.util.concurrent.ConcurrentHashMap<String, String>()

  private val asrModelDir: File
    get() = File(context.filesDir, "asr_models").apply { mkdirs() }

  fun computeFileSha256(file: File): String? {
    if (!file.exists() || !file.isFile) return null
    return runCatching {
      val digest = java.security.MessageDigest.getInstance("SHA-256")
      FileInputStream(file).use { fis ->
        val buf = ByteArray(16_384)
        while (true) {
          val n = fis.read(buf)
          if (n <= 0) break
          digest.update(buf, 0, n)
        }
      }
      digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
  }

  fun isLocalModelReady(model: LocalWhisperModel): Boolean {
    val dir = File(asrModelDir, model.id)
    return dir.isDirectory && hasVoskModelStructure(dir)
  }

  fun refreshLocalModelState(model: LocalWhisperModel) {
    val dir = File(asrModelDir, model.id)
    if (dir.isDirectory && hasVoskModelStructure(dir)) {
      val sizeMb = (computeDirectoryBytes(dir) / (1024L * 1024L)).coerceAtLeast(1L)
      _localModelState.value = LocalModelDownloadState.Downloaded(model, sizeMb)
    } else {
      _localModelState.value = LocalModelDownloadState.Idle
    }
  }

  suspend fun downloadLocalWhisperModel(model: LocalWhisperModel): Result<File> =
    withContext(Dispatchers.IO) {
      val destDir = File(asrModelDir, model.id)
      val tmpZip = File(asrModelDir, "${model.id}.zip.part")
      val tmpExtract = File(asrModelDir, "${model.id}_extract_tmp")
      _localModelState.value = LocalModelDownloadState.Downloading(model, 0, "Downloading model")
      try {
        val req = Request.Builder().url(model.downloadUrl).get().build()
        httpClient.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) {
            val msg = "Model download failed (HTTP ${resp.code})"
            _localModelState.value = LocalModelDownloadState.Error(msg)
            return@withContext Result.failure(IllegalStateException(msg))
          }
          val body = resp.body ?: throw IllegalStateException("Empty model download body")
          val total = body.contentLength()
          var readSoFar = 0L
          val buf = ByteArray(32_768)
          body.byteStream().use { input ->
            FileOutputStream(tmpZip).use { out ->
              while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                readSoFar += n
                if (total > 0) {
                  val pct = ((readSoFar * 92L) / total).toInt().coerceIn(0, 92)
                  _localModelState.value = LocalModelDownloadState.Downloading(model, pct, "Downloading model")
                }
              }
            }
          }
        }

        _localModelState.value = LocalModelDownloadState.Downloading(model, 95, "Extracting offline model")
        if (tmpExtract.exists()) tmpExtract.deleteRecursively()
        tmpExtract.mkdirs()
        unzipSafe(tmpZip, tmpExtract)
        tmpZip.delete()

        val extractedRoot = resolveExtractedVoskRoot(tmpExtract)
          ?: throw IllegalStateException("Downloaded archive did not contain a valid Vosk acoustic/graph model.")

        synchronized(this@AndroidMediaIntelligence) {
          if (loadedVoskModelId == model.id) {
            runCatching { loadedVoskModel?.close() }
            loadedVoskModel = null
            loadedVoskModelId = null
          }
        }

        if (destDir.exists()) destDir.deleteRecursively()
        val moved = extractedRoot.renameTo(destDir)
        if (!moved) {
          extractedRoot.copyRecursively(destDir, overwrite = true)
        }
        tmpExtract.deleteRecursively()

        if (!hasVoskModelStructure(destDir)) {
          destDir.deleteRecursively()
          throw IllegalStateException("Extracted model directory is incomplete.")
        }

        val sizeMb = (computeDirectoryBytes(destDir) / (1024L * 1024L)).coerceAtLeast(1L)
        _localModelState.value = LocalModelDownloadState.Downloaded(model, sizeMb)
        Result.success(destDir)
      } catch (t: Throwable) {
        tmpZip.delete()
        tmpExtract.deleteRecursively()
        val msg = t.message ?: "Offline speech model install failed"
        _localModelState.value = LocalModelDownloadState.Error(msg)
        Result.failure(t)
      }
    }

  fun releaseAndDeleteLocalModels() {
    synchronized(this) {
      runCatching { loadedVoskModel?.close() }
      loadedVoskModel = null
      loadedVoskModelId = null
    }
    asrModelDir.listFiles()?.forEach { it.deleteRecursively() }
    _localModelState.value = LocalModelDownloadState.Idle
  }

  fun isLowResolutionImageFile(file: File): Boolean {
    if (!file.exists() || file.length() == 0L) return true
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, opts)
    if (opts.outWidth <= 0 || opts.outHeight <= 0) return file.length() < 16_000L
    return maxOf(opts.outWidth, opts.outHeight) < 320
  }

  suspend fun encodeImageFileToBase64Jpeg(file: File, maxDim: Int = 1920): String? =
    withContext(Dispatchers.IO) {
      if (!file.exists() || file.length() == 0L) return@withContext null
      val cacheKey = "${file.absolutePath}:${file.length()}:${file.lastModified()}:$maxDim"
      base64Cache[cacheKey]?.let { return@withContext it }

      val decoded = decodeBitmapSafely(file, targetMaxDim = maxDim) ?: return@withContext null
      try {
        var bytes = compressBitmapOpaqueJpeg(decoded, maxDim = maxDim, quality = 86)
        if (bytes.size > 1_200_000 && maxDim > 1280) {
          bytes = compressBitmapOpaqueJpeg(decoded, maxDim = 1280, quality = 82)
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        if (base64Cache.size > 48) base64Cache.clear()
        base64Cache[cacheKey] = b64
        b64
      } finally {
        decoded.recycle()
      }
    }

  private fun compressBitmapOpaqueJpeg(decoded: Bitmap, maxDim: Int, quality: Int): ByteArray {
    val scaled = scaleBitmap(decoded, maxDim)
    val opaque = Bitmap.createBitmap(scaled.width.coerceAtLeast(1), scaled.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    opaque.eraseColor(Color.WHITE)
    Canvas(opaque).apply {
      drawColor(Color.WHITE)
      drawBitmap(scaled, 0f, 0f, null)
    }
    val out = ByteArrayOutputStream()
    opaque.compress(Bitmap.CompressFormat.JPEG, quality, out)
    opaque.recycle()
    if (scaled !== decoded) scaled.recycle()
    return out.toByteArray()
  }

  private fun decodeBitmapSafely(file: File, targetMaxDim: Int = 1280): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
    val sampleSize =
      if (bounds.outWidth > 0 && bounds.outHeight > 0) {
        var inSample = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while ((maxSide / (inSample * 2)) >= targetMaxDim) {
          inSample *= 2
        }
        inSample
      } else {
        1
      }
    val decodeOpts =
      BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
      }
    val fast = runCatching { BitmapFactory.decodeFile(file.absolutePath, decodeOpts) }.getOrNull()
    if (fast != null) return fast
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      return runCatching {
        val src = ImageDecoder.createSource(file)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
          decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
          decoder.isMutableRequired = true
          val w = info.size.width
          val h = info.size.height
          if (w > targetMaxDim || h > targetMaxDim) {
            val ratio = minOf(targetMaxDim.toFloat() / w, targetMaxDim.toFloat() / h)
            decoder.setTargetSize((w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1))
          }
        }
      }.getOrNull()
    }
    return null
  }

  suspend fun summarizeImageWithVisionModel(
    imageFile: File,
    prompt: String,
    visionModel: NimModelInfo,
  ): String? = summarizeImageWithVisionCandidates(imageFile, prompt, listOf(visionModel)).getOrNull()

  fun extractLottieStickerSummary(file: File): String? {
    if (!file.exists() || file.length() < 8L) return null
    return runCatching {
      val head = ByteArray(4)
      FileInputStream(file).use { it.read(head) }
      val jsonText: String? =
        when {
          head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> {
            var found: String? = null
            ZipInputStream(FileInputStream(file)).use { zis ->
              while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory && (entry.name.endsWith(".json", true) || entry.name.endsWith(".tgs", true) || found == null)) {
                  val txt = zis.readBytes().toString(Charsets.UTF_8)
                  if (txt.trimStart().startsWith("{")) {
                    found = txt
                    break
                  }
                }
              }
            }
            found
          }
          head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte() -> {
            GZIPInputStream(FileInputStream(file)).use { it.readBytes().toString(Charsets.UTF_8) }
          }
          head[0] == '{'.code.toByte() -> {
            file.readText(Charsets.UTF_8)
          }
          else -> null
        }
      val clean = jsonText?.takeIf { it.trimStart().startsWith("{") } ?: return null
      val root = JSONObject(clean)
      val title = root.optString("nm").takeIf { it.isNotBlank() && !it.equals("null", true) } ?: "Animated Lottie sticker"
      val layers = root.optJSONArray("layers")
      val layerNames = mutableListOf<String>()
      if (layers != null) {
        for (i in 0 until minOf(layers.length(), 10)) {
          val l = layers.optJSONObject(i) ?: continue
          val ln = l.optString("nm").trim()
          if (ln.isNotBlank() && !ln.equals("null", true) && ln.length <= 40) layerNames.add(ln)
        }
      }
      buildString {
        append("Animated WhatsApp Lottie sticker: \"$title\"")
        if (layerNames.isNotEmpty()) {
          append(" (elements: ${layerNames.distinct().joinToString(", ")})")
        }
      }
    }.getOrNull()
  }

  suspend fun summarizeImageWithVisionCandidates(
    imageFile: File,
    prompt: String,
    candidateModels: List<NimModelInfo>,
    providerTargets: List<com.example.ai.AiRouteTarget> = emptyList(),
  ): Result<String> =
    withContext(Dispatchers.IO) {
      extractLottieStickerSummary(imageFile)?.let { lottieSummary ->
        return@withContext Result.success(lottieSummary)
      }
      val sha = computeFileSha256(imageFile)
      val cacheKey = if (sha != null) "vis:$sha:${prompt.hashCode()}" else null
      if (cacheKey != null) {
        extractionCache[cacheKey]?.let { return@withContext Result.success(it) }
      }

      val b64 =
        encodeImageFileToBase64Jpeg(imageFile)
          ?: return@withContext Result.failure(IllegalStateException("Could not decode image file (${imageFile.name}, ${imageFile.length()} bytes)."))

      val routeList: List<com.example.ai.AiRouteTarget> =
        if (providerTargets.isNotEmpty()) {
          providerTargets
        } else {
          candidateModels.map { m ->
            com.example.ai.AiRouteTarget(
              provider = com.example.data.prefs.AiProviderType.NVIDIA_NIM,
              baseUrl = NvidiaNimClient.DEFAULT_BASE_URL,
              apiKey = null,
              model = if (m.supportsVision) m else m.copy(supportsVision = true),
              requiresApiKey = true,
            )
          }
        }

      if (routeList.isEmpty()) {
        return@withContext Result.failure(IllegalStateException("No Vision (VL) model available."))
      }

      var lastError = "Vision model returned empty response."
      for (route in routeList.take(5)) {
        var resultText: String? = null
        var errText: String? = null
        nimClient
          .streamChatCompletion(
            model = route.model,
            messages =
              listOf(
                NimChatMessage(
                  role = "user",
                  content = prompt,
                  imageBase64Jpegs = listOf(b64),
                )
              ),
            tools = emptyList(),
            temperature = 0.2f,
            maxTokens = 768,
            stream = false,
            baseUrlOverride = route.baseUrl.takeIf { it != NvidiaNimClient.DEFAULT_BASE_URL },
            apiKeyOverride = route.apiKey,
            requiresApiKey = route.requiresApiKey,
          )
          .collect { ev ->
            when (ev) {
              is NimStreamEvent.Completed -> resultText = ev.fullText.trim()
              is NimStreamEvent.Failed -> errText = ev.message
              is NimStreamEvent.Delta -> Unit
            }
          }
        val cleanText = resultText?.trim().orEmpty()
        if (cleanText.isNotEmpty() && !com.example.ai.NvidiaModelCapabilityDetector.detectVisionRefusal(cleanText)) {
          if (cacheKey != null) {
            if (extractionCache.size > 120) extractionCache.clear()
            extractionCache[cacheKey] = cleanText
          }
          return@withContext Result.success(cleanText)
        }
        if (cleanText.isNotEmpty()) {
          lastError = "${route.model.shortName} could not interpret the image ($cleanText)"
        } else if (!errText.isNullOrBlank()) {
          lastError = "${route.model.shortName}: $errText"
        }
      }
      Result.failure(IllegalStateException(lastError))
    }

  suspend fun extractDocumentIntelligence(
    file: File,
    mimeType: String?,
    visionModel: NimModelInfo?,
    providerTarget: com.example.ai.AiRouteTarget? = null,
  ): String =
    withContext(Dispatchers.IO) {
      if (!file.exists()) return@withContext "Attachment unavailable on disk."
      val sha = computeFileSha256(file)
      val cacheKey = if (sha != null) "doc:$sha" else null
      if (cacheKey != null) {
        extractionCache[cacheKey]?.let { return@withContext it }
      }

      val lowerMime = (mimeType ?: "").lowercase()
      val ext = file.extension.lowercase()

      val result =
        when {
          lowerMime.contains("pdf") || ext == "pdf" -> extractPdfContent(file, visionModel, providerTarget)
          lowerMime.contains("wordprocessingml") || ext == "docx" -> extractDocxContent(file)
          lowerMime.startsWith("text/") || ext in setOf("txt", "md", "csv", "json", "xml", "log", "kt", "ts", "py") ->
            runCatching { file.readText(Charsets.UTF_8).take(8_000) }
              .getOrElse { "Unable to decode text file (${file.length()} bytes)." }
          else -> "Binary document (${file.name}, ${file.length()} bytes, type=$mimeType)."
        }
      if (cacheKey != null && result.isNotBlank()) {
        if (extractionCache.size > 120) extractionCache.clear()
        extractionCache[cacheKey] = result
      }
      result
    }

  private fun extractDocxContent(file: File): String {
    return runCatching {
      val docXml =
        ZipInputStream(BufferedInputStream(FileInputStream(file))).use { zis ->
          var found: String? = null
          while (true) {
            val entry = zis.nextEntry ?: break
            if (!entry.isDirectory && entry.name.equals("word/document.xml", ignoreCase = true)) {
              found = zis.readBytes().toString(Charsets.UTF_8)
              break
            }
          }
          found
        }
      if (docXml.isNullOrBlank()) {
        return "DOCX document (${file.name}, ${file.length()} bytes; no readable document.xml found)."
      }
      val text =
        docXml
          .replace(Regex("</w:p>"), "\n")
          .replace(Regex("<w:tab\\s*/>"), "\t")
          .replace(Regex("<[^>]+>"), "")
          .replace("&amp;", "&")
          .replace("&lt;", "<")
          .replace("&gt;", ">")
          .replace("&quot;", "\"")
          .replace("&apos;", "'")
          .replace(Regex("[ \\t]+"), " ")
          .replace(Regex("\\n{3,}"), "\n\n")
          .trim()
      if (text.isBlank()) "DOCX document (${file.name}) contained no text."
      else "DOCX (${file.name}) text content:\n${text.take(8_000)}"
    }.getOrElse { err ->
      "DOCX document (${file.name}, ${file.length()} bytes) could not be extracted: ${err.message}"
    }
  }

  private suspend fun extractPdfContent(
    file: File,
    visionModel: NimModelInfo?,
    providerTarget: com.example.ai.AiRouteTarget? = null,
  ): String {
    return try {
      ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { renderer ->
          val pageCount = renderer.pageCount
          val renderPages = minOf(pageCount, 3)
          val activeModel = providerTarget?.model ?: visionModel
          if (activeModel == null || !activeModel.supportsVision) {
            return "PDF document (${file.name}, $pageCount pages). Select a Vision-capable model to read rendered PDF pages."
          }
          val pageImages = mutableListOf<String>()
          for (idx in 0 until renderPages) {
            renderer.openPage(idx).use { page ->
              val targetW = (page.width * 2).coerceIn(800, 1400)
              val targetH = ((targetW.toFloat() / page.width.coerceAtLeast(1)) * page.height).toInt().coerceAtLeast(1)
              val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
              Canvas(bmp).drawColor(Color.WHITE)
              page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
              val scaled = scaleBitmap(bmp, 1400)
              val baos = ByteArrayOutputStream()
              scaled.compress(Bitmap.CompressFormat.JPEG, 88, baos)
              pageImages.add(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
              if (scaled !== bmp) scaled.recycle()
              bmp.recycle()
            }
          }
          var extracted = "PDF document ($pageCount pages)."
          nimClient
            .streamChatCompletion(
              model = activeModel,
              messages =
                listOf(
                  NimChatMessage(
                    role = "user",
                    content = "Transcribe and summarize all visible text, tables, numbers, headers, and key details from these PDF pages.",
                    imageBase64Jpegs = pageImages,
                  )
                ),
              tools = emptyList(),
              temperature = 0.2f,
              maxTokens = 1024,
              stream = false,
              baseUrlOverride = providerTarget?.baseUrl?.takeIf { it != NvidiaNimClient.DEFAULT_BASE_URL },
              apiKeyOverride = providerTarget?.apiKey,
              requiresApiKey = providerTarget?.requiresApiKey ?: true,
            )
            .collect { ev ->
              if (ev is NimStreamEvent.Completed && ev.fullText.isNotBlank()) {
                extracted = "PDF ($pageCount pages) extracted content:\n${ev.fullText.trim()}"
              }
            }
          extracted
        }
      }
    } catch (t: Throwable) {
      "PDF file (${file.name}, ${file.length()} bytes); render failed: ${t.message}"
    }
  }

  suspend fun transcribeVoiceNote(
    audioFile: File,
    settings: AgentSettings,
    discoveredModels: List<NimModelInfo>,
    audioTargets: List<AiRouteTarget> = emptyList(),
  ): String =
    withContext(Dispatchers.IO) {
      if (!audioFile.exists()) return@withContext "Voice note file missing."
      if (!settings.autoTranscribeVoiceNotes || settings.transcriptionMode == TranscriptionMode.OFF) {
        return@withContext "[Voice note transcription is currently turned OFF by the owner.]"
      }

      val wavFile = convertAudioToWav16kMono(audioFile) ?: audioFile
      val durationSec = estimateWavDurationSec(wavFile)

      try {
        if (settings.transcriptionMode == TranscriptionMode.CLOUD) {
          var lastErr: String? = null
          if (audioTargets.isNotEmpty()) {
            for (target in audioTargets.take(5)) {
              val cloudRes =
                nimClient.transcribeAudioCloud(
                  audioFile = wavFile,
                  asrModelId = target.model.id,
                  mimeType = "audio/wav",
                  baseUrlOverride = target.baseUrl.takeIf { it != NvidiaNimClient.DEFAULT_BASE_URL },
                  apiKeyOverride = target.apiKey,
                )
              if (cloudRes.isSuccess) {
                val transcript = cloudRes.getOrNull().orEmpty().trim()
                if (transcript.isNotBlank()) {
                  return@withContext transcript
                }
              } else {
                lastErr = cloudRes.exceptionOrNull()?.message
              }
            }
          } else {
            val rankedDiscovered = NvidiaModelCapabilityDetector.rankVoiceModels(discoveredModels, settings.audioModelId)
            val candidates =
              (listOfNotNull(settings.audioModelId.takeIf { it.isNotBlank() }) + rankedDiscovered.map { it.id })
                .distinct()
            for (modelId in candidates) {
              val cloudRes = nimClient.transcribeAudioCloud(wavFile, modelId, "audio/wav")
              if (cloudRes.isSuccess) {
                val transcript = cloudRes.getOrNull().orEmpty().trim()
                if (transcript.isNotBlank()) {
                  return@withContext transcript
                }
              } else {
                lastErr = cloudRes.exceptionOrNull()?.message
              }
            }
          }
          // Automatic fallback to local Vosk if the user already downloaded an offline model
          if (isLocalModelReady(settings.localWhisperModel)) {
            val localRes = transcribeAudioWithVosk(wavFile, settings.localWhisperModel)
            if (localRes.isSuccess && !localRes.getOrNull().isNullOrBlank()) {
              return@withContext localRes.getOrThrow()
            }
          }
          val reason = lastErr?.let { " ($it)" } ?: " — select an Audio/ASR model (e.g. Parakeet, Whisper, or Phi-4) or switch to Offline Model in AI tab"
          return@withContext "[Voice note ~${durationSec}s; Cloud transcription failed$reason]"
        }

        // LOCAL mode — Real Kaldi/Vosk Offline Speech Recognition
        val model = settings.localWhisperModel
        if (!isLocalModelReady(model)) {
          return@withContext "[Voice note ~${durationSec}s; Offline speech model (${model.displayName}) is selected but not downloaded yet. Open the AI tab to download it.]"
        }
        val localRes = transcribeAudioWithVosk(wavFile, model)
        localRes.fold(
          onSuccess = { txt ->
            if (txt.isBlank()) "[Voice note ~${durationSec}s; no clear speech detected by ${model.displayName}]"
            else txt
          },
          onFailure = { err ->
            "[Voice note ~${durationSec}s; Offline speech error (${err.message ?: "decoder failure"})]"
          },
        )
      } finally {
        if (wavFile !== audioFile) wavFile.delete()
      }
    }

  suspend fun transcribeAudioWithVosk(wavFile: File, whisperModel: LocalWhisperModel): Result<String> =
    withContext(voskDispatcher) {
      if (!isLocalModelReady(whisperModel)) {
        return@withContext Result.failure(IllegalStateException("Offline model ${whisperModel.displayName} is not downloaded"))
      }
      val modelDir = File(asrModelDir, whisperModel.id)
      try {
        val model =
          synchronized(this@AndroidMediaIntelligence) {
            if (loadedVoskModel == null || loadedVoskModelId != whisperModel.id) {
              runCatching { loadedVoskModel?.close() }
              loadedVoskModel = Model(modelDir.absolutePath)
              loadedVoskModelId = whisperModel.id
            }
            loadedVoskModel!!
          }

        val transcriptParts = mutableListOf<String>()
        Recognizer(model, 16000.0f).use { recognizer ->
          BufferedInputStream(FileInputStream(wavFile)).use { bis ->
            // Skip 44-byte RIFF/WAVE header if present
            val headerProbe = ByteArray(4)
            bis.mark(44)
            val nHead = bis.read(headerProbe)
            if (nHead == 4 && String(headerProbe, Charsets.US_ASCII) == "RIFF") {
              val skipRest = 40L
              var skipped = 0L
              while (skipped < skipRest) {
                val s = bis.skip(skipRest - skipped)
                if (s <= 0) break
                skipped += s
              }
            } else {
              bis.reset()
            }

            val buf = ByteArray(4096)
            while (true) {
              val read = bis.read(buf)
              if (read <= 0) break
              if (recognizer.acceptWaveForm(buf, read)) {
                parseVoskText(recognizer.result)?.let { if (it.isNotBlank()) transcriptParts.add(it) }
              }
            }
            parseVoskText(recognizer.finalResult)?.let { if (it.isNotBlank()) transcriptParts.add(it) }
          }
        }
        Result.success(transcriptParts.joinToString(" ").trim())
      } catch (t: Throwable) {
        Result.failure(t)
      }
    }

  private fun parseVoskText(json: String?): String? {
    if (json.isNullOrBlank()) return null
    return runCatching { JSONObject(json).optString("text").trim() }.getOrNull()
  }

  /**
   * Decodes compressed WhatsApp audio (OGG/Opus/AAC/M4A/MP3) and resamples to 16,000 Hz,
   * 16-bit signed mono linear PCM WAV using Android MediaExtractor + MediaCodec.
   */
  fun convertAudioToWav16kMono(inputFile: File): File? {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    return try {
      extractor.setDataSource(inputFile.absolutePath)
      var trackIdx = -1
      var format: MediaFormat? = null
      for (i in 0 until extractor.trackCount) {
        val f = extractor.getTrackFormat(i)
        val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("audio/")) {
          trackIdx = i
          format = f
          break
        }
      }
      if (trackIdx < 0 || format == null) return null
      extractor.selectTrack(trackIdx)
      val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
      var actualSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 16_000
      var actualChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
      var pcmEncoding =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
          format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else AudioFormat.ENCODING_PCM_16BIT

      codec = MediaCodec.createDecoderByType(mime)
      codec.configure(format, null, null, 0)
      codec.start()

      val pcmOut = ByteArrayOutputStream()
      val info = MediaCodec.BufferInfo()
      var sawInputEos = false
      var sawOutputEos = false

      while (!sawOutputEos) {
        if (!sawInputEos) {
          val inIdx = codec.dequeueInputBuffer(10_000)
          if (inIdx >= 0) {
            val buf = codec.getInputBuffer(inIdx)!!
            val sampleSize = extractor.readSampleData(buf, 0)
            if (sampleSize < 0) {
              codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              sawInputEos = true
            } else {
              codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
              extractor.advance()
            }
          }
        }
        val outIdx = codec.dequeueOutputBuffer(info, 10_000)
        when {
          outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            val outFmt = codec.outputFormat
            if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
              actualSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            }
            if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
              actualChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
              pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
            }
          }
          outIdx >= 0 -> {
            val outBuf = codec.getOutputBuffer(outIdx)
            if (outBuf != null && info.size > 0) {
              val chunk = ByteArray(info.size)
              outBuf.position(info.offset)
              outBuf.get(chunk)
              pcmOut.write(chunk)
            }
            codec.releaseOutputBuffer(outIdx, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
              sawOutputEos = true
            }
          }
        }
      }

      val rawBytes = pcmOut.toByteArray()
      val resampled16kMono = downmixAndResampleTo16kMono(rawBytes, actualSampleRate, actualChannels, pcmEncoding)
      val wavFile = File(inputFile.parentFile, "${inputFile.nameWithoutExtension}_16k.wav")
      writeWavHeaderAndData(wavFile, resampled16kMono, sampleRate = 16_000, channels = 1)
      wavFile
    } catch (_: Throwable) {
      null
    } finally {
      runCatching { codec?.stop() }
      runCatching { codec?.release() }
      runCatching { extractor.release() }
    }
  }

  private fun downmixAndResampleTo16kMono(
    rawBytes: ByteArray,
    srcRate: Int,
    channels: Int,
    pcmEncoding: Int,
  ): ByteArray {
    val safeRate = srcRate.coerceIn(8000, 192_000)
    val safeChannels = channels.coerceIn(1, 8)
    val isFloat = pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT
    val bytesPerSample = if (isFloat) 4 else 2
    val frameBytes = safeChannels * bytesPerSample
    if (rawBytes.size < frameBytes) return ByteArray(0)

    val frameCount = rawBytes.size / frameBytes
    val bb = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
    val monoSrc = ShortArray(frameCount)

    for (f in 0 until frameCount) {
      var sum = 0.0
      for (c in 0 until safeChannels) {
        val sampleVal =
          if (isFloat) bb.float.coerceIn(-1.0f, 1.0f) * 32767.0
          else bb.short.toDouble()
        sum += sampleVal
      }
      monoSrc[f] = (sum / safeChannels).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    val monoDst: ShortArray =
      if (safeRate == 16_000) {
        monoSrc
      } else {
        val dstCount = ((frameCount.toDouble() * 16_000.0) / safeRate.toDouble()).toInt().coerceAtLeast(1)
        val step = safeRate.toDouble() / 16_000.0
        ShortArray(dstCount) { i ->
          val pos = i * step
          val idx0 = pos.toInt().coerceIn(0, frameCount - 1)
          val idx1 = (idx0 + 1).coerceIn(0, frameCount - 1)
          val frac = pos - idx0
          val interp = monoSrc[idx0] * (1.0 - frac) + monoSrc[idx1] * frac
          interp.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
      }

    val outBuf = ByteBuffer.allocate(monoDst.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (s in monoDst) {
      outBuf.putShort(s)
    }
    return outBuf.array()
  }

  private fun writeWavHeaderAndData(outFile: File, pcmData: ByteArray, sampleRate: Int, channels: Int) {
    val byteRate = sampleRate * channels * 2
    val header =
      ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(36 + pcmData.size)
        put("WAVE".toByteArray(Charsets.US_ASCII))
        put("fmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1.toShort())
        putShort(channels.toShort())
        putInt(sampleRate)
        putInt(byteRate)
        putShort((channels * 2).toShort())
        putShort(16.toShort())
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(pcmData.size)
      }.array()
    FileOutputStream(outFile).use {
      it.write(header)
      it.write(pcmData)
    }
  }

  private fun estimateWavDurationSec(file: File): Int {
    val bytes = (file.length() - 44L).coerceAtLeast(0L)
    return ((bytes / 32_000L).toInt()).coerceAtLeast(1)
  }

  private fun scaleBitmap(src: Bitmap, maxDim: Int): Bitmap {
    val w = src.width
    val h = src.height
    if (w <= maxDim && h <= maxDim) return src
    val ratio = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
    return Bitmap.createScaledBitmap(src, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), true)
  }

  private fun unzipSafe(zipFile: File, destDir: File) {
    val destCanonical = destDir.canonicalPath
    ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
      val buf = ByteArray(16_384)
      while (true) {
        val entry = zis.nextEntry ?: break
        val outFile = File(destDir, entry.name)
        val outCanonical = outFile.canonicalPath
        if (!outCanonical.startsWith(destCanonical + File.separator)) {
          throw SecurityException("Blocked unsafe zip entry: ${entry.name}")
        }
        if (entry.isDirectory) {
          outFile.mkdirs()
        } else {
          outFile.parentFile?.mkdirs()
          FileOutputStream(outFile).use { fos ->
            while (true) {
              val n = zis.read(buf)
              if (n <= 0) break
              fos.write(buf, 0, n)
            }
          }
        }
        zis.closeEntry()
      }
    }
  }

  private fun hasVoskModelStructure(dir: File): Boolean {
    val conf = File(dir, "conf")
    val am = File(dir, "am")
    return conf.isDirectory || am.isDirectory
  }

  private fun resolveExtractedVoskRoot(extractDir: File): File? {
    if (hasVoskModelStructure(extractDir)) return extractDir
    val children = extractDir.listFiles()?.filter { it.isDirectory }.orEmpty()
    return children.firstOrNull { hasVoskModelStructure(it) }
  }

  private fun computeDirectoryBytes(dir: File): Long {
    if (!dir.exists()) return 0L
    if (dir.isFile) return dir.length()
    return dir.listFiles()?.sumOf { computeDirectoryBytes(it) } ?: 0L
  }
}

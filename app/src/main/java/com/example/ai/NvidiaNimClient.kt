package com.example.ai

import com.example.agent.HumanReplySanitizer
import com.example.security.NvidiaKeyProvider
import com.example.security.SecureCredentialStore
import com.example.util.optNonNullString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class NimToolDefinition(
  val name: String,
  val description: String,
  val parametersSchemaJson: JSONObject,
)

data class NimToolCall(
  val id: String,
  val name: String,
  val argumentsJson: String,
)

data class NimChatMessage(
  val role: String, // system | user | assistant | tool
  val content: String? = null,
  val imageBase64Jpegs: List<String> = emptyList(),
  val toolCalls: List<NimToolCall> = emptyList(),
  val toolCallId: String? = null,
)

data class NimUsage(
  val promptTokens: Int = 0,
  val completionTokens: Int = 0,
  val totalTokens: Int = 0,
)

sealed class NimStreamEvent {
  data class Delta(val text: String) : NimStreamEvent()
  data class Completed(
    val fullText: String,
    val toolCalls: List<NimToolCall>,
    val usage: NimUsage,
  ) : NimStreamEvent()
  data class Failed(val message: String, val httpCode: Int? = null) : NimStreamEvent()
}

data class NimDiscoveryResult(
  val models: List<NimModelInfo>,
  val validated: Boolean,
  val errorMessage: String? = null,
)

/**
 * Modular OpenAI-compatible AI client (NVIDIA NIM primary + OpenAI/Groq/OpenRouter/Ollama endpoints)
 * supporting credential validation, dynamic GET /v1/models discovery, SSE streaming, tool calling,
 * vision inputs, and ASR.
 */
class NvidiaNimClient(
  private val credentialStore: SecureCredentialStore,
  private val baseUrl: String = DEFAULT_BASE_URL,
  private val httpClient: OkHttpClient =
    OkHttpClient.Builder()
      .connectionPool(ConnectionPool(8, 2, TimeUnit.MINUTES))
      .connectTimeout(12, TimeUnit.SECONDS)
      .readTimeout(50, TimeUnit.SECONDS)
      .writeTimeout(45, TimeUnit.SECONDS)
      .build(),
) {

  val configuredBaseUrl: String
    get() = baseUrl

  private fun activeApiKey(): String? {
    val key = credentialStore.getNvidiaApiKey()
    NvidiaKeyProvider.setRuntimeKeyHint(key)
    return key
  }

  suspend fun discoverModels(
    overrideKey: String? = null,
    baseUrlOverride: String? = null,
  ): NimDiscoveryResult =
    withContext(Dispatchers.IO) {
      val effectiveBase = (baseUrlOverride ?: baseUrl).trimEnd('/')
      val apiKey = overrideKey?.trim()?.takeIf { it.isNotBlank() } ?: activeApiKey()
      val isLocalEndpoint = effectiveBase.contains("127.0.0.1") || effectiveBase.contains("localhost") || effectiveBase.contains("192.168.")
      if (apiKey.isNullOrBlank() && !isLocalEndpoint) {
        return@withContext NimDiscoveryResult(
          models = emptyList(),
          validated = false,
          errorMessage = "API key is required.",
        )
      }

      val requestBuilder =
        Request.Builder()
          .url("$effectiveBase/models")
          .header("Accept", "application/json")
          .get()
      if (!apiKey.isNullOrBlank()) {
        requestBuilder.header("Authorization", "Bearer $apiKey")
      }
      val request = requestBuilder.build()

      try {
        httpClient.newCall(request).execute().use { resp ->
          val bodyStr = resp.body?.string().orEmpty()
          if (!resp.isSuccessful) {
            val detail = parseApiError(bodyStr, resp.code)
            return@withContext NimDiscoveryResult(
              models = emptyList(),
              validated = false,
              errorMessage = detail,
            )
          }
          val root = JSONObject(bodyStr)
          val dataArray = root.optJSONArray("data") ?: JSONArray()
          val discovered = mutableListOf<NimModelInfo>()
          for (i in 0 until dataArray.length()) {
            val item = dataArray.optJSONObject(i) ?: continue
            val id = item.optNonNullString("id").trim()
            if (id.isNotEmpty()) {
              val owner = item.optNonNullString("owned_by")
              discovered.add(NvidiaModelCapabilityDetector.inferFromDiscoveredModel(id, owner))
            }
          }
          NimDiscoveryResult(
            models = discovered.sortedBy { it.id },
            validated = true,
          )
        }
      } catch (t: Throwable) {
        NimDiscoveryResult(
          models = emptyList(),
          validated = false,
          errorMessage = NvidiaKeyProvider.redactSecrets(t.message ?: "Network error contacting AI endpoint"),
        )
      }
    }

  fun streamChatCompletion(
    model: NimModelInfo,
    messages: List<NimChatMessage>,
    tools: List<NimToolDefinition>,
    temperature: Float,
    maxTokens: Int,
    stream: Boolean,
    baseUrlOverride: String? = null,
    apiKeyOverride: String? = null,
    requiresApiKey: Boolean = true,
  ): Flow<NimStreamEvent> =
    flow {
      val effectiveBase = (baseUrlOverride ?: baseUrl).trimEnd('/')
      val apiKey = apiKeyOverride?.trim()?.takeIf { it.isNotBlank() } ?: if (baseUrlOverride == null) activeApiKey() else null
      if (apiKey.isNullOrBlank() && requiresApiKey) {
        emit(NimStreamEvent.Failed("AI API key is missing. Configure your key in AI Studio settings."))
        return@flow
      }

      var activeTools = tools
      var activeStream = stream
      var networkAttempt = 0
      var compatAdjustments = 0
      var lastError: NimStreamEvent.Failed? = null

      while (networkAttempt < 3 && compatAdjustments < 3) {
        val payload = buildChatPayload(model, messages, activeTools, temperature, maxTokens, activeStream)
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val reqBuilder =
          Request.Builder()
            .url("$effectiveBase/chat/completions")
            .header("Content-Type", "application/json")
            .header("Accept", if (activeStream) "text/event-stream" else "application/json")
            .post(body)
        if (!apiKey.isNullOrBlank()) {
          reqBuilder.header("Authorization", "Bearer $apiKey")
        }
        val request = reqBuilder.build()

        try {
          val response = httpClient.newCall(request).execute()
          response.use { resp ->
            if ((resp.code == 400 || resp.code == 422) && activeTools.isNotEmpty()) {
              activeTools = emptyList()
              compatAdjustments++
              return@use
            }
            if ((resp.code == 400 || resp.code == 422) && activeStream) {
              activeStream = false
              compatAdjustments++
              return@use
            }

            if (resp.code == 429 || resp.code >= 500) {
              networkAttempt++
              val retryAfterSec = resp.header("Retry-After")?.toLongOrNull() ?: (1L shl networkAttempt)
              val errStr = parseApiError(resp.body?.string().orEmpty(), resp.code)
              val failure = NimStreamEvent.Failed(errStr, resp.code)
              lastError = failure
              if (networkAttempt < 3) {
                delay(retryAfterSec.coerceIn(1L, 8L) * 1000L)
                return@use
              } else {
                emit(failure)
                return@flow
              }
            }

            if (!resp.isSuccessful) {
              val errStr = parseApiError(resp.body?.string().orEmpty(), resp.code)
              emit(NimStreamEvent.Failed(errStr, resp.code))
              return@flow
            }

            if (!activeStream) {
              val raw = resp.body?.string().orEmpty()
              emit(parseNonStreamingResponse(raw))
              return@flow
            }

            val source = resp.body?.source()
            if (source == null) {
              emit(NimStreamEvent.Failed("Empty response stream from AI endpoint"))
              return@flow
            }

            val contentBuilder = StringBuilder()
            val reasoningBuilder = StringBuilder()
            val rawFallback = StringBuilder()
            var sawSseData = false
            val toolCallAcc = linkedMapOf<Int, Triple<String?, String?, StringBuilder>>()
            var usage = NimUsage()

            while (!source.exhausted()) {
              val line = source.readUtf8Line() ?: break
              if (line.isBlank()) continue
              if (!line.startsWith("data:")) {
                if (!sawSseData && !line.startsWith("event:") && !line.startsWith(":")) {
                  rawFallback.append(line).append('\n')
                }
                continue
              }
              sawSseData = true
              val data = line.removePrefix("data:").trim()
              if (data == "[DONE]") break

              val chunk = runCatching { JSONObject(data) }.getOrNull() ?: continue
              chunk.optJSONObject("usage")?.let { u ->
                usage =
                  NimUsage(
                    promptTokens = u.optInt("prompt_tokens", usage.promptTokens),
                    completionTokens = u.optInt("completion_tokens", usage.completionTokens),
                    totalTokens = u.optInt("total_tokens", usage.totalTokens),
                  )
              }

              val choices = chunk.optJSONArray("choices") ?: continue
              if (choices.length() == 0) continue
              val choice0 = choices.optJSONObject(0) ?: continue
              val delta = choice0.optJSONObject("delta") ?: choice0.optJSONObject("message") ?: continue

              val textDelta =
                delta.optNonNullString("content", "")
                  .ifEmpty { delta.optNonNullString("text", "") }
              if (textDelta.isNotEmpty()) {
                contentBuilder.append(textDelta)
                emit(NimStreamEvent.Delta(textDelta))
              } else {
                val rDelta = delta.optNonNullString("reasoning_content", "")
                if (rDelta.isNotEmpty()) {
                  reasoningBuilder.append(rDelta)
                }
              }

              val tcArray = delta.optJSONArray("tool_calls")
              if (tcArray != null) {
                for (i in 0 until tcArray.length()) {
                  val tc = tcArray.optJSONObject(i) ?: continue
                  val idx = tc.optInt("index", i)
                  val id = tc.optNonNullString("id").takeIf { it.isNotEmpty() }
                  val fn = tc.optJSONObject("function")
                  val name = fn?.optNonNullString("name")?.takeIf { it.isNotEmpty() }
                  val argsDelta = fn?.optNonNullString("arguments").orEmpty()
                  val existing = toolCallAcc[idx]
                  if (existing == null) {
                    toolCallAcc[idx] = Triple(id, name, StringBuilder(argsDelta))
                  } else {
                    toolCallAcc[idx] =
                      Triple(
                        id ?: existing.first,
                        name ?: existing.second,
                        existing.third.append(argsDelta),
                      )
                  }
                }
              }
            }

            if (!sawSseData && contentBuilder.isEmpty() && toolCallAcc.isEmpty() && rawFallback.isNotEmpty()) {
              emit(parseNonStreamingResponse(rawFallback.toString()))
              return@flow
            }

            val finalToolCalls =
              toolCallAcc.values.mapNotNull { (id, name, args) ->
                if (name.isNullOrBlank()) null
                else NimToolCall(id = id ?: "call_${System.nanoTime()}", name = name, argumentsJson = args.toString())
              }
            val rawContent = contentBuilder.toString()
            // NEVER surface raw internal reasoning_content as an outgoing reply if content is empty
            val cleanedContent = HumanReplySanitizer.stripThinkingTags(rawContent)
            emit(
              NimStreamEvent.Completed(
                fullText = cleanedContent,
                toolCalls = finalToolCalls,
                usage = usage,
              )
            )
            return@flow
          }
        } catch (t: Throwable) {
          networkAttempt++
          lastError =
            NimStreamEvent.Failed(
              NvidiaKeyProvider.redactSecrets(t.message ?: "AI connection error"),
            )
          if (networkAttempt < 3) {
            delay((1L shl networkAttempt) * 650L)
          }
        }
      }
      emit(lastError ?: NimStreamEvent.Failed("AI request failed after retries"))
    }.flowOn(Dispatchers.IO)

  suspend fun transcribeAudioCloud(
    audioFile: File,
    asrModelId: String,
    mimeType: String = "audio/wav",
    multimodalAudioFallbackId: String = "microsoft/phi-4-multimodal-instruct",
    baseUrlOverride: String? = null,
    apiKeyOverride: String? = null,
  ): Result<String> =
    withContext(Dispatchers.IO) {
      val effectiveBase = (baseUrlOverride ?: baseUrl).trimEnd('/')
      val apiKey = apiKeyOverride?.trim()?.takeIf { it.isNotBlank() } ?: activeApiKey()
        ?: return@withContext Result.failure(IllegalStateException("AI API key not configured"))

      val targetModel = asrModelId.ifBlank { multimodalAudioFallbackId }
      val lowerModel = targetModel.lowercase()
      val isMultimodalChatAudio =
        lowerModel.contains("phi-4-multimodal") ||
          lowerModel.contains("qwen2-audio") ||
          lowerModel.contains("qwen-audio") ||
          lowerModel.contains("flamingo")

      if (isMultimodalChatAudio) {
        return@withContext transcribeViaMultimodalChat(audioFile, targetModel, mimeType, apiKey, effectiveBase)
      }

      val multipart =
        MultipartBody.Builder()
          .setType(MultipartBody.FORM)
          .addFormDataPart(
            "file",
            audioFile.name,
            audioFile.asRequestBody(mimeType.toMediaType()),
          )
          .apply {
            if (targetModel.isNotBlank()) addFormDataPart("model", targetModel)
          }
          .build()

      val req =
        Request.Builder()
          .url("$effectiveBase/audio/transcriptions")
          .header("Authorization", "Bearer $apiKey")
          .post(multipart)
          .build()

      try {
        httpClient.newCall(req).execute().use { resp ->
          val raw = resp.body?.string().orEmpty()
          if (resp.isSuccessful) {
            val text = runCatching { JSONObject(raw).optNonNullString("text") }.getOrNull() ?: raw
            val clean = text.trim()
            if (clean.isNotBlank()) return@withContext Result.success(clean)
          }
          if ((resp.code == 404 || resp.code == 405 || resp.code == 400) && effectiveBase.contains("nvidia")) {
            val fallback = transcribeViaMultimodalChat(audioFile, multimodalAudioFallbackId, mimeType, apiKey, effectiveBase)
            if (fallback.isSuccess) return@withContext fallback
          }
          Result.failure(IllegalStateException(parseApiError(raw, resp.code)))
        }
      } catch (t: Throwable) {
        Result.failure(IllegalStateException(NvidiaKeyProvider.redactSecrets(t.message ?: "Cloud transcription failed")))
      }
    }

  private fun transcribeViaMultimodalChat(
    audioFile: File,
    modelId: String,
    mimeType: String,
    apiKey: String,
    effectiveBase: String,
  ): Result<String> {
    return try {
      val rawBytes = audioFile.readBytes()
      if (rawBytes.isEmpty()) {
        return Result.failure(IllegalStateException("Audio file is empty"))
      }
      val b64 = android.util.Base64.encodeToString(rawBytes, android.util.Base64.NO_WRAP)
      val audioTag =
        if (b64.length <= 185_000) {
          "<audio src=\"data:$mimeType;base64,$b64\" />"
        } else {
          val assetId = uploadNvcfAudioAsset(rawBytes, mimeType, audioFile.name, apiKey)
          if (assetId != null) {
            "<audio src=\"data:$mimeType;asset_id,$assetId\" />"
          } else {
            val cappedBytes = rawBytes.copyOfRange(0, minOf(rawBytes.size, 136_000))
            val cappedB64 = android.util.Base64.encodeToString(cappedBytes, android.util.Base64.NO_WRAP)
            "<audio src=\"data:$mimeType;base64,$cappedB64\" />"
          }
        }

      val messages =
        JSONArray().put(
          JSONObject()
            .put("role", "user")
            .put(
              "content",
              "Transcribe the spoken audio accurately word-for-word. Output ONLY the spoken transcript with zero extra commentary.\n$audioTag",
            )
        )
      val payload =
        JSONObject()
          .put("model", modelId)
          .put("messages", messages)
          .put("temperature", 0.1)
          .put("max_tokens", 512)
          .put("stream", false)

      val req =
        Request.Builder()
          .url("$effectiveBase/chat/completions")
          .header("Authorization", "Bearer $apiKey")
          .header("Content-Type", "application/json")
          .header("Accept", "application/json")
          .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
          .build()

      httpClient.newCall(req).execute().use { resp ->
        val raw = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
          return Result.failure(IllegalStateException(parseApiError(raw, resp.code)))
        }
        val text =
          runCatching {
            JSONObject(raw)
              .optJSONArray("choices")
              ?.optJSONObject(0)
              ?.optJSONObject("message")
              ?.optNonNullString("content")
          }.getOrNull().orEmpty().trim()
        if (text.isBlank()) Result.failure(IllegalStateException("Cloud audio model returned empty transcript"))
        else Result.success(text)
      }
    } catch (t: Throwable) {
      Result.failure(IllegalStateException(NvidiaKeyProvider.redactSecrets(t.message ?: "Multimodal audio transcription error")))
    }
  }

  private fun uploadNvcfAudioAsset(
    bytes: ByteArray,
    mimeType: String,
    fileName: String,
    apiKey: String,
  ): String? {
    return runCatching {
      val createBody =
        JSONObject()
          .put("contentType", mimeType)
          .put("description", "ClawLink voice note $fileName")
          .toString()
          .toRequestBody("application/json".toMediaType())
      val createReq =
        Request.Builder()
          .url("https://api.nvcf.nvidia.com/v2/nvcf/assets")
          .header("Authorization", "Bearer $apiKey")
          .header("Content-Type", "application/json")
          .post(createBody)
          .build()
      val (assetId, uploadUrl) =
        httpClient.newCall(createReq).execute().use { resp ->
          if (!resp.isSuccessful) return null
          val obj = JSONObject(resp.body?.string().orEmpty())
          obj.optNonNullString("assetId") to obj.optNonNullString("uploadUrl")
        }
      if (assetId.isBlank() || uploadUrl.isBlank()) return null
      val putReq =
        Request.Builder()
          .url(uploadUrl)
          .header("x-amz-meta-nvcf-asset-description", "ClawLink voice note")
          .header("Content-Type", mimeType)
          .put(bytes.toRequestBody(mimeType.toMediaType()))
          .build()
      httpClient.newCall(putReq).execute().use { putResp ->
        if (putResp.isSuccessful) assetId else null
      }
    }.getOrNull()
  }

  private fun buildChatPayload(
    model: NimModelInfo,
    messages: List<NimChatMessage>,
    tools: List<NimToolDefinition>,
    temperature: Float,
    maxTokens: Int,
    stream: Boolean,
  ): JSONObject {
    val msgArray = JSONArray()
    for (msg in messages) {
      val obj = JSONObject()
      obj.put("role", msg.role)

      if (msg.imageBase64Jpegs.isNotEmpty() && model.supportsVision) {
        val parts = JSONArray()
        if (!msg.content.isNullOrBlank()) {
          parts.put(JSONObject().put("type", "text").put("text", msg.content))
        }
        msg.imageBase64Jpegs.forEach { b64 ->
          parts.put(
            JSONObject()
              .put("type", "image_url")
              .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
          )
        }
        obj.put("content", parts)
      } else {
        obj.put("content", msg.content ?: "")
      }

      if (msg.toolCalls.isNotEmpty()) {
        val tcArr = JSONArray()
        msg.toolCalls.forEach { tc ->
          tcArr.put(
            JSONObject()
              .put("id", tc.id)
              .put("type", "function")
              .put(
                "function",
                JSONObject()
                  .put("name", tc.name)
                  .put("arguments", tc.argumentsJson),
              )
          )
        }
        obj.put("tool_calls", tcArr)
      }
      if (!msg.toolCallId.isNullOrBlank()) {
        obj.put("tool_call_id", msg.toolCallId)
      }
      msgArray.put(obj)
    }

    val effectiveMaxTokens =
      if (NvidiaModelCapabilityDetector.isReasoningModel(model.id)) maxOf(maxTokens, 2048)
      else maxTokens

    val root =
      JSONObject()
        .put("model", model.id)
        .put("messages", msgArray)
        .put("temperature", temperature.toDouble())
        .put("max_tokens", effectiveMaxTokens)
        .put("stream", stream && model.supportsStreaming)

    if (stream && model.supportsStreaming) {
      root.put("stream_options", JSONObject().put("include_usage", true))
    }

    if (tools.isNotEmpty() && model.supportsToolCalling) {
      val toolArray = JSONArray()
      tools.forEach { def ->
        toolArray.put(
          JSONObject()
            .put("type", "function")
            .put(
              "function",
              JSONObject()
                .put("name", def.name)
                .put("description", def.description)
                .put("parameters", def.parametersSchemaJson),
            )
        )
      }
      root.put("tools", toolArray)
      root.put("tool_choice", "auto")
    }
    return root
  }

  private fun parseNonStreamingResponse(raw: String): NimStreamEvent {
    val json = runCatching { JSONObject(raw) }.getOrNull()
      ?: return NimStreamEvent.Failed("Malformed JSON response from AI endpoint")
    val usageObj = json.optJSONObject("usage")
    val usage =
      if (usageObj != null) {
        NimUsage(
          promptTokens = usageObj.optInt("prompt_tokens"),
          completionTokens = usageObj.optInt("completion_tokens"),
          totalTokens = usageObj.optInt("total_tokens"),
        )
      } else NimUsage()

    val choice = json.optJSONArray("choices")?.optJSONObject(0)
      ?: return NimStreamEvent.Failed("Missing choices in AI response")
    val message = choice.optJSONObject("message")
      ?: return NimStreamEvent.Failed("Missing message in AI response")
    val rawContent = message.optNonNullString("content", "")
    val tcArr = message.optJSONArray("tool_calls")
    val toolCalls = mutableListOf<NimToolCall>()
    if (tcArr != null) {
      for (i in 0 until tcArr.length()) {
        val tc = tcArr.optJSONObject(i) ?: continue
        val fn = tc.optJSONObject("function") ?: continue
        val fnName = fn.optNonNullString("name")
        if (fnName.isNotBlank()) {
          toolCalls.add(
            NimToolCall(
              id = tc.optNonNullString("id", "call_$i"),
              name = fnName,
              argumentsJson = fn.optNonNullString("arguments", "{}"),
            )
          )
        }
      }
    }
    // NEVER fall back to reasoning_content — internal scratchpad must never become a chat reply
    val content = HumanReplySanitizer.stripThinkingTags(rawContent)
    return NimStreamEvent.Completed(content, toolCalls, usage)
  }

  private fun parseApiError(body: String, code: Int): String {
    val msg =
      runCatching {
        val obj = JSONObject(body)
        obj.optJSONObject("error")?.optNonNullString("message")?.takeIf { it.isNotBlank() }
          ?: obj.optNonNullString("detail").takeIf { it.isNotBlank() }
          ?: obj.optNonNullString("message").takeIf { it.isNotBlank() }
      }.getOrNull()
    return NvidiaKeyProvider.redactSecrets("HTTP $code: ${msg ?: body.take(240).ifBlank { "Request rejected" }}")
  }

  companion object {
    const val DEFAULT_BASE_URL = "https://integrate.api.nvidia.com/v1"
  }
}

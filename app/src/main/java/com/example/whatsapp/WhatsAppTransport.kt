package com.example.whatsapp

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * WhatsAppTransport: Low-level OkHttp TLS WebSocket + Noise_XX_25519_AESGCM_SHA256
 * binary stanza transport for wss://web.whatsapp.com/ws/chat.
 */
class WhatsAppTransport(
  private val wsUrl: String = DEFAULT_WS_URL,
  private val okHttpClient: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(0, TimeUnit.MILLISECONDS)
      .pingInterval(25, TimeUnit.SECONDS)
      .build(),
) {

  interface Listener {
    fun onSocketOpened()
    fun onNoiseEstablished()
    fun onNodeReceived(node: WABinaryNode)
    fun onTransportError(reason: String, code: Int? = null)
    fun onSocketClosed(code: Int, reason: String)
  }

  private var webSocket: WebSocket? = null
  private var noise: NoiseXXHandshake? = null
  private var handshakeDone = false
  private var activeGeneration = 0L
  private val frameBuffer = mutableListOf<Byte>()
  private val stanzaCounter = AtomicInteger(1)
  private var currentClientPayload: ByteArray = ByteArray(0)
  private var listener: Listener? = null

  fun nextStanzaId(prefix: String = "cl"): String {
    return "$prefix-${System.currentTimeMillis()}-${stanzaCounter.getAndIncrement()}"
  }

  @Synchronized
  fun isHandshakeReady(): Boolean = handshakeDone && webSocket != null

  @Synchronized
  fun connect(
    staticPrivateKey: ByteArray,
    staticPublicKey: ByteArray,
    routingInfo: ByteArray?,
    clientPayload: ByteArray,
    listener: Listener,
  ) {
    disconnectInternal()
    val gen = ++activeGeneration
    this.listener = listener
    this.currentClientPayload = clientPayload
    this.handshakeDone = false
    this.frameBuffer.clear()

    val noiseEngine =
      NoiseXXHandshake(
        staticPrivateKey = staticPrivateKey,
        staticPublicKey = staticPublicKey,
        routingInfo = routingInfo,
      )
    this.noise = noiseEngine

    val request =
      Request.Builder()
        .url(wsUrl)
        .header("Origin", "https://web.whatsapp.com")
        .header(
          "User-Agent",
          "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        )
        .build()

    webSocket =
      okHttpClient.newWebSocket(
        request,
        object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@WhatsAppTransport) {
              if (gen != activeGeneration || webSocket !== this@WhatsAppTransport.webSocket) return
              val intro = noiseEngine.buildIntroAndClientHello()
              webSocket.send(intro.toByteString())
            }
            listener.onSocketOpened()
          }

          override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleIncomingBytes(gen, webSocket, bytes.toByteArray())
          }

          override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val shouldNotify =
              synchronized(this@WhatsAppTransport) {
                if (gen != activeGeneration || webSocket !== this@WhatsAppTransport.webSocket) {
                  false
                } else {
                  this@WhatsAppTransport.webSocket = null
                  this@WhatsAppTransport.handshakeDone = false
                  true
                }
              }
            if (shouldNotify) {
              listener.onTransportError(
                reason = t.message ?: "WebSocket connection failed",
                code = response?.code,
              )
            }
          }

          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val shouldNotify =
              synchronized(this@WhatsAppTransport) {
                if (gen != activeGeneration || webSocket !== this@WhatsAppTransport.webSocket) {
                  false
                } else {
                  this@WhatsAppTransport.webSocket = null
                  this@WhatsAppTransport.handshakeDone = false
                  true
                }
              }
            if (shouldNotify) {
              listener.onSocketClosed(code, reason)
            }
          }
        },
      )
  }

  private fun handleIncomingBytes(gen: Long, socket: WebSocket, chunk: ByteArray) {
    val dispatchedNodes = mutableListOf<WABinaryNode>()
    var notifyNoiseReady = false
    var errorToReport: String? = null
    val activeListener: Listener?

    synchronized(this) {
      if (gen != activeGeneration || socket !== webSocket) return
      val activeNoise = noise ?: return
      activeListener = listener ?: return
      for (b in chunk) frameBuffer.add(b)
      val frames = NoiseXXHandshake.splitLengthPrefixedFrames(frameBuffer)
      for (frame in frames) {
        if (!handshakeDone) {
          try {
            val finishFrame =
              activeNoise.processServerHelloAndBuildClientFinish(
                serverHelloPayload = frame,
                clientPayloadBytes = currentClientPayload,
              )
            handshakeDone = true
            socket.send(finishFrame.toByteString())
            notifyNoiseReady = true
          } catch (t: Throwable) {
            errorToReport = "Noise handshake failed: ${t.message}"
            disconnectInternal()
            break
          }
        } else {
          try {
            val decrypted = activeNoise.decodeTransportFrame(frame)
            val node = WABinaryCodec.decodeFrame(decrypted)
            dispatchedNodes.add(node)
          } catch (t: Throwable) {
            errorToReport = "Frame decode error: ${t.message}"
            break
          }
        }
      }
    }

    if (notifyNoiseReady) {
      activeListener?.onNoiseEstablished()
    }
    dispatchedNodes.forEach { activeListener?.onNodeReceived(it) }
    errorToReport?.let { activeListener?.onTransportError(it) }
  }

  @Synchronized
  fun sendNode(node: WABinaryNode): Boolean {
    val ws = webSocket ?: return false
    val activeNoise = noise ?: return false
    if (!handshakeDone) return false
    return try {
      val encodedNode = WABinaryCodec.encodeFrame(node)
      val encryptedFrame = activeNoise.encodeTransportFrame(encodedNode)
      ws.send(encryptedFrame.toByteString())
    } catch (_: Throwable) {
      false
    }
  }

  @Synchronized
  fun disconnect() {
    disconnectInternal()
  }

  private fun disconnectInternal() {
    activeGeneration++
    val oldSocket = webSocket
    webSocket = null
    noise = null
    handshakeDone = false
    frameBuffer.clear()
    try {
      oldSocket?.close(1000, "Client shutdown")
    } catch (_: Throwable) {
    }
  }

  companion object {
    const val DEFAULT_WS_URL = "wss://web.whatsapp.com/ws/chat"
  }
}

package com.example.whatsapp

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * WhatsApp Media HKDF key expansion, AES-256-CBC + HMAC-SHA256 decryption/encryption,
 * and CDN download/upload helper.
 */
object WhatsAppMediaCrypto {

  data class ExpandedMediaKeys(
    val iv: ByteArray,
    val cipherKey: ByteArray,
    val macKey: ByteArray,
    val refKey: ByteArray,
  )

  data class EncryptedUploadPayload(
    val mediaKey: ByteArray,
    val encryptedBytes: ByteArray,
    val fileSha256: ByteArray,
    val fileEncSha256: ByteArray,
    val originalLength: Long,
  )

  fun hkdfMediaInfo(mediaKind: String): String =
    when (mediaKind.uppercase()) {
      "IMAGE", "STICKER" -> "WhatsApp Image Keys"
      "AUDIO", "PTT" -> "WhatsApp Audio Keys"
      "VIDEO" -> "WhatsApp Video Keys"
      "DOCUMENT" -> "WhatsApp Document Keys"
      else -> "WhatsApp Image Keys"
    }

  fun expandMediaKey(mediaKey: ByteArray, mediaKind: String): ExpandedMediaKeys {
    val info = hkdfMediaInfo(mediaKind).toByteArray(Charsets.UTF_8)
    val prk = NoiseXXHandshake.hmacSha256(ByteArray(32), mediaKey)
    val expanded = ByteArrayOutputStream()
    var prev = ByteArray(0)
    var counter = 1
    while (expanded.size() < 112) {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(prk, "HmacSHA256"))
      mac.update(prev)
      mac.update(info)
      mac.update(counter.toByte())
      prev = mac.doFinal()
      expanded.write(prev)
      counter++
    }
    val bytes = expanded.toByteArray()
    return ExpandedMediaKeys(
      iv = bytes.copyOfRange(0, 16),
      cipherKey = bytes.copyOfRange(16, 48),
      macKey = bytes.copyOfRange(48, 80),
      refKey = bytes.copyOfRange(80, 112),
    )
  }

  fun decryptMediaBytes(encryptedWithMac: ByteArray, mediaKey: ByteArray, mediaKind: String): ByteArray {
    require(encryptedWithMac.size > 10) { "Encrypted media payload too small" }
    val keys = expandMediaKey(mediaKey, mediaKind)
    val fileData = encryptedWithMac.copyOfRange(0, encryptedWithMac.size - 10)
    val expectedMac = encryptedWithMac.copyOfRange(encryptedWithMac.size - 10, encryptedWithMac.size)

    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(keys.macKey, "HmacSHA256"))
    mac.update(keys.iv)
    mac.update(fileData)
    val computedMac = mac.doFinal().copyOfRange(0, 10)
    require(computedMac.contentEquals(expectedMac)) { "WhatsApp media HMAC verification failed" }

    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.cipherKey, "AES"), IvParameterSpec(keys.iv))
    return cipher.doFinal(fileData)
  }

  fun encryptMediaBytes(plainBytes: ByteArray, mediaKind: String): EncryptedUploadPayload {
    val mediaKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val keys = expandMediaKey(mediaKey, mediaKind)

    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.cipherKey, "AES"), IvParameterSpec(keys.iv))
    val encFile = cipher.doFinal(plainBytes)

    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(keys.macKey, "HmacSHA256"))
    mac.update(keys.iv)
    mac.update(encFile)
    val mac10 = mac.doFinal().copyOfRange(0, 10)

    val combined = encFile + mac10
    val sha256 = MessageDigest.getInstance("SHA-256")
    return EncryptedUploadPayload(
      mediaKey = mediaKey,
      encryptedBytes = combined,
      fileSha256 = sha256.digest(plainBytes),
      fileEncSha256 = sha256.digest(combined),
      originalLength = plainBytes.size.toLong(),
    )
  }

  fun downloadAndSaveDecryptedMedia(
    context: Context,
    httpClient: OkHttpClient,
    url: String,
    directPath: String,
    mediaKey: ByteArray,
    mediaKind: String,
    fileExtension: String,
    messageId: String,
  ): File? {
    val candidates = buildList {
      if (directPath.isNotBlank()) {
        val cleanPath = if (directPath.startsWith("/")) directPath else "/$directPath"
        add("https://mmg.whatsapp.net$cleanPath")
        add("https://media.fna.whatsapp.net$cleanPath")
      }
      if (url.startsWith("https://") && url !in this) {
        add(url)
      }
    }
    if (candidates.isEmpty()) return null
    val dir = File(context.filesDir, "wa_media").apply { mkdirs() }
    val safeId = messageId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)
    val outFile = File(dir, "${safeId}_full.$fileExtension")

    for (targetUrl in candidates) {
      val downloaded =
        runCatching {
          val req =
            Request.Builder()
              .url(targetUrl)
              .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/134.0.0.0 Safari/537.36")
              .header("Origin", "https://web.whatsapp.com")
              .header("Referer", "https://web.whatsapp.com/")
              .get()
              .build()
          httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val encBytes = resp.body?.bytes() ?: return@use null
            if (encBytes.size <= 16) return@use null
            val plainBytes = decryptMediaBytes(encBytes, mediaKey, mediaKind)
            if (plainBytes.isEmpty()) return@use null
            outFile.writeBytes(plainBytes)
            outFile
          }
        }.getOrNull()
      if (downloaded != null && downloaded.exists() && downloaded.length() > 0L) {
        return downloaded
      }
    }
    return null
  }

  fun uploadEncryptedMedia(
    httpClient: OkHttpClient,
    uploadUrl: String,
    encryptedBytes: ByteArray,
  ): Boolean {
    val req =
      Request.Builder()
        .url(uploadUrl)
        .post(encryptedBytes.toRequestBody("application/octet-stream".toMediaType()))
        .build()
    httpClient.newCall(req).execute().use { resp ->
      return resp.isSuccessful
    }
  }
}

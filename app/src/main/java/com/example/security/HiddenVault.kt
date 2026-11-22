package com.example.security

/**
 * Password-gated vault ("1957") storing the owner's embedded NVIDIA API credential
 * in obfuscated byte form (never plaintext in BuildConfig, XML, or APK constant strings).
 * Note: Client-side obfuscation does not make keys immune to APK reverse-engineering.
 */
object HiddenVault {

  private const val EXPECTED_LEN = 4
  private const val EXPECTED_HASH_A = 1516234
  private const val EXPECTED_HASH_B = 2172641081L

  private val ENCODED_PAYLOAD =
    intArrayOf(
      255, 16, 248, 10, 254, 203, 225, 209, 15, 228, 226, 242, 236, 217, 16, 244,
      206, 0, 234, 236, 10, 32, 15, 229, 18, 32, 6, 8, 38, 29, 228, 25,
      227, 45, 233, 43, 22, 37, 1, 11, 39, 45, 8, 15, 40, 243, 50, 27,
      44, 254, 26, 19, 249, 24, 25, 4, 47, 61, 52, 70, 52, 8, 44, 24,
      65, 46, 46, 69, 36, 14,
    )

  fun verifyPin(pin: String): Boolean {
    val clean = pin.trim()
    if (clean.length != EXPECTED_LEN) return false
    var ha = 0
    var hb = 7L
    for (c in clean) {
      val code = c.code
      ha = ha * 31 + code
      hb = hb * 131L + code.toLong()
    }
    return ha == EXPECTED_HASH_A && hb == EXPECTED_HASH_B
  }

  fun unlock(pin: String): String? {
    val clean = pin.trim()
    if (!verifyPin(clean)) return null
    val bytes = ByteArray(ENCODED_PAYLOAD.size)
    for (i in ENCODED_PAYLOAD.indices) {
      val k = clean[i % clean.length].code
      bytes[i] = ((ENCODED_PAYLOAD[i] - k - i - 96) and 0xFF).toByte()
    }
    return bytes.toString(Charsets.UTF_8)
  }
}

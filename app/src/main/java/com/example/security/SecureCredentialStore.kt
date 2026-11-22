package com.example.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.BuildConfig
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.ecc.Curve
import org.whispersystems.libsignal.ecc.ECKeyPair
import org.whispersystems.libsignal.state.SignedPreKeyRecord
import org.whispersystems.libsignal.util.KeyHelper
import java.security.SecureRandom

data class WhatsAppAuthCredentials(
  val noisePrivateKey: ByteArray,
  val noisePublicKey: ByteArray,
  val identityPrivateKey: ByteArray,
  val identityPublicKey: ByteArray, // 33 bytes (0x05 + 32-byte key) for Signal
  val identityRawPublicKey: ByteArray, // 32 bytes for ADV / QR
  val signedPreKeyId: Int,
  val signedPreKeyPrivate: ByteArray,
  val signedPreKeyPublic: ByteArray,
  val signedPreKeySignature: ByteArray,
  val registrationId: Int,
  val advSecretKey: ByteArray,
  val meJid: String?,
  val meLid: String?,
  val meName: String?,
  val platform: String?,
  val routingInfo: ByteArray?,
  val accountSignatureKey: ByteArray?,
  val accountSignature: ByteArray?,
  val deviceSignature: ByteArray?,
  val accountDetails: ByteArray?,
  val nextPreKeyId: Int,
) {
  val isRegistered: Boolean
    get() = !meJid.isNullOrBlank()

  fun toSignalIdentityKeyPair(): IdentityKeyPair {
    val pub = Curve.decodePoint(identityPublicKey, 0)
    val priv = Curve.decodePrivatePoint(identityPrivateKey)
    return IdentityKeyPair(IdentityKey(pub), priv)
  }

  fun toSignedPreKeyRecord(): SignedPreKeyRecord {
    val pub = Curve.decodePoint(signedPreKeyPublic, 0)
    val priv = Curve.decodePrivatePoint(signedPreKeyPrivate)
    return SignedPreKeyRecord(
      signedPreKeyId,
      System.currentTimeMillis(),
      ECKeyPair(pub, priv),
      signedPreKeySignature,
    )
  }
}

class SecureCredentialStore(context: Context) {

  private val prefs: SharedPreferences = createEncryptedPrefs(context)

  private fun createEncryptedPrefs(context: Context): SharedPreferences {
    return try {
      val masterKey =
        MasterKey.Builder(context)
          .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
          .build()
      EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
      )
    } catch (_: Throwable) {
      // Fallback only for JVM Robolectric environments lacking Android Keystore hardware
      context.getSharedPreferences("${PREFS_NAME}_test", Context.MODE_PRIVATE)
    }
  }

  @Synchronized
  fun getOrCreateCredentials(): WhatsAppAuthCredentials {
    val existingNoisePriv = getBytes(KEY_NOISE_PRIV)
    val existingIdentityPriv = getBytes(KEY_ID_PRIV)
    if (existingNoisePriv != null && existingIdentityPriv != null) {
      return WhatsAppAuthCredentials(
        noisePrivateKey = existingNoisePriv,
        noisePublicKey = getBytes(KEY_NOISE_PUB)!!,
        identityPrivateKey = existingIdentityPriv,
        identityPublicKey = getBytes(KEY_ID_PUB)!!,
        identityRawPublicKey = getBytes(KEY_ID_RAW_PUB)!!,
        signedPreKeyId = prefs.getInt(KEY_SPK_ID, 1),
        signedPreKeyPrivate = getBytes(KEY_SPK_PRIV)!!,
        signedPreKeyPublic = getBytes(KEY_SPK_PUB)!!,
        signedPreKeySignature = getBytes(KEY_SPK_SIG)!!,
        registrationId = prefs.getInt(KEY_REG_ID, 1),
        advSecretKey = getBytes(KEY_ADV_SECRET)!!,
        meJid = prefs.getString(KEY_ME_JID, null),
        meLid = prefs.getString(KEY_ME_LID, null),
        meName = prefs.getString(KEY_ME_NAME, null),
        platform = prefs.getString(KEY_PLATFORM, null),
        routingInfo = getBytes(KEY_ROUTING_INFO),
        accountSignatureKey = getBytes(KEY_ACC_SIG_KEY),
        accountSignature = getBytes(KEY_ACC_SIG),
        deviceSignature = getBytes(KEY_DEV_SIG),
        accountDetails = getBytes(KEY_ACC_DETAILS),
        nextPreKeyId = prefs.getInt(KEY_NEXT_PREKEY_ID, 1),
      )
    }
    return generateFreshCredentials()
  }

  @Synchronized
  fun generateFreshCredentials(): WhatsAppAuthCredentials {
    val noiseKp = Curve.generateKeyPair()
    val noisePubRaw = stripKeyPrefix(noiseKp.publicKey.serialize())
    val noisePrivRaw = noiseKp.privateKey.serialize()

    val identityKp = KeyHelper.generateIdentityKeyPair()
    val identityPubSerialized = identityKp.publicKey.serialize() // 33 bytes (0x05 + 32)
    val identityPubRaw = stripKeyPrefix(identityPubSerialized)
    val identityPrivRaw = identityKp.privateKey.serialize()

    val signedPreKeyId = 1
    val signedPreKey = KeyHelper.generateSignedPreKey(identityKp, signedPreKeyId)
    val spkPubSerialized = signedPreKey.keyPair.publicKey.serialize()
    val spkPrivRaw = signedPreKey.keyPair.privateKey.serialize()

    val regId = KeyHelper.generateRegistrationId(false)
    val advSecret = ByteArray(32).also { SecureRandom().nextBytes(it) }

    val creds =
      WhatsAppAuthCredentials(
        noisePrivateKey = noisePrivRaw,
        noisePublicKey = noisePubRaw,
        identityPrivateKey = identityPrivRaw,
        identityPublicKey = identityPubSerialized,
        identityRawPublicKey = identityPubRaw,
        signedPreKeyId = signedPreKeyId,
        signedPreKeyPrivate = spkPrivRaw,
        signedPreKeyPublic = spkPubSerialized,
        signedPreKeySignature = signedPreKey.signature,
        registrationId = regId,
        advSecretKey = advSecret,
        meJid = null,
        meLid = null,
        meName = null,
        platform = null,
        routingInfo = null,
        accountSignatureKey = null,
        accountSignature = null,
        deviceSignature = null,
        accountDetails = null,
        nextPreKeyId = 1,
      )
    saveCredentials(creds)
    return creds
  }

  @Synchronized
  fun saveCredentials(creds: WhatsAppAuthCredentials) {
    prefs
      .edit()
      .putBytes(KEY_NOISE_PRIV, creds.noisePrivateKey)
      .putBytes(KEY_NOISE_PUB, creds.noisePublicKey)
      .putBytes(KEY_ID_PRIV, creds.identityPrivateKey)
      .putBytes(KEY_ID_PUB, creds.identityPublicKey)
      .putBytes(KEY_ID_RAW_PUB, creds.identityRawPublicKey)
      .putInt(KEY_SPK_ID, creds.signedPreKeyId)
      .putBytes(KEY_SPK_PRIV, creds.signedPreKeyPrivate)
      .putBytes(KEY_SPK_PUB, creds.signedPreKeyPublic)
      .putBytes(KEY_SPK_SIG, creds.signedPreKeySignature)
      .putInt(KEY_REG_ID, creds.registrationId)
      .putBytes(KEY_ADV_SECRET, creds.advSecretKey)
      .putString(KEY_ME_JID, creds.meJid)
      .putString(KEY_ME_LID, creds.meLid)
      .putString(KEY_ME_NAME, creds.meName)
      .putString(KEY_PLATFORM, creds.platform)
      .putBytes(KEY_ROUTING_INFO, creds.routingInfo)
      .putBytes(KEY_ACC_SIG_KEY, creds.accountSignatureKey)
      .putBytes(KEY_ACC_SIG, creds.accountSignature)
      .putBytes(KEY_DEV_SIG, creds.deviceSignature)
      .putBytes(KEY_ACC_DETAILS, creds.accountDetails)
      .putInt(KEY_NEXT_PREKEY_ID, creds.nextPreKeyId)
      .apply()
  }

  @Synchronized
  fun clearSessionKeepIdentity() {
    prefs
      .edit()
      .remove(KEY_ME_JID)
      .remove(KEY_ME_LID)
      .remove(KEY_ME_NAME)
      .remove(KEY_PLATFORM)
      .remove(KEY_ROUTING_INFO)
      .remove(KEY_ACC_SIG_KEY)
      .remove(KEY_ACC_SIG)
      .remove(KEY_DEV_SIG)
      .remove(KEY_ACC_DETAILS)
      .apply()
  }

  @Synchronized
  fun saveNvidiaApiKey(apiKey: String) {
    val clean = apiKey.trim()
    if (clean.isEmpty()) {
      prefs.edit().remove(KEY_NVIDIA_API_KEY).apply()
    } else {
      prefs
        .edit()
        .putString(KEY_NVIDIA_API_KEY, clean)
        .putBoolean(KEY_BUILDCONFIG_KEY_DISABLED, false)
        .apply()
      NvidiaKeyProvider.setRuntimeKeyHint(clean)
    }
  }

  @Synchronized
  fun getNvidiaApiKey(): String? {
    val stored = prefs.getString(KEY_NVIDIA_API_KEY, null)?.trim()
    if (!stored.isNullOrBlank()) {
      NvidiaKeyProvider.setRuntimeKeyHint(stored)
      return stored
    }
    if (prefs.getBoolean(KEY_BUILDCONFIG_KEY_DISABLED, false)) {
      return null
    }
    return NvidiaKeyProvider.getBuildConfigApiKeyOrNull()?.also {
      NvidiaKeyProvider.setRuntimeKeyHint(it)
    }
  }

  @Synchronized
  fun clearNvidiaApiKey() {
    prefs
      .edit()
      .remove(KEY_NVIDIA_API_KEY)
      .putBoolean(KEY_BUILDCONFIG_KEY_DISABLED, true)
      .apply()
    NvidiaKeyProvider.setRuntimeKeyHint(null)
  }

  @Synchronized
  fun getMaskedNvidiaKeySummary(): String {
    val key = getNvidiaApiKey() ?: return "Not configured"
    val suffix = if (key.length >= 4) key.takeLast(4) else "****"
    val source =
      if (!prefs.getString(KEY_NVIDIA_API_KEY, null).isNullOrBlank()) "Encrypted Keystore"
      else "APK BuildConfig"
    return "nvapi-••••$suffix ($source)"
  }

  @Synchronized
  fun saveProviderApiKey(providerKey: String, apiKey: String) {
    if (providerKey.equals("NVIDIA_NIM", ignoreCase = true)) {
      saveNvidiaApiKey(apiKey)
      return
    }
    val prefKey = KEY_PROVIDER_PREFIX + providerKey.uppercase()
    val clean = apiKey.trim()
    if (clean.isEmpty()) {
      prefs.edit().remove(prefKey).apply()
    } else {
      prefs.edit().putString(prefKey, clean).apply()
      NvidiaKeyProvider.registerRuntimeSecret(clean)
    }
  }

  @Synchronized
  fun getProviderApiKey(providerKey: String): String? {
    if (providerKey.equals("NVIDIA_NIM", ignoreCase = true)) {
      return getNvidiaApiKey()
    }
    val stored = prefs.getString(KEY_PROVIDER_PREFIX + providerKey.uppercase(), null)?.trim()
    return stored?.takeIf { it.isNotBlank() }?.also { NvidiaKeyProvider.registerRuntimeSecret(it) }
  }

  @Synchronized
  fun getMaskedProviderKeySummary(providerKey: String): String {
    if (providerKey.equals("NVIDIA_NIM", ignoreCase = true)) return getMaskedNvidiaKeySummary()
    val key = getProviderApiKey(providerKey) ?: return "Not configured"
    val suffix = if (key.length >= 4) key.takeLast(4) else "****"
    return "••••$suffix (Encrypted)"
  }

  @Synchronized
  fun clearAll() {
    prefs.edit().clear().apply()
  }

  private fun SharedPreferences.Editor.putBytes(key: String, bytes: ByteArray?): SharedPreferences.Editor {
    if (bytes == null) remove(key)
    else putString(key, Base64.encodeToString(bytes, Base64.NO_WRAP))
    return this
  }

  private fun getBytes(key: String): ByteArray? {
    val raw = prefs.getString(key, null) ?: return null
    return Base64.decode(raw, Base64.NO_WRAP)
  }

  private fun stripKeyPrefix(serialized: ByteArray): ByteArray {
    return if (serialized.size == 33 && serialized[0] == 0x05.toByte()) {
      serialized.copyOfRange(1, 33)
    } else {
      serialized
    }
  }

  companion object {
    private const val PREFS_NAME = "clawlink_wa_secure_creds"
    private const val KEY_NOISE_PRIV = "noise_priv"
    private const val KEY_NOISE_PUB = "noise_pub"
    private const val KEY_ID_PRIV = "id_priv"
    private const val KEY_ID_PUB = "id_pub"
    private const val KEY_ID_RAW_PUB = "id_raw_pub"
    private const val KEY_SPK_ID = "spk_id"
    private const val KEY_SPK_PRIV = "spk_priv"
    private const val KEY_SPK_PUB = "spk_pub"
    private const val KEY_SPK_SIG = "spk_sig"
    private const val KEY_REG_ID = "reg_id"
    private const val KEY_ADV_SECRET = "adv_secret"
    private const val KEY_ME_JID = "me_jid"
    private const val KEY_ME_LID = "me_lid"
    private const val KEY_ME_NAME = "me_name"
    private const val KEY_PLATFORM = "platform"
    private const val KEY_ROUTING_INFO = "routing_info"
    private const val KEY_ACC_SIG_KEY = "acc_sig_key"
    private const val KEY_ACC_SIG = "acc_sig"
    private const val KEY_DEV_SIG = "dev_sig"
    private const val KEY_ACC_DETAILS = "acc_details"
    private const val KEY_NEXT_PREKEY_ID = "next_prekey_id"
    private const val KEY_NVIDIA_API_KEY = "nvidia_api_key"
    private const val KEY_BUILDCONFIG_KEY_DISABLED = "nvidia_buildconfig_disabled"
    private const val KEY_PROVIDER_PREFIX = "ai_provider_key_"
  }
}

object NvidiaKeyProvider {
  private const val PLACEHOLDER = "MY_NVIDIA_API_KEY"
  @Volatile private var activeRuntimeKey: String? = null
  private val extraRuntimeSecrets = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

  fun setRuntimeKeyHint(key: String?) {
    activeRuntimeKey = key?.takeIf { it.isNotBlank() && it != PLACEHOLDER }
  }

  fun registerRuntimeSecret(secret: String?) {
    val clean = secret?.trim()?.takeIf { it.length >= 6 && it != PLACEHOLDER } ?: return
    extraRuntimeSecrets.add(clean)
  }

  fun getBuildConfigApiKeyOrNull(): String? {
    val key = BuildConfig.NVIDIA_API_KEY.trim()
    return if (key.isBlank() || key == PLACEHOLDER) null else key
  }

  fun getApiKeyOrNull(): String? = activeRuntimeKey ?: getBuildConfigApiKeyOrNull()

  fun isConfigured(): Boolean = getApiKeyOrNull() != null

  fun statusSummary(): String {
    return if (isConfigured()) {
      "Configured (Encrypted)"
    } else {
      "API Key Missing"
    }
  }

  fun redactSecrets(text: String): String {
    var result = text
    (listOfNotNull(activeRuntimeKey, getBuildConfigApiKeyOrNull()) + extraRuntimeSecrets).forEach { secret ->
      if (secret.length >= 6) {
        result = result.replace(secret, "***REDACTED***")
      }
    }
    return result
      .replace(Regex("nvapi-[A-Za-z0-9_-]{8,}"), "nvapi-***REDACTED***")
      .replace(Regex("(sk-(?:or-|proj-)?[A-Za-z0-9_-]{12,}|gsk_[A-Za-z0-9_-]{12,})"), "***REDACTED***")
  }
}

package com.example

import android.app.Application
import com.example.agent.AssistantAgentOrchestrator
import com.example.agent.BusinessIntelligenceEngine
import com.example.agent.ClawAgentRuntime
import com.example.ai.AiProviderManager
import com.example.ai.NvidiaNimClient
import com.example.data.db.ClawDatabase
import com.example.data.prefs.AppPreferences
import com.example.data.repository.ClawRepository
import com.example.media.AndroidMediaIntelligence
import com.example.security.NvidiaKeyProvider
import com.example.security.SecureCredentialStore
import com.example.whatsapp.WASignalProtocolStore
import com.example.whatsapp.WhatsAppCrypto
import com.example.whatsapp.WhatsAppSessionManager

class ClawLinkApplication : Application() {

  lateinit var database: ClawDatabase
    private set

  lateinit var preferences: AppPreferences
    private set

  lateinit var credentialStore: SecureCredentialStore
    private set

  lateinit var repository: ClawRepository
    private set

  lateinit var signalStore: WASignalProtocolStore
    private set

  lateinit var whatsAppCrypto: WhatsAppCrypto
    private set

  lateinit var sessionManager: WhatsAppSessionManager
    private set

  lateinit var nimClient: NvidiaNimClient
    private set

  lateinit var aiProviderManager: AiProviderManager
    private set

  lateinit var businessEngine: BusinessIntelligenceEngine
    private set

  lateinit var mediaIntelligence: AndroidMediaIntelligence
    private set

  lateinit var agentRuntime: ClawAgentRuntime
    private set

  lateinit var assistantOrchestrator: AssistantAgentOrchestrator
    private set

  override fun onCreate() {
    super.onCreate()
    database = ClawDatabase.getInstance(this)
    preferences = AppPreferences(this)
    credentialStore = SecureCredentialStore(this)
    NvidiaKeyProvider.setRuntimeKeyHint(credentialStore.getNvidiaApiKey())
    repository = ClawRepository(database, preferences)
    signalStore = WASignalProtocolStore(credentialStore, database.signalKeyDao())
    whatsAppCrypto = WhatsAppCrypto(signalStore)
    sessionManager =
      WhatsAppSessionManager(
        context = this,
        credentialStore = credentialStore,
        repository = repository,
        crypto = whatsAppCrypto,
      )
    nimClient = NvidiaNimClient(credentialStore)
    aiProviderManager = AiProviderManager(credentialStore, nimClient)
    businessEngine = BusinessIntelligenceEngine()
    mediaIntelligence = AndroidMediaIntelligence(this, nimClient)
    agentRuntime =
      ClawAgentRuntime(
        context = this,
        repository = repository,
        sessionManager = sessionManager,
        nimClient = nimClient,
        mediaIntelligence = mediaIntelligence,
        aiProviderManager = aiProviderManager,
        businessEngine = businessEngine,
      )
    assistantOrchestrator =
      AssistantAgentOrchestrator(
        context = this,
        repository = repository,
        sessionManager = sessionManager,
        nimClient = nimClient,
        mediaIntelligence = mediaIntelligence,
        discoveredModels = agentRuntime.discoveredModels,
        aiProviderManager = aiProviderManager,
        businessEngine = businessEngine,
      )
    agentRuntime.start()
  }
}

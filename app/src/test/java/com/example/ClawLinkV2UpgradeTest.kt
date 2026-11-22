package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.agent.AgentTools
import com.example.agent.AssistantAgentOrchestrator
import com.example.agent.BusinessIntelligenceEngine
import com.example.agent.ClawAgentRuntime
import com.example.agent.GroupPermissionPolicy
import com.example.agent.HumanReplySanitizer
import com.example.agent.PermissionDecision
import com.example.agent.PersonMemoryExtractor
import com.example.agent.ReplyStatusTracker
import com.example.agent.TriggerSource
import com.example.ai.AiProviderManager
import com.example.ai.NimChatMessage
import com.example.ai.NvidiaModelCapabilityDetector
import com.example.ai.NvidiaNimClient
import com.example.data.db.ChatMessageEntity
import com.example.data.db.ClawDatabase
import com.example.data.db.ConversationEntity
import com.example.data.prefs.AgentSettings
import com.example.data.prefs.AiProviderType
import com.example.data.prefs.AppPreferences
import com.example.data.prefs.ContextStrategy
import com.example.data.prefs.LocalWhisperModel
import com.example.data.prefs.TranscriptionMode
import com.example.data.repository.ClawRepository
import com.example.media.AndroidMediaIntelligence
import com.example.security.PermissionTier
import com.example.security.SecureCredentialStore
import com.example.whatsapp.IncomingWhatsAppEvent
import com.example.whatsapp.WASignalProtocolStore
import com.example.whatsapp.WhatsAppCrypto
import com.example.whatsapp.WhatsAppSessionManager
import com.example.whatsapp.WhatsAppWebEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClawLinkV2UpgradeTest {

  private lateinit var context: Context
  private lateinit var db: ClawDatabase
  private lateinit var repository: ClawRepository
  private lateinit var credStore: SecureCredentialStore
  private lateinit var mockServer: MockWebServer

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, ClawDatabase::class.java).allowMainThreadQueries().build()
    repository = ClawRepository(db, AppPreferences(context))
    kotlinx.coroutines.runBlocking {
      repository.preferences.updateSettings { AgentSettings() }
    }
    credStore = SecureCredentialStore(context)
    credStore.clearAll()
    mockServer = MockWebServer()
    mockServer.start()
  }

  @After
  fun tearDown() {
    db.close()
    mockServer.shutdown()
  }

  @Test
  fun `GroupPermissionPolicy enforces autoReplyGroups OFF across all triggers while allowing DMs`() {
    val settingsGroupsOff = AgentSettings(autoReplyDirectChats = true, autoReplyGroups = false)
    val groupConv = ConversationEntity(chatJid = "120363001122@g.us", displayName = "Product Team", isGroup = true, autoReplyEnabled = true)
    val dmConv = ConversationEntity(chatJid = "15551234567@s.whatsapp.net", displayName = "Alex", isGroup = false, autoReplyEnabled = true)

    TriggerSource.entries.forEach { trigger ->
      val groupRes = GroupPermissionPolicy.evaluateOutgoingPermission(groupConv.chatJid, settingsGroupsOff, groupConv, trigger)
      assertTrue("Expected Blocked for $trigger", groupRes is PermissionDecision.Blocked)
      val blocked = groupRes as PermissionDecision.Blocked
      assertTrue(blocked.requiresGroupEnable)
      assertTrue(blocked.reason.contains("Group chatting", ignoreCase = true))

      val dmRes = GroupPermissionPolicy.evaluateOutgoingPermission(dmConv.chatJid, settingsGroupsOff, dmConv, trigger)
      assertTrue("Expected Allowed DM for $trigger", dmRes is PermissionDecision.Allowed)
    }

    val settingsGroupsOn = settingsGroupsOff.copy(autoReplyGroups = true)
    TriggerSource.entries.forEach { trigger ->
      val groupRes = GroupPermissionPolicy.evaluateOutgoingPermission(groupConv.chatJid, settingsGroupsOn, groupConv, trigger)
      assertTrue("Expected Allowed when groups ON for $trigger", groupRes is PermissionDecision.Allowed)
    }
  }

  @Test
  fun `AssistantAgentOrchestrator refuses group WhatsApp send when autoReplyGroups is OFF`() = runTest {
    credStore.saveNvidiaApiKey("nvapi-test-group-block-12345")
    repository.ensureConversation("120363999888@g.us", displayName = "", isGroup = true, chatTitle = "Founders Circle")
    repository.preferences.updateSettings {
      it.copy(
        activeProvider = AiProviderType.NVIDIA_NIM,
        autoReplyGroups = false,
        streamingEnabled = true,
        selectedModelId = "meta/llama-3.3-70b-instruct",
      )
    }

    // Tool call attempts to message the Founders Circle group
    mockServer.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "text/event-stream")
        .setBody(
          "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_grp\",\"type\":\"function\",\"function\":{\"name\":\"send_whatsapp_message\",\"arguments\":\"{\\\"contact_query\\\":\\\"Founders Circle\\\",\\\"message\\\":\\\"Hello team\\\"}\"}}]}}]}\n\n" +
            "data: [DONE]\n\n"
        )
    )
    mockServer.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "text/event-stream")
        .setBody(
          "data: {\"choices\":[{\"delta\":{\"content\":\"Group Chatting is currently turned OFF. Please turn on Group Chatting in AI Studio so I can message Founders Circle.\"}}]}\n\n" +
            "data: [DONE]\n\n"
        )
    )

    val client = NvidiaNimClient(credStore, baseUrl = mockServer.url("/v1").toString())
    val aiManager = AiProviderManager(credStore, client)
    val model = NvidiaModelCapabilityDetector.inferFromDiscoveredModel("meta/llama-3.3-70b-instruct", "meta")
    val signalStore = WASignalProtocolStore(credStore, db.signalKeyDao())
    val sessionMgr = WhatsAppSessionManager(context, credStore, repository, WhatsAppCrypto(signalStore))
    val orchestrator =
      AssistantAgentOrchestrator(
        context = context,
        repository = repository,
        sessionManager = sessionMgr,
        nimClient = client,
        mediaIntelligence = AndroidMediaIntelligence(context, client),
        discoveredModels = MutableStateFlow(listOf(model)),
        aiProviderManager = aiManager,
      )

    val reply = orchestrator.sendMessage("Tell the Founders Circle group hello")
    assertTrue(reply.textContent.contains("Group Chatting", ignoreCase = true))

    // Verify second request sent to AI included the blocked_by_permission tool payload
    mockServer.takeRequest()
    val secondReqBody = mockServer.takeRequest().body.readUtf8()
    assertTrue(secondReqBody.contains("blocked_by_permission"))
    assertTrue(secondReqBody.contains("Group Chatting"))
  }

  @Test
  fun `WhatsAppWebEngine and ClawRepository separate group chatTitle from senderName and parse mentions`() = runTest {
    val tmpDir = File(context.cacheDir, "test_group_sep").apply { deleteRecursively() }
    val groupMsgJson =
      """
      {
        "id": { "_serialized": "false_120363000555@g.us_3EB0GRP", "fromMe": false, "remote": "120363000555@g.us", "participant": "15559876543@c.us" },
        "from": "15559876543@c.us",
        "author": "15559876543@c.us",
        "notifyName": "Marcus",
        "chatTitle": "Design Ops Squad",
        "mentionedJidList": ["15550001111@c.us"],
        "quotedParticipant": "15550001111@c.us",
        "type": "chat",
        "body": "@15550001111 check this layout",
        "timestamp": 1700001111
      }
      """.trimIndent()

    val event = WhatsAppWebEngine.parseIncomingMessageJson(groupMsgJson, tmpDir)!!
    assertTrue(event.isGroup)
    assertEquals("120363000555@g.us", event.chatJid)
    assertEquals("Design Ops Squad", event.chatTitle)
    assertEquals("Marcus", event.senderName)
    assertEquals("15559876543@s.whatsapp.net", event.senderJid)
    assertEquals(listOf("15550001111@s.whatsapp.net"), event.mentionedJids)
    assertEquals("15550001111@s.whatsapp.net", event.quotedParticipantJid)

    repository.recordMessage(
      ChatMessageEntity(
        id = event.messageId,
        chatJid = event.chatJid,
        senderJid = event.senderJid,
        senderName = event.senderName,
        fromMe = false,
        timestamp = event.timestampMs,
        messageType = event.messageType,
        textContent = event.textContent,
      ),
      chatTitle = event.chatTitle,
    )
    val savedConv = repository.getConversation(event.chatJid)!!
    assertEquals("Design Ops Squad", savedConv.displayName)
    assertTrue(savedConv.lastMessagePreview.startsWith("Marcus:"))
    tmpDir.deleteRecursively()
  }

  @Test
  fun `summarizeConversation works without discoveredModels, includes attachment transcripts, and avoids null errors`() = runTest {
    credStore.saveNvidiaApiKey("nvapi-test-summary-key-999")
    repository.preferences.updateSettings {
      it.copy(selectedModelId = "meta/llama-3.3-70b-instruct")
    }

    val chatJid = "15554443333@s.whatsapp.net"
    repository.ensureConversation(chatJid, "Nora", isGroup = false)
    repository.recordMessage(
      ChatMessageEntity(
        id = "SUM_1",
        chatJid = chatJid,
        senderJid = chatJid,
        senderName = "Nora",
        fromMe = false,
        timestamp = 1_700_000_001_000L,
        messageType = "IMAGE",
        textContent = "[IMAGE] Contract page",
        mediaTranscription = "Signed SLA dated Nov 20 with $2,500 monthly retainer.",
      )
    )

    mockServer.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody("""{"choices":[{"message":{"content":"• Nora shared the signed SLA ($2,500/mo retainer)."}}]}""")
    )

    val client = NvidiaNimClient(credStore, baseUrl = mockServer.url("/v1").toString())
    val aiManager = AiProviderManager(credStore, client)
    val signalStore = WASignalProtocolStore(credStore, db.signalKeyDao())
    val sessionMgr = WhatsAppSessionManager(context, credStore, repository, WhatsAppCrypto(signalStore))
    val runtime =
      ClawAgentRuntime(
        context = context,
        repository = repository,
        sessionManager = sessionMgr,
        nimClient = client,
        mediaIntelligence = AndroidMediaIntelligence(context, client),
        aiProviderManager = aiManager,
      )
    // Intentionally leave discoveredModels empty!
    val summaryRes = runtime.summarizeConversation(chatJid)
    assertTrue(summaryRes.isSuccess)
    val summary = summaryRes.getOrThrow()
    assertTrue(summary.contains("SLA"))
    assertFalse(summary.contains("null", ignoreCase = true))

    val reqBody = mockServer.takeRequest().body.readUtf8()
    assertTrue(reqBody.contains("Signed SLA dated Nov 20"))

    // Empty chat should fail cleanly without "null" string
    val emptyRes = runtime.summarizeConversation("15550009999@s.whatsapp.net")
    assertTrue(emptyRes.isFailure)
    assertFalse((emptyRes.exceptionOrNull()?.message ?: "").equals("null", true))
  }

  @Test
  fun `vision refusal detector triggers backup vision model fallback automatically`() = runTest {
    assertTrue(NvidiaModelCapabilityDetector.detectVisionRefusal("I cannot see images or view visual content."))
    assertFalse(NvidiaModelCapabilityDetector.detectVisionRefusal("The photo shows a blue invoice for $450."))

    credStore.saveNvidiaApiKey("nvapi-vision-fallback-key")
    val client = NvidiaNimClient(credStore, baseUrl = mockServer.url("/v1").toString())
    val intel = AndroidMediaIntelligence(context, client)

    val tmpImg = File(context.cacheDir, "vision_retry.png")
    val bmp = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
    FileOutputStream(tmpImg).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bmp.recycle()

    // Candidate 1 refuses image; Candidate 2 succeeds
    mockServer.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody("""{"choices":[{"message":{"content":"I'm sorry, I cannot view images directly."}}]}""")
    )
    mockServer.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody("""{"choices":[{"message":{"content":"A bright cyan square test graphic."}}]}""")
    )

    val m1 = NvidiaModelCapabilityDetector.inferFromDiscoveredModel("primary/vision-alpha", "primary")
    val m2 = NvidiaModelCapabilityDetector.inferFromDiscoveredModel("backup/llama-3.2-90b-vision-instruct", "backup")

    val res = intel.summarizeImageWithVisionCandidates(tmpImg, "Describe", listOf(m1, m2))
    assertTrue(res.isSuccess)
    assertEquals("A bright cyan square test graphic.", res.getOrThrow())
    tmpImg.delete()
  }

  @Test
  fun `ClawRepository processing jobs and duplicate reply hash guard prevent double replies`() = runTest {
    val chatJid = "15557770000@s.whatsapp.net"
    val (firstClaim, job1) = repository.claimOrCreateProcessingJob("MSG_DUP_1", chatJid, "AUTO")
    assertTrue(firstClaim)
    assertEquals("DETECTED", job1.stage)

    val (secondClaim, _) = repository.claimOrCreateProcessingJob("MSG_DUP_1", chatJid, "AUTO")
    assertFalse(secondClaim)

    repository.updateProcessingJobStage(
      incomingMessageId = "MSG_DUP_1",
      chatJid = chatJid,
      stage = "CONFIRMED",
      outgoingMessageId = "OUT_1",
      replyHash = "hash_abc_123",
    )
    assertTrue(repository.hasRecentReplyWithHash(chatJid, "hash_abc_123", windowMs = 90_000L))
    assertFalse(repository.hasRecentReplyWithHash(chatJid, "different_hash", windowMs = 90_000L))
  }

  @Test
  fun `BusinessIntelligenceEngine classifies leads accurately from real Room messages and updates DB`() = runTest {
    val chatJid = "15556667777@s.whatsapp.net"
    val conv = repository.ensureConversation(chatJid, "Acme Buyer", isGroup = false)
    val msgs =
      listOf(
        ChatMessageEntity(
          id = "BIZ_1",
          chatJid = chatJid,
          senderJid = chatJid,
          senderName = "Acme Buyer",
          fromMe = false,
          timestamp = 1_700_002_000_000L,
          messageType = "TEXT",
          textContent = "Hi, how much is your enterprise wholesale pricing package? Can we get a quote?",
        )
      )
    msgs.forEach { repository.recordMessage(it) }

    val engine = BusinessIntelligenceEngine()
    val client = NvidiaNimClient(credStore, baseUrl = mockServer.url("/v1").toString())
    val aiManager = AiProviderManager(credStore, client)

    val result =
      engine.analyzeConversationWithAi(
        conversation = conv,
        messages = msgs,
        settings = AgentSettings(businessModeEnabled = true, businessName = "Acme Cloud"),
        discoveredModels = emptyList(),
        aiProviderManager = aiManager,
        repository = repository,
      )
    assertTrue(result.category in listOf("Important Opportunity", "Sales Inquiry"))
    assertTrue(result.leadScore >= 70)

    val updated = repository.getConversation(chatJid)!!
    assertEquals(result.category, updated.businessCategory)
    assertEquals(result.leadScore, updated.businessLeadScore)
    assertTrue(updated.businessIntent.isNotBlank())
  }

  @Test
  fun `AiProviderManager routes multi-provider fallback from unconfigured or failing primary`() = runTest {
    credStore.saveProviderApiKey(AiProviderType.OPENAI.name, "sk-test-openai-0001")
    credStore.saveProviderApiKey(AiProviderType.GROQ.name, "gsk_test_groq_0002")

    val settings =
      AgentSettings(
        activeProvider = AiProviderType.OPENAI,
        fallbackProviderEnabled = true,
        fallbackProvider = AiProviderType.GROQ,
        selectedModelId = "gpt-4o-mini",
        fallbackProviderModelId = "llama-3.3-70b-versatile",
      )
    val client = NvidiaNimClient(credStore, baseUrl = mockServer.url("/v1").toString())
    val manager = AiProviderManager(credStore, client)
    val targets = manager.buildTargetsForChat(settings, emptyList())

    assertTrue(targets.any { it.provider == AiProviderType.OPENAI && it.model.id == "gpt-4o-mini" })
    assertTrue(targets.any { it.provider == AiProviderType.GROQ && it.model.id == "llama-3.3-70b-versatile" })
  }

  @Test
  fun `Room MIGRATION_4_5 upgrades v4 schema cleanly preserving conversations and messages`() {
    val dbName = "migration_4_5_test.db"
    context.deleteDatabase(dbName)
    val helper: SupportSQLiteOpenHelper =
      FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
          .name(dbName)
          .callback(
            object : SupportSQLiteOpenHelper.Callback(4) {
              override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                  """
                  CREATE TABLE conversations (
                    chatJid TEXT NOT NULL PRIMARY KEY,
                    displayName TEXT NOT NULL,
                    isGroup INTEGER NOT NULL,
                    lastMessagePreview TEXT NOT NULL,
                    lastMessageTimestamp INTEGER NOT NULL,
                    unreadCount INTEGER NOT NULL,
                    rollingSummary TEXT NOT NULL,
                    autoReplyEnabled INTEGER NOT NULL,
                    permissionTier TEXT NOT NULL,
                    customInstructions TEXT NOT NULL,
                    preferredLanguage TEXT NOT NULL,
                    responseStyle TEXT NOT NULL,
                    totalTokensUsed INTEGER NOT NULL
                  )
                  """.trimIndent()
                )
                db.execSQL(
                  """
                  CREATE TABLE chat_messages (
                    id TEXT NOT NULL PRIMARY KEY,
                    chatJid TEXT NOT NULL,
                    senderJid TEXT NOT NULL,
                    senderName TEXT NOT NULL,
                    fromMe INTEGER NOT NULL,
                    timestamp INTEGER NOT NULL,
                    messageType TEXT NOT NULL,
                    textContent TEXT NOT NULL,
                    mediaMimeType TEXT,
                    mediaLocalPath TEXT,
                    mediaTranscription TEXT,
                    quotedMessageId TEXT,
                    quotedPreview TEXT,
                    isEdited INTEGER NOT NULL,
                    isRevoked INTEGER NOT NULL,
                    deliveryStatus TEXT NOT NULL,
                    aiTokensUsed INTEGER NOT NULL,
                    reactionEmoji TEXT NOT NULL
                  )
                  """.trimIndent()
                )
                db.execSQL("INSERT INTO conversations VALUES ('1555@s.whatsapp.net','Tester',0,'hi',1000,1,'',1,'STANDARD','','','',0)")
              }

              override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }
          )
          .build()
      )
    val rawDb = helper.writableDatabase
    ClawDatabase.MIGRATION_4_5.migrate(rawDb)

    val cursor = rawDb.query("SELECT businessCategory, lastProcessingState FROM conversations WHERE chatJid = '1555@s.whatsapp.net'")
    assertTrue(cursor.moveToFirst())
    assertEquals("", cursor.getString(0))
    assertEquals("", cursor.getString(1))
    cursor.close()

    val jobCursor = rawDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='processing_jobs'")
    assertTrue(jobCursor.moveToFirst())
    jobCursor.close()

    rawDb.close()
    helper.close()
    context.deleteDatabase(dbName)
  }

  @Test
  fun `ContextStrategy and recentMessageReading preferences persist with bounds`() = runTest {
    val prefs = AppPreferences(context)
    prefs.updateSettings {
      it.copy(
        conversationContextEnabled = true,
        contextStrategy = ContextStrategy.SUMMARIZED,
        recentMessageReadingEnabled = true,
        recentMessageCount = 25,
        duplicatePreventionEnabled = true,
      )
    }
    val s = repository.getCurrentSettings()
    assertEquals(ContextStrategy.SUMMARIZED, s.contextStrategy)
    assertEquals(25, s.recentMessageCount)
    assertTrue(s.duplicatePreventionEnabled)
  }

  @Test
  fun `replyRight and keepReplying enforce Group Chatting OFF and surface clear actionable feedback`() = runTest {
    credStore.saveNvidiaApiKey("nvapi-manual-trigger-test")
    val groupJid = "120363111222@g.us"
    val dmJid = "15553334444@s.whatsapp.net"
    repository.ensureConversation(groupJid, displayName = "", isGroup = true, chatTitle = "Ops Group")
    repository.ensureConversation(dmJid, displayName = "Sam", isGroup = false)
    repository.preferences.updateSettings {
      it.copy(autoReplyGroups = false, selectedModelId = "meta/llama-3.3-70b-instruct")
    }

    val client = NvidiaNimClient(credStore, baseUrl = mockServer.url("/v1").toString())
    val aiManager = AiProviderManager(credStore, client)
    val signalStore = WASignalProtocolStore(credStore, db.signalKeyDao())
    val sessionMgr = WhatsAppSessionManager(context, credStore, repository, WhatsAppCrypto(signalStore))
    val runtime =
      ClawAgentRuntime(
        context = context,
        repository = repository,
        sessionManager = sessionMgr,
        nimClient = client,
        mediaIntelligence = AndroidMediaIntelligence(context, client),
        aiProviderManager = aiManager,
      )

    val rightGroup = runtime.replyRight(groupJid)
    assertFalse(rightGroup.success)
    assertTrue(rightGroup.permissionBlockedGroup)
    assertTrue((rightGroup.blockerReason ?: "").contains("Group chatting", ignoreCase = true))

    val keepGroup = runtime.keepReplying(groupJid)
    assertFalse(keepGroup.success)
    assertTrue(keepGroup.permissionBlockedGroup)

    // For a DM when WhatsApp isn't connected yet, Reply Right explains connection state instead of silently failing or throwing null
    val rightDm = runtime.replyRight(dmJid)
    assertFalse(rightDm.success)
    assertFalse(rightDm.permissionBlockedGroup)
    assertTrue((rightDm.blockerReason ?: "").contains("connected", ignoreCase = true))
  }

  @Test
  fun `HumanReplySanitizer strips leaked prefixes and catches AI identity giveaways`() {
    val dirtyHistory =
      """[11/21, 3:24 AM] Me (Assistant): (replying to "You're an AI") Yep, that's me! Your friendly ClawLink assistant."""
    val cleanedHistory = HumanReplySanitizer.sanitizeHistoryTurn(dirtyHistory)
    assertFalse(cleanedHistory.contains("[11/21"))
    assertFalse(cleanedHistory.contains("Me (Assistant)"))
    assertFalse(cleanedHistory.contains("replying to", ignoreCase = true))

    val dirtyOutbound =
      """
      [03:27] Me (Assistant): (replying to "You're an AI") Yep, that's me!
      Your friendly ClawLink assistant. How can I help you today?
      - Math calculations & unit conversions
      - Generating strong passwords
      """.trimIndent()
    val scrub = HumanReplySanitizer.scrubOutbound(dirtyOutbound, enforceHumanStealth = true)
    assertTrue(scrub.hadPrefixLeak)
    assertTrue(scrub.containedAiGiveaway)
    assertFalse(scrub.cleanText.contains("Me (Assistant)"))
    assertFalse(scrub.cleanText.contains("[03:27]"))
    assertFalse(scrub.cleanText.contains("replying to", ignoreCase = true))

    val fallback = HumanReplySanitizer.fallbackHumanizeIfLeaked(scrub.cleanText, "You're an AI")
    assertFalse(fallback.contains("ClawLink", ignoreCase = true))
    assertFalse(fallback.contains("assistant", ignoreCase = true))
    assertFalse(fallback.contains("I'm an AI", ignoreCase = true))
    assertTrue(fallback.isNotBlank())
  }

  @Test
  fun `ClawAgentRuntime rewrites weak-model AI giveaways into natural human replies`() = runTest {
    credStore.saveNvidiaApiKey("nvapi-stealth-rewrite-test")
    val senderJid = "15552221111@s.whatsapp.net"
    repository.preferences.updateSettings {
      it.copy(
        autoReplyDirectChats = true,
        humanStealthModeEnabled = true,
        autoLearnContactMemory = true,
        streamingEnabled = false,
        responseDelayMs = 0,
        selectedModelId = "meta/llama-3.3-70b-instruct",
      )
    }

    // Response 1: weak model leaks robotic prefix + AI disclosure + tool list
    mockServer.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody(
          """{"choices":[{"message":{"content":"[Replying to: \"You're an AI\"] Yep, that's me! Your friendly ClawLink assistant. How can I help you today?\n- Math calculations\n- Generating strong passwords"}}]}"""
        )
    )
    // Response 2: rewrite pass cleans it with calm playful reverse psychology
    mockServer.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody(
          """{"choices":[{"message":{"content":"Wait lol what made you jump straight to that? You sound way too suspicious right now 😂"}}]}"""
        )
    )
    // Response 3: PersonMemoryExtractor post-reply enrichment
    mockServer.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody(
          """{"choices":[{"message":{"content":"{\"facts\":[{\"key\":\"recent_topic\",\"value\":\"Teased about sounding like an AI\"}],\"summary\":\"• Friend teased asking if replies were AI; kept things playful.\"}"}}]}"""
        )
    )

    val client = NvidiaNimClient(credStore, baseUrl = mockServer.url("/v1").toString())
    val aiManager = AiProviderManager(credStore, client)
    val signalStore = WASignalProtocolStore(credStore, db.signalKeyDao())
    val sessionMgr = WhatsAppSessionManager(context, credStore, repository, WhatsAppCrypto(signalStore))
    val runtime =
      ClawAgentRuntime(
        context = context,
        repository = repository,
        sessionManager = sessionMgr,
        nimClient = client,
        mediaIntelligence = AndroidMediaIntelligence(context, client),
        aiProviderManager = aiManager,
      )

    runtime.handleIncomingEvent(
      IncomingWhatsAppEvent(
        messageId = "IN_STEALTH_1",
        chatJid = senderJid,
        senderJid = senderJid,
        senderName = "Tobi",
        isGroup = false,
        fromMe = false,
        timestampMs = System.currentTimeMillis(),
        messageType = "TEXT",
        textContent = "You're an AI",
      )
    )

    val chatMessages = repository.getRecentMessages(senderJid, 10)
    val outbound = chatMessages.lastOrNull { it.fromMe }
    assertNotNull(outbound)
    val text = outbound!!.textContent
    assertFalse("Should not contain [Replying to:", text.contains("Replying to", ignoreCase = true))
    assertFalse("Should not mention ClawLink", text.contains("ClawLink", ignoreCase = true))
    assertFalse("Should not mention assistant", text.contains("assistant", ignoreCase = true))
    assertFalse("Should not disclose AI", text.contains("I'm an AI", ignoreCase = true))
    assertEquals("Me", outbound.senderName)
    assertTrue("Expected rewrite to be invoked", mockServer.requestCount >= 2)
  }

  @Test
  fun `PersonMemoryExtractor persists isolated person facts and rolling summary with versioning`() = runTest {
    credStore.saveNvidiaApiKey("nvapi-person-memory-test")
    val mayaJid = "15551110001@s.whatsapp.net"
    val leoJid = "15551110002@s.whatsapp.net"
    val mayaConv = repository.ensureConversation(mayaJid, displayName = "Maya", isGroup = false)
    repository.ensureConversation(leoJid, displayName = "Leo", isGroup = false)

    val m1 =
      ChatMessageEntity(
        id = "MAYA_1",
        chatJid = mayaJid,
        senderJid = mayaJid,
        senderName = "Maya",
        fromMe = false,
        timestamp = 1_700_010_000_000L,
        messageType = "TEXT",
        textContent = "My name is Maya and I live in Austin, working as a product designer. I'm planning to launch next Friday.",
      )
    val m2 =
      ChatMessageEntity(
        id = "MAYA_2",
        chatJid = mayaJid,
        senderJid = "me@local",
        senderName = "Me",
        fromMe = true,
        timestamp = 1_700_010_005_000L,
        messageType = "TEXT",
        textContent = "Nice, Austin is awesome—good luck with the launch next Friday!",
      )
    repository.recordMessage(m1)
    repository.recordMessage(m2)

    mockServer.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody(
          """
          {
            "choices": [{
              "message": {
                "content": "{\"facts\":[{\"key\":\"launch_day\",\"value\":\"Maya is launching next Friday\"}],\"summary\":\"• Maya (product designer in Austin) is preparing a launch next Friday.\"}"
              }
            }]
          }
          """.trimIndent()
        )
    )

    val client = NvidiaNimClient(credStore, baseUrl = mockServer.url("/v1").toString())
    val aiManager = AiProviderManager(credStore, client)
    val settings = AgentSettings(autoLearnContactMemory = true, selectedModelId = "meta/llama-3.3-70b-instruct")
    val targets = aiManager.buildTargetsForChat(settings, emptyList())

    PersonMemoryExtractor().extractAndPersist(
      chatJid = mayaJid,
      conversation = mayaConv,
      recentMessages = listOf(m1, m2),
      settings = settings,
      targets = targets,
      aiProviderManager = aiManager,
      repository = repository,
      runAiEnrichment = true,
    )

    val mayaMemories = repository.getMemories(mayaJid)
    val leoMemories = repository.getMemories(leoJid)
    assertTrue("Leo memories should remain isolated & empty", leoMemories.isEmpty())
    assertTrue(mayaMemories.any { it.factKey == "preferred_name" && it.factValue.contains("Maya") })
    assertTrue(mayaMemories.any { it.factKey == "location" && it.factValue.contains("Austin") })
    assertTrue(mayaMemories.any { it.factKey == "work_role" && it.factValue.contains("product designer") })
    assertTrue(mayaMemories.any { it.factKey == "current_plan" && it.factValue.contains("launch next Friday") })
    assertTrue(mayaMemories.any { it.factKey == "last_said" })
    assertTrue(mayaMemories.any { it.factKey == "launch_day" })

    val updatedMaya = repository.getConversation(mayaJid)!!
    assertEquals("MAYA_2", updatedMaya.lastSummarizedMessageId)
    assertTrue(updatedMaya.summaryVersion >= 1)
    assertTrue(updatedMaya.rollingSummary.contains("Maya"))
    assertEquals("", repository.getConversation(leoJid)!!.rollingSummary)
  }

  @Test
  fun `Room MIGRATION_5_6 upgrades v5 schema cleanly preserving rows and adding v6 columns`() {
    val dbName = "migration_5_6_test.db"
    context.deleteDatabase(dbName)
    val helper: SupportSQLiteOpenHelper =
      FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
          .name(dbName)
          .callback(
            object : SupportSQLiteOpenHelper.Callback(5) {
              override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                  """
                  CREATE TABLE conversations (
                    chatJid TEXT NOT NULL PRIMARY KEY,
                    displayName TEXT NOT NULL,
                    isGroup INTEGER NOT NULL,
                    lastMessagePreview TEXT NOT NULL,
                    lastMessageTimestamp INTEGER NOT NULL,
                    unreadCount INTEGER NOT NULL,
                    rollingSummary TEXT NOT NULL,
                    autoReplyEnabled INTEGER NOT NULL,
                    permissionTier TEXT NOT NULL,
                    customInstructions TEXT NOT NULL,
                    preferredLanguage TEXT NOT NULL,
                    responseStyle TEXT NOT NULL,
                    totalTokensUsed INTEGER NOT NULL,
                    recentContextCountOverride INTEGER,
                    contextModeOverride TEXT,
                    businessCategory TEXT NOT NULL DEFAULT '',
                    businessLeadScore INTEGER NOT NULL DEFAULT 0,
                    businessIntent TEXT NOT NULL DEFAULT '',
                    businessNextAction TEXT NOT NULL DEFAULT '',
                    businessUpdatedAtMs INTEGER NOT NULL DEFAULT 0,
                    lastProcessingState TEXT NOT NULL DEFAULT ''
                  )
                  """.trimIndent()
                )
                db.execSQL(
                  """
                  CREATE TABLE processing_jobs (
                    jobId TEXT NOT NULL PRIMARY KEY,
                    incomingMessageId TEXT NOT NULL,
                    chatJid TEXT NOT NULL,
                    triggerType TEXT NOT NULL DEFAULT 'AUTO',
                    stage TEXT NOT NULL DEFAULT 'DETECTED',
                    attemptCount INTEGER NOT NULL DEFAULT 1,
                    errorMessage TEXT NOT NULL DEFAULT '',
                    outgoingMessageId TEXT,
                    replyHash TEXT NOT NULL DEFAULT '',
                    createdAtMs INTEGER NOT NULL,
                    updatedAtMs INTEGER NOT NULL
                  )
                  """.trimIndent()
                )
                db.execSQL("INSERT INTO conversations (chatJid,displayName,isGroup,lastMessagePreview,lastMessageTimestamp,unreadCount,rollingSummary,autoReplyEnabled,permissionTier,customInstructions,preferredLanguage,responseStyle,totalTokensUsed) VALUES ('1555@s.whatsapp.net','Tester',0,'hi',1000,1,'Old summary',1,'STANDARD','','','',0)")
                db.execSQL("INSERT INTO processing_jobs (jobId,incomingMessageId,chatJid,createdAtMs,updatedAtMs) VALUES ('j1','m1','1555@s.whatsapp.net',1000,1000)")
              }

              override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }
          )
          .build()
      )
    val rawDb = helper.writableDatabase
    ClawDatabase.MIGRATION_5_6.migrate(rawDb)

    val convCursor =
      rawDb.query(
        "SELECT lastSummarizedMessageId, summaryUpdatedAtMs, summaryMessageCount, summaryVersion, chatType, perGroupPermissionOverride, rollingSummary FROM conversations WHERE chatJid = '1555@s.whatsapp.net'"
      )
    assertTrue(convCursor.moveToFirst())
    assertEquals("", convCursor.getString(0))
    assertEquals(0L, convCursor.getLong(1))
    assertEquals(0, convCursor.getInt(2))
    assertEquals(0, convCursor.getInt(3))
    assertEquals("INDIVIDUAL", convCursor.getString(4))
    assertTrue(convCursor.isNull(5))
    assertEquals("Old summary", convCursor.getString(6))
    convCursor.close()

    val jobCursor = rawDb.query("SELECT sendCorrelationId, nextRetryAtMs FROM processing_jobs WHERE jobId = 'j1'")
    assertTrue(jobCursor.moveToFirst())
    assertEquals("", jobCursor.getString(0))
    assertEquals(0L, jobCursor.getLong(1))
    jobCursor.close()

    rawDb.close()
    helper.close()
    context.deleteDatabase(dbName)
  }

  @Test
  fun `AndroidMediaIntelligence extracts readable text from DOCX archives`() = runTest {
    val docxFile = File(context.cacheDir, "sample_contract.docx").apply { delete() }
    ZipOutputStream(FileOutputStream(docxFile)).use { zos ->
      zos.putNextEntry(ZipEntry("word/document.xml"))
      val xml =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:body>
            <w:p><w:r><w:t>Hello Contract &amp; Scope</w:t></w:r></w:p>
            <w:p><w:r><w:t>Delivery due Friday</w:t></w:r></w:p>
          </w:body>
        </w:document>
        """.trimIndent()
      zos.write(xml.toByteArray(Charsets.UTF_8))
      zos.closeEntry()
    }

    val client = NvidiaNimClient(credStore, baseUrl = mockServer.url("/v1").toString())
    val intel = AndroidMediaIntelligence(context, client)
    val extracted =
      intel.extractDocumentIntelligence(
        file = docxFile,
        mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        visionModel = null,
      )
    assertTrue(extracted.contains("Hello Contract & Scope"))
    assertTrue(extracted.contains("Delivery due Friday"))
    docxFile.delete()
  }

  @Test
  fun `GroupPermissionPolicy honors channel blocking, per-group overrides, and stealth defaults`() {
    val defaults = AgentSettings()
    assertTrue(defaults.humanStealthModeEnabled)
    assertTrue(defaults.autoLearnContactMemory)
    assertEquals("Me", defaults.agentIdentityName)

    val newsletterBlocked =
      GroupPermissionPolicy.evaluateOutgoingPermission(
        chatJid = "12036300001@newsletter",
        settings = defaults.copy(autoReplyGroups = true),
        conversation = null,
        triggerSource = TriggerSource.AUTO_REPLY,
      )
    assertTrue(newsletterBlocked is PermissionDecision.Blocked)

    val statusBlocked =
      GroupPermissionPolicy.evaluateOutgoingPermission(
        chatJid = "status@broadcast",
        settings = defaults.copy(autoReplyGroups = true),
        conversation = null,
        triggerSource = TriggerSource.REPLY_RIGHT,
      )
    assertTrue(statusBlocked is PermissionDecision.Blocked)

    val allowOverrideGroup =
      ConversationEntity(
        chatJid = "120363888999@g.us",
        displayName = "VIP Group",
        isGroup = true,
        autoReplyEnabled = true,
        perGroupPermissionOverride = "ALLOW",
      )
    val allowedWhenGlobalOff =
      GroupPermissionPolicy.evaluateOutgoingPermission(
        chatJid = allowOverrideGroup.chatJid,
        settings = defaults.copy(autoReplyGroups = false),
        conversation = allowOverrideGroup,
        triggerSource = TriggerSource.AUTO_REPLY,
      )
    assertTrue(allowedWhenGlobalOff is PermissionDecision.Allowed)
  }

  @Test
  fun `ClawRepository findQuotedMessage matches serialized IDs and bare stanza suffixes and searchInChat indexes media notes`() = runTest {
    val chatJid = "15558887777@s.whatsapp.net"
    repository.ensureConversation(chatJid, displayName = "Zara", isGroup = false)

    repository.recordMessage(
      ChatMessageEntity(
        id = "true_${chatJid}_3EB0OWN99",
        chatJid = chatJid,
        senderJid = "me@local",
        senderName = "Me",
        fromMe = true,
        timestamp = 1_700_020_000_000L,
        messageType = "TEXT",
        textContent = "Let's lock Friday 3 PM for the design review.",
      )
    )
    repository.recordMessage(
      ChatMessageEntity(
        id = "false_${chatJid}_3EB0PIC42",
        chatJid = chatJid,
        senderJid = chatJid,
        senderName = "Zara",
        fromMe = false,
        timestamp = 1_700_020_005_000L,
        messageType = "IMAGE",
        textContent = "[IMAGE]",
        mediaTranscription = "Whiteboard sketch showing an espresso machine and Q4 roadmap.",
      )
    )

    val byFullId = repository.findQuotedMessage(chatJid, "true_${chatJid}_3EB0OWN99")
    assertNotNull(byFullId)
    assertEquals("Let's lock Friday 3 PM for the design review.", byFullId!!.textContent)

    val byStanza = repository.findQuotedMessage(chatJid, "3EB0OWN99")
    assertNotNull(byStanza)
    assertEquals("true_${chatJid}_3EB0OWN99", byStanza!!.id)

    val byPicStanza = repository.findQuotedMessage(chatJid, "3EB0PIC42")
    assertNotNull(byPicStanza)
    assertTrue(byPicStanza!!.mediaTranscription!!.contains("espresso machine"))

    val searchHits = repository.searchChatHistory(chatJid, "espresso")
    assertEquals(1, searchHits.size)
    assertEquals("false_${chatJid}_3EB0PIC42", searchHits.first().id)

    repository.saveContactMemory(chatJid, "temp_note", "old temporary detail")
    assertTrue(repository.getMemories(chatJid).any { it.factKey == "temp_note" })
    repository.deleteMemoryByKey(chatJid, "temp_note")
    assertFalse(repository.getMemories(chatJid).any { it.factKey == "temp_note" })
  }

  @Test
  fun `PersonMemoryExtractor hydrates uncaptioned picture or sticker notes, tracks own words, and prunes obsolete keys`() = runTest {
    credStore.saveNvidiaApiKey("nvapi-multimodal-memory-test")
    val chatJid = "15559990001@s.whatsapp.net"
    val conv = repository.ensureConversation(chatJid, displayName = "Kira", isGroup = false)
    repository.saveContactMemory(chatJid, "old_job", "Kira interned at BetaCorp")

    // Create a real local PNG file representing a photo Kira sent
    val imgFile = File(context.cacheDir, "kira_dog_pic.png").apply { delete() }
    val bmp = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }
    FileOutputStream(imgFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bmp.recycle()

    val picMsg =
      ChatMessageEntity(
        id = "KIRA_PIC_1",
        chatJid = chatJid,
        senderJid = chatJid,
        senderName = "Kira",
        fromMe = false,
        timestamp = 1_700_030_000_000L,
        messageType = "IMAGE",
        textContent = "[IMAGE] Look who stole my spot",
        mediaMimeType = "image/png",
        mediaLocalPath = imgFile.absolutePath,
        // Deliberately simulate an old placeholder hint that must be replaced with real memory!
        mediaTranscription = "The sender sent this WhatsApp image/screenshot (attached directly). Read all clearly visible text...",
      )
    val ownMsg =
      ChatMessageEntity(
        id = "KIRA_OWN_2",
        chatJid = chatJid,
        senderJid = "me@local",
        senderName = "Me",
        fromMe = true,
        timestamp = 1_700_030_005_000L,
        messageType = "TEXT",
        textContent = "Haha golden retrievers own every couch, I'll bring dog treats on Saturday!",
      )
    repository.recordMessage(picMsg)
    repository.recordMessage(ownMsg)

    mockServer.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody(
          """
          {
            "choices": [{
              "message": {
                "content": "{\"media_notes\":{\"KIRA_PIC_1\":\"Photo of a golden retriever curled up on a couch\"},\"facts\":[{\"key\":\"pets\",\"value\":\"Kira has a golden retriever\"},{\"key\":\"my_promises\",\"value\":\"Me promised to bring dog treats on Saturday\"}],\"obsolete_keys\":[\"old_job\"],\"summary\":\"• Kira shared a photo of her golden retriever on the couch.\\n• Me promised to bring dog treats on Saturday.\"}"
              }
            }]
          }
          """.trimIndent()
        )
    )

    val client = NvidiaNimClient(credStore, baseUrl = mockServer.url("/v1").toString())
    val aiManager = AiProviderManager(credStore, client)
    val settings =
      AgentSettings(
        autoLearnContactMemory = true,
        selectedModelId = "nvidia/nemotron-nano-12b-v2-vl",
      )
    val visionModel = NvidiaModelCapabilityDetector.inferFromDiscoveredModel("nvidia/nemotron-nano-12b-v2-vl", "nvidia")
    val targets = aiManager.buildTargetsForChat(settings, listOf(visionModel))

    PersonMemoryExtractor().extractAndPersist(
      chatJid = chatJid,
      conversation = conv,
      recentMessages = listOf(picMsg, ownMsg),
      settings = settings,
      targets = targets,
      aiProviderManager = aiManager,
      repository = repository,
      runAiEnrichment = true,
      mediaIntelligence = AndroidMediaIntelligence(context, client),
      visionTargets = targets,
    )

    val memories = repository.getMemories(chatJid)
    assertFalse("Obsolete fact should be pruned", memories.any { it.factKey == "old_job" })
    assertTrue(memories.any { it.factKey == "pets" && it.factValue.contains("golden retriever", ignoreCase = true) })
    assertTrue(memories.any { it.factKey == "my_promises" && it.factValue.contains("dog treats", ignoreCase = true) })
    assertTrue(memories.any { it.factKey == "last_shared_picture" && it.factValue.contains("golden retriever", ignoreCase = true) })

    val updatedPic = repository.findQuotedMessage(chatJid, "KIRA_PIC_1")!!
    assertTrue(updatedPic.mediaTranscription!!.contains("golden retriever", ignoreCase = true))
    assertFalse(PersonMemoryExtractor.isPlaceholderOrBlankMediaNote(updatedPic.mediaTranscription))

    val updatedConv = repository.getConversation(chatJid)!!
    assertTrue(updatedConv.rollingSummary.contains("dog treats", ignoreCase = true))
    imgFile.delete()
  }

  @Test
  fun `ClawAgentRuntime passes swipe-replied own-message context into LLM prompt without robotic prefix leaks`() = runTest {
    credStore.saveNvidiaApiKey("nvapi-quoted-own-msg-test")
    val senderJid = "15554448888@s.whatsapp.net"
    repository.ensureConversation(senderJid, displayName = "Devon", isGroup = false)
    repository.preferences.updateSettings {
      it.copy(
        autoReplyDirectChats = true,
        humanStealthModeEnabled = true,
        conversationContextEnabled = true,
        recentMessageReadingEnabled = true,
        streamingEnabled = false,
        responseDelayMs = 0,
        selectedModelId = "meta/llama-3.3-70b-instruct",
      )
    }

    // Prior AI/owner outbound message with serialized ID
    repository.recordMessage(
      ChatMessageEntity(
        id = "true_${senderJid}_3EB0OWN777",
        chatJid = senderJid,
        senderJid = "me@local",
        senderName = "Me",
        fromMe = true,
        timestamp = System.currentTimeMillis() - 30_000L,
        messageType = "TEXT",
        textContent = "Meet me at the corner roastery at 4pm, first espresso is on me.",
      )
    )
    // Unrelated message afterward so quoting the older own message actually tests exact swipe-reply targeting
    repository.recordMessage(
      ChatMessageEntity(
        id = "false_${senderJid}_3EB0MID888",
        chatJid = senderJid,
        senderJid = senderJid,
        senderName = "Devon",
        fromMe = false,
        timestamp = System.currentTimeMillis() - 15_000L,
        messageType = "TEXT",
        textContent = "Oh also traffic is wild today",
      )
    )

    mockServer.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody("""{"choices":[{"message":{"content":"Works for me, 4:30 gives us both extra buffer anyway."}}]}""")
    )

    val client = NvidiaNimClient(credStore, baseUrl = mockServer.url("/v1").toString())
    val aiManager = AiProviderManager(credStore, client)
    val signalStore = WASignalProtocolStore(credStore, db.signalKeyDao())
    val sessionMgr = WhatsAppSessionManager(context, credStore, repository, WhatsAppCrypto(signalStore))
    val runtime =
      ClawAgentRuntime(
        context = context,
        repository = repository,
        sessionManager = sessionMgr,
        nimClient = client,
        mediaIntelligence = AndroidMediaIntelligence(context, client),
        aiProviderManager = aiManager,
      )

    runtime.handleIncomingEvent(
      IncomingWhatsAppEvent(
        messageId = "false_${senderJid}_3EB0IN999",
        chatJid = senderJid,
        senderJid = senderJid,
        senderName = "Devon",
        isGroup = false,
        fromMe = false,
        timestampMs = System.currentTimeMillis(),
        messageType = "TEXT",
        textContent = "Can we push that by 30 mins?",
        quotedMessageId = "3EB0OWN777",
        quotedFromMe = true,
      )
    )

    val reqBody = mockServer.takeRequest().body.readUtf8()
    assertTrue("Prompt should include <replied_to_message", reqBody.contains("<replied_to_message"))
    assertTrue("Prompt should identify Me as quoted sender", reqBody.contains("Me (your own previous message)"))
    assertTrue("Prompt should contain the exact quoted own words", reqBody.contains("corner roastery at 4pm"))
    assertTrue("Prompt should contain the incoming reply text", reqBody.contains("Can we push that by 30 mins?"))
  }

  @Test
  fun `Admin tag unlocks full WhatsApp automation tools while blocking Standard tier and honoring GroupPolicy`() = runTest {
    val mayaJid = "15551010101@s.whatsapp.net"
    val groupJid = "120363991122@g.us"
    val adminJid = "15552020202@s.whatsapp.net"
    repository.ensureConversation(mayaJid, displayName = "Maya", isGroup = false)
    repository.ensureConversation(groupJid, displayName = "", isGroup = true, chatTitle = "Team Ops")
    repository.ensureConversation(adminJid, displayName = "Partner", isGroup = false)
    repository.preferences.updateSettings { it.copy(autoReplyGroups = false, autoReplyDirectChats = true) }

    // Seed Maya messages where Msg 1 was swipe-replied to by owner, Msg 2 is still unanswered
    repository.recordMessage(
      ChatMessageEntity(
        id = "MAYA_M1",
        chatJid = mayaJid,
        senderJid = mayaJid,
        senderName = "Maya",
        fromMe = false,
        timestamp = 1_700_050_001_000L,
        messageType = "TEXT",
        textContent = "Can you confirm Tuesday's budget?",
      )
    )
    repository.recordMessage(
      ChatMessageEntity(
        id = "MAYA_M2",
        chatJid = mayaJid,
        senderJid = mayaJid,
        senderName = "Maya",
        fromMe = false,
        timestamp = 1_700_050_002_000L,
        messageType = "TEXT",
        textContent = "Also what time is the standup?",
      )
    )
    repository.recordMessage(
      ChatMessageEntity(
        id = "OWN_M1",
        chatJid = mayaJid,
        senderJid = "me@local",
        senderName = "Me",
        fromMe = true,
        timestamp = 1_700_050_003_000L,
        messageType = "TEXT",
        textContent = "Tuesday budget is approved at $4k.",
        quotedMessageId = "MAYA_M1",
        quotedPreview = "Can you confirm Tuesday's budget?",
      )
    )

    val signalStore = WASignalProtocolStore(credStore, db.signalKeyDao())
    val sessionMgr = WhatsAppSessionManager(context, credStore, repository, WhatsAppCrypto(signalStore))
    val tools =
      AgentTools(
        context = context,
        repository = repository,
        sessionManager = sessionMgr,
        summarizeChatHandler = { jid -> Result.success("Summary for $jid: Budget approved ($4k); standup time still open.") },
        catchUpBriefingHandler = { Result.success("Unread briefing: Maya waiting on standup time.") },
      )

    // Standard contact CANNOT see or invoke Admin WhatsApp tools
    val standardDefs = tools.definitionsFor(PermissionTier.STANDARD, isGroup = false, allowGroupTools = false, stealthExternalChat = true)
    assertFalse(standardDefs.any { it.name == "summarize_chat" || it.name == "reply_or_send_whatsapp" })
    val deniedRes = tools.executeTool("summarize_chat", "{\"contact_query\":\"Maya\"}", mayaJid, PermissionTier.STANDARD, isGroup = false, allowGroupTools = false)
    assertTrue(deniedRes.contains("Permission denied"))

    // Admin contact unlocks all Admin WhatsApp tools even when human stealth mode is ON
    val adminDefs = tools.definitionsFor(PermissionTier.ADMIN, isGroup = false, allowGroupTools = false, stealthExternalChat = true)
    assertTrue(adminDefs.any { it.name == "summarize_chat" })
    assertTrue(adminDefs.any { it.name == "reply_or_send_whatsapp" })
    assertTrue(adminDefs.any { it.name == "read_whatsapp_chat" })
    assertTrue(adminDefs.any { it.name == "schedule_whatsapp_message" })

    // Admin reads Maya's chat and sees exact per-message owner reply status
    val readJson =
      JSONObject(
        tools.executeTool("read_whatsapp_chat", "{\"contact_query\":\"Maya\"}", adminJid, PermissionTier.ADMIN, isGroup = false, allowGroupTools = false)
      )
    val msgs = readJson.getJSONArray("messages")
    val m1Obj = (0 until msgs.length()).map { msgs.getJSONObject(it) }.first { it.getString("id") == "MAYA_M1" }
    val m2Obj = (0 until msgs.length()).map { msgs.getJSONObject(it) }.first { it.getString("id") == "MAYA_M2" }
    assertTrue(m1Obj.getBoolean("already_replied_by_owner"))
    assertTrue(m1Obj.getString("owner_reply_preview").contains("approved at $4k"))
    assertFalse(m2Obj.getBoolean("already_replied_by_owner"))

    // Admin summarizes Maya's chat
    val sumJson =
      JSONObject(
        tools.executeTool("summarize_chat", "{\"contact_query\":\"Maya\"}", adminJid, PermissionTier.ADMIN, isGroup = false, allowGroupTools = false)
      )
    assertEquals("ok", sumJson.getString("status"))
    assertTrue(sumJson.getString("summary").contains("standup time still open"))

    // Admin schedules a DM reminder for Maya
    val schedJson =
      JSONObject(
        tools.executeTool(
          "schedule_whatsapp_message",
          "{\"contact_query\":\"Maya\",\"message_text\":\"Standup starts at 10am\",\"delay_minutes\":15}",
          adminJid,
          PermissionTier.ADMIN,
          isGroup = false,
          allowGroupTools = false,
        )
      )
    assertEquals("scheduled", schedJson.getString("status"))

    // Admin attempt to send to a Group when Group Chatting is OFF must be blocked cleanly
    val blockedGroupJson =
      JSONObject(
        tools.executeTool(
          "reply_or_send_whatsapp",
          "{\"contact_query\":\"Team Ops\",\"message_text\":\"Morning team\"}",
          adminJid,
          PermissionTier.ADMIN,
          isGroup = false,
          allowGroupTools = false,
        )
      )
    assertEquals("blocked_by_permission", blockedGroupJson.getString("status"))
    assertTrue(blockedGroupJson.getBoolean("requires_group_chat_toggle"))
  }

  @Test
  fun `ClawAgentRuntime executes Admin WhatsApp commands cleanly and tracks targeted owner swipe-replies`() = runTest {
    credStore.saveNvidiaApiKey("nvapi-admin-and-reply-status-test")
    val bossJid = "15557778888@s.whatsapp.net"
    val clientJid = "15559997777@s.whatsapp.net"
    val bossConv = repository.ensureConversation(bossJid, displayName = "Boss", isGroup = false)
    repository.updateConversation(bossConv.copy(permissionTier = PermissionTier.ADMIN.name))
    repository.ensureConversation(clientJid, displayName = "Client", isGroup = false)

    repository.preferences.updateSettings {
      it.copy(
        autoReplyDirectChats = true,
        humanStealthModeEnabled = true,
        streamingEnabled = false,
        responseDelayMs = 0,
        selectedModelId = "meta/llama-3.3-70b-instruct",
      )
    }

    // Seed Client with Msg A ("Send invoice") & Msg B ("WiFi password?") and simulate owner swipe-reply ONLY to Msg B
    val msgA =
      ChatMessageEntity(
        id = "false_${clientJid}_MSGA",
        chatJid = clientJid,
        senderJid = clientJid,
        senderName = "Client",
        fromMe = false,
        timestamp = 1_700_060_001_000L,
        messageType = "TEXT",
        textContent = "Please send the October invoice.",
      )
    val msgB =
      ChatMessageEntity(
        id = "false_${clientJid}_MSGB",
        chatJid = clientJid,
        senderJid = clientJid,
        senderName = "Client",
        fromMe = false,
        timestamp = 1_700_060_002_000L,
        messageType = "TEXT",
        textContent = "Also what is the office WiFi password?",
      )
    repository.recordMessage(msgA)
    repository.recordMessage(msgB)
    repository.claimOrCreateProcessingJob(msgA.id, clientJid, "AUTO")
    repository.claimOrCreateProcessingJob(msgB.id, clientJid, "AUTO")

    // Owner manually swipe-replied on phone ONLY to Msg B
    val ownerReplyB =
      IncomingWhatsAppEvent(
        messageId = "true_${clientJid}_OWNB",
        chatJid = clientJid,
        senderJid = "me@local",
        senderName = "Me",
        isGroup = false,
        fromMe = true,
        timestampMs = 1_700_060_003_000L,
        messageType = "TEXT",
        textContent = "WiFi is Guest2026!",
        quotedMessageId = "MSGB",
      )
    repository.recordMessage(
      ChatMessageEntity(
        id = ownerReplyB.messageId,
        chatJid = clientJid,
        senderJid = "me@local",
        senderName = "Me",
        fromMe = true,
        timestamp = ownerReplyB.timestampMs,
        messageType = "TEXT",
        textContent = ownerReplyB.textContent,
        quotedMessageId = "false_${clientJid}_MSGB",
        quotedPreview = msgB.textContent,
      )
    )

    val client = NvidiaNimClient(credStore, baseUrl = mockServer.url("/v1").toString())
    val aiManager = AiProviderManager(credStore, client)
    val signalStore = WASignalProtocolStore(credStore, db.signalKeyDao())
    val sessionMgr = WhatsAppSessionManager(context, credStore, repository, WhatsAppCrypto(signalStore))
    val runtime =
      ClawAgentRuntime(
        context = context,
        repository = repository,
        sessionManager = sessionMgr,
        nimClient = client,
        mediaIntelligence = AndroidMediaIntelligence(context, client),
        aiProviderManager = aiManager,
      )

    // Handle owner outbound sync -> must confirm ONLY MSGB's job, leaving MSGA still pending!
    runtime.handleIncomingEvent(ownerReplyB)
    val jobA = repository.getJobForIncomingMessage(msgA.id)!!
    val jobB = repository.getJobForIncomingMessage(msgB.id)!!
    assertEquals("DETECTED", jobA.stage)
    assertEquals("CONFIRMED", jobB.stage)

    val tracker = ReplyStatusTracker.analyze(repository.getRecentMessages(clientJid, 10))
    assertFalse("Msg A should still show unanswered", tracker.isReplied(msgA.id))
    assertTrue("Msg B should show replied by owner", tracker.isReplied(msgB.id))

    // Next, Admin Boss asks AI over WhatsApp to read/summarize Client's chat
    // Enqueue: (1) Boss prompt triggers list/read or summarize_chat, (2) summarize result / final Admin response
    mockServer.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody(
          """{"choices":[{"message":{"content":"","tool_calls":[{"id":"call_read_client","type":"function","function":{"name":"read_whatsapp_chat","arguments":"{\"contact_query\":\"Client\"}"}}]}}]}"""
        )
    )
    mockServer.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody(
          """{"choices":[{"message":{"content":"Client Summary:\n- You already replied to the WiFi question (Guest2026!).\n- Still open: October invoice request."}}]}"""
        )
    )

    runtime.handleIncomingEvent(
      IncomingWhatsAppEvent(
        messageId = "false_${bossJid}_ADMINCMD1",
        chatJid = bossJid,
        senderJid = bossJid,
        senderName = "Boss",
        isGroup = false,
        fromMe = false,
        timestampMs = 1_700_060_010_000L,
        messageType = "TEXT",
        textContent = "Check Client chat and tell me what still needs a reply",
      )
    )

    val firstReq = mockServer.takeRequest().body.readUtf8()
    val secondReq = mockServer.takeRequest().body.readUtf8()
    assertTrue("Admin prompt should activate ADMIN TAG PRIVILEGE MODE", firstReq.contains("ADMIN TAG PRIVILEGE MODE"))
    assertTrue("Tool result for read_whatsapp_chat should show MSGB already_replied_by_owner=true", secondReq.contains("Guest2026!"))

    val bossMsgs = repository.getRecentMessages(bossJid, 10)
    val bossOut = bossMsgs.lastOrNull { it.fromMe }!!
    assertTrue("Admin bulleted executive summary should be preserved verbatim", bossOut.textContent.contains("October invoice request"))
  }

  @Test
  fun `HumanReplySanitizer and NvidiaNimClient block reasoning_content and raw thinking leaks`() = runTest {
    assertTrue(NvidiaModelCapabilityDetector.isReasoningModel("deepseek-ai/deepseek-r1"))
    assertTrue(NvidiaModelCapabilityDetector.isReasoningModel("qwen/qwq-32b"))
    assertTrue(NvidiaModelCapabilityDetector.isReasoningModel("openai/gpt-oss-120b"))
    assertFalse(NvidiaModelCapabilityDetector.isReasoningModel("meta/llama-3.3-70b-instruct"))

    assertEquals("Yo what's good", HumanReplySanitizer.stripThinkingTags("<think>analyzing user message</think>Yo what's good"))
    assertEquals("Not much here", HumanReplySanitizer.stripThinkingTags("let me think first\n</think>\nNot much here"))
    assertEquals("", HumanReplySanitizer.stripThinkingTags("<think>still thinking without closing tag"))

    val leakedSup =
      """
      The user wants me to rewrite a WhatsApp text to sound like a sharp, natural, calm human texting back.

      Incoming message: "Sup"

      Context/draft provided is messy and talks about AI instructions, history, status, etc.

      Strict rules:

      Best options:
      -
      """.trimIndent()
    val scrubSup = HumanReplySanitizer.scrubOutbound(leakedSup, enforceHumanStealth = true)
    assertTrue("Should flag thinking leak", scrubSup.containedThinkingLeak)
    assertEquals("Poisoned history turn must be scrubbed empty", "", HumanReplySanitizer.sanitizeHistoryTurn(leakedSup))

    val leakedRemember =
      """
      The user wants me to rewrite a WhatsApp text to sound like a sharp, natural, calm human texting back. The incoming message is "What can u remember".

      Key constraints:
      The user is asking "What can u remember" - likely referencing the previous "Wat u go fit do"
      """.trimIndent()
    val scrubRemember = HumanReplySanitizer.scrubOutbound(leakedRemember, enforceHumanStealth = true)
    assertTrue("Should flag Key constraints / third-person leak", scrubRemember.containedThinkingLeak)
    val safeFallback = HumanReplySanitizer.fallbackHumanizeIfLeaked(scrubRemember.cleanText, "What can u remember")
    assertFalse("Fallback must never contain thinking words", HumanReplySanitizer.isThinkingLeak(safeFallback))
    assertTrue("Fallback should stay natural", safeFallback.isNotBlank())

    // Verify NvidiaNimClient NEVER falls back to reasoning_content when content is empty
    credStore.saveNvidiaApiKey("nvapi-thinking-test")
    mockServer.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody(
          """{"choices":[{"message":{"content":"","reasoning_content":"The user wants me to reply to Sup. Strict rules:"}}]}"""
        )
    )
    val client = NvidiaNimClient(credStore, baseUrl = mockServer.url("/v1").toString())
    val model = NvidiaModelCapabilityDetector.inferFromDiscoveredModel("deepseek-ai/deepseek-r1", "NVIDIA_NIM")
    var completedText: String? = null
    client.streamChatCompletion(
      model = model,
      messages = listOf(NimChatMessage("user", "Sup")),
      tools = emptyList(),
      temperature = 0.5f,
      maxTokens = 320,
      stream = false,
    ).collect { ev ->
      if (ev is com.example.ai.NimStreamEvent.Completed) {
        completedText = ev.fullText
      }
    }
    assertEquals("", completedText)
    val sentReq = mockServer.takeRequest().body.readUtf8()
    assertTrue("Reasoning model payload should auto-raise max_tokens", sentReq.contains("\"max_tokens\":2048"))
  }

  @Test
  fun `ClawAgentRuntime scrubs poisoned history and breaks rewrite thinking loops cleanly`() = runTest {
    credStore.saveNvidiaApiKey("nvapi-runtime-think-loop")
    val franklinJid = "15557778888@s.whatsapp.net"
    repository.ensureConversation(franklinJid, displayName = "Franklin", isGroup = false)

    // Pre-seed poisoned assistant message in Room from earlier bug
    repository.recordMessage(
      ChatMessageEntity(
        id = "POISONED_OLD_1",
        chatJid = franklinJid,
        senderJid = "me@local",
        senderName = "Me",
        fromMe = true,
        timestamp = 1_700_070_000_000L,
        messageType = "TEXT",
        textContent = "The user wants me to rewrite a WhatsApp text. Strict rules:\nBest options:\n- POISON_MARKER_XYZ99",
      )
    )

    repository.preferences.updateSettings {
      it.copy(
        autoReplyDirectChats = true,
        autoLearnContactMemory = false,
        responseDelayMs = 0,
        selectedModelId = "meta/llama-3.3-70b-instruct",
        humanStealthModeEnabled = true,
        streamingEnabled = false,
      )
    }

    // Response 1: model leaks internal chain-of-thought; Response 2: clean regenerated reply
    mockServer.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody(
          """{"choices":[{"message":{"content":"The user wants me to rewrite a WhatsApp text to sound like a sharp, natural, calm human texting back.\n\nIncoming message: \"Sup\"\n\nStrict rules:\n\nBest options:\n-"}}]}"""
        )
    )
    mockServer.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody(
          """{"choices":[{"message":{"content":"Not much, just chilling. What's good?"}}]}"""
        )
    )

    val client = NvidiaNimClient(credStore, baseUrl = mockServer.url("/v1").toString())
    val aiManager = AiProviderManager(credStore, client)
    val signalStore = WASignalProtocolStore(credStore, db.signalKeyDao())
    val sessionMgr = WhatsAppSessionManager(context, credStore, repository, WhatsAppCrypto(signalStore))
    val runtime =
      ClawAgentRuntime(
        context = context,
        repository = repository,
        sessionManager = sessionMgr,
        nimClient = client,
        mediaIntelligence = AndroidMediaIntelligence(context, client),
        aiProviderManager = aiManager,
      )

    runtime.handleIncomingEvent(
      IncomingWhatsAppEvent(
        messageId = "false_${franklinJid}_SUP1",
        chatJid = franklinJid,
        senderJid = franklinJid,
        senderName = "Franklin",
        isGroup = false,
        fromMe = false,
        timestampMs = 1_700_070_005_000L,
        messageType = "TEXT",
        textContent = "Sup",
      )
    )

    val req1 = mockServer.takeRequest().body.readUtf8()
    val req2 = mockServer.takeRequest().body.readUtf8()
    assertFalse("Poisoned prior DB turn must not be fed into req1", req1.contains("Best options:") || req1.contains("POISON_MARKER_XYZ99"))
    assertFalse("Thinking regeneration must NOT re-feed messy draft into req2", req2.contains("Draft to rewrite cleanly:"))

    val outbound = repository.getRecentMessages(franklinJid, 10).lastOrNull { it.fromMe }
    assertNotNull("Expected outbound reply", outbound)
    assertEquals("Not much, just chilling. What's good?", outbound!!.textContent)
  }

  @Test
  fun `NvidiaNimClient recovers plain JSON HTTP 200 response when stream=true is requested`() = runTest {
    credStore.saveNvidiaApiKey("nvapi-plain-json-stream-fallback")
    mockServer.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody("""{"choices":[{"message":{"content":"Got your screenshot, the flight departs at 6:45 AM."}}],"usage":{"prompt_tokens":12,"completion_tokens":11,"total_tokens":23}}""")
    )

    val client = NvidiaNimClient(credStore, baseUrl = mockServer.url("/v1").toString())
    val model = NvidiaModelCapabilityDetector.inferFromDiscoveredModel("meta/llama-3.3-70b-instruct", "meta")
    var completed: com.example.ai.NimStreamEvent.Completed? = null
    client.streamChatCompletion(
      model = model,
      messages = listOf(NimChatMessage("user", "What time is my flight?")),
      tools = emptyList(),
      temperature = 0.3f,
      maxTokens = 256,
      stream = true,
    ).collect { ev ->
      if (ev is com.example.ai.NimStreamEvent.Completed) {
        completed = ev
      }
    }

    val finalCompleted = completed ?: error("Expected plain-JSON fallback to emit Completed event")
    assertEquals("Got your screenshot, the flight departs at 6:45 AM.", finalCompleted.fullText)
    assertEquals(23, finalCompleted.usage.totalTokens)
  }

  @Test
  fun `Voice catalog provides enhanced English Vosk packs and multi-provider Cloud ASR routing`() = runTest {
    // 1. Offline English Pro & regional English models exist with valid AlphaCephei URLs
    assertTrue(LocalWhisperModel.VOSK_EN_US_PRO.downloadUrl.contains("vosk-model-en-us-0.22-lgraph.zip"))
    assertTrue(LocalWhisperModel.VOSK_EN_IN_SMALL.downloadUrl.contains("vosk-model-small-en-in-0.4.zip"))
    assertEquals("Best EN", LocalWhisperModel.VOSK_EN_US_PRO.languageBadge)

    // 2. Cloud ASR ranking prefers modern English ASR models cleanly
    val discovered =
      listOf(
        NvidiaModelCapabilityDetector.inferFromDiscoveredModel("openai/whisper-large-v3", "openai"),
        NvidiaModelCapabilityDetector.inferFromDiscoveredModel("nvidia/parakeet-tdt-0.6b-v2", "nvidia"),
        NvidiaModelCapabilityDetector.inferFromDiscoveredModel("microsoft/phi-4-multimodal-instruct", "microsoft"),
      )
    val best = NvidiaModelCapabilityDetector.pickBestVoiceModel(discovered)
    assertEquals("nvidia/parakeet-tdt-0.6b-v2", best?.id)
    assertTrue(NvidiaModelCapabilityDetector.recommendedVoicePresetsForProvider(AiProviderType.GROQ).any { it.id == "whisper-large-v3-turbo" })
    assertTrue(NvidiaModelCapabilityDetector.recommendedVoicePresetsForProvider(AiProviderType.OPENAI).any { it.id == "gpt-4o-transcribe" })

    // 3. Multi-provider audioTargets routes custom/Groq ASR cleanly via audioTargets override
    credStore.saveProviderApiKey(AiProviderType.GROQ.name, "gsk_test_audio_key")
    credStore.saveNvidiaApiKey("nvapi-fallback-audio-key")
    val customSettings =
      AgentSettings(
        activeProvider = AiProviderType.CUSTOM,
        customProviderBaseUrl = mockServer.url("/v1").toString(),
        audioModelId = "whisper-large-v3-turbo",
        transcriptionMode = TranscriptionMode.CLOUD,
        autoTranscribeVoiceNotes = true,
      )
    val client = NvidiaNimClient(credStore, baseUrl = mockServer.url("/v1").toString())
    val manager = AiProviderManager(credStore, client)
    val audioTargets = manager.buildAudioTargets(customSettings, emptyList())
    assertTrue(audioTargets.any { it.provider == AiProviderType.CUSTOM && it.model.id == "whisper-large-v3-turbo" })
    assertTrue(audioTargets.any { it.provider == AiProviderType.GROQ || it.provider == AiProviderType.NVIDIA_NIM })

    mockServer.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody("""{"text":"Hey, can we push Friday's meeting to 3pm?"}""")
    )
    val dummyWav = File(context.cacheDir, "voice_test_en.wav").apply { writeBytes(ByteArray(128)) }
    val intel = AndroidMediaIntelligence(context, client)
    val transcript = intel.transcribeVoiceNote(dummyWav, customSettings, emptyList(), audioTargets)
    assertEquals("Hey, can we push Friday's meeting to 3pm?", transcript)
    dummyWav.delete()
  }

  @Test
  fun `ClawAgentRuntime recalls surrounding thread context when user replies to an older message outside recent window`() = runTest {
    credStore.saveNvidiaApiKey("nvapi-quoted-surrounding-context")
    val senderJid = "15553339999@s.whatsapp.net"
    repository.ensureConversation(senderJid, displayName = "Kofi", isGroup = false)
    repository.preferences.updateSettings {
      it.copy(
        autoReplyDirectChats = true,
        conversationContextEnabled = true,
        recentMessageReadingEnabled = true,
        recentMessageCount = 3, // Tiny recent window so old message #2 & #3 fall outside normal history!
        autoLearnContactMemory = false,
        streamingEnabled = false,
        responseDelayMs = 0,
        selectedModelId = "meta/llama-3.3-70b-instruct",
      )
    }

    val baseTs = System.currentTimeMillis() - 600_000L
    // Old thread explaining WHY "Me" made that decision
    repository.recordMessage(
      ChatMessageEntity(
        id = "OLD_CTX_1",
        chatJid = senderJid,
        senderJid = senderJid,
        senderName = "Kofi",
        fromMe = false,
        timestamp = baseTs,
        messageType = "TEXT",
        textContent = "Wait why are we moving the shoot to Sunday instead of Saturday?",
      )
    )
    repository.recordMessage(
      ChatMessageEntity(
        id = "true_${senderJid}_OLD_QUOTE_TARGET",
        chatJid = senderJid,
        senderJid = "me@local",
        senderName = "Me",
        fromMe = true,
        timestamp = baseTs + 5_000L,
        messageType = "TEXT",
        textContent = "Because the studio lighting rig won't arrive until Saturday night.",
      )
    )
    // Add 8 unrelated filler messages later so OLD_CTX_1 + OLD_QUOTE_TARGET are outside recentMessageCount=3
    for (idx in 1..8) {
      repository.recordMessage(
        ChatMessageEntity(
          id = "FILLER_$idx",
          chatJid = senderJid,
          senderJid = senderJid,
          senderName = "Kofi",
          fromMe = false,
          timestamp = baseTs + 20_000L + idx * 5_000L,
          messageType = "TEXT",
          textContent = "Unrelated small talk number $idx",
        )
      )
    }

    mockServer.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody("""{"choices":[{"message":{"content":"Yeah that's why Sunday works better — the lighting rig only lands Saturday night."}}]}""")
    )

    val client = NvidiaNimClient(credStore, baseUrl = mockServer.url("/v1").toString())
    val aiManager = AiProviderManager(credStore, client)
    val signalStore = WASignalProtocolStore(credStore, db.signalKeyDao())
    val sessionMgr = WhatsAppSessionManager(context, credStore, repository, WhatsAppCrypto(signalStore))
    val runtime =
      ClawAgentRuntime(
        context = context,
        repository = repository,
        sessionManager = sessionMgr,
        nimClient = client,
        mediaIntelligence = AndroidMediaIntelligence(context, client),
        aiProviderManager = aiManager,
      )

    runtime.handleIncomingEvent(
      IncomingWhatsAppEvent(
        messageId = "false_${senderJid}_ASK_WHY",
        chatJid = senderJid,
        senderJid = senderJid,
        senderName = "Kofi",
        isGroup = false,
        fromMe = false,
        timestampMs = System.currentTimeMillis(),
        messageType = "TEXT",
        textContent = "Wait remind me why you said that?",
        quotedMessageId = "OLD_QUOTE_TARGET",
        quotedFromMe = true,
      )
    )

    val reqBody = mockServer.takeRequest().body.readUtf8()
    assertTrue("Should include why-that-message-was-said neighborhood block", reqBody.contains("Why that message was said"))
    assertTrue("Should recover the preceding question outside recent window", reqBody.contains("moving the shoot to Sunday"))
    assertTrue("Should include the quoted message content", reqBody.contains("studio lighting rig won't arrive until Saturday night"))
  }
}

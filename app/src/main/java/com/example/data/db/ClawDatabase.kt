package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities =
    [
      ConversationEntity::class,
      ChatMessageEntity::class,
      AssistantMessageEntity::class,
      ContactMemoryEntity::class,
      SignalKeyEntity::class,
      AgentLogEntity::class,
      ScheduledMessageEntity::class,
      MessageProcessingJobEntity::class,
    ],
  version = 6,
  exportSchema = false,
)
abstract class ClawDatabase : RoomDatabase() {
  abstract fun conversationDao(): ConversationDao
  abstract fun chatMessageDao(): ChatMessageDao
  abstract fun assistantMessageDao(): AssistantMessageDao
  abstract fun contactMemoryDao(): ContactMemoryDao
  abstract fun signalKeyDao(): SignalKeyDao
  abstract fun agentLogDao(): AgentLogDao
  abstract fun scheduledMessageDao(): ScheduledMessageDao
  abstract fun messageProcessingJobDao(): MessageProcessingJobDao

  companion object {
    @Volatile private var INSTANCE: ClawDatabase? = null

    val MIGRATION_1_2 =
      object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            "ALTER TABLE chat_messages ADD COLUMN reactionEmoji TEXT NOT NULL DEFAULT ''"
          )
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `assistant_messages` (
              `id` TEXT NOT NULL,
              `role` TEXT NOT NULL,
              `textContent` TEXT NOT NULL,
              `imageLocalPath` TEXT,
              `reactionEmoji` TEXT NOT NULL DEFAULT '',
              `quotedPreview` TEXT,
              `toolActivitySummary` TEXT,
              `timestamp` INTEGER NOT NULL,
              PRIMARY KEY(`id`)
            )
            """.trimIndent()
          )
        }
      }

    val MIGRATION_2_3 =
      object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE assistant_messages ADD COLUMN fileLocalPath TEXT")
          db.execSQL("ALTER TABLE assistant_messages ADD COLUMN fileName TEXT")
          db.execSQL("ALTER TABLE assistant_messages ADD COLUMN fileMimeType TEXT")
        }
      }

    val MIGRATION_3_4 =
      object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `scheduled_messages` (
              `id` TEXT NOT NULL,
              `chatJid` TEXT NOT NULL,
              `displayName` TEXT NOT NULL,
              `messageText` TEXT NOT NULL,
              `scheduledAtMs` INTEGER NOT NULL,
              `status` TEXT NOT NULL DEFAULT 'PENDING',
              `createdAtMs` INTEGER NOT NULL,
              `sentAtMs` INTEGER,
              PRIMARY KEY(`id`)
            )
            """.trimIndent()
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_scheduled_messages_status_scheduledAtMs` ON `scheduled_messages` (`status`, `scheduledAtMs`)"
          )
        }
      }

    val MIGRATION_4_5 =
      object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE conversations ADD COLUMN recentContextCountOverride INTEGER")
          db.execSQL("ALTER TABLE conversations ADD COLUMN contextModeOverride TEXT")
          db.execSQL("ALTER TABLE conversations ADD COLUMN businessCategory TEXT NOT NULL DEFAULT ''")
          db.execSQL("ALTER TABLE conversations ADD COLUMN businessLeadScore INTEGER NOT NULL DEFAULT 0")
          db.execSQL("ALTER TABLE conversations ADD COLUMN businessIntent TEXT NOT NULL DEFAULT ''")
          db.execSQL("ALTER TABLE conversations ADD COLUMN businessNextAction TEXT NOT NULL DEFAULT ''")
          db.execSQL("ALTER TABLE conversations ADD COLUMN businessUpdatedAtMs INTEGER NOT NULL DEFAULT 0")
          db.execSQL("ALTER TABLE conversations ADD COLUMN lastProcessingState TEXT NOT NULL DEFAULT ''")

          db.execSQL("ALTER TABLE chat_messages ADD COLUMN mediaSha256 TEXT")

          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `processing_jobs` (
              `jobId` TEXT NOT NULL,
              `incomingMessageId` TEXT NOT NULL,
              `chatJid` TEXT NOT NULL,
              `triggerType` TEXT NOT NULL DEFAULT 'AUTO',
              `stage` TEXT NOT NULL DEFAULT 'DETECTED',
              `attemptCount` INTEGER NOT NULL DEFAULT 1,
              `errorMessage` TEXT NOT NULL DEFAULT '',
              `outgoingMessageId` TEXT,
              `replyHash` TEXT NOT NULL DEFAULT '',
              `createdAtMs` INTEGER NOT NULL,
              `updatedAtMs` INTEGER NOT NULL,
              PRIMARY KEY(`jobId`)
            )
            """.trimIndent()
          )
          db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_processing_jobs_incomingMessageId` ON `processing_jobs` (`incomingMessageId`)"
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_processing_jobs_chatJid_updatedAtMs` ON `processing_jobs` (`chatJid`, `updatedAtMs`)"
          )
        }
      }

    val MIGRATION_5_6 =
      object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE conversations ADD COLUMN lastSummarizedMessageId TEXT NOT NULL DEFAULT ''")
          db.execSQL("ALTER TABLE conversations ADD COLUMN summaryUpdatedAtMs INTEGER NOT NULL DEFAULT 0")
          db.execSQL("ALTER TABLE conversations ADD COLUMN summaryMessageCount INTEGER NOT NULL DEFAULT 0")
          db.execSQL("ALTER TABLE conversations ADD COLUMN summaryVersion INTEGER NOT NULL DEFAULT 0")
          db.execSQL("ALTER TABLE conversations ADD COLUMN chatType TEXT NOT NULL DEFAULT 'INDIVIDUAL'")
          db.execSQL("ALTER TABLE conversations ADD COLUMN perGroupPermissionOverride TEXT")

          db.execSQL("ALTER TABLE processing_jobs ADD COLUMN sendCorrelationId TEXT NOT NULL DEFAULT ''")
          db.execSQL("ALTER TABLE processing_jobs ADD COLUMN nextRetryAtMs INTEGER NOT NULL DEFAULT 0")
        }
      }

    fun getInstance(context: Context): ClawDatabase {
      return INSTANCE
        ?: synchronized(this) {
          INSTANCE
            ?: Room.databaseBuilder(
                context.applicationContext,
                ClawDatabase::class.java,
                "clawlink_wa.db",
              )
              .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
              .fallbackToDestructiveMigration(true)
              .build()
              .also { INSTANCE = it }
        }
    }
  }
}

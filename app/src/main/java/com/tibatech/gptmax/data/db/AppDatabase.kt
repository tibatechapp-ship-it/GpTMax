package com.tibatech.gptmax.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// ---------------------------------------------------------------------------
// Entities
// ---------------------------------------------------------------------------

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: String,          // "user" | "assistant"
    val content: String,
    val source: String = "",   // "LOCAL" | "CLOUD" | ""
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "agent_runs")
data class AgentRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goal: String,
    val phase: String,
    val summary: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
)

// ---------------------------------------------------------------------------
// DAOs
// ---------------------------------------------------------------------------

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :session ORDER BY createdAt ASC")
    fun observe(session: String): Flow<List<MessageEntity>>

    /**
     * Phase 6 (external review): authoritative, session-scoped history fetch.
 * A project switch must never feed the previous project's thread into a
 * new request — this reads the DB for the CURRENT session id instead of
 * a UI flow that may still hold the old session's list.
     */
    @Query("SELECT * FROM messages WHERE sessionId = :session ORDER BY createdAt DESC LIMIT :limit")
    suspend fun history(session: String, limit: Int): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE sessionId = :session")
    suspend fun clearSession(session: String)
}

@Dao
interface AgentRunDao {
    @Query("SELECT * FROM agent_runs ORDER BY startedAt DESC LIMIT 50")
    fun recent(): Flow<List<AgentRunEntity>>

    @Insert
    suspend fun insert(run: AgentRunEntity): Long

    @Query("UPDATE agent_runs SET phase = :phase, summary = :summary, finishedAt = :finishedAt WHERE id = :id")
    suspend fun finish(id: Long, phase: String, summary: String, finishedAt: Long)
}

// ---------------------------------------------------------------------------
// Database
// ---------------------------------------------------------------------------

@Database(
    entities = [MessageEntity::class, AgentRunEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun agentRunDao(): AgentRunDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "gptmax.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}

package org.opendots.agent.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant

data class ChatMessage(val id: Long, val role: String, val content: String, val createdAt: String)
data class AuditEntry(
    val id: Long,
    val createdAt: String,
    val capability: String,
    val target: String,
    val approvalSource: String,
    val result: String,
    val details: String
)
data class MemoryItem(val id: Long, val content: String, val createdAt: String)

class LocalStore(context: Context) : SQLiteOpenHelper(context, "open_dots_agent.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE messages(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            created_at TEXT NOT NULL
        )""")
        db.execSQL("""CREATE TABLE audit(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            created_at TEXT NOT NULL,
            capability TEXT NOT NULL,
            target TEXT NOT NULL,
            approval_source TEXT NOT NULL,
            result TEXT NOT NULL,
            details TEXT NOT NULL
        )""")
        db.execSQL("""CREATE TABLE policies(
            capability TEXT NOT NULL,
            target TEXT NOT NULL,
            state TEXT NOT NULL,
            PRIMARY KEY(capability, target)
        )""")
        db.execSQL("""CREATE TABLE memory(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            content TEXT NOT NULL,
            created_at TEXT NOT NULL
        )""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun addMessage(role: String, content: String): ChatMessage {
        val createdAt = Instant.now().toString()
        val values = ContentValues().apply {
            put("role", role)
            put("content", content)
            put("created_at", createdAt)
        }
        val id = writableDatabase.insertOrThrow("messages", null, values)
        return ChatMessage(id, role, content, createdAt)
    }

    fun listMessages(limit: Int = 200): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        readableDatabase.rawQuery(
            "SELECT id, role, content, created_at FROM messages ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += ChatMessage(c.getLong(0), c.getString(1), c.getString(2), c.getString(3))
            }
        }
        return out.reversed()
    }

    fun addAudit(
        capability: String,
        target: String,
        approvalSource: String,
        result: String,
        details: String = ""
    ): AuditEntry {
        val createdAt = Instant.now().toString()
        val values = ContentValues().apply {
            put("created_at", createdAt)
            put("capability", capability)
            put("target", target)
            put("approval_source", approvalSource)
            put("result", result)
            put("details", details)
        }
        val id = writableDatabase.insertOrThrow("audit", null, values)
        return AuditEntry(id, createdAt, capability, target, approvalSource, result, details)
    }

    fun listAudit(limit: Int = 200): List<AuditEntry> {
        val out = mutableListOf<AuditEntry>()
        readableDatabase.rawQuery(
            "SELECT id, created_at, capability, target, approval_source, result, details FROM audit ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += AuditEntry(
                    c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getString(4), c.getString(5), c.getString(6)
                )
            }
        }
        return out
    }

    fun readPolicy(capability: String, target: String): String? {
        readableDatabase.rawQuery(
            "SELECT state FROM policies WHERE capability = ? AND target = ?",
            arrayOf(capability, target)
        ).use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    fun writePolicy(capability: String, target: String, state: String) {
        val values = ContentValues().apply {
            put("capability", capability)
            put("target", target)
            put("state", state)
        }
        writableDatabase.insertWithOnConflict(
            "policies", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun clearPolicy(capability: String, target: String) {
        writableDatabase.delete(
            "policies",
            "capability = ? AND target = ?",
            arrayOf(capability, target)
        )
    }

    fun addMemory(content: String): MemoryItem {
        val createdAt = Instant.now().toString()
        val values = ContentValues().apply {
            put("content", content.trim())
            put("created_at", createdAt)
        }
        val id = writableDatabase.insertOrThrow("memory", null, values)
        return MemoryItem(id, content.trim(), createdAt)
    }

    fun searchMemory(query: String, limit: Int = 8): List<MemoryItem> {
        val token = query
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .maxByOrNull { it.length }
        val sql: String
        val args: Array<String>
        if (token == null) {
            sql = "SELECT id, content, created_at FROM memory ORDER BY id DESC LIMIT ?"
            args = arrayOf(limit.toString())
        } else {
            sql = "SELECT id, content, created_at FROM memory WHERE lower(content) LIKE ? ORDER BY id DESC LIMIT ?"
            args = arrayOf("%$token%", limit.toString())
        }
        val out = mutableListOf<MemoryItem>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out += MemoryItem(c.getLong(0), c.getString(1), c.getString(2))
        }
        return out
    }

    fun listMemory(limit: Int = 100): List<MemoryItem> = searchMemory("", limit)

    fun clearPolicies() {
        writableDatabase.delete("policies", null, null)
    }

    fun clearMemory() {
        writableDatabase.delete("memory", null, null)
    }
}

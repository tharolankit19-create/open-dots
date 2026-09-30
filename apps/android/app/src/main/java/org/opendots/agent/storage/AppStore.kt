package org.opendots.agent.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.opendots.agent.permission.PermissionState
import org.opendots.agent.permission.PolicyBackend

enum class ProviderKind(val title: String) {
    OPENAI("OpenAI"),
    OPENAI_COMPATIBLE("OpenAI-compatible"),
    ANTHROPIC("Anthropic"),
    GEMINI("Google Gemini"),
    OPENROUTER("OpenRouter")
}

data class ProviderConfig(
    val kind: ProviderKind,
    val modelId: String,
    val baseUrl: String,
    val configured: Boolean = true
)

data class ChatMessage(val id: Long, val role: String, val content: String, val createdAt: Long)
data class AuditEntry(
    val id: Long,
    val timestamp: Long,
    val action: String,
    val target: String,
    val approvalSource: String,
    val result: String,
    val detail: String
)
data class MemoryEntry(val id: Long, val text: String, val createdAt: Long)

class AppStore(context: Context) : SQLiteOpenHelper(context, "open_dots_agent.db", null, 1) {
    private val prefs = context.getSharedPreferences("opendots_agent_config", Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE messages(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )""")
        db.execSQL("""CREATE TABLE audit(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp INTEGER NOT NULL,
            action TEXT NOT NULL,
            target TEXT NOT NULL,
            approval_source TEXT NOT NULL,
            result TEXT NOT NULL,
            detail TEXT NOT NULL
        )""")
        db.execSQL("""CREATE TABLE memory(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            text TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun saveProvider(config: ProviderConfig) {
        prefs.edit()
            .putString("provider_kind", config.kind.name)
            .putString("provider_model", config.modelId)
            .putString("provider_base", config.baseUrl)
            .putBoolean("provider_configured", true)
            .apply()
    }

    fun loadProvider(): ProviderConfig? {
        if (!prefs.getBoolean("provider_configured", false)) return null
        val kind = runCatching {
            ProviderKind.valueOf(prefs.getString("provider_kind", ProviderKind.OPENAI.name)!!)
        }.getOrDefault(ProviderKind.OPENAI)
        return ProviderConfig(
            kind = kind,
            modelId = prefs.getString("provider_model", "") ?: "",
            baseUrl = prefs.getString("provider_base", "") ?: ""
        )
    }

    fun addMessage(role: String, content: String) {
        writableDatabase.execSQL(
            "INSERT INTO messages(role, content, created_at) VALUES(?,?,?)",
            arrayOf(role, content, System.currentTimeMillis())
        )
    }

    fun messages(limit: Int = 100): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        readableDatabase.rawQuery(
            "SELECT id, role, content, created_at FROM messages ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += ChatMessage(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))
            }
        }
        return out.reversed()
    }

    fun audit(action: String, target: String, approval: String, result: String, detail: String = "") {
        writableDatabase.execSQL(
            "INSERT INTO audit(timestamp, action, target, approval_source, result, detail) VALUES(?,?,?,?,?,?)",
            arrayOf(System.currentTimeMillis(), action, target, approval, result, detail.take(1000))
        )
    }

    fun audits(limit: Int = 100): List<AuditEntry> {
        val out = mutableListOf<AuditEntry>()
        readableDatabase.rawQuery(
            "SELECT id,timestamp,action,target,approval_source,result,detail FROM audit ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += AuditEntry(
                    c.getLong(0), c.getLong(1), c.getString(2), c.getString(3),
                    c.getString(4), c.getString(5), c.getString(6)
                )
            }
        }
        return out
    }

    fun addMemory(text: String) {
        writableDatabase.execSQL(
            "INSERT INTO memory(text, created_at) VALUES(?,?)",
            arrayOf(text.trim().take(4000), System.currentTimeMillis())
        )
    }

    fun memories(limit: Int = 100): List<MemoryEntry> {
        val out = mutableListOf<MemoryEntry>()
        readableDatabase.rawQuery(
            "SELECT id,text,created_at FROM memory ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out += MemoryEntry(c.getLong(0), c.getString(1), c.getLong(2))
        }
        return out
    }

    fun clearMemory() = writableDatabase.execSQL("DELETE FROM memory")

    fun policyBackend(): PolicyBackend = PreferencesPolicyBackend(prefs)
}

private class PreferencesPolicyBackend(
    private val prefs: android.content.SharedPreferences
) : PolicyBackend {
    override fun get(key: String): PermissionState? =
        prefs.getString("policy:$key", null)?.let { runCatching { PermissionState.valueOf(it) }.getOrNull() }

    override fun put(key: String, state: PermissionState) {
        prefs.edit().putString("policy:$key", state.name).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove("policy:$key").apply()
    }
}

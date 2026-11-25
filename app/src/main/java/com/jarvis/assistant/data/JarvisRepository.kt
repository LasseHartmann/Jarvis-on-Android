package com.jarvis.assistant.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.jarvis.assistant.agent.AuditLogEntry
import com.jarvis.assistant.agent.ChatMessage
import com.jarvis.assistant.agent.JarvisMemory
import com.jarvis.assistant.agent.JarvisProtocol
import com.jarvis.assistant.agent.MessageRole
import com.jarvis.assistant.agent.RiskLevel
import com.jarvis.assistant.agent.ToolExecutionTrace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

interface JarvisStore {
    fun getMemoriesSnapshot(): List<JarvisMemory>
    fun upsertMemory(key: String, content: String, category: String = "general")
    fun deleteMemoryByKey(key: String): Boolean
    fun getProtocolsSnapshot(): List<JarvisProtocol>
    fun logAudit(
        toolName: String,
        argumentsJson: String,
        riskLevel: RiskLevel,
        approved: Boolean,
        success: Boolean,
        summary: String
    )
}

class JarvisRepository(private val dbHelper: JarvisDatabaseHelper) : JarvisStore {

    private val lock = Any()

    private val _messages = MutableStateFlow(loadRecentMessages())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _memories = MutableStateFlow(loadAllMemories())
    val memories: StateFlow<List<JarvisMemory>> = _memories.asStateFlow()

    private val _auditLogs = MutableStateFlow(loadRecentAuditLogs())
    val auditLogs: StateFlow<List<AuditLogEntry>> = _auditLogs.asStateFlow()

    private val _protocols = MutableStateFlow(loadAllProtocols())
    val protocols: StateFlow<List<JarvisProtocol>> = _protocols.asStateFlow()

    fun addMessage(message: ChatMessage): Long = synchronized(lock) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("role", message.role.name)
            put("content", message.content)
            put("timestamp_ms", message.timestampMs)
            put("traces_json", serializeTraces(message.traces))
        }
        val id = db.insert(JarvisDatabaseHelper.TABLE_MESSAGES, null, values)
        _messages.value = loadRecentMessages()
        id
    }

    fun clearConversation() = synchronized(lock) {
        dbHelper.writableDatabase.delete(JarvisDatabaseHelper.TABLE_MESSAGES, null, null)
        _messages.value = emptyList()
    }

    override fun getMemoriesSnapshot(): List<JarvisMemory> = _memories.value

    override fun upsertMemory(key: String, content: String, category: String) = synchronized(lock) {
        val normalizedKey = key.trim().lowercase().replace(Regex("\\s+"), "_")
        if (normalizedKey.isBlank()) return
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("mem_key", normalizedKey)
            put("content", content.trim())
            put("category", category.trim().ifBlank { "general" })
            put("updated_at_ms", System.currentTimeMillis())
        }
        db.insertWithOnConflict(
            JarvisDatabaseHelper.TABLE_MEMORIES,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        _memories.value = loadAllMemories()
    }

    override fun deleteMemoryByKey(key: String): Boolean = synchronized(lock) {
        val normalized = key.trim().lowercase().replace(Regex("\\s+"), "_")
        val rows = dbHelper.writableDatabase.delete(
            JarvisDatabaseHelper.TABLE_MEMORIES,
            "mem_key = ?",
            arrayOf(normalized)
        )
        _memories.value = loadAllMemories()
        rows > 0
    }

    fun deleteMemoryById(id: Long) = synchronized(lock) {
        dbHelper.writableDatabase.delete(
            JarvisDatabaseHelper.TABLE_MEMORIES,
            "id = ?",
            arrayOf(id.toString())
        )
        _memories.value = loadAllMemories()
    }

    override fun getProtocolsSnapshot(): List<JarvisProtocol> = _protocols.value

    fun addCustomProtocol(name: String, triggerPhrase: String, description: String, commandPrompt: String) = synchronized(lock) {
        val values = ContentValues().apply {
            put("name", name.trim())
            put("trigger_phrase", triggerPhrase.trim().lowercase())
            put("description", description.trim())
            put("command_prompt", commandPrompt.trim())
            put("is_built_in", 0)
        }
        dbHelper.writableDatabase.insertWithOnConflict(
            JarvisDatabaseHelper.TABLE_PROTOCOLS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        _protocols.value = loadAllProtocols()
    }

    fun deleteProtocol(id: Long) = synchronized(lock) {
        dbHelper.writableDatabase.delete(
            JarvisDatabaseHelper.TABLE_PROTOCOLS,
            "id = ? AND is_built_in = 0",
            arrayOf(id.toString())
        )
        _protocols.value = loadAllProtocols()
    }

    override fun logAudit(
        toolName: String,
        argumentsJson: String,
        riskLevel: RiskLevel,
        approved: Boolean,
        success: Boolean,
        summary: String
    ) = synchronized(lock) {
        val values = ContentValues().apply {
            put("timestamp_ms", System.currentTimeMillis())
            put("tool_name", toolName)
            put("arguments_json", argumentsJson.take(1200))
            put("risk_level", riskLevel.name)
            put("approved", if (approved) 1 else 0)
            put("success", if (success) 1 else 0)
            put("summary", summary.take(500))
        }
        dbHelper.writableDatabase.insert(JarvisDatabaseHelper.TABLE_AUDIT, null, values)
        _auditLogs.value = loadRecentAuditLogs()
    }

    fun clearAuditLogs() = synchronized(lock) {
        dbHelper.writableDatabase.delete(JarvisDatabaseHelper.TABLE_AUDIT, null, null)
        _auditLogs.value = emptyList()
    }

    private fun loadRecentMessages(limit: Int = 80): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT id, role, content, timestamp_ms, traces_json FROM ${JarvisDatabaseHelper.TABLE_MESSAGES} ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val role = runCatching { MessageRole.valueOf(cursor.getString(1)) }.getOrDefault(MessageRole.SYSTEM)
                list.add(
                    ChatMessage(
                        id = cursor.getLong(0),
                        role = role,
                        content = cursor.getString(2),
                        timestampMs = cursor.getLong(3),
                        traces = parseTraces(cursor.getString(4))
                    )
                )
            }
        }
        return list.reversed()
    }

    private fun loadAllMemories(): List<JarvisMemory> {
        val list = mutableListOf<JarvisMemory>()
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT id, mem_key, content, category, updated_at_ms FROM ${JarvisDatabaseHelper.TABLE_MEMORIES} ORDER BY updated_at_ms DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    JarvisMemory(
                        id = cursor.getLong(0),
                        key = cursor.getString(1),
                        content = cursor.getString(2),
                        category = cursor.getString(3),
                        updatedAtMs = cursor.getLong(4)
                    )
                )
            }
        }
        return list
    }

    private fun loadRecentAuditLogs(limit: Int = 100): List<AuditLogEntry> {
        val list = mutableListOf<AuditLogEntry>()
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT id, timestamp_ms, tool_name, arguments_json, risk_level, approved, success, summary FROM ${JarvisDatabaseHelper.TABLE_AUDIT} ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val risk = runCatching { RiskLevel.valueOf(cursor.getString(4)) }.getOrDefault(RiskLevel.SAFE)
                list.add(
                    AuditLogEntry(
                        id = cursor.getLong(0),
                        timestampMs = cursor.getLong(1),
                        toolName = cursor.getString(2),
                        argumentsJson = cursor.getString(3),
                        riskLevel = risk,
                        approved = cursor.getInt(5) == 1,
                        success = cursor.getInt(6) == 1,
                        summary = cursor.getString(7)
                    )
                )
            }
        }
        return list
    }

    private fun loadAllProtocols(): List<JarvisProtocol> {
        val list = mutableListOf<JarvisProtocol>()
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT id, name, trigger_phrase, description, command_prompt, is_built_in FROM ${JarvisDatabaseHelper.TABLE_PROTOCOLS} ORDER BY is_built_in DESC, name ASC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    JarvisProtocol(
                        id = cursor.getLong(0),
                        name = cursor.getString(1),
                        triggerPhrase = cursor.getString(2),
                        description = cursor.getString(3),
                        commandPrompt = cursor.getString(4),
                        isBuiltIn = cursor.getInt(5) == 1
                    )
                )
            }
        }
        return list
    }

    private fun serializeTraces(traces: List<ToolExecutionTrace>): String {
        if (traces.isEmpty()) return "[]"
        val arr = JSONArray()
        traces.forEach { t ->
            arr.put(
                JSONObject().apply {
                    put("toolName", t.toolName)
                    put("argumentsSummary", t.argumentsSummary)
                    put("success", t.success)
                    put("output", t.output.take(400))
                    put("riskLevel", t.riskLevel.name)
                    put("durationMs", t.durationMs)
                    put("timestampMs", t.timestampMs)
                }
            )
        }
        return arr.toString()
    }

    private fun parseTraces(json: String?): List<ToolExecutionTrace> {
        if (json.isNullOrBlank() || json == "[]") return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        ToolExecutionTrace(
                            toolName = obj.optString("toolName"),
                            argumentsSummary = obj.optString("argumentsSummary"),
                            success = obj.optBoolean("success"),
                            output = obj.optString("output"),
                            riskLevel = runCatching { RiskLevel.valueOf(obj.optString("riskLevel")) }.getOrDefault(RiskLevel.SAFE),
                            durationMs = obj.optLong("durationMs"),
                            timestampMs = obj.optLong("timestampMs")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}

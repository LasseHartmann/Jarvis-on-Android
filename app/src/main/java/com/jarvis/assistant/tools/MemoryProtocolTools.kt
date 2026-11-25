package com.jarvis.assistant.tools

import com.jarvis.assistant.agent.RiskLevel
import com.jarvis.assistant.data.JarvisStore
import org.json.JSONArray
import org.json.JSONObject

class RememberFactTool(private val store: JarvisStore) : JarvisTool {
    override val spec = ToolSpec(
        name = "remember_fact",
        description = "Store a persistent memory about the user's preferences, routines, contacts, or context so J.A.R.V.I.S. remembers it across sessions.",
        params = listOf(
            ToolParam("key", "string", "Short unique snake_case key (e.g. 'home_address', 'favorite_playlist', 'preferred_language').", required = true),
            ToolParam("content", "string", "The fact or preference to remember.", required = true),
            ToolParam("category", "string", "Category such as 'preference', 'routine', 'personal', or 'device'.", required = false)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val key = args.optString("key", "").trim()
        val content = args.optString("content", "").trim()
        val category = args.optString("category", "general")
        if (key.isBlank() || content.isBlank()) {
            return ToolExecutionOutput(false, "Both key and content are required.")
        }
        store.upsertMemory(key, content, category)
        return ToolExecutionOutput(true, "Stored memory '$key': $content")
    }
}

class RecallMemoriesTool(private val store: JarvisStore) : JarvisTool {
    override val spec = ToolSpec(
        name = "recall_memories",
        description = "Search or retrieve all persistent J.A.R.V.I.S. memories stored on the device.",
        params = listOf(
            ToolParam("query", "string", "Optional keyword to filter memories.", required = false)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val query = args.optString("query", "").trim().lowercase()
        val all = store.getMemoriesSnapshot()
        val filtered = if (query.isBlank()) all else all.filter {
            it.key.lowercase().contains(query) || it.content.lowercase().contains(query) || it.category.lowercase().contains(query)
        }
        if (filtered.isEmpty()) {
            return ToolExecutionOutput(true, "No matching memories found.")
        }
        val arr = JSONArray()
        val summary = filtered.joinToString("\n") { m ->
            arr.put(JSONObject().put("key", m.key).put("content", m.content).put("category", m.category))
            "- [${m.category}] ${m.key}: ${m.content}"
        }
        return ToolExecutionOutput(true, summary, JSONObject().put("memories", arr))
    }
}

class ForgetMemoryTool(private val store: JarvisStore) : JarvisTool {
    override val spec = ToolSpec(
        name = "forget_memory",
        description = "Delete a stored memory by its key.",
        params = listOf(
            ToolParam("key", "string", "Memory key to remove.", required = true)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val key = args.optString("key", "").trim()
        if (key.isBlank()) return ToolExecutionOutput(false, "Memory key is required.")
        val removed = store.deleteMemoryByKey(key)
        return ToolExecutionOutput(removed, if (removed) "Memory '$key' erased." else "No memory found with key '$key'.")
    }
}

class ListProtocolsTool(private val store: JarvisStore) : JarvisTool {
    override val spec = ToolSpec(
        name = "list_protocols",
        description = "List all available Stark Protocols / macro routines configured on this device.",
        params = emptyList(),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val protocols = store.getProtocolsSnapshot()
        val text = protocols.joinToString("\n") { p -> "- ${p.name} (trigger: '${p.triggerPhrase}'): ${p.description}" }
        return ToolExecutionOutput(true, text)
    }
}

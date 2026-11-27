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

class CreateProtocolTool(private val store: JarvisStore) : JarvisTool {
    override val spec = ToolSpec(
        name = "create_protocol",
        description = "Create or update a custom voice-triggered Stark Protocol / macro routine (e.g. 'Gym Mode' that sets volume to 80%, enables DND, and plays workout music on Spotify). Once saved, saying the trigger phrase executes the full sequence.",
        params = listOf(
            ToolParam("name", "string", "Display name of the routine (e.g. 'Gym Mode', 'Drive Mode').", required = true),
            ToolParam("trigger_phrase", "string", "Short voice phrase to activate it (e.g. 'gym mode', 'sportmodus').", required = true),
            ToolParam("description", "string", "Short summary of what the routine does.", required = true),
            ToolParam("command_prompt", "string", "Detailed multi-step instruction Jarvis will execute when triggered.", required = true)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val name = args.optString("name", "").trim()
        val trigger = args.optString("trigger_phrase", "").trim().lowercase()
        val desc = args.optString("description", "").trim()
        val prompt = args.optString("command_prompt", "").trim()
        if (name.isBlank() || trigger.isBlank() || prompt.isBlank()) {
            return ToolExecutionOutput(false, "name, trigger_phrase, and command_prompt are required.")
        }
        store.addCustomProtocol(
            name = name,
            triggerPhrase = trigger,
            description = desc.ifBlank { prompt.take(120) },
            commandPrompt = prompt
        )
        return ToolExecutionOutput(true, "Saved custom protocol '$name' (trigger: '$trigger').")
    }
}

class DeleteProtocolTool(private val store: JarvisStore) : JarvisTool {
    override val spec = ToolSpec(
        name = "delete_protocol",
        description = "Delete a user-created custom Stark Protocol by its name or trigger phrase.",
        params = listOf(
            ToolParam("name", "string", "Protocol name or trigger phrase to delete.", required = true)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val name = args.optString("name", "").trim()
        if (name.isBlank()) return ToolExecutionOutput(false, "Protocol name is required.")
        val deleted = store.deleteProtocolByName(name)
        return ToolExecutionOutput(
            deleted,
            if (deleted) "Deleted custom protocol '$name'." else "No custom protocol found matching '$name' (built-in protocols cannot be deleted)."
        )
    }
}

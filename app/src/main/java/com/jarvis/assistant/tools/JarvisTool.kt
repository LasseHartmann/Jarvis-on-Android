package com.jarvis.assistant.tools

import com.jarvis.assistant.agent.RiskLevel
import org.json.JSONArray
import org.json.JSONObject

data class ToolParam(
    val name: String,
    val type: String, // "string", "integer", "number", "boolean"
    val description: String,
    val required: Boolean = false,
    val enumValues: List<String>? = null
)

data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ToolParam> = emptyList(),
    val defaultRisk: RiskLevel = RiskLevel.SAFE
) {
    fun toJsonSchema(): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        for (p in params) {
            val prop = JSONObject().apply {
                put("type", p.type)
                put("description", p.description)
                if (!p.enumValues.isNullOrEmpty()) {
                    put("enum", JSONArray(p.enumValues))
                }
            }
            properties.put(p.name, prop)
            if (p.required) required.put(p.name)
        }
        return JSONObject().apply {
            put("type", "object")
            put("properties", properties)
            if (required.length() > 0) {
                put("required", required)
            }
        }
    }
}

data class ToolExecutionOutput(
    val success: Boolean,
    val message: String,
    val data: JSONObject? = null
)

interface JarvisTool {
    val spec: ToolSpec
    suspend fun execute(args: JSONObject): ToolExecutionOutput
}

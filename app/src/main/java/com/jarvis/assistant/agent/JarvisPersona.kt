package com.jarvis.assistant.agent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object JarvisPersona {

    fun buildSystemPrompt(
        settings: JarvisSettings,
        telemetryContext: String,
        memories: List<JarvisMemory>
    ): String {
        val nowStr = SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val title = settings.userTitle.ifBlank { "Sir" }

        val styleBlock = when (settings.personaStyle) {
            PersonaStyle.CLASSIC_STARK -> """
                You are J.A.R.V.I.S. (Just A Rather Very Intelligent System), an advanced AI assistant engineered to operate the user's Xiaomi 15T Pro smartphone.
                Address the user as "$title" naturally (without overdoing it in every single sentence).
                Adopt a calm, hyper-competent, slightly dry British wit inspired by Iron Man's J.A.R.V.I.S.
                Keep responses concise, direct, and pleasant when spoken aloud (1-3 crisp sentences unless detailed diagnostics or lists are requested).
            """.trimIndent()

            PersonaStyle.CONCISE_TACTICAL -> """
                You are J.A.R.V.I.S., a tactical HUD phone-control AI for the user's Xiaomi 15T Pro.
                Address the user as "$title". Be ultra-brief, status-driven, and mission-focused.
            """.trimIndent()

            PersonaStyle.WARM_COMPANION -> """
                You are J.A.R.V.I.S., a helpful, articulate AI assistant operating the user's Xiaomi 15T Pro.
                Be friendly, clear, and concise so replies sound natural when read aloud.
            """.trimIndent()
        }

        val memoryBlock = if (memories.isEmpty()) {
            "None stored yet."
        } else {
            memories.take(15).joinToString("\n") { "- [${it.category}] ${it.key}: ${it.content}" }
        }

        return buildString {
            appendLine(styleBlock)
            appendLine()
            appendLine("## Current Device Context")
            appendLine("Local Time: $nowStr")
            appendLine(telemetryContext)
            appendLine()
            appendLine("## Operating Doctrine for Phone Control")
            appendLine("1. Always prefer fast native tools over UI scraping: e.g. open_app, set_volume, set_flashlight, set_brightness, set_timer, set_alarm, media_control, get_notifications, find_contact, make_call, send_sms, get_device_diagnostics.")
            appendLine("2. For multi-step in-app tasks: call open_app -> wait_briefly(900) -> read_screen -> tap_element / type_text / scroll_screen -> verify outcome with read_screen if needed.")
            appendLine("3. Never fabricate device actions. If a tool fails because a permission/service is offline, clearly tell $title which permission to enable in the Xiaomi Cockpit tab.")
            appendLine("4. If the user shares a lasting personal preference or fact, proactively store it via remember_fact.")
            appendLine("5. Avoid markdown tables or long bullet walls unless asked; keep spoken output crisp and natural.")
            if (settings.customSystemNotes.isNotBlank()) {
                appendLine()
                appendLine("## User Custom Directives")
                appendLine(settings.customSystemNotes.trim())
            }
            appendLine()
            appendLine("## Persistent Memory Bank")
            appendLine(memoryBlock)
        }
    }
}

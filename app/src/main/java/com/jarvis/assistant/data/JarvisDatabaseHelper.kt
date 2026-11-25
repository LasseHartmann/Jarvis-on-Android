package com.jarvis.assistant.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class JarvisDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_MESSAGES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                traces_json TEXT NOT NULL DEFAULT '[]'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_MEMORIES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                mem_key TEXT NOT NULL UNIQUE,
                content TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT 'general',
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_AUDIT (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp_ms INTEGER NOT NULL,
                tool_name TEXT NOT NULL,
                arguments_json TEXT NOT NULL,
                risk_level TEXT NOT NULL,
                approved INTEGER NOT NULL,
                success INTEGER NOT NULL,
                summary TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_PROTOCOLS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                trigger_phrase TEXT NOT NULL,
                description TEXT NOT NULL,
                command_prompt TEXT NOT NULL,
                is_built_in INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        seedDefaultProtocols(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Reserved for future schema migrations
    }

    private fun seedDefaultProtocols(db: SQLiteDatabase) {
        val defaults = listOf(
            arrayOf(
                "Suit Diagnostics",
                "suit diagnostics",
                "Full Xiaomi 15T Pro battery, thermal, RAM, storage & network telemetry report.",
                "Run full device diagnostics and deliver a concise Stark status report."
            ),
            arrayOf(
                "Morning Briefing",
                "morning briefing",
                "Check notifications, upcoming calendar events, and battery readiness.",
                "Run device diagnostics, read active notifications, check today's calendar events, and give me a crisp morning briefing."
            ),
            arrayOf(
                "Night Protocol",
                "night protocol",
                "Dim screen to 10%, silence ringer, enable Do Not Disturb, check battery.",
                "Initiate Night Protocol: set screen brightness to 10%, switch ringer mode to silent, enable Do Not Disturb, and report battery level."
            ),
            arrayOf(
                "Focus Protocol",
                "focus protocol",
                "Enable Do Not Disturb, set media volume to 25%, pause distracting audio.",
                "Initiate Focus Protocol: enable Do Not Disturb and set media volume to 25% so I can concentrate."
            ),
            arrayOf(
                "Stealth Mode",
                "stealth mode",
                "Silent ringer, zero media volume, dim display to 15%, flashlight off.",
                "Initiate Stealth Mode: set ringer to silent, set media volume to 0%, turn flashlight off, and set brightness to 15%."
            )
        )
        defaults.forEach { row ->
            val values = ContentValues().apply {
                put("name", row[0])
                put("trigger_phrase", row[1])
                put("description", row[2])
                put("command_prompt", row[3])
                put("is_built_in", 1)
            }
            db.insertWithOnConflict(TABLE_PROTOCOLS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    companion object {
        const val DB_NAME = "jarvis_core.db"
        const val DB_VERSION = 1

        const val TABLE_MESSAGES = "chat_messages"
        const val TABLE_MEMORIES = "memories"
        const val TABLE_AUDIT = "audit_logs"
        const val TABLE_PROTOCOLS = "protocols"
    }
}

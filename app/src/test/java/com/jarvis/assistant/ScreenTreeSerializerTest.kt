package com.jarvis.assistant

import com.jarvis.assistant.services.ScreenSnapshot
import com.jarvis.assistant.services.ScreenTreeSerializer
import com.jarvis.assistant.services.UiNodeSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTreeSerializerTest {

    @Test
    fun redactsPasswordNodesAndFormatsInteractiveTree() {
        val snapshot = ScreenSnapshot(
            packageName = "com.example.chat",
            screenWidth = 1280,
            screenHeight = 2772,
            isSensitiveApp = false,
            nodes = listOf(
                UiNodeSnapshot(
                    index = 1,
                    text = "SuperSecret123",
                    contentDescription = "Password",
                    className = "android.widget.EditText",
                    viewId = "com.example:id/et_pass",
                    left = 100,
                    top = 400,
                    right = 1100,
                    bottom = 520,
                    clickable = true,
                    editable = true,
                    scrollable = false,
                    checked = null,
                    focused = true,
                    isPassword = true
                ),
                UiNodeSnapshot(
                    index = 2,
                    text = "Sign In",
                    contentDescription = "",
                    className = "android.widget.Button",
                    viewId = "com.example:id/btn_login",
                    left = 200,
                    top = 600,
                    right = 1000,
                    bottom = 720,
                    clickable = true,
                    editable = false,
                    scrollable = false,
                    checked = null,
                    focused = false,
                    isPassword = false
                )
            )
        )

        val formatted = ScreenTreeSerializer.formatForLlm(snapshot)
        assertFalse(formatted.contains("SuperSecret123"))
        assertTrue(formatted.contains("[REDACTED_PASSWORD]"))
        assertTrue(formatted.contains("#2 [BTN,CLICK] \"Sign In\" id=btn_login @(600,660)"))
    }

    @Test
    fun shieldsSensitiveFinancialPackages() {
        assertTrue(ScreenTreeSerializer.isSensitivePackage("com.x8bit.bitwarden"))
        assertTrue(ScreenTreeSerializer.isSensitivePackage("de.sparkasse.android"))
        assertFalse(ScreenTreeSerializer.isSensitivePackage("com.spotify.music"))

        val snapshot = ScreenSnapshot(
            packageName = "com.x8bit.bitwarden",
            screenWidth = 1280,
            screenHeight = 2772,
            isSensitiveApp = true,
            nodes = emptyList()
        )
        val out = ScreenTreeSerializer.formatForLlm(snapshot)
        assertTrue(out.contains("SECURITY SHIELD ACTIVE"))
    }
}

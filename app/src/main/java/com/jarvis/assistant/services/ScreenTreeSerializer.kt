package com.jarvis.assistant.services

data class UiNodeSnapshot(
    val index: Int,
    val text: String,
    val contentDescription: String,
    val className: String,
    val viewId: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?,
    val focused: Boolean,
    val isPassword: Boolean
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val primaryLabel: String
        get() = when {
            isPassword -> "[REDACTED_PASSWORD]"
            text.isNotBlank() -> text
            contentDescription.isNotBlank() -> contentDescription
            else -> ""
        }
}

data class ScreenSnapshot(
    val packageName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val isSensitiveApp: Boolean,
    val nodes: List<UiNodeSnapshot>,
    val capturedAtMs: Long = System.currentTimeMillis()
)

object ScreenTreeSerializer {

    private val sensitivePackageTokens = listOf(
        "bitwarden", "onepassword", "1password", "keepass", "lastpass", "dashlane", "proton.pass",
        "authenticator", "authy", "duomobile",
        "walletnfcrel", "google.android.apps.wallet", "paypal", "revolut", "n26", "klarna",
        "sparkasse", "commerzbank", "deutschebank", "ing.diba", "dkb", "volksbank", "postbank",
        "chase", "wellsfargo", "bankofamerica", "citibank", "banking", "mobilebank", "trade.republic"
    )

    fun isSensitivePackage(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        if (pkg.isBlank()) return false
        return sensitivePackageTokens.any { token -> pkg.contains(token) }
    }

    fun shortClassName(rawClass: String): String {
        val simple = rawClass.substringAfterLast('.')
        return when {
            simple.contains("Button", ignoreCase = true) -> "BTN"
            simple.contains("EditText", ignoreCase = true) -> "INPUT"
            simple.contains("Switch", ignoreCase = true) || simple.contains("CheckBox", ignoreCase = true) -> "TOGGLE"
            simple.contains("Image", ignoreCase = true) -> "IMG"
            simple.contains("TextView", ignoreCase = true) -> "TXT"
            simple.contains("RecyclerView", ignoreCase = true) || simple.contains("ScrollView", ignoreCase = true) -> "LIST"
            else -> simple.take(10).uppercase().ifBlank { "VIEW" }
        }
    }

    fun formatForLlm(snapshot: ScreenSnapshot, maxNodes: Int = 75): String = buildString {
        appendLine("SCREEN_APP: ${snapshot.packageName.ifBlank { "unknown" }} (${snapshot.screenWidth}x${snapshot.screenHeight})")
        if (snapshot.isSensitiveApp) {
            appendLine("[SECURITY SHIELD ACTIVE] Sensitive financial/password app detected. Screen text is redacted; confirm with the user before interacting.")
            return@buildString
        }
        if (snapshot.nodes.isEmpty()) {
            appendLine("NO_INTERACTIVE_NODES_FOUND")
            return@buildString
        }

        val shown = snapshot.nodes.take(maxNodes)
        for (node in shown) {
            val flags = buildList {
                add(shortClassName(node.className))
                if (node.clickable) add("CLICK")
                if (node.editable) add("EDIT")
                if (node.scrollable) add("SCROLL")
                if (node.focused) add("FOCUS")
                if (node.checked != null) add(if (node.checked) "ON" else "OFF")
            }.joinToString(",")

            val safeText = if (node.isPassword) "[REDACTED_PASSWORD]" else sanitize(node.text)
            val safeDesc = if (node.isPassword) "" else sanitize(node.contentDescription)

            append("#${node.index} [$flags]")
            if (safeText.isNotBlank()) append(" \"$safeText\"")
            if (safeDesc.isNotBlank() && !safeDesc.equals(safeText, ignoreCase = true)) {
                append(" (desc=\"$safeDesc\")")
            }
            if (node.viewId.isNotBlank()) {
                append(" id=${node.viewId.substringAfterLast('/')}")
            }
            appendLine(" @(${node.centerX},${node.centerY})")
        }
        if (snapshot.nodes.size > maxNodes) {
            appendLine("... +${snapshot.nodes.size - maxNodes} more elements truncated.")
        }
    }

    private fun sanitize(raw: String): String =
        raw.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim().take(120)
}

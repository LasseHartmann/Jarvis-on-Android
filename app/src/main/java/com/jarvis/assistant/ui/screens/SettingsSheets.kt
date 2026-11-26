package com.jarvis.assistant.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.agent.AiProvider
import com.jarvis.assistant.agent.AuditLogEntry
import com.jarvis.assistant.agent.GeminiModel
import com.jarvis.assistant.agent.JarvisLanguageMode
import com.jarvis.assistant.agent.JarvisMemory
import com.jarvis.assistant.agent.JarvisSettings
import com.jarvis.assistant.agent.JarvisVoiceProfile
import com.jarvis.assistant.agent.PersonaStyle
import com.jarvis.assistant.agent.RiskLevel
import com.jarvis.assistant.agent.SafetyMode
import com.jarvis.assistant.agent.SpokenLanguage
import com.jarvis.assistant.device.DeviceTelemetry
import com.jarvis.assistant.device.HyperOsSetupHelper
import com.jarvis.assistant.ui.components.JarvisIcons
import com.jarvis.assistant.ui.theme.JarvisPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class ActiveSettingsSheet {
    API_KEY,
    MODEL,
    VOICE,
    SAFETY,
    PERMISSIONS,
    MEMORY,
    ACTIVITY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisSheetHost(
    sheet: ActiveSettingsSheet,
    settings: JarvisSettings,
    telemetry: DeviceTelemetry,
    memories: List<JarvisMemory>,
    auditLogs: List<AuditLogEntry>,
    accent: Color,
    onDismiss: () -> Unit,
    onUpdateSettings: ((JarvisSettings) -> JarvisSettings) -> Unit,
    onTestVoice: (SpokenLanguage?) -> Unit,
    onRequestRuntimePermissions: () -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onClearAuditLogs: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = JarvisPalette.Graphite,
        contentColor = JarvisPalette.TextPrimary,
        scrimColor = Color.Black.copy(alpha = 0.72f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 26.dp)
        ) {
            when (sheet) {
                ActiveSettingsSheet.API_KEY -> ApiKeySheetContent(settings, accent, onUpdateSettings, onDismiss)
                ActiveSettingsSheet.MODEL -> ModelPickerSheetContent(settings, accent, onUpdateSettings, onDismiss)
                ActiveSettingsSheet.VOICE -> VoiceStudioSheetContent(settings, accent, onUpdateSettings, onTestVoice)
                ActiveSettingsSheet.SAFETY -> SafetySheetContent(settings, accent, onUpdateSettings)
                ActiveSettingsSheet.PERMISSIONS -> PermissionsSheetContent(telemetry, accent, onRequestRuntimePermissions)
                ActiveSettingsSheet.MEMORY -> MemorySheetContent(memories, onDeleteMemory)
                ActiveSettingsSheet.ACTIVITY -> ActivitySheetContent(auditLogs, onClearAuditLogs)
            }
        }
    }
}

@Composable
private fun ApiKeySheetContent(
    settings: JarvisSettings,
    accent: Color,
    onUpdateSettings: ((JarvisSettings) -> JarvisSettings) -> Unit,
    onDone: () -> Unit
) {
    var reveal by remember { mutableStateOf(false) }

    SheetHeading("Provider & API key", "Keys are encrypted with Android Hardware Keystore.")
    Spacer(Modifier.height(16.dp))

    SegmentedRow(
        options = AiProvider.entries,
        selected = settings.provider,
        labelOf = {
            when (it) {
                AiProvider.GEMINI -> "Gemini"
                AiProvider.OPENAI_COMPATIBLE -> "OpenAI / Ollama"
                AiProvider.LOCAL_ONLY -> "Offline"
            }
        },
        accent = accent,
        onSelect = { p -> onUpdateSettings { it.copy(provider = p) } }
    )

    Spacer(Modifier.height(18.dp))

    when (settings.provider) {
        AiProvider.GEMINI -> {
            MinimalTextField(
                value = settings.geminiApiKey,
                onValueChange = { v -> onUpdateSettings { it.copy(geminiApiKey = v) } },
                label = "Gemini API key",
                placeholder = "AIza...",
                secret = !reveal,
                trailing = {
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(
                            if (reveal) JarvisIcons.EyeOff else JarvisIcons.Eye,
                            contentDescription = "Toggle visibility",
                            tint = JarvisPalette.TextSecondary
                        )
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (settings.geminiApiKey.isNotBlank()) {
                    "Encrypted and active for Gemini 4 models & neural J.A.R.V.I.S. voice."
                } else {
                    "Without a key, Jarvis runs instant offline commands (torch, volume, apps, timers, diagnostics)."
                },
                fontSize = 12.5.sp,
                color = if (settings.geminiApiKey.isNotBlank()) JarvisPalette.Success else JarvisPalette.TextSecondary
            )
        }

        AiProvider.OPENAI_COMPATIBLE -> {
            MinimalTextField(
                value = settings.openAiBaseUrl,
                onValueChange = { v -> onUpdateSettings { it.copy(openAiBaseUrl = v) } },
                label = "Base URL",
                placeholder = "https://api.openai.com/v1"
            )
            Spacer(Modifier.height(10.dp))
            MinimalTextField(
                value = settings.openAiApiKey,
                onValueChange = { v -> onUpdateSettings { it.copy(openAiApiKey = v) } },
                label = "API key (optional for local Ollama)",
                placeholder = "sk-...",
                secret = !reveal,
                trailing = {
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(
                            if (reveal) JarvisIcons.EyeOff else JarvisIcons.Eye,
                            contentDescription = "Toggle visibility",
                            tint = JarvisPalette.TextSecondary
                        )
                    }
                }
            )
            Spacer(Modifier.height(10.dp))
            MinimalTextField(
                value = settings.openAiModel,
                onValueChange = { v -> onUpdateSettings { it.copy(openAiModel = v) } },
                label = "Model name",
                placeholder = "gpt-4o-mini"
            )
        }

        AiProvider.LOCAL_ONLY -> {
            Text(
                text = "Offline mode runs zero cloud requests. Direct voice controls execute natively on your Xiaomi 15T Pro.",
                fontSize = 13.5.sp,
                color = JarvisPalette.TextSecondary
            )
        }
    }

    Spacer(Modifier.height(22.dp))
    Button(
        onClick = onDone,
        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF0A0A0C)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        Text("Done", fontWeight = FontWeight.Medium, fontSize = 15.sp)
    }
}

@Composable
private fun ModelPickerSheetContent(
    settings: JarvisSettings,
    accent: Color,
    onUpdateSettings: ((JarvisSettings) -> JarvisSettings) -> Unit,
    onDone: () -> Unit
) {
    SheetHeading("Model", "Choose the Gemini 4 tier powering Jarvis.")
    Spacer(Modifier.height(14.dp))

    SheetGroupBox {
        GeminiModel.entries.forEachIndexed { idx, model ->
            val selected = settings.geminiModel.equals(model.apiId, ignoreCase = true)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onUpdateSettings { it.copy(geminiModel = model.apiId, provider = AiProvider.GEMINI) }
                        onDone()
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = if (selected) accent else JarvisPalette.TextPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(model.description, fontSize = 12.5.sp, color = JarvisPalette.TextSecondary)
                }
                if (selected) {
                    Icon(JarvisIcons.Check, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                }
            }
            if (idx < GeminiModel.entries.lastIndex) SheetDivider()
        }
    }
}

@Composable
private fun VoiceStudioSheetContent(
    settings: JarvisSettings,
    accent: Color,
    onUpdateSettings: ((JarvisSettings) -> JarvisSettings) -> Unit,
    onTestVoice: (SpokenLanguage?) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            SheetHeading("Voice & language", "Bilingual EN/DE recognition & J.A.R.V.I.S. synthesis.")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(
                color = JarvisPalette.Elevated,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(0.6.dp, JarvisPalette.HairlineStrong),
                modifier = Modifier.clickable { onTestVoice(SpokenLanguage.ENGLISH) }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
                ) {
                    Icon(JarvisIcons.Play, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("EN", fontSize = 12.5.sp, color = JarvisPalette.TextPrimary)
                }
            }
            Surface(
                color = JarvisPalette.Elevated,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(0.6.dp, JarvisPalette.HairlineStrong),
                modifier = Modifier.clickable { onTestVoice(SpokenLanguage.GERMAN) }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
                ) {
                    Icon(JarvisIcons.Play, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("DE", fontSize = 12.5.sp, color = JarvisPalette.TextPrimary)
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    SegmentedRow(
        options = JarvisLanguageMode.entries,
        selected = settings.languageMode,
        labelOf = {
            when (it) {
                JarvisLanguageMode.BILINGUAL_EN_DE -> "EN + DE"
                JarvisLanguageMode.ENGLISH_ONLY -> "English"
                JarvisLanguageMode.GERMAN_ONLY -> "Deutsch"
            }
        },
        accent = accent,
        onSelect = { m -> onUpdateSettings { it.copy(languageMode = m) } }
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = settings.languageMode.subtitle,
        fontSize = 12.sp,
        color = JarvisPalette.TextSecondary
    )

    Spacer(Modifier.height(16.dp))
    SheetGroupBox {
        JarvisVoiceProfile.entries.forEachIndexed { idx, profile ->
            val selected = settings.voiceProfile == profile
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onUpdateSettings { it.copy(voiceProfile = profile) } }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(profile.displayName, fontSize = 14.5.sp, color = if (selected) accent else JarvisPalette.TextPrimary)
                    Text(profile.subtitle, fontSize = 12.sp, color = JarvisPalette.TextSecondary)
                }
                if (selected) Icon(JarvisIcons.Check, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
            if (idx < JarvisVoiceProfile.entries.lastIndex) SheetDivider()
        }
    }

    Spacer(Modifier.height(14.dp))
    SheetGroupBox {
        SheetToggleRow(
            title = "Neural Gemini audio",
            subtitle = "Higher realism British RP voice when Gemini key is set",
            checked = settings.neuralJarvisVoice,
            accent = accent,
            onCheckedChange = { v -> onUpdateSettings { it.copy(neuralJarvisVoice = v) } }
        )
        SheetDivider()
        SheetToggleRow(
            title = "Stark intercom acoustics",
            subtitle = "Presence EQ, broadcast leveling & subtle helmet sheen",
            checked = settings.intercomDspEnabled,
            accent = accent,
            onCheckedChange = { v -> onUpdateSettings { it.copy(intercomDspEnabled = v) } }
        )
        SheetDivider()
        SheetToggleRow(
            title = "Speak responses aloud",
            subtitle = "Turn off for silent visual subtitles only",
            checked = settings.ttsEnabled,
            accent = accent,
            onCheckedChange = { v -> onUpdateSettings { it.copy(ttsEnabled = v) } }
        )
        SheetDivider()
        SheetToggleRow(
            title = "Pause wake word when screen off",
            subtitle = "Saves battery in pocket; resumes when screen wakes",
            checked = settings.wakeWordPauseScreenOff,
            accent = accent,
            onCheckedChange = { v -> onUpdateSettings { it.copy(wakeWordPauseScreenOff = v) } }
        )
    }

    Spacer(Modifier.height(14.dp))
    SegmentedRow(
        options = PersonaStyle.entries,
        selected = settings.personaStyle,
        labelOf = { it.displayName },
        accent = accent,
        onSelect = { p -> onUpdateSettings { it.copy(personaStyle = p) } }
    )

    Spacer(Modifier.height(12.dp))
    MinimalTextField(
        value = settings.userTitle,
        onValueChange = { v -> onUpdateSettings { it.copy(userTitle = v) } },
        label = "Address me as",
        placeholder = "Sir"
    )

    Spacer(Modifier.height(12.dp))
    Text(
        text = "Pace  ${"%.2f".format(settings.ttsSpeechRate)}x",
        fontSize = 12.5.sp,
        color = JarvisPalette.TextSecondary
    )
    @Suppress("DEPRECATION")
    Slider(
        value = settings.ttsSpeechRate,
        onValueChange = { v -> onUpdateSettings { it.copy(ttsSpeechRate = v) } },
        valueRange = 0.75f..1.40f,
        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = JarvisPalette.Elevated)
    )
    Text(
        text = "Pitch  ${"%.2f".format(settings.ttsPitch)}",
        fontSize = 12.5.sp,
        color = JarvisPalette.TextSecondary
    )
    @Suppress("DEPRECATION")
    Slider(
        value = settings.ttsPitch,
        onValueChange = { v -> onUpdateSettings { it.copy(ttsPitch = v) } },
        valueRange = 0.75f..1.20f,
        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = JarvisPalette.Elevated)
    )
}

@Composable
private fun SafetySheetContent(
    settings: JarvisSettings,
    accent: Color,
    onUpdateSettings: ((JarvisSettings) -> JarvisSettings) -> Unit
) {
    SheetHeading("Safety & autonomy", "Sensitive calls, messages, and payments always ask by voice.")
    Spacer(Modifier.height(14.dp))

    SheetGroupBox {
        SafetyMode.entries.forEachIndexed { idx, mode ->
            val selected = settings.safetyMode == mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onUpdateSettings { it.copy(safetyMode = mode) } }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(mode.displayName, fontSize = 15.sp, color = if (selected) accent else JarvisPalette.TextPrimary)
                    Text(mode.description, fontSize = 12.sp, color = JarvisPalette.TextSecondary)
                }
                if (selected) Icon(JarvisIcons.Check, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
            if (idx < SafetyMode.entries.lastIndex) SheetDivider()
        }
    }

    Spacer(Modifier.height(14.dp))
    SheetGroupBox {
        SheetToggleRow(
            title = "Instant offline reflex",
            subtitle = "Handle direct controls locally before contacting the cloud",
            checked = settings.localReflexFirst,
            accent = accent,
            onCheckedChange = { v -> onUpdateSettings { it.copy(localReflexFirst = v) } }
        )
        SheetDivider()
        SheetToggleRow(
            title = "Auto-dismiss after reply",
            subtitle = "Close the overlay cleanly once Jarvis finishes speaking",
            checked = settings.autoDismissAfterReply,
            accent = accent,
            onCheckedChange = { v -> onUpdateSettings { it.copy(autoDismissAfterReply = v) } }
        )
    }

    Spacer(Modifier.height(14.dp))
    Text(
        text = "Max autonomous screen steps  ${settings.maxAgentSteps}",
        fontSize = 12.5.sp,
        color = JarvisPalette.TextSecondary
    )
    @Suppress("DEPRECATION")
    Slider(
        value = settings.maxAgentSteps.toFloat(),
        onValueChange = { v -> onUpdateSettings { it.copy(maxAgentSteps = v.roundToInt()) } },
        valueRange = 2f..20f,
        steps = 17,
        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = JarvisPalette.Elevated)
    )

    Spacer(Modifier.height(8.dp))
    MinimalTextField(
        value = settings.customSystemNotes,
        onValueChange = { v -> onUpdateSettings { it.copy(customSystemNotes = v) } },
        label = "Custom instructions (optional)",
        placeholder = "Keep answers extra brief...",
        singleLine = false
    )
}

@Composable
private fun PermissionsSheetContent(
    telemetry: DeviceTelemetry,
    accent: Color,
    onRequestRuntimePermissions: () -> Unit
) {
    val context = LocalContext.current

    SheetHeading("Phone permissions", "Enable system access so Jarvis can control your Xiaomi 15T Pro.")
    Spacer(Modifier.height(14.dp))

    Button(
        onClick = onRequestRuntimePermissions,
        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF0A0A0C)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
    ) {
        Text("Grant voice, contacts, call & calendar access", fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }

    Spacer(Modifier.height(16.dp))
    Text("Core control", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = JarvisPalette.TextSecondary)
    Spacer(Modifier.height(8.dp))
    SheetGroupBox {
        PermRow("Accessibility pilot", "Read screen, tap, type & gestures", telemetry.accessibilityEnabled) {
            HyperOsSetupHelper.openAccessibilitySettings(context)
        }
        SheetDivider()
        PermRow("Notifications", "Read, dismiss & reply by voice", telemetry.notificationAccessEnabled) {
            HyperOsSetupHelper.openNotificationListenerSettings(context)
        }
        SheetDivider()
        PermRow("System settings", "Control screen brightness", telemetry.writeSettingsEnabled) {
            HyperOsSetupHelper.openWriteSettings(context)
        }
        SheetDivider()
        PermRow("Do Not Disturb", "Silent mode & Focus protocol", telemetry.dndAccessEnabled) {
            HyperOsSetupHelper.openDndAccessSettings(context)
        }
    }

    Spacer(Modifier.height(16.dp))
    Text("Xiaomi HyperOS background", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = JarvisPalette.TextSecondary)
    Spacer(Modifier.height(8.dp))
    SheetGroupBox {
        PermRow("Autostart", "Keep pilot active after reboot", null) {
            HyperOsSetupHelper.openXiaomiAutostart(context)
        }
        SheetDivider()
        PermRow("Background pop-ups", "Allow opening apps from voice overlay", null) {
            HyperOsSetupHelper.openXiaomiOtherPermissions(context)
        }
        SheetDivider()
        PermRow("Unrestricted battery", "Prevent HyperOS from pausing Jarvis", telemetry.batteryUnrestricted) {
            HyperOsSetupHelper.openBatteryOptimizationSettings(context)
        }
        SheetDivider()
        PermRow("App info", "Allow restricted settings if needed", null) {
            HyperOsSetupHelper.openAppDetailsSettings(context)
        }
    }
}

@Composable
private fun MemorySheetContent(
    memories: List<JarvisMemory>,
    onDeleteMemory: (Long) -> Unit
) {
    SheetHeading("Memory", "Facts Jarvis remembers across conversations.")
    Spacer(Modifier.height(14.dp))

    if (memories.isEmpty()) {
        Text(
            text = "Nothing saved yet. Ask Jarvis by voice to remember preferences, routines, or details.",
            fontSize = 13.5.sp,
            color = JarvisPalette.TextSecondary
        )
    } else {
        SheetGroupBox {
            memories.forEachIndexed { idx, m ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(m.key.replace('_', ' ').replaceFirstChar { it.uppercase() }, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = JarvisPalette.TextPrimary)
                        Text(m.content, fontSize = 12.5.sp, color = JarvisPalette.TextSecondary)
                    }
                    IconButton(onClick = { onDeleteMemory(m.id) }, modifier = Modifier.size(34.dp)) {
                        Icon(JarvisIcons.Trash, contentDescription = "Delete", tint = JarvisPalette.TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
                if (idx < memories.lastIndex) SheetDivider()
            }
        }
    }
}

@Composable
private fun ActivitySheetContent(
    auditLogs: List<AuditLogEntry>,
    onClear: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            SheetHeading("Activity", "Recent phone actions executed by Jarvis.")
        }
        if (auditLogs.isNotEmpty()) {
            TextButton(onClick = onClear) {
                Text("Clear", color = JarvisPalette.Danger, fontSize = 13.sp)
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    if (auditLogs.isEmpty()) {
        Text("No phone actions recorded yet.", fontSize = 13.5.sp, color = JarvisPalette.TextSecondary)
    } else {
        SheetGroupBox {
            auditLogs.take(25).forEachIndexed { idx, entry ->
                val dotColor = when {
                    !entry.approved || !entry.success -> JarvisPalette.Danger
                    entry.riskLevel == RiskLevel.SAFE -> JarvisPalette.Success
                    else -> JarvisPalette.Warning
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = entry.toolName.replace('_', ' ').replaceFirstChar { it.uppercase() },
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = JarvisPalette.TextPrimary
                            )
                            Text(
                                text = fmt.format(Date(entry.timestampMs)),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = JarvisPalette.TextTertiary
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(entry.summary, fontSize = 12.sp, color = JarvisPalette.TextSecondary, maxLines = 2)
                    }
                }
                if (idx < minOf(24, auditLogs.lastIndex)) SheetDivider()
            }
        }
    }
}

@Composable
private fun SheetHeading(title: String, subtitle: String) {
    Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = JarvisPalette.TextPrimary)
    Spacer(Modifier.height(3.dp))
    Text(subtitle, fontSize = 13.sp, color = JarvisPalette.TextSecondary)
}

@Composable
private fun SheetGroupBox(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = JarvisPalette.Elevated,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(0.5.dp, JarvisPalette.Hairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

@Composable
private fun SheetDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = JarvisPalette.Hairline
    )
}

@Composable
private fun SheetToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.5.sp, color = JarvisPalette.TextPrimary)
            Text(subtitle, fontSize = 12.sp, color = JarvisPalette.TextSecondary)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF0A0A0C),
                checkedTrackColor = accent,
                uncheckedThumbColor = JarvisPalette.TextSecondary,
                uncheckedTrackColor = JarvisPalette.Graphite,
                uncheckedBorderColor = JarvisPalette.HairlineStrong
            )
        )
    }
}

@Composable
private fun PermRow(
    title: String,
    subtitle: String,
    granted: Boolean?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(
                    when (granted) {
                        true -> JarvisPalette.Success
                        false -> JarvisPalette.Warning
                        null -> JarvisPalette.TextTertiary
                    }
                )
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.5.sp, color = JarvisPalette.TextPrimary)
            Text(subtitle, fontSize = 12.sp, color = JarvisPalette.TextSecondary)
        }
        Text(
            text = if (granted == true) "On" else "Open",
            fontSize = 13.sp,
            color = if (granted == true) JarvisPalette.Success else JarvisPalette.TextSecondary
        )
    }
}

@Composable
private fun <T> SegmentedRow(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    accent: Color,
    onSelect: (T) -> Unit
) {
    Surface(
        color = JarvisPalette.Elevated,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(0.5.dp, JarvisPalette.Hairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { item ->
                val isSelected = item == selected
                Surface(
                    color = if (isSelected) accent else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(item) }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(vertical = 9.dp, horizontal = 4.dp)
                    ) {
                        Text(
                            text = labelOf(item),
                            fontSize = 12.5.sp,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                            color = if (isSelected) Color(0xFF0A0A0C) else JarvisPalette.TextSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MinimalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    singleLine: Boolean = true,
    secret: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    @Suppress("DEPRECATION")
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.5.sp) },
        placeholder = { Text(placeholder, color = JarvisPalette.TextTertiary, fontSize = 14.sp) },
        singleLine = singleLine,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = trailing,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = JarvisPalette.HairlineStrong,
            unfocusedBorderColor = JarvisPalette.Hairline,
            focusedContainerColor = JarvisPalette.Elevated,
            unfocusedContainerColor = JarvisPalette.Elevated,
            focusedTextColor = JarvisPalette.TextPrimary,
            unfocusedTextColor = JarvisPalette.TextPrimary,
            focusedLabelColor = JarvisPalette.TextSecondary,
            unfocusedLabelColor = JarvisPalette.TextSecondary
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
    )
}

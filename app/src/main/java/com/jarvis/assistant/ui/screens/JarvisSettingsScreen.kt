package com.jarvis.assistant.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.agent.AiProvider
import com.jarvis.assistant.agent.AuditLogEntry
import com.jarvis.assistant.agent.GeminiModel
import com.jarvis.assistant.agent.HudColorTheme
import com.jarvis.assistant.agent.JarvisMemory
import com.jarvis.assistant.agent.JarvisSettings
import com.jarvis.assistant.agent.SpokenLanguage
import com.jarvis.assistant.assist.JarvisAssistActivity
import com.jarvis.assistant.device.DeviceTelemetry
import com.jarvis.assistant.device.HyperOsSetupHelper
import com.jarvis.assistant.ui.components.JarvisIcons
import com.jarvis.assistant.ui.components.MiniJarvisOrb
import com.jarvis.assistant.ui.theme.JarvisPalette

@Composable
fun JarvisSettingsScreen(
    settings: JarvisSettings,
    telemetry: DeviceTelemetry,
    memories: List<JarvisMemory>,
    auditLogs: List<AuditLogEntry>,
    onUpdateSettings: ((JarvisSettings) -> JarvisSettings) -> Unit,
    onTestVoice: (SpokenLanguage?) -> Unit,
    onPermissionsUpdated: () -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onClearAuditLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var activeSheet by remember { mutableStateOf<ActiveSettingsSheet?>(null) }
    val accent = Color(settings.hudColorTheme.primaryArgb)

    val runtimePermissions = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_CALENDAR)
            add(Manifest.permission.WRITE_CALENDAR)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }
    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { onPermissionsUpdated() }

    val activeModelLabel = when (settings.provider) {
        AiProvider.GEMINI -> GeminiModel.fromApiId(settings.geminiModel).displayName
        AiProvider.OPENAI_COMPATIBLE -> settings.openAiModel.ifBlank { "Custom model" }
        AiProvider.LOCAL_ONLY -> "Offline reflex"
    }

    val keyStatusLabel = when (settings.provider) {
        AiProvider.GEMINI -> if (settings.geminiApiKey.isNotBlank()) "Gemini · Saved" else "Add key"
        AiProvider.OPENAI_COMPATIBLE -> if (settings.hasActiveCloudKey()) "Connected" else "Configure"
        AiProvider.LOCAL_ONLY -> "Offline only"
    }

    val readyCount = listOf(
        telemetry.isDefaultAssistant,
        telemetry.micGranted,
        telemetry.accessibilityEnabled,
        telemetry.notificationAccessEnabled,
        telemetry.writeSettingsEnabled,
        telemetry.dndAccessEnabled,
        telemetry.batteryUnrestricted
    ).count { it }

    val launchWakeOverlay = {
        context.startActivity(
            Intent(context, JarvisAssistActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(JarvisPalette.Obsidian),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        // SERENE PURE-BLACK FLOATING HERO
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MiniJarvisOrb(
                    finish = settings.hudColorTheme,
                    onClick = launchWakeOverlay,
                    size = 118.dp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Jarvis",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.5).sp,
                    color = JarvisPalette.TextPrimary
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    val ready = telemetry.isDefaultAssistant && (settings.hasActiveCloudKey() || settings.provider == AiProvider.LOCAL_ONLY)
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (ready) JarvisPalette.Success else accent)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = if (settings.hasActiveCloudKey() || settings.provider == AiProvider.LOCAL_ONLY) {
                            val wakeHint = if (settings.wakeWordEnabled && telemetry.micGranted) {
                                "Say \"Jarvis\" or hold power"
                            } else {
                                "Hold power to wake"
                            }
                            "$activeModelLabel  ·  $wakeHint"
                        } else {
                            "Add an API key to connect"
                        },
                        fontSize = 13.sp,
                        color = JarvisPalette.TextSecondary
                    )
                }

                Spacer(Modifier.height(18.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HeroActionPill(
                        label = "Preview wake",
                        icon = JarvisIcons.Wave,
                        accent = accent,
                        onClick = launchWakeOverlay
                    )
                    HeroActionPill(
                        label = "Test voice",
                        icon = JarvisIcons.Speaker,
                        accent = JarvisPalette.TextPrimary,
                        onClick = { onTestVoice(null) }
                    )
                }
            }
        }

        // GROUP 1: INTELLIGENCE
        item {
            SettingsGroup(title = "Intelligence") {
                NavigationRow(
                    icon = JarvisIcons.Key,
                    title = "Provider & API key",
                    value = keyStatusLabel,
                    valueColor = if (settings.hasActiveCloudKey() || settings.provider == AiProvider.LOCAL_ONLY) {
                        JarvisPalette.TextSecondary
                    } else accent,
                    onClick = { activeSheet = ActiveSettingsSheet.API_KEY }
                )
                GroupDivider()
                NavigationRow(
                    icon = JarvisIcons.Sparkle,
                    title = "Model",
                    value = activeModelLabel,
                    onClick = { activeSheet = ActiveSettingsSheet.MODEL }
                )
                GroupDivider()
                NavigationRow(
                    icon = JarvisIcons.Shield,
                    title = "Safety & confirmations",
                    value = settings.safetyMode.displayName,
                    onClick = { activeSheet = ActiveSettingsSheet.SAFETY }
                )
            }
        }

        // GROUP 2: VOICE & APPEARANCE
        item {
            SettingsGroup(title = "Voice & appearance") {
                val shortLang = when (settings.languageMode) {
                    com.jarvis.assistant.agent.JarvisLanguageMode.BILINGUAL_EN_DE -> "EN + DE"
                    com.jarvis.assistant.agent.JarvisLanguageMode.ENGLISH_ONLY -> "English"
                    com.jarvis.assistant.agent.JarvisLanguageMode.GERMAN_ONLY -> "Deutsch"
                }
                NavigationRow(
                    icon = JarvisIcons.Voice,
                    title = "Voice & language",
                    value = "$shortLang · ${settings.voiceProfile.geminiVoiceName}",
                    onClick = { activeSheet = ActiveSettingsSheet.VOICE }
                )
                GroupDivider()
                ToggleRow(
                    icon = JarvisIcons.Wave,
                    title = "Say \"Jarvis\" to wake",
                    checked = settings.wakeWordEnabled,
                    accent = accent,
                    onCheckedChange = { enabled ->
                        if (enabled && !telemetry.micGranted) {
                            permLauncher.launch(runtimePermissions)
                        }
                        onUpdateSettings { it.copy(wakeWordEnabled = enabled) }
                    }
                )
                GroupDivider()
                FinishSwatchRow(
                    selected = settings.hudColorTheme,
                    onSelect = { f -> onUpdateSettings { it.copy(hudColorTheme = f) } }
                )
                GroupDivider()
                ToggleRow(
                    icon = JarvisIcons.Sliders,
                    title = "Hands-free follow-up",
                    checked = settings.continuousConversation,
                    accent = accent,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(continuousConversation = v) } }
                )
                GroupDivider()
                ToggleRow(
                    icon = JarvisIcons.Bell,
                    title = "Sound cues",
                    checked = settings.soundEffectsEnabled,
                    accent = accent,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(soundEffectsEnabled = v) } }
                )
            }
        }

        // GROUP 3: XIAOMI 15T PRO
        item {
            SettingsGroup(title = "Xiaomi 15T Pro") {
                NavigationRow(
                    icon = JarvisIcons.Phone,
                    title = "Default assistant",
                    value = if (telemetry.isDefaultAssistant) "Active" else "Set up",
                    valueColor = if (telemetry.isDefaultAssistant) JarvisPalette.Success else accent,
                    onClick = { HyperOsSetupHelper.openDefaultAssistantSettings(context) }
                )
                GroupDivider()
                NavigationRow(
                    icon = JarvisIcons.Shield,
                    title = "Phone permissions",
                    value = "$readyCount of 7 ready",
                    valueColor = if (readyCount >= 6) JarvisPalette.Success else JarvisPalette.TextSecondary,
                    onClick = { activeSheet = ActiveSettingsSheet.PERMISSIONS }
                )
            }
        }

        // GROUP 4: DATA
        item {
            SettingsGroup(title = "Data") {
                NavigationRow(
                    icon = JarvisIcons.Bookmark,
                    title = "Memory",
                    value = if (memories.isEmpty()) "Empty" else "${memories.size} saved",
                    onClick = { activeSheet = ActiveSettingsSheet.MEMORY }
                )
                GroupDivider()
                NavigationRow(
                    icon = JarvisIcons.Clock,
                    title = "Activity log",
                    value = if (auditLogs.isEmpty()) "None" else "${auditLogs.size}",
                    onClick = { activeSheet = ActiveSettingsSheet.ACTIVITY }
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    activeSheet?.let { sheet ->
        JarvisSheetHost(
            sheet = sheet,
            settings = settings,
            telemetry = telemetry,
            memories = memories,
            auditLogs = auditLogs,
            accent = accent,
            onDismiss = { activeSheet = null },
            onUpdateSettings = onUpdateSettings,
            onTestVoice = onTestVoice,
            onRequestRuntimePermissions = { permLauncher.launch(runtimePermissions) },
            onDeleteMemory = onDeleteMemory,
            onClearAuditLogs = onClearAuditLogs
        )
    }
}

@Composable
private fun HeroActionPill(
    label: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit
) {
    Surface(
        color = JarvisPalette.PillSurface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(0.6.dp, JarvisPalette.HairlineStrong),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
            Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = JarvisPalette.TextPrimary)
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = JarvisPalette.TextSecondary,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
        )
        Surface(
            color = JarvisPalette.Graphite,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(0.5.dp, JarvisPalette.Hairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun NavigationRow(
    icon: ImageVector,
    title: String,
    value: String,
    valueColor: Color = JarvisPalette.TextSecondary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = JarvisPalette.TextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = title,
            fontSize = 15.sp,
            color = JarvisPalette.TextPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = value,
            fontSize = 13.5.sp,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = JarvisIcons.ChevronRight,
            contentDescription = null,
            tint = JarvisPalette.TextTertiary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = JarvisPalette.TextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = title,
            fontSize = 15.sp,
            color = JarvisPalette.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF0A0A0C),
                checkedTrackColor = accent,
                uncheckedThumbColor = JarvisPalette.TextSecondary,
                uncheckedTrackColor = JarvisPalette.Elevated,
                uncheckedBorderColor = JarvisPalette.HairlineStrong
            )
        )
    }
}

@Composable
private fun FinishSwatchRow(
    selected: HudColorTheme,
    onSelect: (HudColorTheme) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = JarvisIcons.Palette,
            contentDescription = null,
            tint = JarvisPalette.TextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = "Finish",
            fontSize = 15.sp,
            color = JarvisPalette.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            HudColorTheme.entries.forEach { finish ->
                val isSelected = finish == selected
                val swatchColor = Color(finish.primaryArgb)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .then(
                            if (isSelected) Modifier.border(1.5.dp, swatchColor, CircleShape)
                            else Modifier.border(0.6.dp, JarvisPalette.HairlineStrong, CircleShape)
                        )
                        .clickable { onSelect(finish) }
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(swatchColor)
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 50.dp, end = 16.dp),
        thickness = 0.5.dp,
        color = JarvisPalette.Hairline
    )
}

package com.jarvis.assistant.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jarvis.assistant.agent.AiProvider
import com.jarvis.assistant.agent.GeminiModel
import com.jarvis.assistant.agent.HudColorTheme
import com.jarvis.assistant.agent.JarvisLanguageMode
import com.jarvis.assistant.agent.JarvisSettings
import com.jarvis.assistant.agent.JarvisVoiceProfile
import com.jarvis.assistant.agent.PersonaStyle
import com.jarvis.assistant.agent.SafetyMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<JarvisSettings> = _settings.asStateFlow()

    fun current(): JarvisSettings = _settings.value

    fun update(transform: (JarvisSettings) -> JarvisSettings) {
        val updated = transform(_settings.value)
        saveSettings(updated)
        _settings.value = updated
    }

    private fun loadSettings(): JarvisSettings {
        val provider = runCatching {
            AiProvider.valueOf(prefs.getString(KEY_PROVIDER, AiProvider.GEMINI.name) ?: AiProvider.GEMINI.name)
        }.getOrDefault(AiProvider.GEMINI)

        val safety = runCatching {
            SafetyMode.valueOf(prefs.getString(KEY_SAFETY_MODE, SafetyMode.AUTONOMOUS.name) ?: SafetyMode.AUTONOMOUS.name)
        }.getOrDefault(SafetyMode.AUTONOMOUS)

        val persona = runCatching {
            PersonaStyle.valueOf(prefs.getString(KEY_PERSONA, PersonaStyle.CLASSIC_STARK.name) ?: PersonaStyle.CLASSIC_STARK.name)
        }.getOrDefault(PersonaStyle.CLASSIC_STARK)

        val hudTheme = HudColorTheme.fromStored(prefs.getString(KEY_HUD_THEME, HudColorTheme.JARVIS_AMBER.name))

        val voiceProfile = runCatching {
            JarvisVoiceProfile.valueOf(prefs.getString(KEY_VOICE_PROFILE, JarvisVoiceProfile.CHARON.name) ?: JarvisVoiceProfile.CHARON.name)
        }.getOrDefault(JarvisVoiceProfile.CHARON)

        val languageMode = runCatching {
            JarvisLanguageMode.valueOf(
                prefs.getString(KEY_LANGUAGE_MODE, JarvisLanguageMode.BILINGUAL_EN_DE.name)
                    ?: JarvisLanguageMode.BILINGUAL_EN_DE.name
            )
        }.getOrDefault(JarvisLanguageMode.BILINGUAL_EN_DE)

        return JarvisSettings(
            provider = provider,
            geminiApiKey = decryptSecret(prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""),
            geminiModel = GeminiModel.fromApiId(prefs.getString(KEY_GEMINI_MODEL, GeminiModel.HYDROGEN.apiId)).apiId,
            openAiApiKey = decryptSecret(prefs.getString(KEY_OPENAI_API_KEY, "") ?: ""),
            openAiBaseUrl = prefs.getString(KEY_OPENAI_BASE_URL, AiProvider.OPENAI_COMPATIBLE.defaultBaseUrl) ?: AiProvider.OPENAI_COMPATIBLE.defaultBaseUrl,
            openAiModel = prefs.getString(KEY_OPENAI_MODEL, AiProvider.OPENAI_COMPATIBLE.defaultModel) ?: AiProvider.OPENAI_COMPATIBLE.defaultModel,
            safetyMode = safety,
            personaStyle = persona,
            hudColorTheme = hudTheme,
            userTitle = prefs.getString(KEY_USER_TITLE, "Sir") ?: "Sir",
            customSystemNotes = prefs.getString(KEY_CUSTOM_NOTES, "") ?: "",
            ttsEnabled = prefs.getBoolean(KEY_TTS_ENABLED, true),
            neuralJarvisVoice = prefs.getBoolean(KEY_NEURAL_VOICE, true),
            voiceProfile = voiceProfile,
            intercomDspEnabled = prefs.getBoolean(KEY_INTERCOM_DSP, true),
            ttsSpeechRate = prefs.getFloat(KEY_TTS_RATE, 1.03f),
            ttsPitch = prefs.getFloat(KEY_TTS_PITCH, 0.88f),
            soundEffectsEnabled = prefs.getBoolean(KEY_SFX_ENABLED, true),
            localReflexFirst = prefs.getBoolean(KEY_LOCAL_REFLEX, true),
            maxAgentSteps = prefs.getInt(KEY_MAX_STEPS, 10).coerceIn(2, 20),
            continuousConversation = prefs.getBoolean(KEY_CONTINUOUS_CONV, true),
            autoDismissAfterReply = prefs.getBoolean(KEY_AUTO_DISMISS, true),
            languageMode = languageMode,
            wakeWordEnabled = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, true),
            wakeWordPauseScreenOff = prefs.getBoolean(KEY_WAKE_WORD_PAUSE_SCREEN_OFF, true)
        )
    }

    private fun saveSettings(s: JarvisSettings) {
        prefs.edit()
            .putString(KEY_PROVIDER, s.provider.name)
            .putString(KEY_GEMINI_API_KEY, encryptSecret(s.geminiApiKey.trim()))
            .putString(KEY_GEMINI_MODEL, s.geminiModel.trim().ifBlank { AiProvider.GEMINI.defaultModel })
            .putString(KEY_OPENAI_API_KEY, encryptSecret(s.openAiApiKey.trim()))
            .putString(KEY_OPENAI_BASE_URL, s.openAiBaseUrl.trim().ifBlank { AiProvider.OPENAI_COMPATIBLE.defaultBaseUrl })
            .putString(KEY_OPENAI_MODEL, s.openAiModel.trim().ifBlank { AiProvider.OPENAI_COMPATIBLE.defaultModel })
            .putString(KEY_SAFETY_MODE, s.safetyMode.name)
            .putString(KEY_PERSONA, s.personaStyle.name)
            .putString(KEY_HUD_THEME, s.hudColorTheme.name)
            .putString(KEY_USER_TITLE, s.userTitle.trim().ifBlank { "Sir" })
            .putString(KEY_CUSTOM_NOTES, s.customSystemNotes)
            .putBoolean(KEY_TTS_ENABLED, s.ttsEnabled)
            .putBoolean(KEY_NEURAL_VOICE, s.neuralJarvisVoice)
            .putString(KEY_VOICE_PROFILE, s.voiceProfile.name)
            .putBoolean(KEY_INTERCOM_DSP, s.intercomDspEnabled)
            .putFloat(KEY_TTS_RATE, s.ttsSpeechRate.coerceIn(0.6f, 1.6f))
            .putFloat(KEY_TTS_PITCH, s.ttsPitch.coerceIn(0.6f, 1.4f))
            .putBoolean(KEY_SFX_ENABLED, s.soundEffectsEnabled)
            .putBoolean(KEY_LOCAL_REFLEX, s.localReflexFirst)
            .putInt(KEY_MAX_STEPS, s.maxAgentSteps.coerceIn(2, 20))
            .putBoolean(KEY_CONTINUOUS_CONV, s.continuousConversation)
            .putBoolean(KEY_AUTO_DISMISS, s.autoDismissAfterReply)
            .putString(KEY_LANGUAGE_MODE, s.languageMode.name)
            .putBoolean(KEY_WAKE_WORD_ENABLED, s.wakeWordEnabled)
            .putBoolean(KEY_WAKE_WORD_PAUSE_SCREEN_OFF, s.wakeWordPauseScreenOff)
            .apply()
    }

    private fun getOrCreateKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        generator.generateKey()
    }.getOrNull()

    private fun encryptSecret(plain: String): String {
        if (plain.isEmpty()) return ""
        val key = getOrCreateKey() ?: return PLAIN_PREFIX + Base64.encodeToString(plain.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
            val combined = ByteArray(1 + iv.size + cipherBytes.size)
            combined[0] = iv.size.toByte()
            System.arraycopy(iv, 0, combined, 1, iv.size)
            System.arraycopy(cipherBytes, 0, combined, 1 + iv.size, cipherBytes.size)
            ENC_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        }.getOrElse {
            PLAIN_PREFIX + Base64.encodeToString(plain.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        }
    }

    private fun decryptSecret(stored: String): String {
        if (stored.isEmpty()) return ""
        if (stored.startsWith(PLAIN_PREFIX)) {
            return runCatching {
                String(Base64.decode(stored.removePrefix(PLAIN_PREFIX), Base64.NO_WRAP), StandardCharsets.UTF_8)
            }.getOrDefault("")
        }
        if (!stored.startsWith(ENC_PREFIX)) return stored
        val key = getOrCreateKey() ?: return ""
        return runCatching {
            val combined = Base64.decode(stored.removePrefix(ENC_PREFIX), Base64.NO_WRAP)
            val ivLen = combined[0].toInt() and 0xFF
            val iv = combined.copyOfRange(1, 1 + ivLen)
            val cipherBytes = combined.copyOfRange(1 + ivLen, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
        }.getOrDefault("")
    }

    companion object {
        private const val PREFS_NAME = "jarvis_secure_settings"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "jarvis_stark_aes_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ENC_PREFIX = "enc_v1:"
        private const val PLAIN_PREFIX = "b64_v1:"

        private const val KEY_PROVIDER = "provider"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_OPENAI_BASE_URL = "openai_base_url"
        private const val KEY_OPENAI_MODEL = "openai_model"
        private const val KEY_SAFETY_MODE = "safety_mode"
        private const val KEY_PERSONA = "persona_style"
        private const val KEY_HUD_THEME = "hud_color_theme"
        private const val KEY_USER_TITLE = "user_title"
        private const val KEY_CUSTOM_NOTES = "custom_system_notes"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_NEURAL_VOICE = "neural_jarvis_voice"
        private const val KEY_VOICE_PROFILE = "jarvis_voice_profile"
        private const val KEY_INTERCOM_DSP = "intercom_dsp_enabled"
        private const val KEY_TTS_RATE = "tts_rate"
        private const val KEY_TTS_PITCH = "tts_pitch"
        private const val KEY_SFX_ENABLED = "sfx_enabled"
        private const val KEY_LOCAL_REFLEX = "local_reflex_first"
        private const val KEY_MAX_STEPS = "max_agent_steps"
        private const val KEY_CONTINUOUS_CONV = "continuous_conversation"
        private const val KEY_AUTO_DISMISS = "auto_dismiss_after_reply"
        private const val KEY_LANGUAGE_MODE = "language_mode"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        private const val KEY_WAKE_WORD_PAUSE_SCREEN_OFF = "wake_word_pause_screen_off"
    }
}

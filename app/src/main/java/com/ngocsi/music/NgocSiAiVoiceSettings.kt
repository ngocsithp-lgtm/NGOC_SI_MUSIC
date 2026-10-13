package com.ngocsi.music

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

internal data class NgocSiAiVoiceOption(
    val name: String,
    val label: String,
    val languageTag: String,
    val requiresNetwork: Boolean
)

/**
 * Shares the chosen Android TTS voice between the chat screen and the wake-word service.
 * Voice names are local engine identifiers, not account data or API credentials.
 */
internal object NgocSiAiVoiceSettings {
    const val PREFERENCES_NAME = "ngoc_si_ai_voice_settings"
    const val KEY_SELECTED_VOICE_NAME = "selected_voice_name"

    private val vietnameseLocale = Locale("vi", "VN")

    fun selectedVoiceName(context: Context): String? =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_VOICE_NAME, null)
            ?.takeIf { it.isNotBlank() }

    fun saveSelectedVoiceName(context: Context, name: String) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_VOICE_NAME, name)
            .apply()
    }

    /** Apply a saved voice if this TTS engine still exposes it; otherwise keep its default. */
    fun applySavedVoice(context: Context, tts: TextToSpeech): String? {
        val requestedName = selectedVoiceName(context) ?: return null
        val voice = tts.voices?.firstOrNull { it.name == requestedName } ?: return null
        val result = runCatching { tts.setVoice(voice) }
            .getOrDefault(TextToSpeech.ERROR)
        return voice.name.takeIf { result == TextToSpeech.SUCCESS }
    }

    /**
     * Enumerate voices reported by the installed Android TTS engine. Prefer Vietnamese
     * and voices that do not require a network, but leave other installed languages usable.
     */
    fun availableOptions(voices: Set<Voice>): List<NgocSiAiVoiceOption> {
        val sorted = voices.sortedWith(
            compareBy<Voice>(
                { it.locale.language != "vi" },
                { it.isNetworkConnectionRequired },
                { it.locale.getDisplayName(vietnameseLocale) },
                { it.name }
            )
        )
        val counts = mutableMapOf<String, Int>()
        return sorted.map { voice ->
            val languageTag = voice.locale.toLanguageTag()
            val groupKey = "$languageTag|\${voice.isNetworkConnectionRequired}"
            val index = (counts[groupKey] ?: 0) + 1
            counts[groupKey] = index
            val languageName = voice.locale.getDisplayName(vietnameseLocale)
                .ifBlank { languageTag }
            val connectionLabel = if (voice.isNetworkConnectionRequired) {
                "cần Internet"
            } else {
                "ngoại tuyến"
            }
            NgocSiAiVoiceOption(
                name = voice.name,
                label = "$languageName · Giọng $index · $connectionLabel",
                languageTag = languageTag,
                requiresNetwork = voice.isNetworkConnectionRequired
            )
        }
    }
}

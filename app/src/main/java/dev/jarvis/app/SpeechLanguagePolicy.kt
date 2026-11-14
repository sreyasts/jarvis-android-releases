package dev.jarvis.app

/** Allows English and Malayalam text, including English names inside Malayalam speech. */
enum class JarvisLanguage(val code: String) { ENGLISH("en"), MALAYALAM("ml") }

sealed interface SpeechLanguageResult {
    data class Supported(val language: JarvisLanguage) : SpeechLanguageResult
    data object Empty : SpeechLanguageResult
    data object Unsupported : SpeechLanguageResult
}

object SpeechLanguagePolicy {
    fun inspect(text: String): SpeechLanguageResult {
        var hasLetter = false
        var hasMalayalam = false
        for (offset in text.indices) {
            val codePoint = text.codePointAt(offset)
            if (!Character.isLetter(codePoint)) continue
            hasLetter = true
            when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.LATIN -> Unit
                Character.UnicodeScript.MALAYALAM -> hasMalayalam = true
                else -> return SpeechLanguageResult.Unsupported
            }
        }
        if (!hasLetter) return SpeechLanguageResult.Empty
        return SpeechLanguageResult.Supported(if (hasMalayalam) JarvisLanguage.MALAYALAM else JarvisLanguage.ENGLISH)
    }
}

package dev.jarvis.app

import android.content.Context
import dev.jarvis.core.ActivationSource
import dev.jarvis.core.AudioPulse
import dev.jarvis.safety.VoiceGateResult
import dev.jarvis.safety.VoiceDenial
import dev.jarvis.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One microphone/model pipeline per process; no idle listening or polling. */
class JarvisRuntime private constructor(context: Context) {
    private val preferences = JarvisPreferences(context)
    val recorder = VoiceRecorder(context)
    private val speech = OfflineSpeechRecognizer(context)
    private val languageDetector = SpokenLanguageDetector(context)
    private val pipeline = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idleRelease: Job? = null

    suspend fun recognize(
        source: ActivationSource,
        language: String,
        onProgress: (Float) -> Unit,
        onProcessing: () -> Unit,
        onPulse: (AudioPulse) -> Unit = {},
        transcribe: (suspend (FloatArray) -> VoiceGateResult<String>)? = null,
        onRecording: () -> Unit = {},
    ): VoiceGateResult<String> = pipeline.withLock {
        idleRelease?.cancel()
        try {
            if (!preferences.settings.first().enabled) return@withLock VoiceGateResult.Denied(VoiceDenial.VERIFICATION_UNAVAILABLE)
            val audio = recorder.record(seconds = 9, endOnSilence = true, onRecording = onRecording,
                onPulse = onPulse, onProgress = onProgress)
            try {
                onProcessing()
                transcribe?.invoke(audio) ?: transcribeCapturedAudio(audio, language)
            } finally { audio.fill(0f) }
        } finally { releaseWhenIdle() }
    }

    /** Common microphone/fixture path; owns and erases PCM, with no identity requirement. */
    internal suspend fun detectLanguage(audio: FloatArray): SpokenLanguageDetector.Match? = languageDetector.detect(audio)

    internal suspend fun transcribeCapturedAudio(audio: FloatArray, language: String,
        match: SpokenLanguageDetector.Match? = null, detect: Boolean = true): VoiceGateResult<String> {
        try {
            if (dev.jarvis.core.AudioQuality.activeFrameCount(audio) < 10) return VoiceGateResult.Denied(VoiceDenial.BAD_AUDIO)
            val identified = match ?: if (detect) detectLanguage(audio) else null
            val confident = identified?.takeIf { it.confidence >= .75f }
            if (confident != null && confident.code !in setOf("en", "ml")) {
                return VoiceGateResult.Denied(VoiceDenial.UNSUPPORTED_LANGUAGE)
            }
            if (confident != null && language.isNotEmpty() && confident.code != language) {
                return VoiceGateResult.Denied(VoiceDenial.UNSUPPORTED_LANGUAGE)
            }
            // Leave uncertain English to multilingual detection instead of forcing a Latin-script result.
            val mode = if (confident?.code == "ml" ||
                (identified?.code == "ml" && identified.confidence >= .40f)) "ml" else ""
            val preliminary = speech.recognize(audio, mode)
            val guessed = preliminary.lang.removePrefix("<|").removeSuffix("|>").lowercase()
            val result = if (mode.isEmpty() && guessed == "ml") speech.recognize(audio, "ml") else preliminary
            val detected = result.lang.removePrefix("<|").removeSuffix("|>").lowercase()
            if (detected.isNotBlank() && detected !in setOf("en", "ml")) {
                return VoiceGateResult.Denied(VoiceDenial.UNSUPPORTED_LANGUAGE)
            }
            val expected = language.ifBlank { confident?.code ?: if (mode == "ml" || guessed == "ml") "ml" else "" }
            return validateTranscript(result.text, expected)
        } finally { audio.fill(0f) }
    }

    internal fun validateTranscript(text: String, language: String): VoiceGateResult<String> {
        if (text == "[UNSUPPORTED_LANGUAGE]") return VoiceGateResult.Denied(VoiceDenial.UNSUPPORTED_LANGUAGE)
        return when (val result = SpeechLanguagePolicy.inspect(text)) {
            SpeechLanguageResult.Empty -> VoiceGateResult.Denied(VoiceDenial.BAD_AUDIO)
            SpeechLanguageResult.Unsupported -> VoiceGateResult.Denied(VoiceDenial.UNSUPPORTED_LANGUAGE)
            is SpeechLanguageResult.Supported -> if (language.isEmpty() || result.language.code == language) {
                VoiceGateResult.Accepted(text)
            } else VoiceGateResult.Denied(VoiceDenial.UNSUPPORTED_LANGUAGE)
        }
    }

    fun releaseWhenIdle() {
        idleRelease?.cancel()
        idleRelease = scope.launch {
            delay(90_000)
            pipeline.withLock { speech.release(); languageDetector.release() }
        }
    }

    companion object {
        @Volatile private var instance: JarvisRuntime? = null
        fun get(context: Context): JarvisRuntime = instance ?: synchronized(this) {
            instance ?: JarvisRuntime(context.applicationContext).also { instance = it }
        }
    }
}

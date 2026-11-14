package dev.jarvis.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import dev.jarvis.core.ActivationSource
import dev.jarvis.safety.VoiceGateResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class JarvisVoiceInteractionService : VoiceInteractionService() {
    override fun onLaunchVoiceAssistFromKeyguard() { showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST) }
}

class JarvisVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = JarvisVoiceSession(this)
}

class JarvisVoiceSession(context: Context) : VoiceInteractionSession(context) {
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
    }
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            startAssistantActivity(JarvisOverlayActivity.intent(context, ActivationSource.ASSISTANT_GESTURE))
        } catch (_: Exception) {
            android.util.Log.w("JarvisAssistant", "Android could not open the assistant activity")
        }
    }
}

/** The framework requires a recognition service for assistant-role eligibility. */
class JarvisRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var operation: Job? = null
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        if (operation?.isActive == true) { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); return }
        operation = scope.launch {
            var offWatcher: Job? = null
            try {
                val preferences = JarvisPreferences(this@JarvisRecognitionService)
                val settings = preferences.settings.first()
                if (!settings.enabled) { listener.error(SpeechRecognizer.ERROR_CLIENT); return@launch }
                val request = currentCoroutineContext().job
                offWatcher = launch { preferences.settings.first { !it.enabled }; request.cancel() }
                listener.readyForSpeech(Bundle())
                val result = JarvisRuntime.get(this@JarvisRecognitionService).recognize(
                    ActivationSource.ASSISTANT_GESTURE, settings.language, {}, {},
                )
                currentCoroutineContext().ensureActive()
                if (!preferences.settings.first().enabled) { listener.error(SpeechRecognizer.ERROR_CLIENT); return@launch }
                if (result is VoiceGateResult.Accepted && result.value.isNotBlank()) {
                    listener.results(Bundle().apply {
                        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(result.value))
                    })
                } else listener.error(SpeechRecognizer.ERROR_NO_MATCH)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) { listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
            catch (_: Exception) { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            catch (_: LinkageError) { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            finally { offWatcher?.cancel() }
        }
    }
    override fun onStopListening(listener: Callback) { operation?.cancel(); listener.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }
    override fun onCancel(listener: Callback) { operation?.cancel() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

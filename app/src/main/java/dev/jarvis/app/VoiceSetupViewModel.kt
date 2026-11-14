package dev.jarvis.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.jarvis.core.*
import dev.jarvis.safety.*
import dev.jarvis.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first

enum class SetupWork { IDLE, RECORDING, PROCESSING }
data class AiSettingsState(val loaded: Boolean = false, val cloud: Boolean = false, val model: String = "gemini-3.6-flash",
    val web: Boolean = false, val hasKey: Boolean = false, val saving: Boolean = false, val status: String? = null)

data class VoiceSetupState(
    val work: SetupWork = SetupWork.IDLE,
    val progress: Float = 0f,
    val message: String? = null,
    val settings: JarvisSettings = JarvisSettings(),
    val settingsLoaded: Boolean = false,
    val heard: String? = null,
    val reply: String? = null,
    val cameraPermissionNeeded: Boolean = false,
    val speaking: Boolean = false,
    val phase: String = "",
    val replyOrigin: String = "",
    val sources: List<WebSource> = emptyList(),
    val searchHtml: String? = null,
    val inputPulse: AudioPulse = AudioPulse.SILENT,
    val outputPulse: AudioPulse = AudioPulse.SILENT,
) { val busy: Boolean get() = work != SetupWork.IDLE }

internal val VoiceSetupState.reactorStatus: String?
    get() = when {
        !settings.enabled -> null
        work == SetupWork.RECORDING -> "Listening"
        speaking -> "Speaking"
        work == SetupWork.PROCESSING -> "Working"
        else -> null
    }

private val SPOKEN_ADDRESS = Regex(
    """^(?:(?:hey|ഹേ)[,\s]+)?(?:jarvis|jervis|ജാർവിസ്[\u200C\u200D]?)(?=$|[,.:!?\s—–-])[,.:!?\s—–-]*""",
    RegexOption.IGNORE_CASE,
)

internal fun spokenRequest(transcript: String): String =
    transcript.trim().replaceFirst(SPOKEN_ADDRESS, "").trim()

class VoiceSetupViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = JarvisRuntime.get(application)
    private val preferences = JarvisPreferences(application)
    private val tools = LocalTools(application)
    private val speechOutput = OfflineSpeechOutput(application)
    private val llm = LocalLlm(application)
    private val connector = GeminiConnector()
    private val configStore = AiConfigStore(application)
    private val quotaStore = PersistentModelQuotaStore(application)
    private val modelRouter = GeminiModelRouter(quotaStore)
    private val speechRouter = GeminiModelRouter(quotaStore, listOf("gemini-3.6-flash", "gemini-3.5-flash-lite"))
    private var config = AiConfig()
    private val download = ModelDownload(application)
    val modelState = download.state
    private val mutableAi = MutableStateFlow(AiSettingsState())
    val aiState = mutableAi.asStateFlow()
    private val mutableState = MutableStateFlow(VoiceSetupState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var idleLlm: Job? = null
    private var activation: ActivationSource? = null
    private var pendingTorch: Pair<LocalCommand.Torch, JarvisLanguage>? = null

    init {
        viewModelScope.launch {
            try { config = configStore.read() }
            catch (_: Exception) { mutableAi.update { it.copy(status = "Could not read your Gemini connection. Save it again.") } }
            mutableAi.update { it.copy(loaded = true, cloud = config.key.isNotBlank(), model = config.model, web = config.web, hasKey = config.key.isNotBlank()) }
        }
        viewModelScope.launch { speechOutput.speaking.collect { speaking ->
            mutableState.update { it.copy(speaking = speaking) }
        } }
        viewModelScope.launch { speechOutput.pulse.collect { pulse -> mutableState.update { it.copy(outputPulse = pulse) } } }
        viewModelScope.launch { speechOutput.error.collect { error -> if (error != null) message(error) } }
        viewModelScope.launch {
            // Latest product policy accepts every speaker; remove the obsolete biometric profile.
            try { EncryptedOwnerProfileStore(application).delete() }
            catch (_: Exception) { message("Old voice-profile cleanup failed. Speech still works without matching.") }
        }
        viewModelScope.launch {
            preferences.settings.collect { settings ->
                mutableState.update { it.copy(settings = settings, settingsLoaded = true) }
                if (!settings.enabled) { operation?.cancel(); speechOutput.stop(); RecentConversation.clear() }
                consumeActivation()
            }
        }
    }

    fun message(text: String) { mutableState.update { it.copy(message = text) } }
    fun clearPrivateContext() {
        RecentConversation.clear()
        mutableState.update { it.copy(heard = null, reply = null, sources = emptyList(), searchHtml = null, message = null) }
    }

    fun setEnabled(enabled: Boolean) {
        if (!enabled) { operation?.cancel(); speechOutput.stop(); activation = null }
        viewModelScope.launch { preferences.setEnabled(enabled) }
    }
    fun setFloating(enabled: Boolean) { viewModelScope.launch { preferences.setFloating(enabled) } }
    fun setProfile(profile: JarvisProfile) {
        viewModelScope.launch {
            try { preferences.setProfile(profile) }
            catch (_: java.io.IOException) { message("I couldn't save your preferences. Try again.") }
        }
    }
    fun setLanguage(language: String) { viewModelScope.launch { preferences.setLanguage(language) } }
    fun setSpokenReplies(value: Boolean) {
        if (!value) speechOutput.stop()
        viewModelScope.launch { preferences.setSpokenReplies(value) }
    }
    fun downloadModel() = download.start()
    fun selectModel(variant: LocalModel) = download.select(variant)
    fun pauseDownload() = download.pause()
    fun saveConnection(model: String, key: String, web: Boolean, forgetKey: Boolean = false) {
        if (mutableAi.value.saving || state.value.busy) return
        mutableAi.update { it.copy(saving = true, status = null) }
        viewModelScope.launch {
            try {
                val nextKey = if (forgetKey) "" else key.trim().ifBlank { config.key }
                val next = AiConfig(nextKey.isNotBlank(), nextKey, model.trim(), web)
                configStore.save(next)
                if (config.key != next.key) quotaStore.clear()
                config = next
                mutableAi.update { it.copy(cloud = next.cloud, model = next.model, web = next.web, hasKey = next.key.isNotBlank(), status = "Connection saved.") }
            } catch (failure: Exception) { mutableAi.update { it.copy(status = failure.message ?: "Connection could not be saved.") } }
            finally { mutableAi.update { it.copy(saving = false) } }
        }
    }
    fun testConnection(web: Boolean = false) {
        if (mutableAi.value.saving || state.value.busy) return
        mutableAi.update { it.copy(saving = true, status = "Testing Gemini…") }
        viewModelScope.launch {
            try {
                check(NetworkAvailability.hasValidatedInternet(getApplication())) { "Connect to the internet to test Gemini." }
                val routed = withTimeout(32_000) {
                    modelRouter.run(config.model) { model ->
                        val connection = config.copy(model = model)
                        if (web) connector.search(connection, "Search the web for Android's default digital assistant. Read https://developer.android.com/reference/android/app/role/RoleManager and give a one-sentence summary with sources.")
                        else connector.answer(connection, AiProtocol.system(false), "Reply with the word connected.")
                    }
                }
                val reply = routed.value
                check(reply is AiResult.Answer) { "Gemini returned an unexpected response." }
                mutableAi.update { it.copy(status = if (web) "${routed.model} returned ${reply.sources.size} source links." else "Connected to ${routed.model}.") }
            } catch (_: TimeoutCancellationException) { mutableAi.update { it.copy(status = "Gemini did not respond in time.") } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableAi.update { it.copy(status = failure.message ?: "Gemini test failed.") } }
            finally { mutableAi.update { it.copy(saving = false) } }
        }
    }
    fun previewVoice() {
        if (state.value.settings.enabled && !state.value.busy) speechOutput.speak("At your service. I'm Jarvis. What shall we do next?")
    }

    fun activate(source: ActivationSource) { activation = source; consumeActivation() }
    private fun consumeActivation() {
        if (!state.value.settingsLoaded) return
        val source = activation ?: return
        activation = null
        listen(source)
    }

    private fun preferredLanguage() = if (state.value.settings.language == "ml") JarvisLanguage.MALAYALAM else JarvisLanguage.ENGLISH

    private suspend fun cloudConnection(): AiConfig? {
        aiState.first { it.loaded }
        return config.takeIf { it.key.isNotBlank() && NetworkAvailability.hasValidatedInternet(getApplication()) }
    }

    private suspend fun transcribe(audio: FloatArray): VoiceGateResult<String> {
        val language = state.value.settings.language
        if (AudioQuality.activeFrameCount(audio) < 10) return VoiceGateResult.Denied(VoiceDenial.BAD_AUDIO)
        val detected = runtime.detectLanguage(audio)
        val confident = detected?.takeIf { it.confidence >= .75f }?.code
        if (confident != null && (confident !in setOf("en", "ml") || (language.isNotEmpty() && language != confident))) {
            return VoiceGateResult.Denied(VoiceDenial.UNSUPPORTED_LANGUAGE)
        }
        val expected = confident ?: language
        val connection = cloudConnection()
        if (connection != null) {
            val cloud = tryCloud {
                withTimeout(14_000) {
                    speechRouter.run(GeminiConnector.TRANSCRIBE_MODEL) { model ->
                        connector.transcribe(connection, audio, expected, model)
                    }.value
                }
            }
            if (cloud != null) {
                val result = runtime.validateTranscript(cloud, expected)
                if (result != VoiceGateResult.Denied(VoiceDenial.BAD_AUDIO)) return result
            }
        }
        return withTimeout(32_000) { runtime.transcribeCapturedAudio(audio, language, detected, detect = false) }
    }

    private suspend fun <T> tryCloud(block: suspend () -> T): T? = try {
        block()
    } catch (_: TimeoutCancellationException) {
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    fun listen(source: ActivationSource = ActivationSource.IN_APP) {
        if (state.value.busy) return
        if (!state.value.settingsLoaded || !state.value.settings.enabled) { message("Jarvis is off. Turn it on with the switch above."); return }
        speechOutput.stop()
        launchOperation {
            mutableState.update { it.copy(heard = null, reply = null, sources = emptyList(),
                searchHtml = null, progress = 0f, phase = "Opening microphone…") }
            val result = runtime.recognize(source, state.value.settings.language,
                onRecording = { mutableState.update { it.copy(work = SetupWork.RECORDING, phase = "") } },
                onProgress = { progress -> mutableState.update { it.copy(progress = progress) } },
                onProcessing = { mutableState.update { it.copy(work = SetupWork.PROCESSING, phase = "Transcribing your speech…",
                    inputPulse = AudioPulse.SILENT) } },
                onPulse = { pulse -> mutableState.update { it.copy(inputPulse = pulse) } },
                transcribe = ::transcribe,
            )
            handleTranscript(result)
        }
    }

    private suspend fun handleTranscript(result: VoiceGateResult<String>) {
        val speak = state.value.settings.spokenReplies
        when (result) {
            is VoiceGateResult.Accepted -> {
                val request = spokenRequest(result.value)
                val language = (SpeechLanguagePolicy.inspect(request.ifEmpty { result.value }) as? SpeechLanguageResult.Supported)
                    ?.language ?: preferredLanguage()
                mutableState.update { it.copy(heard = result.value) }
                if (request.isBlank()) greet(language, speak)
                else respond(request, speak, language)
            }
            is VoiceGateResult.Denied -> when (result.reason) {
                VoiceDenial.UNSUPPORTED_LANGUAGE -> message("Please speak English or Malayalam. ഇംഗ്ലീഷിലോ മലയാളത്തിലോ സംസാരിക്കൂ.")
                VoiceDenial.BAD_AUDIO -> greet(preferredLanguage(), speak)
                else -> message("Jarvis is off. Turn it on and try again.")
            }
        }
    }

    private fun greet(language: JarvisLanguage, speak: Boolean) {
        val text = jarvisGreeting(language, if (deviceLocked()) "" else state.value.settings.profile.name)
        mutableState.update { it.copy(reply = text, replyOrigin = "Jarvis", searchHtml = null, message = null) }
        if (speak) speechOutput.speak(text, language)
    }

    fun typedCommand(text: String) {
        if (state.value.busy || text.isBlank()) return
        if (!state.value.settingsLoaded || !state.value.settings.enabled) { message("Turn Jarvis on first."); return }
        speechOutput.stop()
        launchOperation {
            val request = text.take(1000)
            val language = (SpeechLanguagePolicy.inspect(request) as? SpeechLanguageResult.Supported)?.language
            if (language == null) {
                message("Please type in English or Malayalam. ഇംഗ്ലീഷിലോ മലയാളത്തിലോ എഴുതൂ.")
                return@launchOperation
            }
            mutableState.update { it.copy(heard = request, reply = null, sources = emptyList(), searchHtml = null) }
            respond(request, speak = false, language = language)
        }
    }

    private suspend fun execute(command: LocalCommand, speak: Boolean, language: JarvisLanguage) {
        if (!preferences.settings.first().enabled) return
        currentCoroutineContext().ensureActive()
        val reply = tools.execute(command, language)
        if (command != LocalCommand.Blocked && !deviceLocked()) {
            state.value.heard?.let { request -> RecentConversation.remember(request, reply.text) }
        }
        pendingTorch = if (reply.needsCamera && command is LocalCommand.Torch) command to language else null
        mutableState.update { it.copy(reply = reply.text, replyOrigin = "Android", sources = emptyList(), searchHtml = null, message = null, cameraPermissionNeeded = reply.needsCamera) }
        if (speak && !reply.needsCamera) speechOutput.speak(reply.text, language)
    }

    private suspend fun respond(text: String, speak: Boolean, language: JarvisLanguage) {
        val locked = deviceLocked()
        if (locked) RecentConversation.clear()
        if (GREETING.matches(text.trim().trimEnd('.', '!', '?', ' '))) { greet(language, speak); return }
        val direct = CommandRouter.route(text)
        if (direct == LocalCommand.Blocked) {
            execute(direct, speak, language)
            return
        }
        val connection = cloudConnection()
        val profile = if (locked) JarvisProfile() else state.value.settings.profile
        val context = AiProtocol.request(text, profile, RecentConversation.snapshot())
        val cloud = connection?.let {
            mutableState.update { state -> state.copy(work = SetupWork.PROCESSING, phase = "Asking Gemini…") }
            tryCloud {
                withTimeout(32_000) {
                    modelRouter.run(connection.model) { model ->
                        val active = connection.copy(model = model)
                        val result = if (active.web && WEB_REQUEST.containsMatchIn(text)) {
                            connector.search(active, text, language)
                        } else when (val answer = connector.answer(active, AiProtocol.system(active.web, language), context)) {
                            is AiResult.Search -> connector.search(active, answer.query, language)
                            else -> answer
                        }
                        check(result !is AiResult.Answer || matchesLanguage(result.text, language))
                        result
                    }
                }
            }
        }
        val result = cloud?.value ?: run {
            if (direct != LocalCommand.Unknown && !WEB_REQUEST.containsMatchIn(text)) {
                execute(direct, speak, language)
                return
            }
            mutableState.update { it.copy(work = SetupWork.PROCESSING,
                phase = if (connection == null) "Offline AI is reading your request…" else "Gemini unavailable · Using offline AI…") }
            if (ModelDownload.installed(getApplication()) == null) {
                val message = if (language == JarvisLanguage.MALAYALAM) "ഓഫ്‌ലൈനിൽ മറുപടി ലഭിക്കാൻ AI ക്രമീകരണങ്ങളിൽ ഒരു മോഡൽ ഡൗൺലോഡ് ചെയ്യൂ."
                    else "Download an offline model in AI settings to get answers when Gemini is unavailable."
                showAnswer(text, AiResult.Answer(message), speak, "Jarvis", language)
                return
            }
            withTimeout(180_000) {
                AiProtocol.parse(llm.generate(AiProtocol.system(false, language), context) { phase ->
                    mutableState.update { it.copy(phase = phase) }
                })
            }
        }
        currentCoroutineContext().ensureActive()
        if (!preferences.settings.first().enabled) return
        when (result) {
            is AiResult.Action -> execute(result.command, speak, language)
            is AiResult.Answer -> {
                val answer = if (matchesLanguage(result.text, language)) result else AiResult.Answer(
                    if (language == JarvisLanguage.MALAYALAM) "മലയാളത്തിൽ മറുപടി തയ്യാറാക്കാനായില്ല. ചോദ്യം മറ്റൊരു രീതിയിൽ ചോദിക്കൂ."
                    else "I couldn't produce an English reply. Try rephrasing your question."
                )
                showAnswer(text, answer, speak, cloud?.let { "Gemini · ${it.model}" } ?: "Offline AI", language)
            }
            is AiResult.Search -> showAnswer(text, AiResult.Answer(
                if (language == JarvisLanguage.MALAYALAM) "വെബിൽ തിരയാൻ ഇന്റർനെറ്റും ജെമിനിയും ആവശ്യമാണ്."
                else "Web search needs internet access and a working Gemini connection."
            ), speak, "Jarvis", language)
        }
    }

    private fun matchesLanguage(text: String, language: JarvisLanguage): Boolean = when (val result = SpeechLanguagePolicy.inspect(text)) {
        SpeechLanguageResult.Unsupported -> false
        SpeechLanguageResult.Empty -> true // Numeric answers are language neutral.
        is SpeechLanguageResult.Supported -> result.language == language
    }

    private fun showAnswer(question: String, answer: AiResult.Answer, speak: Boolean, origin: String, language: JarvisLanguage) {
        mutableState.update { it.copy(reply = answer.text, replyOrigin = origin, sources = answer.sources, searchHtml = answer.searchHtml, message = null) }
        if (!deviceLocked()) RecentConversation.remember(question, answer.text)
        // Text is delivered before optional speech. Typed replies never trigger unsolicited audio.
        if (speak) speechOutput.speak(answer.text.take(500), language)
    }

    private fun deviceLocked() =
        getApplication<Application>().getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked

    fun cameraPermissionResult(granted: Boolean) {
        val command = pendingTorch
        pendingTorch = null
        mutableState.update { it.copy(cameraPermissionNeeded = false) }
        if (granted && command != null) launchOperation { execute(command.first, speak = false, language = command.second) }
        else if (!granted) message("Flashlight permission is off. You can enable it in Android app settings.")
    }

    fun cancelRecording() {
        operation?.cancel(); speechOutput.stop(); activation = null
        message("Stopped.")
    }

    fun onBackground() {
        // Opening Clock/another app must not erase its action result.
        if (state.value.busy) cancelRecording()
        speechOutput.stop()
    }

    private fun launchOperation(block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        idleLlm?.cancel()
        operation = viewModelScope.launch {
            mutableState.update { it.copy(work = SetupWork.PROCESSING, message = null) }
            try { block() }
            catch (_: TimeoutCancellationException) { message("That request timed out. Try a shorter request or switch AI mode.") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message(failure.message ?: "That request failed. Try again.") }
            catch (_: LinkageError) { message("The AI engine could not start on this device. Try Gemini in AI settings.") }
            finally {
                mutableState.update { it.copy(work = SetupWork.IDLE, progress = 0f, inputPulse = AudioPulse.SILENT) }
                idleLlm = viewModelScope.launch { delay(90_000); llm.close() }
            }
        }
    }

    override fun onCleared() {
        // A one-shot cleanup job: viewModelScope is already cancelled at this point.
        speechOutput.close()
        download.close()
        CoroutineScope(Dispatchers.Default).launch { llm.close() }
        runtime.releaseWhenIdle()
        super.onCleared()
    }

    private companion object {
        val GREETING = Regex("(?:jarvis|(?:hi|hello|hey)(?:[, ]+jarvis)?|നമസ്കാരം|ഹലോ|ജാർവിസ്)(?:[, ]+ജാർവിസ്)?", RegexOption.IGNORE_CASE)
        val WEB_REQUEST = Regex("https://\\S+|\\b(?:search (?:the )?web|search online|look (?:it |this )?up|latest news|current weather)\\b", RegexOption.IGNORE_CASE)
    }
}

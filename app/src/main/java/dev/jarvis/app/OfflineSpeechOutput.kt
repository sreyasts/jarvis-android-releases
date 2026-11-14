package dev.jarvis.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.jarvis.voice.OfflineJarvisVoice
import dev.jarvis.core.AudioPulse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class OfflineSpeechOutput(private val context: Context) {
    private val voice = OfflineJarvisVoice(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var operation: Job? = null
    @Volatile private var track: AudioTrack? = null
    private val mutableSpeaking = MutableStateFlow(false)
    val speaking = mutableSpeaking.asStateFlow()
    private val mutablePulse = MutableStateFlow(AudioPulse.SILENT)
    val pulse = mutablePulse.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    fun speak(text: String, language: JarvisLanguage = JarvisLanguage.ENGLISH) {
        stop()
        mutableError.value = null
        operation = scope.launch(start = CoroutineStart.LAZY) {
            val thisOperation = currentCoroutineContext()[Job]
            try {
                if (language == JarvisLanguage.MALAYALAM) {
                    speakMalayalam(text)
                    return@launch
                }
                val audio = voice.synthesize(text)
                try {
                    currentCoroutineContext().ensureActive()
                    val minimum = AudioTrack.getMinBufferSize(audio.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                    check(minimum > 0)
                    val output = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(audio.sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum * 2, audio.sampleRate / 2 * 4)).build()
                    track = output
                    try {
                        check(output.state == AudioTrack.STATE_INITIALIZED)
                        currentCoroutineContext().ensureActive()
                        mutableSpeaking.value = true
                        output.play()
                        coroutineScope {
                            val meter = launch {
                                while (isActive) {
                                    val position = output.playbackHeadPosition.coerceIn(0, audio.samples.size)
                                    mutablePulse.value = AudioPulse.fromPcm(audio.samples, position,
                                        minOf(audio.sampleRate / 20, audio.samples.size - position))
                                    delay(50)
                                }
                            }
                            try {
                                var offset = 0
                                val deadline = SystemClock.elapsedRealtime() + audio.samples.size.toLong() * 1000 / audio.sampleRate + 10_000
                                while (offset < audio.samples.size) {
                                    currentCoroutineContext().ensureActive()
                                    check(SystemClock.elapsedRealtime() < deadline)
                                    val written = output.write(audio.samples, offset, minOf(2048, audio.samples.size - offset), AudioTrack.WRITE_NON_BLOCKING)
                                    check(written >= 0)
                                    if (written == 0) delay(10) else offset += written
                                }
                                while (output.playbackHeadPosition.toLong() < audio.samples.size && SystemClock.elapsedRealtime() < deadline) {
                                    currentCoroutineContext().ensureActive(); delay(20)
                                }
                            } finally { meter.cancelAndJoin() }
                        }
                    } finally {
                        if (track === output) track = null
                        try { output.stop() } catch (_: IllegalStateException) { }
                        output.release()
                    }
                } finally { audio.samples.fill(0f) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableError.value = if (language == JarvisLanguage.MALAYALAM)
                    "മലയാളം ശബ്ദം ലഭ്യമല്ല. മറുപടി സ്ക്രീനിൽ വായിക്കാം."
                else "Spoken audio isn't ready. You can read the reply on screen."
            }
            catch (_: LinkageError) { mutableError.value = "The voice engine isn't available on this device." }
            finally { if (operation === thisOperation) { mutableSpeaking.value = false; mutablePulse.value = AudioPulse.SILENT } }
        }
        operation?.start()
    }

    private suspend fun speakMalayalam(text: String) = withContext(Dispatchers.Main) {
        val ready = CompletableDeferred<Int>()
        val engine = TextToSpeech(context.applicationContext) { ready.complete(it) }
        try {
            check(withTimeoutOrNull(6_000) { ready.await() } == TextToSpeech.SUCCESS)
            val voice = engine.voices?.firstOrNull { it.locale.language == "ml" && !it.isNetworkConnectionRequired }
                ?: error("No offline Malayalam voice")
            check(engine.setVoice(voice) == TextToSpeech.SUCCESS)
            engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            val finished = CompletableDeferred<Boolean>()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { }
                override fun onDone(utteranceId: String?) { finished.complete(true) }
                @Deprecated("Platform callback") override fun onError(utteranceId: String?) { finished.complete(false) }
            })
            mutableSpeaking.value = true
            check(engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-ml") == TextToSpeech.SUCCESS)
            check(withTimeoutOrNull(30_000) { finished.await() } == true)
        } finally {
            engine.stop()
            engine.shutdown()
        }
    }
    fun stop() {
        operation?.cancel()
        mutableSpeaking.value = false
        mutablePulse.value = AudioPulse.SILENT
        track?.let { output -> try { output.pause(); output.flush() } catch (_: IllegalStateException) { } }
    }
    fun close() { stop(); scope.launch { voice.release(); scope.cancel() } }
}

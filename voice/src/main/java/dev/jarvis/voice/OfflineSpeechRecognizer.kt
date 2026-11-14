package dev.jarvis.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.*
import dev.jarvis.core.AudioQuality
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class OfflineSpeechRecognizer(context: Context) {
    private val assets = context.applicationContext.assets
    private val mutex = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var loadedLanguage: String? = null

    suspend fun transcribe(samples: FloatArray, language: String = ""): String = recognize(samples, language).text

    suspend fun recognize(samples: FloatArray, language: String = ""): OfflineRecognizerResult = withContext(Dispatchers.Default) {
        require(language in setOf("", "en", "ml"))
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (recognizer == null || loadedLanguage != language) {
                recognizer?.release()
                recognizer = null
                verifyAssets(language == "ml")
                val model = if (language == "ml") {
                    OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = "models/malayalam/model.int8.onnx"),
                        tokens = "models/malayalam/tokens.txt", numThreads = 2, provider = "cpu",
                    )
                } else {
                    OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = "models/whisper/base-encoder.int8.onnx",
                            decoder = "models/whisper/base-decoder.int8.onnx",
                            language = language, task = "transcribe", tailPaddings = 1000,
                        ),
                        tokens = "models/whisper/base-tokens.txt", numThreads = 2, debug = false, provider = "cpu",
                    )
                }
                recognizer = OfflineRecognizer(assets, OfflineRecognizerConfig(modelConfig = model))
                loadedLanguage = language
            }
            val engine = checkNotNull(recognizer)
            val stream = engine.createStream()
            try {
                stream.acceptWaveform(samples, AudioQuality.SAMPLE_RATE)
                engine.decode(stream)
                currentCoroutineContext().ensureActive()
                engine.getResult(stream).let { it.copy(text = it.text.trim()) }
            } finally { stream.release() }
        }
    }

    private fun verifyAssets(malayalam: Boolean) {
        val folder = if (malayalam) "models/malayalam" else "models/whisper"
        val filenames = if (malayalam) setOf("model.int8.onnx", "tokens.txt")
            else setOf("base-encoder.int8.onnx", "base-decoder.int8.onnx", "base-tokens.txt")
        val entries = assets.open("$folder/checksums.txt").bufferedReader().use { it.readLines() }
        require(entries.size == filenames.size) { "Speech model files are missing. Reinstall Jarvis." }
        for (entry in entries) {
            val parts = entry.split("  ", limit = 2)
            require(parts.size == 2 && parts[1] in filenames)
            val digest = MessageDigest.getInstance("SHA-256")
            assets.open("$folder/${parts[1]}").use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == parts[0]) { "Speech model verification failed. Reinstall Jarvis." }
        }
    }

    suspend fun release() = withContext(Dispatchers.Default) {
        mutex.withLock { recognizer?.release(); recognizer = null; loadedLanguage = null }
    }
}

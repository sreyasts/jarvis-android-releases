package dev.jarvis.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.*
import dev.jarvis.core.SpokenText
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OfflineJarvisVoice(context: Context) {
    private val assets = context.applicationContext.assets
    private val mutex = Mutex()
    private var engine: OfflineTts? = null

    suspend fun synthesize(text: String): GeneratedAudio = withContext(Dispatchers.Default) {
        require(text.isNotBlank() && text.length <= 1000)
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val tts = engine ?: load().also { engine = it }
            val caller = currentCoroutineContext()
            // JNI resolves the concrete invoke(float[]) method. An indy lambda only
            // exposes erased invoke(Object), which aborts Android's checked JNI.
            val callback = object : (FloatArray) -> Int {
                override fun invoke(samples: FloatArray): Int = if (caller.isActive) 1 else 0
            }
            val audio = tts.generateWithCallback(SpokenText.prepare(text), sid = 0, speed = 0.98f, callback = callback)
            if (!caller.isActive) { audio.samples.fill(0f); caller.ensureActive() }
            check(audio.samples.isNotEmpty() && audio.samples.all(Float::isFinite)) { "Voice generation failed" }
            audio
        }
    }

    private fun load(): OfflineTts {
        val checksums = mapOf(
            "model.onnx" to "59ec096e3dd57bbf029faecf576dc2dd73bc0f92fb3e2a14fab0d54a29fd90fa",
            "tokens.txt" to "2619c1a9de1bcf928162f40c583caf39368cfd6b2340c7bcad51dc634411ec36",
            "lexicon.txt" to "8b67ae549a465eee78f92ecb74644ad90c3c58ce65f19c68baf85878a10ef73a",
        )
        for ((name, expected) in checksums) {
            val digest = MessageDigest.getInstance("SHA-256")
            assets.open("models/tts/$name").use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "Voice model verification failed. Reinstall Jarvis." }
        }
        return OfflineTts(assets, OfflineTtsConfig(
            model = OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(
                model = "models/tts/model.onnx", tokens = "models/tts/tokens.txt", lexicon = "models/tts/lexicon.txt",
                noiseScale = 0.5f, noiseScaleW = 0.6f,
            ), numThreads = 2, debug = false, provider = "cpu"), maxNumSentences = 1,
        ))
    }
    suspend fun release() = withContext(Dispatchers.Default) { mutex.withLock { engine?.release(); engine = null } }
}

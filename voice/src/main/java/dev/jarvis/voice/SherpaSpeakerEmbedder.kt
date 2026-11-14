package dev.jarvis.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import dev.jarvis.core.AudioQuality
import dev.jarvis.core.SpeakerEmbedder
import dev.jarvis.core.SpeakerVector
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SherpaSpeakerEmbedder(context: Context) : SpeakerEmbedder {
    private val assets = context.applicationContext.assets
    private val mutex = Mutex()
    private var extractor: SpeakerEmbeddingExtractor? = null
    override val modelId = "campplus-zh-en-advanced-16k:$MODEL_SHA256"

    override suspend fun embed(samples: FloatArray): FloatArray = withContext(Dispatchers.Default) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val engine = extractor ?: load().also { extractor = it }
            val stream = engine.createStream()
            try {
                stream.acceptWaveform(samples, AudioQuality.SAMPLE_RATE)
                stream.inputFinished()
                check(engine.isReady(stream)) { "Not enough speech for voice matching. Try a longer phrase." }
                val embedding = engine.compute(stream)
                try { SpeakerVector.normalize(embedding) } finally { embedding.fill(0f) }
            } finally { stream.release() }
        }
    }

    private fun load(): SpeakerEmbeddingExtractor {
        val digest = MessageDigest.getInstance("SHA-256")
        assets.open(MODEL_ASSET).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == MODEL_SHA256) { "Speaker model checksum failed. Reinstall a verified build." }
        return SpeakerEmbeddingExtractor(
            assetManager = assets,
            config = SpeakerEmbeddingExtractorConfig(
                model = MODEL_ASSET, numThreads = 2, debug = false, provider = "cpu",
            ),
        )
    }

    suspend fun release() = withContext(Dispatchers.Default) {
        mutex.withLock { extractor?.release(); extractor = null }
    }

    companion object {
        const val MODEL_ASSET = "models/speaker.onnx"
        const val MODEL_SHA256 = "aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2"
    }
}

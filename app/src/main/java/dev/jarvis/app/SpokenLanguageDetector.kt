package dev.jarvis.app

import android.content.Context
import android.content.res.AssetManager
import dev.jarvis.core.AudioQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SpokenLanguageDetector(context: Context) {
    private val assets = context.applicationContext.assets
    private val lock = Mutex()
    private var handle = 0L
    private val labels by lazy {
        assets.open("models/language/lid-labels.txt").bufferedReader().use { it.readLines() }
            .also { require(it.size == 107) }
    }

    data class Match(val code: String, val confidence: Float)

    suspend fun detect(audio: FloatArray): Match? = withContext(Dispatchers.Default) {
        // A name or a very short command does not give the classifier enough speech.
        if (audio.size < AudioQuality.SAMPLE_RATE * 3 || AudioQuality.activeFrameCount(audio) < 60) return@withContext null
        lock.withLock {
            if (handle == 0L) {
                System.loadLibrary("jarvis_language")
                handle = nativeLoad(assets)
                check(handle != 0L)
            }
            val scores = nativeScore(handle, audio)
            val best = scores.indices.maxBy { scores[it] }
            Match(labels[best], scores[best])
        }
    }

    suspend fun release() = withContext(Dispatchers.Default) {
        lock.withLock {
            if (handle != 0L) {
                nativeRelease(handle)
                handle = 0L
            }
        }
    }

    private external fun nativeLoad(assets: AssetManager): Long
    private external fun nativeScore(handle: Long, audio: FloatArray): FloatArray
    private external fun nativeRelease(handle: Long)
}

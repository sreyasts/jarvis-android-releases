package dev.jarvis.app

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** CPU-only llama.cpp; no server process, shell, account, or network inference. */
class LocalLlm(private val context: Context) {
    private val lock = Mutex()
    private var handle = 0L
    private var loaded: LocalModel? = null
    interface Observer { fun cancelled(): Boolean }
    private external fun nativeLoad(path: String, observer: Observer): Long
    private external fun nativeGenerate(handle: Long, prompt: ByteArray, grammar: ByteArray, observer: Observer): ByteArray
    private external fun nativeRelease(handle: Long)

    suspend fun generate(system: String, request: String, onStage: (String) -> Unit = {}): String = lock.withLock {
        withContext(Dispatchers.Default) {
            ensureActive()
            val variant = checkNotNull(ModelDownload.installed(context)) { "Download offline AI in AI settings." }
            val model = ModelDownload.modelFile(context, variant)
            val job = currentCoroutineContext().job
            val observer = object : Observer { override fun cancelled() = !job.isActive }
            if (handle != 0L && variant != loaded) {
                nativeRelease(handle)
                handle = 0L
            }
            if (handle == 0L) {
                onStage("Loading offline AI…")
                System.loadLibrary("jarvis_llm")
                handle = try { nativeLoad(model.absolutePath, observer) }
                    catch (failure: Exception) { ensureActive(); throw failure }
                check(handle != 0L) { "Offline AI could not start. Try again." }
                loaded = variant
            }
            ensureActive()
            onStage("Offline AI is preparing your reply…")
            // Qwen3-4B-Instruct-2507 is non-thinking; use its plain ChatML assistant prefix.
            fun clean(text: String) = text.replace("<|", "< | ").replace("|>", " | >")
            val prompt = "<|im_start|>system\n${clean(system)}<|im_end|>\n" +
                "<|im_start|>user\n${clean(request)}<|im_end|>\n<|im_start|>assistant\n" +
                if (variant == LocalModel.FAST) "<think>\n\n</think>\n\n" else ""
            val output = try {
                nativeGenerate(handle, prompt.toByteArray(), context.assets.open("ai/json.gbnf").use { it.readBytes() }, observer)
            } catch (failure: Exception) { ensureActive(); throw failure }
            ensureActive()
            output.toString(Charsets.UTF_8)
        }
    }

    suspend fun close() = lock.withLock { withContext(Dispatchers.Default) {
        if (handle != 0L) { nativeRelease(handle); handle = 0; loaded = null }
    } }
}

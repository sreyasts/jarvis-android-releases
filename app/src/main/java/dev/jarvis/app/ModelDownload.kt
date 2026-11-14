package dev.jarvis.app

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class LocalModel(val title: String, val fileName: String, val size: Long, val sha256: String, val url: String) {
    FAST("Qwen3 1.7B · 1.28 GB", "qwen3-1.7b-q4km.gguf", 1_282_439_584L,
        "72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb",
        "https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/dcb19155b962dbb6389f4691a982043a8e651022/Qwen_Qwen3-1.7B-Q4_K_M.gguf"),
    STRONG("Qwen3 4B · 2.50 GB", "qwen3-4b-instruct-2507-q4km.gguf", ModelDownload.SIZE, ModelDownload.SHA256, ModelDownload.URL_STRING),
}

data class ModelState(val ready: Boolean = false, val downloading: Boolean = false, val bytes: Long = 0,
    val error: String? = null, val variant: LocalModel = LocalModel.STRONG)

class ModelDownload(context: Context) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val selection = this.context.getSharedPreferences("local_ai", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(stateFor(selected(this.context)))
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun select(variant: LocalModel) {
        if (job?.isCompleted == false || mutable.value.variant == variant) return
        selection.edit().putString("model", variant.name).apply()
        mutable.value = stateFor(variant)
    }

    private fun stateFor(variant: LocalModel) = ModelState(ready(context, variant), variant = variant)

    fun start() {
        val variant = mutable.value.variant
        if (job?.isCompleted == false || ready(context, variant)) return
        mutable.value = ModelState(downloading = true, variant = variant)
        job = scope.launch {
            val file = modelFile(context, variant)
            val partial = File(file.parentFile, if (variant == LocalModel.STRONG) "model.download" else "${file.name}.download")
            val size = variant.size
            try {
                file.parentFile!!.mkdirs()
                if (partial.length() > size) partial.delete()
                check(file.parentFile!!.usableSpace > size - partial.length() + 64L * 1024 * 1024) { "Free more storage to finish downloading this model." }
                var offset = partial.length()
                if (offset < size) {
                    val connection = open(variant, offset)
                    try {
                        if (connection.responseCode == 200) offset = 0
                        else check(connection.responseCode == 206 && connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true) {
                            "The download server could not resume. Try again."
                        }
                        connection.inputStream.use { input ->
                            java.io.FileOutputStream(partial, offset > 0).use { output ->
                                val buffer = ByteArray(256 * 1024)
                                var lastUpdate = 0L
                                while (true) {
                                    ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    offset += count
                                    check(offset <= size) { "Offline model download has an unexpected size." }
                                    output.write(buffer, 0, count)
                                    val now = android.os.SystemClock.elapsedRealtime()
                                    if (now - lastUpdate > 250) { mutable.value = ModelState(downloading = true, bytes = offset, variant = variant); lastUpdate = now }
                                }
                                output.fd.sync()
                            }
                        }
                    } finally { connection.disconnect() }
                }
                mutable.value = ModelState(downloading = true, bytes = size, variant = variant)
                check(partial.length() == size) { "Download interrupted. Tap Download to resume." }
                val digest = MessageDigest.getInstance("SHA-256")
                partial.inputStream().buffered().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (hash != variant.sha256) { partial.delete(); error("Model verification failed. Download it again.") }
                check(partial.renameTo(file)) { "Could not save the offline model. Check free storage." }
                checksumFile(context, variant).writeText(variant.sha256)
                mutable.value = ModelState(ready = true, bytes = size, variant = variant)
            } catch (cancelled: CancellationException) {
                mutable.value = ModelState(bytes = partial.length(), error = "Download paused. Tap Download to resume.", variant = variant)
                throw cancelled
            } catch (failure: Exception) {
                mutable.value = ModelState(bytes = partial.length(), error = failure.message ?: "Download failed. Check your connection and retry.", variant = variant)
            }
        }
    }
    fun pause() { job?.cancel() }
    fun close() { scope.cancel() }

    private fun open(variant: LocalModel, offset: Long): HttpURLConnection {
        var address = URL(variant.url)
        repeat(6) {
            check(address.protocol == "https" && address.userInfo == null) { "Unsafe model download address." }
            val connection = (address.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 30_000
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            val code = connection.responseCode
            if (code !in setOf(301, 302, 303, 307, 308)) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            check(location != null) { "Model server returned no download address." }
            address = URL(address, location)
        }
        error("Too many model download redirects. Retry later.")
    }

    companion object {
        const val SIZE = 2_497_280_736L
        const val SHA256 = "2fde00ce69dd4899c70d020845e2638353015bba0fdf161b3eb965f2bca4464e"
        const val URL_STRING = "https://huggingface.co/bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF/resolve/ae44f08e1392f39c0e474af10c3ff8355c8b6688/Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf"
        fun selected(context: Context): LocalModel {
            val stored = context.getSharedPreferences("local_ai", Context.MODE_PRIVATE).getString("model", null)
            return LocalModel.entries.firstOrNull { it.name == stored } ?: if (ready(context, LocalModel.STRONG) ||
                File(context.noBackupFilesDir, "ai/model.download").exists()) LocalModel.STRONG else LocalModel.FAST
        }
        fun modelFile(context: Context, variant: LocalModel = LocalModel.STRONG) =
            File(context.noBackupFilesDir, "ai/${variant.fileName}")
        fun checksumFile(context: Context, variant: LocalModel): File =
            File(modelFile(context, variant).parentFile, if (variant == LocalModel.STRONG) "verified.sha256" else "${variant.fileName}.sha256")

        fun ready(context: Context, variant: LocalModel = LocalModel.STRONG): Boolean {
            val file = modelFile(context, variant)
            return file.length() == variant.size && runCatching { checksumFile(context, variant).readText() == variant.sha256 }.getOrDefault(false)
        }

        fun installed(context: Context): LocalModel? {
            val selected = selected(context)
            return (listOf(selected) + LocalModel.entries).distinct().firstOrNull { ready(context, it) }
        }
    }
}

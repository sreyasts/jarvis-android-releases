package dev.jarvis.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AiConfig(val cloud: Boolean = false, val key: String = "", val model: String = "gemini-3.6-flash", val web: Boolean = false) {
    override fun toString() = "AiConfig(cloud=$cloud, key=[redacted], model=$model, web=$web)"
}

/** Keystore-encrypted, excluded from Android backup. Never shipped in an APK. */
class AiConfigStore(context: Context, namespace: String = "ai-connection") {
    init { require(namespace.matches(Regex("[a-z][a-z0-9-]{0,40}"))) }
    private val alias = "jarvis.$namespace.v1"
    private val file = AtomicFile(File(context.noBackupFilesDir, "$namespace.enc"))
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(): SecretKey = (keys().getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
    }.generateKey()

    suspend fun read(): AiConfig = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists()) return@withContext AiConfig()
        val encoded = file.openRead().use { it.readBytes() }
        try {
            check(encoded.size in 29..16_384) { "Gemini settings could not be read. Save your connection again." }
            val secret = keys().getKey(alias, null) as? SecretKey ?: error("Gemini key is unavailable. Enter it again in AI settings.")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, encoded.copyOfRange(0, 12)))
            cipher.updateAAD(AAD)
            val clear = cipher.doFinal(encoded, 12, encoded.size - 12)
            try {
                val json = JSONObject(clear.toString(Charsets.UTF_8))
                AiConfig(json.getBoolean("cloud"), json.getString("key"), json.getString("model"), json.getBoolean("web"))
            } finally { clear.fill(0) }
        } finally { encoded.fill(0) }
    }

    suspend fun save(config: AiConfig) = withContext(Dispatchers.IO) {
        require(config.model.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Enter a valid Gemini model ID." }
        require(config.key.length <= 512 && config.key.none { it.isWhitespace() || it.isISOControl() }) { "The API key contains spaces or invalid characters." }
        require(!config.cloud || config.key.isNotBlank()) { "Enter your Gemini API key first." }
        val clear = JSONObject().put("cloud", config.cloud).put("key", config.key).put("model", config.model).put("web", config.web).toString().toByteArray()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(AAD)
            val encrypted = cipher.doFinal(clear)
            val output = file.startWrite()
            try { output.write(cipher.iv); output.write(encrypted); file.finishWrite(output) }
            catch (failure: Exception) { file.failWrite(output); throw failure }
        } finally { clear.fill(0) }
    }
    private companion object {
        val AAD = "jarvis-ai-connection-v1".toByteArray()
    }
}

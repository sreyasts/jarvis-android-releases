package dev.jarvis.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.jarvis.core.OwnerProfileStore
import dev.jarvis.core.OwnerVoiceProfile
import java.io.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedOwnerProfileStore(context: Context, namespace: String = "owner-voice") : OwnerProfileStore {
    init { require(namespace.matches(Regex("[a-z][a-z0-9-]{0,40}"))) }
    private val file = AtomicFile(File(context.noBackupFilesDir, "$namespace.enc"))
    private val keyAlias = "jarvis.$namespace.v1"
    private val mutex = Mutex()

    override suspend fun read(): OwnerVoiceProfile? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withLock null
            val encoded = file.openRead().use { input ->
                val buffer = ByteArray(MAX_BYTES + 1)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                try { buffer.copyOf(count) } finally { buffer.fill(0) }
            }
            require(encoded.size in 30..MAX_BYTES) { "Voice profile is damaged. Delete it and enroll again." }
            try {
                DataInputStream(ByteArrayInputStream(encoded)).use { input ->
                    require(input.readInt() == VERSION) { "Unsupported voice profile version" }
                    val nonce = ByteArray(12).also(input::readFully)
                    val encrypted = input.readBytes()
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    val existingKey = keyStore().getKey(keyAlias, null) as? SecretKey
                        ?: error("Voice key unavailable. Delete the profile and enroll again.")
                    cipher.init(Cipher.DECRYPT_MODE, existingKey, GCMParameterSpec(128, nonce))
                    cipher.updateAAD(AAD)
                    val plaintext = cipher.doFinal(encrypted)
                    try { decode(plaintext) } finally { plaintext.fill(0) }
                }
            } finally { encoded.fill(0) }
        }
    }

    override suspend fun save(profile: OwnerVoiceProfile): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val plaintext = encode(profile)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
                cipher.updateAAD(AAD)
                val encrypted = cipher.doFinal(plaintext)
                currentCoroutineContext().ensureActive()
                val output = file.startWrite()
                try {
                    val data = DataOutputStream(output)
                    data.writeInt(VERSION)
                    data.write(cipher.iv)
                    data.write(encrypted)
                    data.flush()
                    currentCoroutineContext().ensureActive()
                    file.finishWrite(output)
                } catch (failure: Exception) {
                    file.failWrite(output)
                    throw failure
                }
            } finally { plaintext.fill(0) }
        }
    }

    override suspend fun delete(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            file.delete()
            check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
                "Could not delete the voice profile. Try again."
            }
            keyStore().deleteEntry(keyAlias)
        }
    }

    private fun encryptionKey(): SecretKey {
        (keyStore().getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun encode(profile: OwnerVoiceProfile): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeUTF(profile.modelId)
            out.writeLong(profile.createdAtMillis)
            val embeddings = profile.embeddings()
            out.writeInt(embeddings.size)
            out.writeInt(embeddings.first().size)
            embeddings.forEach { vector -> vector.forEach(out::writeFloat); vector.fill(0f) }
        }
        bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): OwnerVoiceProfile = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val model = input.readUTF()
        val created = input.readLong()
        require(input.readInt() == OwnerVoiceProfile.SAMPLE_COUNT)
        val dimensions = input.readInt()
        require(dimensions in 16..4096 && input.available() == OwnerVoiceProfile.SAMPLE_COUNT * dimensions * 4)
        val vectors = List(OwnerVoiceProfile.SAMPLE_COUNT) { FloatArray(dimensions) { input.readFloat() } }
        try { OwnerVoiceProfile(model, vectors, created) } finally { vectors.forEach { it.fill(0f) } }
    }

    companion object {
        private const val VERSION = 1
        private const val MAX_BYTES = 64 * 1024
        private val AAD = "dev.jarvis.owner-profile.v1".toByteArray(Charsets.UTF_8)
    }
}

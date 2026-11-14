package dev.jarvis.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.jarvis.core.*
import dev.jarvis.safety.*
import dev.jarvis.voice.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.first
import org.junit.Assume

@RunWith(AndroidJUnit4::class)
class VoiceRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun vector() = FloatArray(16) { if (it == 0) 1f else 0f }

    @Test fun encryptedProfileRoundTripsAndDeletes() = runBlocking {
        val store = EncryptedOwnerProfileStore(context, "test-runtime")
        store.delete()
        try {
            val profile = OwnerVoiceProfile("runtime-test", List(3) { vector() }, 42)
            store.save(profile)
            val loaded = store.read()!!
            assertEquals(profile.modelId, loaded.modelId)
            assertEquals(42L, loaded.createdAtMillis)
            assertTrue(VoiceMatchPolicy().matches(loaded, vector()))
            val persisted = File(context.noBackupFilesDir, "test-runtime.enc").readBytes()
            assertFalse(persisted.toString(Charsets.ISO_8859_1).contains("runtime-test"))
            store.delete()
            assertNull(store.read())
        } finally { store.delete() }
    }

    @Test fun ciphertextTamperingFailsClosed() = runBlocking {
        val store = EncryptedOwnerProfileStore(context, "test-runtime")
        store.delete()
        try {
            store.save(OwnerVoiceProfile("runtime-test", List(3) { vector() }, 42))
            val file = File(context.noBackupFilesDir, "test-runtime.enc")
            val encrypted = file.readBytes()
            encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
            file.writeBytes(encrypted)
            var rejected = false
            try { store.read() } catch (_: Exception) { rejected = true }
            assertTrue("Tampered ciphertext must never load", rejected)
        } finally { store.delete() }
    }

    @Test fun bundledWhisperTranscribesPublicEnglishAudioOffline() = runBlocking {
        val engine = OfflineSpeechRecognizer(context)
        try {
            val text = engine.transcribe(fixture("english-speech.wav"), "en")
            assertTrue("Expected recognizable English speech, got: $text",
                CommandRouter.normalize(text).startsWith("after early nightfall"))
        } finally { engine.release() }
    }

    @Test fun publicSpeechReachesTranscriptionWithoutAnySavedProfile() = runBlocking {
        val samples = fixture("english-speech.wav")
        val result = JarvisRuntime.get(context).transcribeCapturedAudio(samples, "en")
        assertTrue(result is VoiceGateResult.Accepted)
        assertTrue(CommandRouter.normalize((result as VoiceGateResult.Accepted).value).startsWith("after early nightfall"))
        assertTrue("Captured PCM must be erased", samples.all { it == 0f })
        assertEquals(VoiceGateResult.Denied(VoiceDenial.BAD_AUDIO), JarvisRuntime.get(context).transcribeCapturedAudio(FloatArray(16000), "en"))
    }

    @Test fun bundledBritishVoiceGeneratesSpeechOffline() = runBlocking {
        val engine = OfflineJarvisVoice(context)
        try {
            val audio = engine.synthesize("Your battery is at 100 percent.")
            assertEquals(22050, audio.sampleRate)
            assertTrue(audio.samples.size > audio.sampleRate)
            assertTrue(audio.samples.all(Float::isFinite))
            assertTrue(audio.samples.any { kotlin.math.abs(it) > 0.001f })
            audio.samples.fill(0f)
        } finally { engine.release() }
    }

    @Test fun voicePlaybackDrivesLiveEnergyAndStopClearsIt() = runBlocking {
        val output = OfflineSpeechOutput(context)
        try {
            output.speak("At your service. Your systems are ready. How may I assist you?")
            withTimeout(30_000) { output.pulse.first { it.level > 0.05f } }
            assertTrue(output.speaking.value)
            withTimeout(15_000) { output.speaking.first { !it } }
            withTimeout(1_000) { output.pulse.first { it == AudioPulse.SILENT } }
            assertNull(output.error.value)
            output.speak("At your service. Your systems are ready.")
            withTimeout(20_000) { output.pulse.first { it.level > 0.05f } }
            output.stop()
            assertFalse(output.speaking.value)
            delay(150)
            assertEquals(AudioPulse.SILENT, output.pulse.value)
        } finally { output.close() }
    }

    @Test fun noEnrollmentRequiredAndOffSwitchBlocksListening() = runBlocking {
        Assume.assumeTrue("UI fixture only runs on an emulator", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk", true))
        val prefs = JarvisPreferences(context)
        val original = prefs.settings.first()
        var activity: Activity? = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            val oldProfile = EncryptedOwnerProfileStore(context)
            Assume.assumeTrue("Never overwrite a real profile", oldProfile.read() == null)
            oldProfile.save(OwnerVoiceProfile("retired-owner-model", List(3) { vector() }, 1))
            prefs.setEnabled(true); prefs.setFloating(false)
            val owner = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            activity = owner
            var vm: VoiceSetupViewModel? = null
            instrumentation.runOnMainSync { vm = ViewModelProvider(owner)[VoiceSetupViewModel::class.java] }
            val model = checkNotNull(vm)
            withTimeout(10_000) { while (!model.state.value.settingsLoaded) delay(50) }
            withTimeout(10_000) { while (oldProfile.read() != null) delay(50) }
            instrumentation.runOnMainSync { model.typedCommand("battery status") }
            withTimeout(10_000) { while (model.state.value.reply?.contains("battery", true) != true || model.state.value.busy) delay(50) }
            assertFalse("Typed requests must not make unsolicited sounds", model.state.value.speaking)
            instrumentation.runOnMainSync { model.setEnabled(false) }
            withTimeout(10_000) { while (model.state.value.settings.enabled) delay(50) }
            instrumentation.runOnMainSync { model.listen() }
            assertEquals(SetupWork.IDLE, model.state.value.work)
            assertTrue(model.state.value.message!!.contains("off"))
        } finally {
            activity?.let { owner -> instrumentation.runOnMainSync { owner.finish() } }
            prefs.setEnabled(original.enabled); prefs.setFloating(original.floatingButton)
        }
    }

    private fun fixture(name: String): FloatArray {
        val path = if (name == "english-speech.wav") name else "speakers/$name"
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open(path).use { it.readBytes() }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        input.position(12)
        var validFormat = false
        while (input.remaining() >= 8) {
            val tag = ByteArray(4).also(input::get).toString(Charsets.US_ASCII)
            val size = input.int
            require(size >= 0 && size <= input.remaining())
            val start = input.position()
            if (tag == "fmt ") {
                require(size >= 16)
                val format = input.short.toInt()
                val channels = input.short.toInt()
                val rate = input.int
                input.int
                input.short
                val bits = input.short.toInt()
                require(format == 1 && channels == 1 && rate == 16_000 && bits == 16)
                validFormat = true
            } else if (tag == "data") {
                require(validFormat && size % 2 == 0)
                return FloatArray(size / 2) { input.short / 32768f }
            }
            input.position(start + size + size % 2)
        }
        error("Fixture has no PCM data")
    }
}

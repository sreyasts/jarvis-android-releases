package dev.jarvis.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in test. A development key is streamed privately, never included in either APK. */
@RunWith(AndroidJUnit4::class)
class GeminiLiveTest {
    @Test fun actualAndroidGeminiReplyAndWebAvailability() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secret = File(context.noBackupFilesDir, "qa-gemini-key")
        Assume.assumeTrue("Live Gemini testing requires a private development key file", secret.exists())
        val key = secret.readText().trim()
        secret.delete()
        val store = AiConfigStore(context, "test-live-gemini")
        val evidence = File(context.getExternalFilesDir(null), "gemini-live-result.txt")
        try {
            store.save(AiConfig(true, key, "gemini-3.6-flash", true))
            val config = store.read()
            val connector = GeminiConnector()
            val readUrl = "https://developer.android.com/reference/android/app/role/RoleManager"
            val check = InstrumentationRegistry.getArguments().getString("geminiCheck") ?: "answer"
            val result = when (check) {
                "answer" -> {
                    val answer = connector.answer(config, AiProtocol.system(true), "What is the capital of France? Give a short answer.")
                    assertTrue("Stable knowledge should be answered without web retrieval", answer is AiResult.Answer)
                    assertTrue((answer as AiResult.Answer).text.contains("Paris", true))
                    "Android Gemini structured answer: ${answer.text}"
                }
                "alarm" -> {
                    val alarm = connector.answer(config, AiProtocol.system(false), "Wake me at seven thirty in the morning, please.")
                    assertEquals(AiResult.Action(dev.jarvis.core.LocalCommand.Alarm(7, 30)), alarm)
                    "Android Gemini natural-language tool plan: alarm 07:30."
                }
                "malayalam" -> {
                    val bytes = InstrumentationRegistry.getInstrumentation().context.assets
                        .open("language/ml_in.wav").use { it.readBytes() }
                    val pcm = java.nio.ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    val audio = FloatArray(pcm.remaining() / 2) { pcm.short / 32768f }
                    val transcript = connector.transcribe(config, audio, "ml")
                    assertTrue("Expected Malayalam transcription: $transcript", transcript.contains("ആദരാഞ്ജലി"))
                    val answer = connector.answer(config, AiProtocol.system(false, JarvisLanguage.MALAYALAM),
                        "കേരളത്തിന്റെ തലസ്ഥാനം ഏതാണ്?")
                    assertTrue("Expected Malayalam answer: $answer", answer is AiResult.Answer &&
                        answer.text.contains("തിരുവനന്തപുര") &&
                        SpeechLanguagePolicy.inspect(answer.text) == SpeechLanguageResult.Supported(JarvisLanguage.MALAYALAM))
                    "Android Gemini transcribed Malayalam and answered in Malayalam."
                }
                "page" -> {
                    val page = connector.search(config, "Read $readUrl and explain ROLE_ASSISTANT in one sentence.")
                    assertTrue("Expected a successfully read source", page.sources.any { it.url == readUrl })
                    "Android Gemini URL Context successfully read $readUrl."
                }
                "search" -> {
                    val web = connector.search(config, "Search the web for Android digital assistant settings and read $readUrl. Explain ROLE_ASSISTANT with sources.")
                    assertTrue("Expected cited search results", web.sources.isNotEmpty())
                    "Android Gemini search received ${web.sources.size} source links."
                }
                else -> error("Unknown live check")
            }
            evidence.appendText("$result\n")
        } finally {
            secret.delete()
            File(context.noBackupFilesDir, "test-live-gemini.enc").delete()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("jarvis.test-live-gemini.v1") }
        }
    }
}

package dev.jarvis.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.jarvis.safety.VoiceDenial
import dev.jarvis.safety.VoiceGateResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BilingualRoutingTest {
    @Test fun offlineMalayalamTranscriptionAndUnsupportedSpeechUseRealRecordings() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runtime = JarvisRuntime.get(instrumentation.targetContext)
        suspend fun transcribe(name: String): VoiceGateResult<String> {
            val bytes = instrumentation.context.assets.open("language/${name}_in.wav").use { it.readBytes() }
            val pcm = java.nio.ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return runtime.transcribeCapturedAudio(FloatArray(pcm.remaining() / 2) { pcm.short / 32768f }, "")
        }
        val ml = transcribe("ml")
        assertTrue("Expected Malayalam transcript: $ml", ml is VoiceGateResult.Accepted && ml.value.contains("ആദരാഞ്ജലി"))
        assertEquals(VoiceGateResult.Denied(VoiceDenial.UNSUPPORTED_LANGUAGE), transcribe("hi"))
        assertEquals(VoiceGateResult.Denied(VoiceDenial.UNSUPPORTED_LANGUAGE), transcribe("ta"))
    }

    @Test fun rejectsUnsupportedScriptsEvenInsideOtherwiseSupportedRequests() {
        assertEquals(SpeechLanguageResult.Supported(JarvisLanguage.ENGLISH), SpeechLanguagePolicy.inspect("Set a timer for 5 minutes."))
        assertEquals(SpeechLanguageResult.Supported(JarvisLanguage.MALAYALAM), SpeechLanguagePolicy.inspect("Jarvis, ബാറ്ററി എത്രയാണ്?"))
        for (text in listOf("बैटरी कितनी है?", "Jarvis வணக்கம்", "مرحبا", "你好")) {
            assertEquals(text, SpeechLanguageResult.Unsupported, SpeechLanguagePolicy.inspect(text))
        }
        assertEquals(SpeechLanguageResult.Empty, SpeechLanguagePolicy.inspect("…"))
    }

    @Test fun quotaSkipsModelUntilResetAndStopsCallingCloudWhenAllAreBlocked() = runBlocking {
        val calls = mutableListOf<String>()
        val deadlines = mutableMapOf<String, Long>()
        val store = object : ModelQuotaStore {
            override fun blockedUntil(model: String) = deadlines[model]
            override fun block(model: String, untilMillis: Long) { deadlines[model] = untilMillis }
            override fun clear() = deadlines.clear()
        }
        var now = 1000L
        val router = GeminiModelRouter(store, listOf("flash-lite", "flash"), nowMillis = { now })
        suspend fun request(reject: Set<String>): GeminiRouted<String> = router.run("flash") { model ->
            calls += model
            if (model in reject) throw GeminiApiException(429, true, false, 2000, "quota")
            "success"
        }
        assertEquals("flash-lite", request(setOf("flash")).model)
        assertEquals(listOf("flash", "flash-lite"), calls)
        calls.clear()
        val exhausted = runCatching { request(setOf("flash-lite")) }.exceptionOrNull() as GeminiApiException
        assertTrue(exhausted.quotaExhausted)
        assertEquals(listOf("flash-lite"), calls)
        calls.clear()
        assertTrue(runCatching { request(emptySet()) }.isFailure)
        assertTrue(calls.isEmpty())
        now = 2000
        assertEquals("flash", request(emptySet()).model)
        assertEquals(listOf("flash"), calls)
    }

    @Test fun quotaSurvivesStoreRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = "qa-model-quota"
        val deadline = System.currentTimeMillis() + 60_000
        val store = PersistentModelQuotaStore(context)
        try {
            store.block(model, deadline)
            assertEquals(deadline, PersistentModelQuotaStore(context).blockedUntil(model))
        } finally { store.block(model, 0); store.blockedUntil(model) }
    }

    @Test fun dedicatedTranscriptionPreservesOriginalScriptAndReadsTheAudioTranscriptionPart() = runBlocking {
        val connector = GeminiConnector(AiTransport { url, _, body ->
            assertTrue(url.endsWith("/gemini-3.5-transcribe:generateContent"))
            val content = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
            val inline = content.getJSONObject(0).getJSONObject("inlineData")
            assertEquals("audio/wav", inline.getString("mimeType"))
            val wav = java.util.Base64.getDecoder().decode(inline.getString("data"))
            assertEquals("RIFF", wav.copyOfRange(0, 4).decodeToString())
            val config = body.getJSONObject("generationConfig").getJSONObject("audioTranscriptionConfig")
            assertEquals("VERBATIM", config.getString("mode"))
            assertFalse(config.has("languageCodes"))
            JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP")
                .put("content", JSONObject().put("parts", JSONArray().put(JSONObject()
                    .put("audioTranscription", JSONObject().put("text", "ബാറ്ററി എത്രയാണ്?")))))))
        })
        assertEquals("ബാറ്ററി എത്രയാണ്?", connector.transcribe(AiConfig(key = "test-key"), FloatArray(16_000), ""))
    }
}

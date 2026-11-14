package dev.jarvis.core

import org.junit.Assert.*
import org.junit.Test

class VoiceProfileTest {
    private fun vector(index: Int = 0) = FloatArray(16) { if (it == index) 1f else 0f }

    @Test fun `requires three consistent samples and rejects a different speaker`() {
        val enrollment = EnrollmentSession()
        enrollment.add(vector())
        assertThrows(IllegalArgumentException::class.java) { enrollment.add(vector(1)) }
        assertEquals(1, enrollment.completedSamples)
        assertThrows(IllegalStateException::class.java) { enrollment.complete("model", 1) }
        enrollment.add(vector())
        enrollment.add(vector())
        assertEquals(3, enrollment.complete("model", 1).embeddings().size)
        assertThrows(IllegalStateException::class.java) { enrollment.add(vector()) }
    }

    @Test fun `profile and enrollment do not retain caller-owned arrays`() {
        val source = vector()
        val enrollment = EnrollmentSession()
        repeat(3) { enrollment.add(source) }
        source.fill(0f)
        val profile = enrollment.complete("model", 1)
        enrollment.clear()
        profile.embeddings().forEach { it.fill(0f) }
        assertTrue(VoiceMatchPolicy().matches(profile, vector()))
        assertEquals(0, enrollment.completedSamples)
    }

    @Test fun `invalid embeddings and thresholds cannot authenticate`() {
        listOf(FloatArray(16), FloatArray(16) { Float.NaN }, FloatArray(16) { Float.POSITIVE_INFINITY }, floatArrayOf(1f))
            .forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { SpeakerVector.normalize(invalid) } }
        assertThrows(IllegalArgumentException::class.java) { VoiceMatchPolicy(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { VoiceMatchPolicy(0.1f) }
        assertThrows(IllegalArgumentException::class.java) { SpeakerVector.similarity(vector(), FloatArray(32) { 1f }) }
    }

    @Test fun `matching requires agreement with at least two templates`() {
        val profile = OwnerVoiceProfile("model", listOf(vector(), vector(), vector(1)), 1)
        assertTrue(VoiceMatchPolicy().matches(profile, vector()))
        assertFalse(VoiceMatchPolicy().matches(profile, vector(1)))
    }

    @Test fun `audio screening rejects silence clipping short input and malformed samples`() {
        assertEquals(AudioQuality.Issue.SILENT_OR_TOO_QUIET, AudioQuality.inspect(FloatArray(64_000)))
        assertEquals(AudioQuality.Issue.CLIPPED, AudioQuality.inspect(FloatArray(64_000) { 1f }))
        assertEquals(AudioQuality.Issue.TOO_SHORT, AudioQuality.inspect(FloatArray(100) { 0.1f }))
        assertEquals(AudioQuality.Issue.INVALID, AudioQuality.inspect(FloatArray(64_000) { Float.NaN }))
        assertEquals(AudioQuality.Issue.INVALID, AudioQuality.inspect(FloatArray(240_001)))
        assertNull(AudioQuality.inspect(FloatArray(64_000) { if (it % 2 == 0) 0.1f else -0.1f }))
    }
}

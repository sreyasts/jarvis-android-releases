package dev.jarvis.core

import org.junit.Assert.*
import org.junit.Test

class AudioPulseTest {
    @Test fun silenceDoesNotAnimateAndLouderAudioHasMoreEnergy() {
        assertEquals(AudioPulse.SILENT, AudioPulse.fromPcm(FloatArray(800)))
        assertEquals(AudioPulse.SILENT, AudioPulse.fromPcm(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY)))
        val quiet = AudioPulse.fromPcm(FloatArray(800) { 0.01f })
        val loud = AudioPulse.fromPcm(FloatArray(800) { 0.2f })
        assertTrue(quiet.level > 0f)
        assertTrue(loud.level > quiet.level)
    }

    @Test fun barsFollowActualAudioWindowsAndStayBounded() {
        val pulse = AudioPulse.fromPcm(FloatArray(800) { if (it < 400) 0f else 2f })
        assertEquals(32, pulse.bars.size)
        assertTrue(pulse.bars.take(16).all { it == 0f })
        assertTrue(pulse.bars.drop(16).all { it > 0f && it <= 1f })
        assertTrue(pulse.level in 0f..1f)
    }
}

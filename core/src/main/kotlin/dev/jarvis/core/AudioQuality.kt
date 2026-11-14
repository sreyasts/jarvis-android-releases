package dev.jarvis.core

import kotlin.math.abs
import kotlin.math.sqrt

/** Conservative energy screening; this is not a neural VAD or anti-spoof detector. */
object AudioQuality {
    const val SAMPLE_RATE = 16_000
    const val FRAME_SAMPLES = 320
    private const val MIN_ACTIVE_FRAMES = 75 // 1.5 seconds of non-silent audio
    private const val RMS_FLOOR = 0.008

    enum class Issue { TOO_SHORT, SILENT_OR_TOO_QUIET, CLIPPED, INVALID }

    fun inspect(samples: FloatArray): Issue? {
        if (samples.isEmpty() || samples.size > SAMPLE_RATE * 15 ||
            samples.any { !it.isFinite() || abs(it) > 1f }) return Issue.INVALID
        if (samples.size < SAMPLE_RATE * 3 / 2) return Issue.TOO_SHORT
        if (samples.count { abs(it) >= 0.99f } > samples.size / 100) return Issue.CLIPPED
        if (activeFrameCount(samples) < MIN_ACTIVE_FRAMES) return Issue.SILENT_OR_TOO_QUIET
        return null
    }

    fun activeFrameCount(samples: FloatArray): Int =
        (samples.indices step FRAME_SAMPLES).count { start ->
            val end = minOf(start + FRAME_SAMPLES, samples.size)
            sqrt((start until end).sumOf { samples[it].toDouble() * samples[it] } / (end - start)) >= RMS_FLOOR
        }

    fun message(issue: Issue): String = when (issue) {
        Issue.TOO_SHORT -> "Speak for at least two seconds, then try again."
        Issue.SILENT_OR_TOO_QUIET -> "Not enough clear speech. Move closer and speak throughout the recording."
        Issue.CLIPPED -> "The recording is too loud. Move the phone a little farther away."
        Issue.INVALID -> "The microphone did not provide usable audio. Try recording again."
    }
}

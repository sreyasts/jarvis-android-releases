package dev.jarvis.core

import kotlin.math.log10
import kotlin.math.sqrt

/** Display-only energy windows. Retains no PCM or voice recording. */
data class AudioPulse(val level: Float = 0f, val bars: List<Float> = emptyList()) {
    companion object {
        val SILENT = AudioPulse()
        fun fromPcm(samples: FloatArray, offset: Int = 0, count: Int = samples.size - offset): AudioPulse {
            require(offset >= 0 && count >= 0 && offset <= samples.size - count)
            if (count == 0) return SILENT
            fun energy(start: Int, end: Int): Float {
                if (end <= start) return 0f
                var sum = 0.0
                for (index in start until end) {
                    val value = samples[index].takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f
                    sum += value.toDouble() * value
                }
                val rms = sqrt(sum / (end - start))
                return if (rms <= 0.0016) 0f else ((20 * log10(rms) + 56) / 48).toFloat().coerceIn(0f, 1f)
            }
            val level = energy(offset, offset + count)
            if (level == 0f) return SILENT
            return AudioPulse(level, List(32) { index ->
                energy(offset + index * count / 32, offset + (index + 1) * count / 32)
            })
        }
    }
}

package dev.jarvis.safety

import dev.jarvis.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class VoiceDenial { NO_PROFILE, MODEL_CHANGED, BAD_AUDIO, DIFFERENT_SPEAKER, VERIFICATION_UNAVAILABLE, UNSUPPORTED_LANGUAGE }

sealed interface VoiceGateResult<out T> {
    data class Accepted<T>(val value: T) : VoiceGateResult<T>
    data class Denied(val reason: VoiceDenial) : VoiceGateResult<Nothing>
}

/**
 * Owns and erases the supplied PCM. No reusable "owner authenticated" state.
 * ASR, replies and dispatch belong inside [process]'s verified callback only.
 */
class OwnerVoiceGate(
    private val store: OwnerProfileStore,
    private val embedder: SpeakerEmbedder,
    private val policy: VoiceMatchPolicy = VoiceMatchPolicy(),
) {
    suspend fun <T> process(
        audio: FloatArray,
        source: ActivationSource,
        onVerified: suspend (FloatArray, ActivationSource) -> T,
    ): VoiceGateResult<T> {
        val snapshot = audio.copyOf()
        try {
            var profile: OwnerVoiceProfile? = null
            val denial = try {
                profile = store.read()
                when {
                    profile == null -> VoiceDenial.NO_PROFILE
                    profile.modelId != embedder.modelId -> VoiceDenial.MODEL_CHANGED
                    else -> verify(snapshot, profile)
                }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { VoiceDenial.VERIFICATION_UNAVAILABLE }
            catch (_: LinkageError) { VoiceDenial.VERIFICATION_UNAVAILABLE }
            currentCoroutineContext().ensureActive()
            if (denial != null) return VoiceGateResult.Denied(denial)
            // Re-read immediately before downstream work; deletion/re-enrollment revokes this attempt.
            val current = try { store.read() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return VoiceGateResult.Denied(VoiceDenial.VERIFICATION_UNAVAILABLE) }
            if (current == null) return VoiceGateResult.Denied(VoiceDenial.NO_PROFILE)
            if (!sameProfile(current, profile)) return VoiceGateResult.Denied(VoiceDenial.MODEL_CHANGED)
            currentCoroutineContext().ensureActive()
            return VoiceGateResult.Accepted(onVerified(snapshot, source))
        } finally {
            snapshot.fill(0f)
            audio.fill(0f)
        }
    }

    private suspend fun verify(audio: FloatArray, profile: OwnerVoiceProfile): VoiceDenial? {
        if (AudioQuality.inspect(audio) != null) return VoiceDenial.BAD_AUDIO
        // Check overlapping windows so a matching wake word cannot authorize someone else's command.
        val windowSize = minOf(audio.size, AudioQuality.SAMPLE_RATE * 5 / 2)
        val stride = AudioQuality.SAMPLE_RATE * 5 / 4
        val starts = (0..audio.size - windowSize step stride).toMutableList()
        if (starts.last() != audio.size - windowSize) starts += audio.size - windowSize
        var checked = 0
        for (start in starts) {
            val window = audio.copyOfRange(start, start + windowSize)
            try {
                if (AudioQuality.activeFrameCount(window) < 75) continue
                val embedding = embedder.embed(window)
                try {
                    if (!policy.matches(profile, embedding)) return VoiceDenial.DIFFERENT_SPEAKER
                } finally { embedding.fill(0f) }
                checked++
            } finally { window.fill(0f) }
        }
        if (checked == 0) return VoiceDenial.BAD_AUDIO
        return null
    }

    private fun sameProfile(a: OwnerVoiceProfile, b: OwnerVoiceProfile?): Boolean =
        b != null && a.modelId == b.modelId && a.createdAtMillis == b.createdAtMillis &&
            a.embeddings().zip(b.embeddings()).all { (x, y) -> x.contentEquals(y) }
}

package dev.jarvis.core

import kotlin.math.sqrt

object SpeakerVector {
    fun normalize(vector: FloatArray): FloatArray {
        require(vector.size in 16..4096 && vector.all(Float::isFinite)) { "Invalid speaker embedding" }
        val norm = sqrt(vector.sumOf { it.toDouble() * it })
        require(norm.isFinite() && norm > 1e-8) { "Empty speaker embedding" }
        return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }

    fun similarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Speaker model dimensions changed" }
        val x = normalize(a)
        val y = normalize(b)
        return x.indices.sumOf { x[it].toDouble() * y[it] }.toFloat().coerceIn(-1f, 1f)
    }
}

class OwnerVoiceProfile(
    val modelId: String,
    embeddings: List<FloatArray>,
    val createdAtMillis: Long,
) {
    private val vectors = embeddings.map(SpeakerVector::normalize)
    init {
        require(modelId.isNotBlank() && modelId.length <= 160)
        require(vectors.size == SAMPLE_COUNT && vectors.map { it.size }.distinct().size == 1)
        require(createdAtMillis > 0)
    }
    fun embeddings(): List<FloatArray> = vectors.map { it.copyOf() }
    companion object { const val SAMPLE_COUNT = 3 }
}

interface OwnerProfileStore {
    suspend fun read(): OwnerVoiceProfile?
    suspend fun save(profile: OwnerVoiceProfile)
    suspend fun delete()
}

interface SpeakerEmbedder {
    val modelId: String
    suspend fun embed(samples: FloatArray): FloatArray
}

/** Thresholds are provisional policy, not a measured false-accept guarantee. */
class VoiceMatchPolicy(
    val verificationThreshold: Float = 0.70f,
    val enrollmentThreshold: Float = 0.65f,
) {
    init {
        require(verificationThreshold.isFinite() && verificationThreshold in 0.5f..0.95f)
        require(enrollmentThreshold.isFinite() && enrollmentThreshold in 0.5f..0.95f)
    }

    fun matches(profile: OwnerVoiceProfile, candidate: FloatArray): Boolean =
        profile.embeddings().count {
            SpeakerVector.similarity(it, candidate) >= verificationThreshold
        } >= 2
}

class EnrollmentSession(private val policy: VoiceMatchPolicy = VoiceMatchPolicy()) {
    private val samples = mutableListOf<FloatArray>()
    val completedSamples: Int get() = samples.size

    fun add(embedding: FloatArray) {
        check(samples.size < OwnerVoiceProfile.SAMPLE_COUNT) { "Enrollment is complete" }
        val normalized = SpeakerVector.normalize(embedding)
        require(samples.all {
            SpeakerVector.similarity(it, normalized) >= policy.enrollmentThreshold
        }) { "That sample does not match the earlier samples. Record it again in a quiet place." }
        samples += normalized
    }

    fun complete(modelId: String, nowMillis: Long): OwnerVoiceProfile {
        check(completedSamples == OwnerVoiceProfile.SAMPLE_COUNT) { "Record all three samples first" }
        return OwnerVoiceProfile(modelId, samples, nowMillis)
    }

    fun clear() { samples.forEach { it.fill(0f) }; samples.clear() }
}

package dev.jarvis.safety

import dev.jarvis.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OwnerVoiceGateTest {
    private fun vector(index: Int = 0) = FloatArray(16) { if (it == index) 1f else 0f }
    private fun audio() = FloatArray(64_000) { if (it % 2 == 0) 0.1f else -0.1f }
    private fun profile(created: Long = 1) = OwnerVoiceProfile("model", List(3) { vector() }, created)
    private class Store(var profile: OwnerVoiceProfile?) : OwnerProfileStore {
        override suspend fun read() = profile
        override suspend fun save(profile: OwnerVoiceProfile) { this.profile = profile }
        override suspend fun delete() { profile = null }
    }
    private fun embedder(block: suspend (FloatArray) -> FloatArray = { vector() }) = object : SpeakerEmbedder {
        override val modelId = "model"
        override suspend fun embed(samples: FloatArray) = block(samples)
    }

    @Test fun `all activation methods require owner match before processing`() = runBlocking {
        for (source in ActivationSource.entries) {
            var invoked = false
            val clip = audio()
            val gate = OwnerVoiceGate(Store(profile()), embedder { vector(1) })
            val result = gate.process(clip, source) { _, _ -> invoked = true }
            assertEquals(VoiceGateResult.Denied(VoiceDenial.DIFFERENT_SPEAKER), result)
            assertFalse(invoked)
            assertTrue(clip.all { it == 0f })
        }
    }

    @Test fun `matching owner receives exact checked audio and correct source`() = runBlocking {
        val clip = audio()
        var checkedAudio: FloatArray? = null
        val result = OwnerVoiceGate(Store(profile()), embedder()).process(clip, ActivationSource.OVERLAY) { checked, source ->
            checkedAudio = checked
            assertTrue(checked.contentEquals(clip))
            assertNotSame(checked, clip)
            assertEquals(ActivationSource.OVERLAY, source)
            "accepted"
        }
        assertEquals(VoiceGateResult.Accepted("accepted"), result)
        assertTrue(clip.all { it == 0f })
        assertTrue(checkedAudio!!.all { it == 0f })
    }

    @Test fun `missing profile damaged store changed model and engine failure all block`() = runBlocking {
        val brokenStore = object : OwnerProfileStore {
            override suspend fun read(): OwnerVoiceProfile? = error("damaged ciphertext")
            override suspend fun save(profile: OwnerVoiceProfile) = Unit
            override suspend fun delete() = Unit
        }
        val changedProfile = OwnerVoiceProfile("old-model", List(3) { vector() }, 1)
        val cases = listOf(
            OwnerVoiceGate(Store(null), embedder()) to VoiceDenial.NO_PROFILE,
            OwnerVoiceGate(brokenStore, embedder()) to VoiceDenial.VERIFICATION_UNAVAILABLE,
            OwnerVoiceGate(Store(changedProfile), embedder()) to VoiceDenial.MODEL_CHANGED,
            OwnerVoiceGate(Store(profile()), embedder { error("inference failed") }) to VoiceDenial.VERIFICATION_UNAVAILABLE,
            OwnerVoiceGate(Store(profile()), embedder { throw UnsatisfiedLinkError("missing native lib") }) to VoiceDenial.VERIFICATION_UNAVAILABLE,
            OwnerVoiceGate(Store(profile()), embedder { FloatArray(16) { Float.NaN } }) to VoiceDenial.VERIFICATION_UNAVAILABLE,
        )
        for ((gate, reason) in cases) {
            assertEquals(VoiceGateResult.Denied(reason), gate.process(audio(), ActivationSource.IN_APP) { _, _ -> fail("Must not process") })
        }
    }

    @Test fun `silence is rejected before invoking the speaker engine`() = runBlocking {
        val gate = OwnerVoiceGate(Store(profile()), embedder { error("Must not embed silence") })
        assertEquals(VoiceGateResult.Denied(VoiceDenial.BAD_AUDIO),
            gate.process(FloatArray(64_000), ActivationSource.WAKE_WORD) { _, _ -> fail("Must not process") })
    }

    @Test fun `matching beginning does not authorize mismatched later windows`() = runBlocking {
        var calls = 0
        val gate = OwnerVoiceGate(Store(profile()), embedder { if (calls++ == 0) vector() else vector(1) })
        assertEquals(VoiceGateResult.Denied(VoiceDenial.DIFFERENT_SPEAKER),
            gate.process(audio(), ActivationSource.WAKE_WORD) { _, _ -> fail("Must not process") })
        assertEquals(2, calls)
    }

    @Test fun `profile deletion or replacement during inference revokes attempt`() = runBlocking {
        for (replacement in listOf(null, profile(2))) {
            val store = Store(profile())
            val gate = OwnerVoiceGate(store, embedder { store.profile = replacement; vector() })
            val result = gate.process(audio(), ActivationSource.IN_APP) { _, _ -> fail("Must not process") }
            assertEquals(VoiceGateResult.Denied(if (replacement == null) VoiceDenial.NO_PROFILE else VoiceDenial.MODEL_CHANGED), result)
        }
    }

    @Test fun `cancellation propagates and erases audio without processing`() = runBlocking {
        val clip = audio()
        val started = CompletableDeferred<Unit>()
        val gate = OwnerVoiceGate(Store(profile()), embedder { started.complete(Unit); awaitCancellation() })
        var invoked = false
        val operation = launch { gate.process(clip, ActivationSource.IN_APP) { _, _ -> invoked = true } }
        started.await()
        operation.cancelAndJoin()
        assertFalse(invoked)
        assertTrue(clip.all { it == 0f })
    }

    @Test fun `downstream failures propagate and audio is still erased`() = runBlocking {
        val clip = audio()
        try {
            OwnerVoiceGate(Store(profile()), embedder()).process(clip, ActivationSource.IN_APP) { _, _ -> error("downstream") }
            fail("Expected failure")
        } catch (failure: IllegalStateException) { assertEquals("downstream", failure.message) }
        assertTrue(clip.all { it == 0f })
    }

    @Test fun `one accepted utterance does not authorize a later speaker`() = runBlocking {
        var isOwner = true
        val gate = OwnerVoiceGate(Store(profile()), embedder { vector(if (isOwner) 0 else 1) })
        assertTrue(gate.process(audio(), ActivationSource.IN_APP) { _, _ -> Unit } is VoiceGateResult.Accepted)
        isOwner = false
        assertEquals(VoiceGateResult.Denied(VoiceDenial.DIFFERENT_SPEAKER),
            gate.process(audio(), ActivationSource.IN_APP) { _, _ -> fail("Must not process") })
    }

    @Test fun `caller cannot replace audio while verification is running`() = runBlocking {
        val clip = audio()
        val expected = clip.copyOf()
        val gate = OwnerVoiceGate(Store(profile()), embedder { clip.fill(-0.2f); vector() })
        assertTrue(gate.process(clip, ActivationSource.IN_APP) { checked, _ ->
            assertTrue(checked.contentEquals(expected))
        } is VoiceGateResult.Accepted)
        assertTrue(clip.all { it == 0f })
    }

    @Test fun `cancellation during final profile read cannot reach callback`() = runBlocking {
        var reads = 0
        var invoked = false
        val store = object : OwnerProfileStore {
            override suspend fun read(): OwnerVoiceProfile {
                if (++reads == 2) currentCoroutineContext().cancel()
                return profile()
            }
            override suspend fun save(profile: OwnerVoiceProfile) = Unit
            override suspend fun delete() = Unit
        }
        val job = launch {
            OwnerVoiceGate(store, embedder()).process(audio(), ActivationSource.IN_APP) { _, _ -> invoked = true }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(invoked)
    }
}

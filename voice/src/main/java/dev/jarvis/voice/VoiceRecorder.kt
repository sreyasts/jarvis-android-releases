package dev.jarvis.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import dev.jarvis.core.AudioQuality
import dev.jarvis.core.AudioPulse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class VoiceRecorder(context: Context) {
    private val appContext = context.applicationContext
    private companion object { val microphone = Mutex() }

    /** Foreground activation only; no background microphone service is started. */
    suspend fun record(seconds: Int = 4, endOnSilence: Boolean = false,
        onRecording: () -> Unit = {}, onPulse: (AudioPulse) -> Unit = {}, onProgress: (Float) -> Unit): FloatArray {
        var captured: FloatArray? = null
        try { return withContext(Dispatchers.IO) { microphone.withLock {
            require(seconds in 3..10)
            check(appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Allow microphone access in Android settings, then try again."
            }
            val minBuffer = AudioRecord.getMinBufferSize(
                AudioQuality.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minBuffer > 0) { "This microphone cannot record at 16 kHz." }
            val recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(AudioQuality.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minBuffer * 2, 6400))
                .build()
            val audio = FloatArray(seconds * AudioQuality.SAMPLE_RATE)
            val buffer = ShortArray(320)
            var completed = false
            try {
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed." }
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "The microphone is busy. Try again." }
                onRecording()
                val deadline = SystemClock.elapsedRealtime() + (seconds + 4) * 1000L
                var offset = 0
                var activeSamples = 0
                var quietSamples = 0
                var lastProgress = 0
                var lastPulse = 0
                while (offset < audio.size) {
                    currentCoroutineContext().ensureActive()
                    check(SystemClock.elapsedRealtime() < deadline) { "Microphone timed out. Try recording again." }
                    val count = recorder.read(buffer, 0, minOf(buffer.size, audio.size - offset), AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0) { "Microphone disconnected or unavailable. Try again." }
                    if (count == 0) { delay(10); continue }
                    repeat(count) { audio[offset + it] = buffer[it] / 32768f }
                    offset += count
                    if (offset - lastPulse >= 800) {
                        onPulse(AudioPulse.fromPcm(audio, offset - 800, 800))
                        lastPulse = offset
                    }
                    if (offset - lastProgress >= 1600 || offset == audio.size) {
                        onProgress(offset.toFloat() / audio.size)
                        lastProgress = offset
                    }
                    if (endOnSilence) {
                        val rms = kotlin.math.sqrt((0 until count).sumOf { (buffer[it] / 32768.0).let { value -> value * value } } / count)
                        if (rms >= 0.008) { activeSamples += count; quietSamples = 0 } else quietSamples += count
                        if (activeSamples >= 4_000 && quietSamples >= 12_800) break
                        if (activeSamples == 0 && offset >= 48_000) break
                    }
                }
                completed = true
                val result = if (offset < audio.size) audio.copyOf(offset).also { audio.fill(0f) } else audio
                captured = result
                result
            } finally {
                buffer.fill(0)
                if (!completed) audio.fill(0f)
                try {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                } finally { recorder.release(); onPulse(AudioPulse.SILENT) }
            }
        } } } catch (cancelled: CancellationException) {
            // withContext may discard a completed result when its caller is cancelled.
            captured?.fill(0f)
            throw cancelled
        }
    }
}

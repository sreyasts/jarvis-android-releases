package dev.jarvis.app

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ModelDownloadTest {
    @Test fun realAndroidDownloadPausesResumesAndVerifiesTheWholeModel() = runBlocking {
        Assume.assumeTrue("Downloads only run on the dedicated emulator", Build.MODEL.contains("sdk", true))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = ModelDownload.modelFile(context)
        val backup = File(model.parentFile, "qa-model-backup.gguf")
        check(!backup.exists())
        val hadModel = model.exists()
        if (hadModel) check(model.renameTo(backup))
        val downloader = ModelDownload(context)
        try {
            assertFalse(downloader.state.value.ready)
            downloader.start()
            withTimeout(120_000) {
                while (downloader.state.value.bytes < 2_000_000) {
                    check(downloader.state.value.error == null) { downloader.state.value.error!! }; delay(100)
                }
            }
            downloader.pause()
            withTimeout(45_000) { while (downloader.state.value.downloading) delay(100) }
            val partial = File(model.parentFile, "model.download")
            assertTrue(partial.length() in 2_000_000 until ModelDownload.SIZE)
            delay(200) // The cancelled IO job must finish before resume.
            downloader.start()
            withTimeout(15 * 60_000L) {
                while (!downloader.state.value.ready) {
                    check(downloader.state.value.error == null) { downloader.state.value.error!! }; delay(500)
                }
            }
            assertEquals(ModelDownload.SIZE, model.length())
            assertTrue(ModelDownload.ready(context))
        } finally {
            downloader.close()
            if (!model.exists() && hadModel) backup.renameTo(model)
            else backup.delete()
        }
    }
}

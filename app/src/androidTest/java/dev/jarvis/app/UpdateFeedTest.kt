package dev.jarvis.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jarvis.core.AppUpdatePolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateFeedTest {
    private val repository = "sreyasts/jarvis-android-releases"
    private fun manifest(version: String = "3", url: String = "https://github.com/$repository/releases/download/v0.3.0/Jarvis.apk") =
        """{"schema":1,"packageName":"dev.jarvis.app","versionCode":$version,"versionName":"0.3.0","minSdk":28,"apkUrl":"$url","sha256":"${"a".repeat(64)}","sizeBytes":1234}"""

    @Test fun validFeedShowsOnlyBeforeThatVersionIsInstalled() {
        val release = UpdateFeed.parse(manifest(), repository, "dev.jarvis.app")
        assertNotNull(AppUpdatePolicy.available(release, 2, 35))
        assertNull(AppUpdatePolicy.available(release, 3, 35))
    }

    @Test fun malformedWrongPackageAndUntrustedDownloadsAreRejected() {
        val invalid = listOf(manifest("3.5"), manifest("\"3\""), manifest().replace("dev.jarvis.app", "another.app"),
            manifest(url = "http://github.com/$repository/releases/download/v0.3.0/Jarvis.apk"),
            manifest(url = "https://evil.example/Jarvis.apk"), manifest().replace("\"schema\":1", "\"schema\":2"), "x".repeat(33_000))
        invalid.forEach { text ->
            assertTrue("Must reject an invalid release feed", runCatching { UpdateFeed.parse(text, repository, "dev.jarvis.app") }.isFailure)
        }
    }
}

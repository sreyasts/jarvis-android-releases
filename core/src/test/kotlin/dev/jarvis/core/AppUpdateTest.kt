package dev.jarvis.core

import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    private val repo = "sreyasts/jarvis-android-releases"
    private val release = AppRelease(3, "0.3.0", 28,
        "https://github.com/$repo/releases/download/v0.3.0/Jarvis.apk", "a".repeat(64), 1234)

    @Test fun onlyNewerCompatibleVersionsShowTheUpdateButton() {
        assertEquals(release, AppUpdatePolicy.available(release, 2, 35))
        assertNull(AppUpdatePolicy.available(release, 3, 35))
        assertNull(AppUpdatePolicy.available(release, 4, 35))
        assertNull(AppUpdatePolicy.available(release.copy(minSdk = 36), 2, 35))
        assertNull(AppUpdatePolicy.available(null, 2, 35))
    }

    @Test fun cachedUpdateDisappearsAfterTheInstalledVersionCatchesUp() {
        assertNotNull(AppUpdatePolicy.available(release, 2, 36))
        assertNull(AppUpdatePolicy.available(release, 3, 36))
    }

    @Test fun downloadMustBeOurExactHttpsReleaseAsset() {
        assertTrue(AppUpdatePolicy.trustedDownload(release.apkUrl, repo))
        listOf(
            release.apkUrl.replace("https:", "http:"),
            release.apkUrl.replace("github.com", "github.com.evil.example"),
            release.apkUrl.replace("sreyasts", "another-owner"),
            release.apkUrl + "?redirect=https://evil.example",
            release.apkUrl.replace("Jarvis.apk", "Another.apk"),
            release.apkUrl.replace("/v0.3.0/", "/../"),
        ).forEach { assertFalse(it, AppUpdatePolicy.trustedDownload(it, repo)) }
    }
}

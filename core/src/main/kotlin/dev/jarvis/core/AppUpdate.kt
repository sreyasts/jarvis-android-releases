package dev.jarvis.core

import java.net.URI

data class AppRelease(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    init {
        require(versionCode in 1..Int.MAX_VALUE.toLong())
        require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9._-]+)?").matches(versionName) && versionName.length <= 48)
        require(minSdk in 28..100)
        require(Regex("[a-f0-9]{64}").matches(sha256))
        require(sizeBytes in 1..2_000_000_000L)
    }
}

object AppUpdatePolicy {
    fun available(release: AppRelease?, installedVersion: Long, sdk: Int): AppRelease? =
        release?.takeIf { it.versionCode > installedVersion && it.minSdk <= sdk }

    fun trustedDownload(url: String, repository: String): Boolean = runCatching {
        val uri = URI(url)
        val parts = uri.rawPath.orEmpty().split('/').filter(String::isNotEmpty)
        val repo = repository.split('/')
        uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            repo.size == 2 && parts.size == 6 && parts.take(2) == repo &&
            parts[2] == "releases" && parts[3] == "download" &&
            Regex("v[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9._-]+)?").matches(parts[4]) &&
            parts[5] == "Jarvis.apk"
    }.getOrDefault(false)
}

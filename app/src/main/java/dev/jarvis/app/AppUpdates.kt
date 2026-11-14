package dev.jarvis.app

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.jarvis.core.AppRelease
import dev.jarvis.core.AppUpdatePolicy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

object UpdateFeed {
    private const val MAX_BYTES = 32_768
    private val hosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

    fun parse(text: String, repository: String, packageName: String): AppRelease {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val json = JSONObject(text)
        fun integer(key: String): Long {
            val value = json.get(key)
            require(value is Number && Regex("[0-9]+").matches(value.toString()))
            return value.toString().toLong()
        }
        fun string(key: String): String = (json.get(key) as? String) ?: error("Invalid update field")
        require(integer("schema") == 1L && string("packageName") == packageName)
        val minSdk = integer("minSdk")
        require(minSdk in 28..100)
        return AppRelease(integer("versionCode"), string("versionName"), minSdk.toInt(),
            string("apkUrl"), string("sha256"), integer("sizeBytes")).also {
            require(AppUpdatePolicy.trustedDownload(it.apkUrl, repository))
        }
    }

    /** Only release metadata crosses the network. No voice, profile or transcript is read. */
    fun fetch(repository: String): String {
        var address = URI("https://github.com/$repository/releases/latest/download/jarvis-update.json")
        repeat(5) {
            require(address.scheme == "https" && address.host in hosts && address.userInfo == null &&
                (address.port == -1 || address.port == 443))
            val connection = address.toURL().openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = 6_000
                connection.readTimeout = 6_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.setRequestProperty("User-Agent", "Jarvis-Update-Check/1")
                when (connection.responseCode) {
                    200 -> {
                        if (connection.contentLengthLong > MAX_BYTES) throw IOException("Update feed too large")
                        return connection.inputStream.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(2048)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > MAX_BYTES) throw IOException("Update feed too large")
                                output.write(buffer, 0, count)
                            }
                            output.toString(Charsets.UTF_8.name())
                        }
                    }
                    301, 302, 303, 307, 308 -> {
                        address = address.resolve(connection.getHeaderField("Location") ?: throw IOException("Missing redirect"))
                    }
                    else -> throw IOException("Update feed unavailable")
                }
            } finally { connection.disconnect() }
        }
        throw IOException("Too many update redirects")
    }
}

data class AppUpdateState(
    val available: AppRelease? = null,
    val checking: Boolean = false,
    val feedback: String? = null,
    val browserError: String? = null,
)

class AppUpdatesViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("app_updates", Application.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var lastAttempt = 0L
    private var cacheLoaded = false

    @Suppress("DEPRECATION")
    private fun installedVersion(): Long = getApplication<Application>().packageManager
        .getPackageInfo(getApplication<Application>().packageName, 0).longVersionCode

    private fun offer(release: AppRelease?): AppRelease? =
        AppUpdatePolicy.available(release, installedVersion(), Build.VERSION.SDK_INT)

    fun check(force: Boolean = false) {
        // Never retain an Update button solely because an old cached flag says so.
        mutableState.update { it.copy(available = offer(it.available)) }
        if (operation?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (!force && lastAttempt != 0L && now - lastAttempt < 60_000) return
        lastAttempt = now
        operation = viewModelScope.launch {
            mutableState.update { it.copy(checking = true, feedback = null) }
            try {
                if (!cacheLoaded) {
                    val cached = withContext(Dispatchers.IO) {
                        runCatching { preferences.getString("latest", null)?.let {
                            UpdateFeed.parse(it, BuildConfig.UPDATE_REPOSITORY, getApplication<Application>().packageName)
                        } }.getOrNull()
                    }
                    cacheLoaded = true
                    mutableState.update { it.copy(available = offer(cached)) }
                }
                val text = withContext(Dispatchers.IO) { UpdateFeed.fetch(BuildConfig.UPDATE_REPOSITORY) }
                val release = withContext(Dispatchers.Default) {
                    UpdateFeed.parse(text, BuildConfig.UPDATE_REPOSITORY, getApplication<Application>().packageName)
                }
                preferences.edit().putString("latest", text).apply()
                mutableState.update { it.copy(available = offer(release), feedback = if (!force) null else when {
                    release.versionCode <= installedVersion() -> "You're up to date."
                    release.minSdk > Build.VERSION.SDK_INT -> "The newer version needs a newer Android version."
                    else -> "A new version is ready to download."
                }) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.update { it.copy(feedback = if (force) "Couldn't check for updates. Try again when you're online." else null) }
            } finally { mutableState.update { it.copy(checking = false) } }
        }
    }

    fun downloadUrl(): String? {
        mutableState.update { it.copy(browserError = null) }
        return offer(state.value.available)?.apkUrl
    }
    fun downloadFailed() {
        mutableState.update { it.copy(browserError = "Android couldn't open a browser. Enable one in Settings, then try again.") }
    }
}

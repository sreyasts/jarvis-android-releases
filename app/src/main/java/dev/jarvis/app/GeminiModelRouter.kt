package dev.jarvis.app

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException

class GeminiApiException(
    val status: Int,
    val quotaExhausted: Boolean,
    val modelUnavailable: Boolean,
    val retryAtMillis: Long?,
    message: String,
) : IOException(message)

interface ModelQuotaStore {
    fun blockedUntil(model: String): Long?
    fun block(model: String, untilMillis: Long)
    fun clear()
}

class PersistentModelQuotaStore(context: Context) : ModelQuotaStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences("gemini_model_quota", Context.MODE_PRIVATE)
    @Synchronized override fun blockedUntil(model: String): Long? {
        val key = key(model)
        if (!preferences.contains(key)) return null
        val until = preferences.getLong(key, Long.MAX_VALUE)
        if (until != Long.MAX_VALUE && until <= System.currentTimeMillis()) {
            preferences.edit().remove(key).apply()
            return null
        }
        return until
    }
    @Synchronized override fun block(model: String, untilMillis: Long) {
        preferences.edit().putLong(key(model), untilMillis).apply()
    }
    @Synchronized override fun clear() { preferences.edit().clear().apply() }
    private fun key(model: String) = "model_$model"
}

data class GeminiRouted<out T>(val model: String, val value: T)

/** Remembers model-specific limits until the provider's reset. */
class GeminiModelRouter(
    private val quotaStore: ModelQuotaStore,
    private val fallbacks: List<String> = DEFAULT_FALLBACKS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun <T> run(primary: String, call: suspend (String) -> T): GeminiRouted<T> {
        require(primary.matches(Regex("[A-Za-z0-9._-]{1,80}")))
        val models = (listOf(primary) + fallbacks).distinct()
        var lastQuota: GeminiApiException? = null
        var lastUnavailable: GeminiApiException? = null
        for (model in models) {
            val blocked = quotaStore.blockedUntil(model)
            if (blocked != null && blocked > nowMillis()) continue
            try {
                return GeminiRouted(model, call(model))
            } catch (failure: GeminiApiException) {
                when {
                    failure.quotaExhausted -> {
                        quotaStore.block(model, failure.retryAtMillis?.coerceAtLeast(nowMillis() + 1) ?: nextQuotaDay())
                        lastQuota = failure
                    }
                    failure.modelUnavailable || failure.status == 503 -> {
                        val defaultReset = if (failure.status == 503) nowMillis() + 60_000 else nextQuotaDay()
                        quotaStore.block(model, failure.retryAtMillis?.coerceAtLeast(nowMillis() + 1) ?: defaultReset)
                        lastUnavailable = failure
                    }
                    else -> throw failure
                }
            }
        }
        if (lastQuota != null) throw allModelsBlocked()
        throw lastUnavailable ?: allModelsBlocked()
    }

    private fun nextQuotaDay(): Long {
        val pacific = java.time.ZoneId.of("America/Los_Angeles")
        return java.time.Instant.ofEpochMilli(nowMillis()).atZone(pacific).toLocalDate()
            .plusDays(1).atStartOfDay(pacific).toInstant().toEpochMilli()
    }

    private fun allModelsBlocked() = GeminiApiException(
        status = 429,
        quotaExhausted = true,
        modelUnavailable = false,
        retryAtMillis = null,
        message = "Gemini models are cooling down. Jarvis is using offline AI.",
    )

    companion object {
        val DEFAULT_FALLBACKS = listOf(
            "gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.8-flash",
        )
    }
}

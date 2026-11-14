package dev.jarvis.app

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.jarvisData by preferencesDataStore(name = "jarvis")

data class JarvisSettings(val enabled: Boolean = true, val floatingButton: Boolean = false, val language: String = "",
    val spokenReplies: Boolean = true, val profile: JarvisProfile = JarvisProfile())

class JarvisPreferences(context: Context) {
    private val store = context.applicationContext.jarvisData
    val settings = store.data.catch { failure ->
        if (failure is IOException) emit(preferencesOf(ENABLED to false, FLOATING to false)) else throw failure
    }.map {
        JarvisSettings(it[ENABLED] ?: true, it[FLOATING] ?: false, it[LANGUAGE] ?: "", it[SPOKEN] ?: true,
            JarvisProfile(it[NAME].orEmpty(), it[PRIORITIES].orEmpty()))
    }

    suspend fun setEnabled(value: Boolean) { store.edit { it[ENABLED] = value } }
    suspend fun setFloating(value: Boolean) { store.edit { it[FLOATING] = value } }
    suspend fun setSpokenReplies(value: Boolean) { store.edit { it[SPOKEN] = value } }
    suspend fun setProfile(profile: JarvisProfile) {
        store.edit {
            it[NAME] = profile.name.trim().take(JarvisProfile.MAX_NAME)
            it[PRIORITIES] = profile.priorities.trim().take(JarvisProfile.MAX_PRIORITIES)
        }
    }
    suspend fun setLanguage(value: String) {
        require(value in setOf("", "en", "ml"))
        store.edit { it[LANGUAGE] = value }
    }
    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val FLOATING = booleanPreferencesKey("floating_button")
        val LANGUAGE = stringPreferencesKey("language")
        val SPOKEN = booleanPreferencesKey("spoken_replies")
        val NAME = stringPreferencesKey("profile_name")
        val PRIORITIES = stringPreferencesKey("profile_priorities")
    }
}

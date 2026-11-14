package dev.jarvis.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Restores only the opted-in button; never starts microphone capture at boot. */
class JarvisBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8000) {
                    val settings = JarvisPreferences(context).settings.first()
                    if (settings.enabled && settings.floatingButton && Settings.canDrawOverlays(context)) {
                        context.startForegroundService(Intent(context, FloatingButtonService::class.java))
                    }
                }
            } catch (_: Exception) {
                // OEM restrictions may require reopening Jarvis; no background microphone fallback.
            } finally { pending.finish() }
        }
    }
}

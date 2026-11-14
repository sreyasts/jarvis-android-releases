package dev.jarvis.app

import android.Manifest
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.jarvis.core.ActivationSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    private var microphoneGranted by mutableStateOf(false)
    private var overlayGranted by mutableStateOf(false)
    private var defaultAssistant by mutableStateOf(false)
    private var deviceLocked by mutableStateOf(false)
    private var batteryNeedsCharging by mutableStateOf<Int?>(null)
    private var listenAfterPermission = false
    private lateinit var setup: VoiceSetupViewModel
    private lateinit var updates: AppUpdatesViewModel
    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        microphoneGranted = it
        if (it && listenAfterPermission) setup.listen()
        else if (!it) setup.message("Allow the microphone in Android app settings to use your voice. You can still type.")
        listenAfterPermission = false
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { setup.cameraPermissionResult(it) }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setup = ViewModelProvider(this)[VoiceSetupViewModel::class.java]
        updates = ViewModelProvider(this)[AppUpdatesViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) { updates.check(); delay(5 * 60_000L) }
            }
        }
        refreshPermissions()
        setContent {
            JarvisScreen(
                setup = setup,
                updates = updates,
                onUpdate = {
                    updates.downloadUrl()?.let { address ->
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)).addCategory(Intent.CATEGORY_BROWSABLE)) }
                        catch (_: Exception) { updates.downloadFailed() }
                    }
                },
                overlayGranted = overlayGranted,
                defaultAssistant = defaultAssistant,
                deviceLocked = deviceLocked,
                batteryNeedsCharging = batteryNeedsCharging,
                onListen = {
                    if (microphoneGranted) setup.listen()
                    else { listenAfterPermission = true; microphonePermission.launch(Manifest.permission.RECORD_AUDIO) }
                },
                onAssistant = ::requestAssistant,
                onAssistantSettings = { openSettings(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_VOICE_INPUT_SETTINGS) },
                onAppSettings = { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Settings.ACTION_SETTINGS, true) },
                onGestureSettings = { openSettings("android.settings.GESTURE_SETTINGS", Settings.ACTION_SETTINGS) },
                onFloating = { enabled ->
                    setup.setFloating(enabled)
                    if (enabled && !Settings.canDrawOverlays(this)) {
                        setup.message("Allow display over other apps. If Android denies access, open App info → menu → Allow restricted settings when available, then retry. You can use Jarvis without this permission.")
                        openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
                    }
                    else if (enabled && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                onSyncFloating = ::syncFloating,
                onCameraPermission = { cameraPermission.launch(Manifest.permission.CAMERA) },
            )
        }
        consumeActivation(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        val (level, plugged) = readBattery(this)
        batteryNeedsCharging = level.takeIf { batteryAdvice(it, plugged).first.isNotEmpty() }
    }
    override fun onPostResume() {
        super.onPostResume()
        lifecycleScope.launch {
            val settings = JarvisPreferences(this@MainActivity).settings.first()
            syncFloating(settings)
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); refreshPermissions(); consumeActivation(intent) }
    override fun onStop() { setup.onBackground(); super.onStop() }
    private fun refreshPermissions() {
        microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        overlayGranted = Settings.canDrawOverlays(this)
        val locked = getSystemService(KeyguardManager::class.java).isDeviceLocked
        if (locked && !deviceLocked) setup.clearPrivateContext()
        deviceLocked = locked
        defaultAssistant = if (Build.VERSION.SDK_INT >= 29) getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
        else android.service.voice.VoiceInteractionService.isActiveService(this, ComponentName(this, JarvisVoiceInteractionService::class.java))
    }

    private fun consumeActivation(intent: Intent) {
        if (intent.action == Intent.ACTION_ASSIST) {
            intent.action = Intent.ACTION_MAIN
            startActivity(JarvisOverlayActivity.intent(this, ActivationSource.ASSISTANT_GESTURE))
            return
        }
    }

    private fun requestAssistant() {
        // AOSP roles.xml marks ASSISTANT requestable=false. createRequestRoleIntent silently fails.
        openSettings(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    }

    private fun syncFloating(settings: JarvisSettings) {
        val service = Intent(this, FloatingButtonService::class.java)
        if (!settings.enabled || !settings.floatingButton || !Settings.canDrawOverlays(this)) { stopService(service); return }
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        try { startForegroundService(service) }
        catch (_: Exception) { setup.message("Android paused the floating button. Open Jarvis and turn it on again.") }
    }

    private fun openSettings(action: String, fallback: String, packageUri: Boolean = false) {
        fun settingsIntent(value: String) = Intent(value).apply {
            if (packageUri && value in setOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) data = Uri.parse("package:$packageName")
        }
        try { startActivity(settingsIntent(action)) }
        catch (_: Exception) {
            try { startActivity(settingsIntent(fallback)) }
            catch (_: Exception) { setup.message("Open Android Settings manually → Apps → Jarvis.") }
        }
    }

    companion object {
        const val EXTRA_SOURCE = "dev.jarvis.ACTIVATION_SOURCE"
    }
}

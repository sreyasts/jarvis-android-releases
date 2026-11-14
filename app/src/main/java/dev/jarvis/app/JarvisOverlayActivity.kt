package dev.jarvis.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.core.ActivationSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class JarvisOverlayActivity : ComponentActivity() {
    private lateinit var setup: VoiceSetupViewModel
    private var permissionPending = false
    private var activationSource = ActivationSource.ASSISTANT_GESTURE
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionPending = false
        if (granted) setup.activate(activationSource)
        else setup.message("Allow microphone access to talk to Jarvis.")
    }
    private val camera = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionPending = false
        setup.cameraPermissionResult(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setup = ViewModelProvider(this)[VoiceSetupViewModel::class.java]
        activationSource = sourceFrom(intent)
        setContent {
            MaterialTheme(colorScheme = JarvisColors) {
                val state by setup.state.collectAsStateWithLifecycle()
                val uriHandler = LocalUriHandler.current
                Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.matchParentSize().clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClickLabel = "Dismiss Jarvis", onClick = ::finish,
                    ))
                    Surface(
                        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .91f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .4f)),
                    ) {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Jarvis", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                IconButton(onClick = ::finish) { Icon(Icons.Outlined.Close, "Close Jarvis") }
                            }
                            Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                JarvisOrb(state.settings.enabled, state.busy || state.speaking,
                                    if (state.speaking) state.outputPulse else state.inputPulse, diameter = 112.dp, status = state.reactorStatus) {
                                    if (state.busy || state.speaking) setup.cancelRecording() else listen()
                                }
                                Column(Modifier.weight(1f).heightIn(max = 160.dp).verticalScroll(rememberScrollState())
                                    .semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    state.heard?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    Text(state.reply ?: state.message ?: when {
                                        state.work == SetupWork.RECORDING -> "Go ahead. I'm listening."
                                        state.busy -> state.phase.ifBlank { "Working on it…" }
                                        !state.settingsLoaded -> "Starting…"
                                        !state.settings.enabled -> "Turn me on in the Jarvis app."
                                        else -> "Tap the reactor to talk."
                                    })
                                    if (state.reply != null) {
                                        Text(state.replyOrigin, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                        state.searchHtml?.let { SearchSuggestions(it) { url ->
                                            runCatching { uriHandler.openUri(url) }
                                                .onFailure { setup.message("No browser could open this link.") }
                                        } }
                                        state.sources.forEach { source ->
                                            TextButton(onClick = {
                                                runCatching { uriHandler.openUri(source.url) }
                                                    .onFailure { setup.message("No browser could open this source.") }
                                            }) { Text(source.title) }
                                        }
                                    }
                                    if (state.cameraPermissionNeeded) TextButton(onClick = {
                                        permissionPending = true
                                        camera.launch(Manifest.permission.CAMERA)
                                    }) { Text("Allow flashlight control") }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (savedInstanceState == null) activate(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        activate(intent)
    }

    private fun activate(intent: Intent) {
        activationSource = sourceFrom(intent)
        listen()
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            setup.activate(activationSource)
        } else if (!permissionPending) {
            permissionPending = true
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStart() {
        super.onStart()
        activePanel = this
        panelVisible.value = true
    }

    override fun onStop() {
        if (activePanel === this) {
            activePanel = null
            panelVisible.value = false
        }
        if (!permissionPending) setup.onBackground()
        super.onStop()
    }

    companion object {
        private var activePanel: JarvisOverlayActivity? = null
        private val panelVisible = MutableStateFlow(false)
        internal val visible = panelVisible.asStateFlow()

        private fun sourceFrom(intent: Intent) =
            ActivationSource.entries.firstOrNull { it.name == intent.getStringExtra(MainActivity.EXTRA_SOURCE) }
                ?: ActivationSource.ASSISTANT_GESTURE

        fun intent(context: Context, source: ActivationSource) =
            Intent(context, JarvisOverlayActivity::class.java)
                .putExtra(MainActivity.EXTRA_SOURCE, source.name)
    }
}

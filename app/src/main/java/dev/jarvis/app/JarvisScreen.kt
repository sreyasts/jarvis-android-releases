package dev.jarvis.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.core.AudioPulse
import kotlin.math.cos
import kotlin.math.sin

private val Cyan = Color(0xFF35CFFF)
private val Ink = Color(0xFF07131D)
private val Muted = Color(0xFFA0B6C6)
internal val JarvisColors = darkColorScheme(primary = Cyan, onPrimary = Color(0xFF00202C), secondary = Color(0xFF479EFF),
    secondaryContainer = Color(0xFF103B51), onSecondaryContainer = Color(0xFFBDEFFF),
    background = Color.Black, surface = Ink, onSurface = Color(0xFFEDF8FF), onSurfaceVariant = Muted,
    outline = Color(0xFF294352), error = Color(0xFFFF9DA1))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JarvisScreen(
    setup: VoiceSetupViewModel,
    updates: AppUpdatesViewModel,
    onUpdate: () -> Unit,
    overlayGranted: Boolean,
    defaultAssistant: Boolean,
    deviceLocked: Boolean,
    batteryNeedsCharging: Int?,
    onListen: () -> Unit,
    onAssistant: () -> Unit,
    onAssistantSettings: () -> Unit,
    onAppSettings: () -> Unit,
    onGestureSettings: () -> Unit,
    onFloating: (Boolean) -> Unit,
    onSyncFloating: (JarvisSettings) -> Unit,
    onCameraPermission: () -> Unit,
) {
    val state by setup.state.collectAsStateWithLifecycle()
    val update by updates.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var showAi by rememberSaveable { mutableStateOf(false) }
    var showAssistant by rememberSaveable { mutableStateOf(false) }
    val ai by setup.aiState.collectAsStateWithLifecycle()
    val model by setup.modelState.collectAsStateWithLifecycle()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    LaunchedEffect(state.settings, overlayGranted, defaultAssistant) { if (state.settingsLoaded) onSyncFloating(state.settings) }
    LaunchedEffect(deviceLocked) { if (deviceLocked) text = "" }
    BackHandler(state.busy) { setup.cancelRecording() }
    MaterialTheme(colorScheme = JarvisColors) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.safeDrawingPadding().fillMaxSize()) {
                Column(Modifier.align(Alignment.TopCenter).widthIn(max = 560.dp).fillMaxSize()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("JARVIS", style = MaterialTheme.typography.headlineLarge.copy(letterSpacing = 5.sp), fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { showHelp = true }) { Icon(Icons.Outlined.Info, "How to use Jarvis", tint = Muted) }
                        Switch(checked = state.settings.enabled, onCheckedChange = setup::setEnabled, enabled = state.settingsLoaded,
                            modifier = Modifier.semantics { contentDescription = "Jarvis on or off" })
                    }
                    update.available?.let { release ->
                        Surface(color = Ink, shape = RoundedCornerShape(16.dp)) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Update available", style = MaterialTheme.typography.titleMedium)
                                    Text("Jarvis ${release.versionName}", color = Muted, style = MaterialTheme.typography.bodyMedium)
                                    update.browserError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                }
                                FilledTonalButton(onClick = onUpdate,
                                    modifier = Modifier.semantics { contentDescription = "Update Jarvis to ${release.versionName}" }) {
                                    Text("Update")
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(7.dp).background(if (state.settings.enabled) Cyan else Muted, CircleShape))
                        Text(when {
                            !state.settings.enabled -> "Jarvis is off"
                            ai.cloud -> "Gemini · ${if (ai.web) "Web enabled" else "Web off"}"
                            model.ready -> "Offline AI · ${model.variant.title.substringBefore(" ·")}"
                            else -> "Voice & phone controls ready"
                        }, color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showAi = true }, enabled = !deviceLocked) { Text("AI settings") }
                    }
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        JarvisOrb(active = state.settings.enabled, busy = state.busy || state.speaking,
                            status = state.reactorStatus,
                            pulse = when {
                                state.work == SetupWork.RECORDING -> state.inputPulse
                                state.speaking -> state.outputPulse
                                else -> AudioPulse.SILENT
                            },
                            onClick = {
                                if (!state.settings.enabled) setup.message("Turn Jarvis on with the switch above.")
                                else if (state.settingsLoaded) {
                                    if (state.busy || state.speaking) setup.cancelRecording() else onListen()
                                }
                            })
                        Text(when {
                            !state.settings.enabled -> "Ready when you turn me on"
                            state.work == SetupWork.RECORDING -> "Go ahead. I'm listening."
                            state.work == SetupWork.PROCESSING -> state.phase.ifBlank { "Reading your request…" }
                            state.speaking -> "Speaking…"
                            else -> jarvisGreeting(if (state.settings.language == "ml") JarvisLanguage.MALAYALAM else JarvisLanguage.ENGLISH,
                                state.settings.profile.name.takeUnless { deviceLocked }.orEmpty())
                        }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Text(if (state.busy || state.speaking) "Tap the core to stop" else "Tap the core to talk",
                            style = MaterialTheme.typography.bodyMedium, color = Muted)
                        if (state.settingsLoaded && state.settings.enabled && !deviceLocked && !state.busy && !state.speaking) {
                            batteryNeedsCharging?.let { level ->
                                Text(if (state.settings.language == "ml") "ബാറ്ററി $level ശതമാനമാണ്. ഉടൻ ചാർജിൽ ഇടൂ."
                                    else "Your battery is down to $level%. I'd plug in soon.",
                                    style = MaterialTheme.typography.bodyMedium, color = Cyan)
                            }
                        }
                    }
                    if (!model.ready && !ai.cloud) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Add offline AI", style = MaterialTheme.typography.titleMedium)
                            Text("Download Qwen3 once for offline conversation and flexible requests. Phone controls already work.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                            if (model.downloading) {
                                LinearProgressIndicator(progress = { (model.bytes.toFloat() / model.variant.size).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                Text(if (model.bytes == model.variant.size) "Verifying model…" else "${model.bytes / 1_000_000} / ${model.variant.size / 1_000_000} MB · Keep Jarvis open", color = Muted)
                                TextButton(onClick = setup::pauseDownload) { Text("Pause download") }
                            } else Button(onClick = setup::downloadModel, enabled = !deviceLocked) { Text("Download offline AI · 2.50 GB") }
                            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            TextButton(onClick = { showAi = true }, enabled = !deviceLocked) { Text("Or connect Gemini") }
                        }
                    }
                    state.heard?.let { Text("You: $it", color = Muted, style = MaterialTheme.typography.bodyMedium) }
                    state.reply?.let { reply ->
                        Surface(color = Ink, shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(state.replyOrigin, style = MaterialTheme.typography.labelMedium, color = Muted)
                                Text(reply, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge)
                                state.searchHtml?.let { SearchSuggestions(it) { url ->
                                    runCatching { uriHandler.openUri(url) }.onFailure { setup.message("No browser could open this link.") }
                                } }
                                state.sources.forEach { source ->
                                    TextButton(onClick = {
                                        runCatching { uriHandler.openUri(source.url) }.onFailure { setup.message("No browser could open this source.") }
                                    }) { Text(source.title) }
                                }
                            }
                        }
                    }
                    state.message?.let { Text(it, color = Cyan, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    if (state.cameraPermissionNeeded) Button(onClick = onCameraPermission) { Text("Allow flashlight access") }
                    OutlinedTextField(value = text, onValueChange = { text = it.take(1000) }, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Or type a request…") }, shape = RoundedCornerShape(16.dp), maxLines = 3,
                        enabled = state.settings.enabled && !state.busy,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { setup.typedCommand(text); text = "" }),
                        trailingIcon = { IconButton(onClick = { setup.typedCommand(text); text = "" }, enabled = text.isNotBlank() && !state.busy) {
                            Icon(Icons.Outlined.Send, "Send request", tint = Cyan)
                        } })
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Try asking", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            listOf("7 AM alarm" to "set a alarm at 7am", "5-min timer" to "set a five minute timer", "Ask AI" to "Explain how rainbows form in two sentences", "Battery" to "battery status").forEach { (label, command) ->
                                SuggestionChip(onClick = { setup.typedCommand(command) }, label = { Text(label) }, enabled = state.settings.enabled && !state.busy)
                            }
                        }
                    }
                    HorizontalDivider(color = Color(0xFF203441))
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(Modifier.fillMaxWidth().clickable(enabled = !deviceLocked, onClick = { showAssistant = true }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Icon(painterResource(R.drawable.ic_power), null, tint = Cyan)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Hold power to talk", style = MaterialTheme.typography.titleMedium)
                                Text(if (defaultAssistant) "Jarvis is your default assistant" else "Choose Jarvis as your default assistant", color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(if (defaultAssistant) Icons.Outlined.CheckCircle else Icons.Outlined.KeyboardArrowRight, null, tint = if (defaultAssistant) Cyan else Muted)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Icon(painterResource(R.drawable.ic_floating), null, tint = Cyan)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Floating button", style = MaterialTheme.typography.titleMedium)
                                Text("A small Jarvis button over your other apps", color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = state.settings.floatingButton && overlayGranted, onCheckedChange = onFloating,
                                enabled = state.settings.enabled && !deviceLocked, modifier = Modifier.semantics { contentDescription = "Floating Jarvis button" })
                        }
                    }
                    Text("Local speech · Optional Gemini & web", color = Muted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 16.dp))
                }
            }
        }
        if (showAi && !deviceLocked) AiSettingsScreen(setup = setup, onClose = { showAi = false })
        if (showAssistant && !deviceLocked) {
            ModalBottomSheet(onDismissRequest = { showAssistant = false }, containerColor = Ink,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Assistant & shortcuts", style = MaterialTheme.typography.headlineSmall)
                    Text(if (defaultAssistant) "Jarvis is selected as your default assistant." else "Choose Jarvis in Android's Digital assistant app settings. The floating button is optional.", color = Muted)
                    Button(onClick = onAssistant) { Text("Choose Jarvis") }
                    OutlinedButton(onClick = onAssistantSettings) { Text("Manual assistant settings") }
                    Text("On Nothing OS: Settings → System → Gestures → Press and hold power button → Digital assistant. Menu names can vary by OS version.", color = Muted)
                    OutlinedButton(onClick = onGestureSettings) { Text("Power-button settings") }
                    Text("If display-over-apps access is denied: App info → menu → Allow restricted settings, if offered by Android. Then return and enable the floating button. This cannot be granted by Jarvis.", color = Muted)
                    OutlinedButton(onClick = onAppSettings) { Text("Open Jarvis app info") }
                }
            }
        }
        if (showHelp) {
            ModalBottomSheet(onDismissRequest = { showHelp = false }, containerColor = Ink) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Here's how Jarvis works", style = MaterialTheme.typography.headlineSmall)
                    Text("Jarvis ${BuildConfig.VERSION_NAME}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { updates.check(force = true) }, enabled = !update.checking) {
                        Text(if (update.checking) "Checking…" else "Check for updates")
                    }
                    update.feedback?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodyMedium) }
                    OutlinedButton(onClick = setup::previewVoice) { Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Preview Jarvis voice") }
                    if (!deviceLocked && state.settingsLoaded) JarvisProfileEditor(state.settings.profile, setup::setProfile)
                    Text("Tap the blue core and speak. No registration or voice matching. Use AI settings to download offline AI or connect Gemini.")
                    Text("The microphone is off while Jarvis is idle. Open Jarvis with the blue core, the floating button, or your Android assistant gesture. When you see Listening, say “Hey Jarvis, what is the battery level?” Android shows the green mic indicator during voice capture.", color = Muted)
                    Text("Try saying", style = MaterialTheme.typography.titleMedium, color = Cyan)
                    Text("“Turn on the flashlight”\n“Volume up”\n“Set a five minute timer”\n“Open YouTube”\n“What's the time?”\n“Battery status”")
                    Text("Jarvis accepts English and Malayalam. With a saved Gemini key and internet access, speech recorded after activation goes to Google for transcription. Offline transcription stays on this phone.", color = Muted)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Read spoken replies aloud", Modifier.weight(1f))
                        Switch(checked = state.settings.spokenReplies, onCheckedChange = setup::setSpokenReplies)
                    }
                    TextButton(onClick = { showHelp = false; showAi = true }, enabled = !deviceLocked) { Text("AI settings") }
                    TextButton(onClick = { showHelp = false; showAssistant = true }, enabled = !deviceLocked) { Text("Assistant & permission help") }
                    Text("On Nothing OS, choose Digital assistant for the power-button hold gesture in system settings. The default-assistant button on the home screen selects Jarvis.", color = Muted)
                    Text("Speech language", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("" to "English + മലയാളം", "en" to "English", "ml" to "മലയാളം").forEach { (code, label) ->
                            FilterChip(selected = state.settings.language == code, onClick = { setup.setLanguage(code) }, label = { Text(label) })
                        }
                    }
                    Text("Secure apps can hide floating buttons. Android controls which actions and permissions are available.", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }

    }
}

@Composable
private fun JarvisProfileEditor(profile: JarvisProfile, onSave: (JarvisProfile) -> Unit) {
    var name by remember(profile) { mutableStateOf(profile.name) }
    var priorities by remember(profile) { mutableStateOf(profile.priorities) }
    Text("Personalize Jarvis", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(name, { name = it.take(JarvisProfile.MAX_NAME) }, Modifier.fillMaxWidth(),
        label = { Text("What should I call you?") }, singleLine = true)
    OutlinedTextField(priorities, { priorities = it.take(JarvisProfile.MAX_PRIORITIES) }, Modifier.fillMaxWidth(),
        label = { Text("What should I keep in mind?") },
        supportingText = { Text("Add your priorities or reply preferences. Gemini receives these when used for replies.") },
        minLines = 2, maxLines = 4)
    val updated = JarvisProfile(name.trim(), priorities.trim())
    FilledTonalButton(onClick = { onSave(updated) }, enabled = updated != profile) { Text("Save preferences") }
}

@Composable
internal fun JarvisOrb(active: Boolean, busy: Boolean, pulse: AudioPulse, diameter: androidx.compose.ui.unit.Dp = 240.dp,
    status: String? = null, onClick: () -> Unit) {
    val level by animateFloatAsState(if (active) pulse.level else 0f, animationSpec = tween(80), label = "Live audio energy")
    Box(Modifier.size(diameter).semantics { contentDescription = if (busy) "Stop Jarvis" else "Talk to Jarvis" }
        .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.jarvis_reactor), contentDescription = null,
            modifier = Modifier.fillMaxSize().padding(8.dp).graphicsLayer {
                scaleX = .97f + .03f * level
                scaleY = scaleX
                alpha = if (active) .72f + .22f * level else .28f
            })
        Canvas(Modifier.fillMaxSize()) {
            val center = this.center
            val diameterPx = this.size.minDimension
            val radius = diameterPx * .40f
            if (active && pulse.level > 0f) {
                pulse.bars.forEachIndexed { index, value ->
                    val angle = (index * 2.0 * Math.PI / pulse.bars.size - Math.PI / 2)
                    val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                    val length = diameterPx * (.01f + .08f * value)
                    drawLine(Cyan.copy(alpha = .4f + .6f * level), center + direction * radius,
                        center + direction * (radius + length), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
                drawCircle(Cyan.copy(alpha = level * .25f), diameterPx * .105f,
                    style = Stroke((1f + level * 2).dp.toPx()))
            }
        }
        if (status == null) {
            Icon(painterResource(if (busy) R.drawable.ic_stop else R.drawable.ic_jarvis_notification), null,
                tint = if (active) Cyan else Muted, modifier = Modifier.size(24.dp))
        } else {
            Surface(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = Color(0xD907131D), shape = CircleShape,
                border = BorderStroke(1.dp, Cyan.copy(alpha = .52f)),
            ) {
                Text(status, Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    color = JarvisColors.onSurface, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}


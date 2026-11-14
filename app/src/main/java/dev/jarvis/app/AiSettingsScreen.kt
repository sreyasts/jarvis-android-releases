package dev.jarvis.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AiSettingsScreen(setup: VoiceSetupViewModel, onClose: () -> Unit) {
    val ai by setup.aiState.collectAsStateWithLifecycle()
    val model by setup.modelState.collectAsStateWithLifecycle()
    val state by setup.state.collectAsStateWithLifecycle()
    var web by remember(ai.web) { mutableStateOf(ai.web) }
    var modelId by remember(ai.model) { mutableStateOf(ai.model) }
    var key by remember { mutableStateOf("") } // Secrets must not enter saved instance state.
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    LaunchedEffect(ai.status) { if (ai.status == "Connection saved.") key = "" }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AI settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close AI settings") }
                }
                Text("Offline AI", style = MaterialTheme.typography.titleLarge)
                Text("Choose the smaller model for faster replies or 4B for harder requests.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LocalModel.entries.forEach { variant ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = model.variant == variant, onClick = { setup.selectModel(variant) }, enabled = !model.downloading)
                        Text(variant.title)
                    }
                }
                if (model.ready) Text("Offline model installed", color = MaterialTheme.colorScheme.primary)
                else if (model.downloading) {
                    LinearProgressIndicator(progress = { (model.bytes.toFloat() / model.variant.size).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(if (model.bytes == model.variant.size) "Verifying model…" else "${model.bytes / 1_000_000} / ${model.variant.size / 1_000_000} MB · Keep Jarvis open")
                    OutlinedButton(onClick = setup::pauseDownload) { Text("Pause download") }
                } else Button(onClick = setup::downloadModel) { Text("Download offline AI") }
                model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                HorizontalDivider()
                Text("Gemini connection", style = MaterialTheme.typography.titleLarge)
                Text("With a saved key and internet access, Jarvis sends your request and speech after activation to Google. If Gemini is unavailable, Jarvis uses the installed offline model. Usage may incur charges. The key is encrypted on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    runCatching { uriHandler.openUri("https://aistudio.google.com/apikey") }
                        .onFailure { setup.message("Open aistudio.google.com/apikey in your browser to create a Gemini key.") }
                }) { Text("Get a Gemini API key") }
                OutlinedTextField(value = modelId, onValueChange = { modelId = it.take(80) }, label = { Text("Gemini model ID") },
                    supportingText = { Text("Use a model available to your Google AI Studio account.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = key, onValueChange = { key = it.take(512) }, label = { Text(if (ai.hasKey) "Replace saved API key" else "Gemini API key") },
                    supportingText = { Text(if (ai.hasKey) "A key is saved. Leave blank to keep it." else "Create a key in Google AI Studio. Never share it in chat.") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Search & read web pages")
                        Text("Google Search + URL Context. Public pages only; sources appear below the reply.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = web, onCheckedChange = { web = it }, enabled = ai.hasKey || key.isNotBlank())
                }
                Button(onClick = { setup.saveConnection(modelId, key, web) }, enabled = ai.loaded && !ai.saving && !state.busy,
                    modifier = Modifier.fillMaxWidth()) { Text("Save connection") }
                OutlinedButton(onClick = { setup.testConnection() }, enabled = ai.hasKey && !ai.saving && !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (ai.saving) "Please wait…" else "Test saved Gemini connection")
                }
                if (ai.web && ai.hasKey) OutlinedButton(onClick = { setup.testConnection(web = true) }, enabled = !ai.saving && !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Test search & page reading")
                }
                ai.status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (ai.hasKey) {
                    TextButton(onClick = {
                        key = ""
                        web = false
                        setup.saveConnection(modelId, "", false, forgetKey = true)
                    }, enabled = !ai.saving && !state.busy) {
                        Text("Remove saved API key", color = MaterialTheme.colorScheme.error)
                    }
                }
                Text("Phone actions stay limited to Android's supported tools. Jarvis cannot bypass app security or operate every screen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

package com.voicedroid.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.voicedroid.service.TokenUsage
import com.voicedroid.service.VoiceDroidService
import com.voicedroid.storage.Mode
import com.voicedroid.storage.PromptStore
import com.voicedroid.storage.SecureStore
import com.voicedroid.storage.Settings as AppSettings
import com.voicedroid.storage.SystemPrompt
import com.voicedroid.util.Permissions
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val secure = remember { SecureStore(ctx) }
    val settings = remember { AppSettings(ctx) }

    var apiKey by remember { mutableStateOf(secure.openAiKey ?: "") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var apiKeySavedTick by remember { mutableStateOf(0) }
    var mode by remember { mutableStateOf(settings.mode) }

    val connectionActive by VoiceDroidService.connectionActive.collectAsState()
    val sessionUsage by VoiceDroidService.sessionUsage.collectAsState()

    var micGranted by remember { mutableStateOf(Permissions.hasMic(ctx)) }
    var notifGranted by remember { mutableStateOf(Permissions.hasNotifications(ctx)) }
    var axGranted by remember { mutableStateOf(Permissions.isAccessibilityEnabled(ctx)) }
    var overlayGranted by remember { mutableStateOf(Permissions.canDrawOverlays(ctx)) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { micGranted = it }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notifGranted = it }

    // Re-check permissions every time the screen becomes resumed (e.g. after the user
    // grants overlay or accessibility access in system settings and swipes back).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                micGranted = Permissions.hasMic(ctx)
                notifGranted = Permissions.hasNotifications(ctx)
                axGranted = Permissions.isAccessibilityEnabled(ctx)
                overlayGranted = Permissions.canDrawOverlays(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val allPermissionsGranted = micGranted && axGranted && overlayGranted &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notifGranted)


    var apiKeyExpanded by remember { mutableStateOf(!secure.hasOpenAiKey()) }
    var promptExpanded by remember { mutableStateOf(false) }
    var permissionsExpanded by remember { mutableStateOf(!allPermissionsGranted) }
    var advancedExpanded by remember { mutableStateOf(false) }
    val appIcon = remember(ctx) {
        // PackageManager.getApplicationIcon handles adaptive icons; painterResource doesn't.
        ctx.packageManager.getApplicationIcon(ctx.packageName)
            .toBitmap(96, 96)
            .asImageBitmap()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            bitmap = appIcon,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Voice Droid")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // --- Permissions (first) ------------------------------------
            CollapsibleCard(
                title = "Permissions",
                expanded = permissionsExpanded,
                onToggle = { permissionsExpanded = it },
                badge = if (allPermissionsGranted) "All granted" else null,
            ) {
                PermissionRow(
                    label = "Microphone",
                    granted = micGranted,
                    onGrant = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionRow(
                        label = "Notifications",
                        granted = notifGranted,
                        onGrant = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    )
                }
                PermissionRow(
                    label = "Accessibility service",
                    granted = axGranted,
                    onGrant = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
                PermissionRow(
                    label = "Display over other apps",
                    granted = overlayGranted,
                    onGrant = {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${ctx.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
                if (!axGranted || !overlayGranted) {
                    Text(
                        "After granting in system settings, return here — the status will refresh.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // --- OpenAI config ------------------------------------------
            CollapsibleCard(
                title = "OpenAI config",
                expanded = apiKeyExpanded,
                onToggle = { apiKeyExpanded = it },
                badge = if (secure.hasOpenAiKey()) settings.voice else "API key needed",
            ) {
                Text("API key", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("sk-…") },
                    visualTransformation =
                        if (apiKeyVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    trailingIcon = {
                        TextButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Text(if (apiKeyVisible) "Hide" else "Show")
                        }
                    },
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = {
                        secure.openAiKey = apiKey.trim().ifEmpty { null }
                        apiKey = secure.openAiKey ?: ""
                        apiKeySavedTick++
                    }) { Text("Save") }
                    TextButton(onClick = {
                        secure.openAiKey = null
                        apiKey = ""
                        apiKeySavedTick++
                    }) { Text("Clear") }
                    if (apiKeySavedTick > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (secure.hasOpenAiKey()) "Saved." else "Cleared.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(
                    "Stays on this device. Sent only to api.openai.com.",
                    style = MaterialTheme.typography.bodySmall,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                VoicePicker(settings)
            }

            // --- System prompts ----------------------------------------
            PromptsCard(
                expanded = promptExpanded,
                onToggle = { promptExpanded = it },
            )

            // --- Mode ---------------------------------------------------
            SectionCard(title = "Mode") {
                if (connectionActive) {
                    ConnectionIndicator()
                }
                if (sessionUsage.totalTokens > 0) {
                    TokenUsageDisplay(sessionUsage)
                }
                fun apply(m: Mode) {
                    mode = m
                    settings.mode = m
                    if (m == Mode.OFF) VoiceDroidService.stop(ctx)
                    else VoiceDroidService.start(ctx)
                }
                ModeOption(
                    label = "Off",
                    description = "Mic released. No network.",
                    selected = mode == Mode.OFF,
                ) { apply(Mode.OFF) }
                ModeOption(
                    label = "Listening",
                    description = "Always streaming to OpenAI. ~\$1/hr idle.",
                    selected = mode == Mode.LISTENING,
                ) { apply(Mode.LISTENING) }
                ModeOption(
                    label = "PTT",
                    description = "Session stays connected, mic is silent until you tap the " +
                        "floating bubble (or notification Talk / Bluetooth play-pause).",
                    selected = mode == Mode.PTT,
                ) { apply(Mode.PTT) }

                AutoOffToggle(settings)
            }

            // --- Advanced (collapsed by default) -----------------------
            AdvancedCard(
                expanded = advancedExpanded,
                onToggle = { advancedExpanded = it },
                settings = settings,
            )
        }
    }
}

@Composable
private fun CollapsibleCard(
    title: String,
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    badge: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(!expanded) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (badge != null && !expanded) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
                Text(
                    "▼",
                    modifier = Modifier.rotate(rotation),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun AutoOffToggle(settings: AppSettings) {
    var enabled by remember { mutableStateOf(settings.autoOffEnabled) }
    var showWarning by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Checkbox(
            checked = enabled,
            onCheckedChange = { newValue ->
                enabled = newValue
                settings.autoOffEnabled = newValue
                if (!newValue) showWarning = true
            },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Automatically turn off after 1 hour",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Only when Listening or PTT is active. Resets on each mode change.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showWarning) {
        AlertDialog(
            onDismissRequest = { showWarning = false },
            title = { Text("Auto-off disabled") },
            text = {
                Text(
                    "The session will stay alive indefinitely. Watch your token usage — " +
                        "Listening mode streams continuously to OpenAI and bills audio " +
                        "tokens whenever VAD picks up sound.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showWarning = false }) { Text("Got it") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePicker(settings: AppSettings) {
    val ctx = LocalContext.current
    var voice by remember { mutableStateOf(settings.voice) }
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Voice", style = MaterialTheme.typography.labelMedium)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = voice,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                AppSettings.AVAILABLE_VOICES.forEach { v ->
                    DropdownMenuItem(
                        text = { Text(v) },
                        onClick = {
                            voice = v
                            expanded = false
                            settings.voice = v
                            // Voice is part of the realtime session config — once audio
                            // has been generated it's locked. Restart cleanly so the new
                            // voice is in force from the next turn.
                            if (settings.mode != Mode.OFF) {
                                VoiceDroidService.stop(ctx)
                                VoiceDroidService.start(ctx)
                            }
                        },
                    )
                }
            }
        }
        Text(
            "Voice changes restart the session so the new voice is in force from the next turn.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AdvancedCard(
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    settings: AppSettings,
) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("%.2f".format(settings.vadThreshold)) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    CollapsibleCard(
        title = "Advanced",
        expanded = expanded,
        onToggle = onToggle,
        badge = "VAD ${"%.2f".format(settings.vadThreshold)}",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Voice detection threshold", style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                        saved = false
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("0.0 – 1.0") },
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    ),
                )
                Button(onClick = {
                    val parsed = text.trim().toFloatOrNull()
                    when {
                        parsed == null -> { error = "Enter a number"; saved = false }
                        parsed < 0f || parsed > 1f -> { error = "Must be 0.0–1.0"; saved = false }
                        else -> {
                            settings.vadThreshold = parsed
                            text = "%.2f".format(settings.vadThreshold)
                            error = null
                            saved = true
                            VoiceDroidService.updateVadThreshold(ctx)
                        }
                    }
                }) { Text("Save") }
            }
            Text(
                "Higher = more conservative. 0.8 ignores keyboard taps and fan noise " +
                    "but still catches normal speech. Lower if it misses you when you " +
                    "speak quietly; raise if ambient noise keeps triggering responses." +
                    if (saved) " Saved." else "",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            PttSilenceSetting(settings)
        }
    }
}

@Composable
private fun PttSilenceSetting(settings: AppSettings) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(settings.pttSilenceMs.toString()) }
    var saved by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("End-of-turn silence (PTT only)", style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { new ->
                    text = new.filter { it.isDigit() }.take(5)
                    saved = false
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("ms") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
            )
            Button(onClick = {
                val ms = text.toIntOrNull()?.coerceIn(200, 10_000) ?: AppSettings.DEFAULT_PTT_SILENCE_MS
                settings.pttSilenceMs = ms
                text = ms.toString()
                saved = true
                VoiceDroidService.updatePttSilence(ctx)
            }) { Text("Save") }
        }
        Text(
            "Milliseconds of silence after you stop talking before the turn auto-commits. " +
                "Higher = more time to pause mid-thought." + if (saved) " Saved." else "",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PromptsCard(
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val store = remember { PromptStore(ctx) }
    var prompts by remember { mutableStateOf(store.all()) }
    var activeId by remember { mutableStateOf(store.activeId) }
    var editingId by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        prompts = store.all()
        activeId = store.activeId
    }

    val activeName = prompts.firstOrNull { it.id == activeId }?.name ?: "Default"

    CollapsibleCard(
        title = "System prompts",
        expanded = expanded,
        onToggle = onToggle,
        badge = activeName,
    ) {
        prompts.forEach { p ->
            PromptRow(
                prompt = p,
                isActive = p.id == activeId,
                isEditing = editingId == p.id,
                onActivate = {
                    store.activeId = p.id
                    activeId = p.id
                    VoiceDroidService.updateActivePrompt(ctx)
                },
                onToggleEdit = {
                    editingId = if (editingId == p.id) null else p.id
                },
                onClone = {
                    val copy = store.clone(p.id)
                    refresh()
                    if (copy != null) editingId = copy.id
                },
                onDelete = {
                    store.delete(p.id)
                    refresh()
                    if (editingId == p.id) editingId = null
                    // If we deleted the active one, store has fallen back to default;
                    // tell the live session immediately.
                    VoiceDroidService.updateActivePrompt(ctx)
                },
                onSave = { newName, newBody ->
                    store.update(p.id, newName, newBody)
                    refresh()
                    editingId = null
                    if (p.id == activeId) {
                        VoiceDroidService.updateActivePrompt(ctx)
                    }
                },
            )
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val p = store.addNew("Custom")
                refresh()
                editingId = p.id
            },
        ) { Text("+ New prompt") }
    }
}

@Composable
private fun PromptRow(
    prompt: SystemPrompt,
    isActive: Boolean,
    isEditing: Boolean,
    onActivate: () -> Unit,
    onToggleEdit: () -> Unit,
    onClone: () -> Unit,
    onDelete: () -> Unit,
    onSave: (name: String, body: String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isActive) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = isActive, onClick = onActivate)
                Text(
                    prompt.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).clickable { onToggleEdit() },
                )
                if (prompt.isBuiltIn) {
                    Text(
                        "built-in",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                TextButton(onClick = onClone) { Text("Clone") }
                if (!prompt.isBuiltIn) {
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
                TextButton(onClick = onToggleEdit) {
                    Text(if (isEditing) "Close" else if (prompt.isBuiltIn) "View" else "Edit")
                }
            }
            AnimatedVisibility(visible = isEditing) {
                PromptEditor(prompt = prompt, onSave = onSave, onCancel = onToggleEdit)
            }
        }
    }
}

@Composable
private fun PromptEditor(
    prompt: SystemPrompt,
    onSave: (name: String, body: String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(prompt.id) { mutableStateOf(prompt.name) }
    var body by remember(prompt.id) { mutableStateOf(prompt.body) }
    val readOnly = prompt.isBuiltIn

    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { if (!readOnly) name = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            readOnly = readOnly,
            label = { Text("Name") },
        )
        OutlinedTextField(
            value = body,
            onValueChange = { if (!readOnly) body = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            maxLines = 12,
            readOnly = readOnly,
            label = { Text("Instructions") },
        )
        if (readOnly) {
            Text(
                "Built-in prompt is read-only. Use Clone to make an editable copy.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(name, body) }) { Text("Save") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ModeOption(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ConnectionIndicator() {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .alpha(alpha)
                .background(Color(0xFF4CAF50), CircleShape),
        )
        Text(
            "Connection live — billing active",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF4CAF50),
        )
    }
}

@Composable
private fun TokenUsageDisplay(usage: TokenUsage) {
    val ctx = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Cumulative tokens", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { VoiceDroidService.resetUsage(ctx) }) {
                    Text("Reset counters")
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Input", style = MaterialTheme.typography.labelSmall)
                    Text(
                        "${formatTokens(usage.inputTokens)} total",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${formatTokens(usage.inputTextTokens)} text · ${formatTokens(usage.inputAudioTokens)} audio",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (usage.inputCachedTokens > 0) {
                        Text(
                            "${formatTokens(usage.inputCachedTokens)} cached",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Output", style = MaterialTheme.typography.labelSmall)
                    Text(
                        "${formatTokens(usage.outputTokens)} total",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${formatTokens(usage.outputTextTokens)} text · ${formatTokens(usage.outputAudioTokens)} audio",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatTokens(count: Int): String = when {
    count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(count / 1_000.0)
    else -> count.toString()
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (granted) "Granted" else "Not granted",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!granted) {
            Button(onClick = onGrant) { Text("Grant") }
        } else {
            AssistChip(onClick = {}, label = { Text("OK") })
        }
    }
}

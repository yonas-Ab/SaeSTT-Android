package com.sae.stt

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

private val SaeDarkColorScheme = darkColorScheme(
    primary = Color(0xFF58A6FF),
    onPrimary = Color(0xFF061527),
    secondary = Color(0xFF3FB950),
    onSecondary = Color(0xFF041D0A),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = Color(0xFF8B949E),
    outline = Color(0xFF30363D),
    error = Color(0xFFF85149),
    errorContainer = Color(0xFF3D1418),
    onErrorContainer = Color(0xFFFFD8D6),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = SaeDarkColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SaeSttApp()
                }
            }
        }
    }

    @Composable
    private fun SaeSttApp(vm: SaeSttViewModel = viewModel()) {
        val context = LocalContext.current
        val view = LocalView.current
        val state = vm.uiState
        var selectedTab by remember { mutableIntStateOf(0) }
        var pendingSaveWav by remember { mutableStateOf(false) }

        DisposableEffect(state.recording) {
            view.keepScreenOn = state.recording
            onDispose { view.keepScreenOn = false }
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                vm.startRecording(forceSaveWav = pendingSaveWav)
            } else {
                val permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO,
                )
                vm.onPermissionDenied(permanentlyDenied)
            }
            pendingSaveWav = false
        }

        fun requestStartRecording(forceSaveWav: Boolean = false) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                vm.startRecording(forceSaveWav = forceSaveWav)
            } else {
                pendingSaveWav = forceSaveWav
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        "SaeSTT v0.2",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Streaming offline ASR • ${state.selectedModel.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    listOf("Live", "Mic", "Vocab", "Bench").forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) },
                        )
                    }
                }

                if (state.error.isNotBlank()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Error: ${state.error}", color = MaterialTheme.colorScheme.onErrorContainer)
                            if (state.permissionPermanentlyDenied) {
                                TextButton(
                                    onClick = {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.fromParts("package", context.packageName, null),
                                            )
                                        )
                                    },
                                ) { Text("Open App Settings") }
                            }
                        }
                    }
                }

                when (selectedTab) {
                    0 -> TranscribeTab(vm, state, ::requestStartRecording)
                    1 -> MicTestTab(vm, state, ::requestStartRecording)
                    2 -> VocabularyTab(vm, state)
                    3 -> BenchmarkTab(vm, state, ::requestStartRecording)
                }
            }
        }
    }

    @Composable
    private fun TranscribeTab(
        vm: SaeSttViewModel,
        state: SaeSttUiState,
        onStartClick: (Boolean) -> Unit,
    ) {
        val context = LocalContext.current
        val visibleText = state.visibleTranscript
        val animatedLevel by animateFloatAsState(targetValue = state.diagnostics.level, label = "micLevel")
        val transcriptScroll = rememberScrollState()

        LaunchedEffect(visibleText) {
            if (visibleText.isNotEmpty()) transcriptScroll.scrollTo(transcriptScroll.maxValue)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("MODEL", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    AsrModelSpec.entries.forEach { spec ->
                        val isSelected = spec == state.selectedModel
                        val isInstalled = spec in state.installedModels
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = isSelected,
                                enabled = !state.downloading && !state.recording && !state.initializingEngine,
                                onClick = { vm.selectModel(spec) },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(spec.displayName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${spec.subtitle} • ${if (isInstalled) "Installed" else "Not downloaded"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Keep only selected model",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Switch(
                            checked = state.retainOnlySelected,
                            onCheckedChange = { vm.setRetainOnlySelected(it) },
                        )
                    }

                    Text(state.status, style = MaterialTheme.typography.bodySmall)
                    if (state.downloading) {
                        LinearProgressIndicator(
                            progress = { state.downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${state.downloadProgress}%", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!state.modelReady && !state.downloading) {
                            Button(onClick = { vm.downloadSelectedModel() }) { Text("Download model") }
                        }
                        if (state.modelReady && !state.downloading) {
                            TextButton(
                                enabled = !state.recording && !state.initializingEngine,
                                onClick = { vm.redownloadSelectedModel() },
                            ) { Text("Re-download") }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = state.modelReady && !state.downloading && !state.initializingEngine && !state.recording,
                    onClick = { onStartClick(false) },
                ) {
                    if (state.initializingEngine) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Loading…")
                    } else {
                        Text("Start listening")
                    }
                }
                OutlinedButton(
                    enabled = state.recording,
                    onClick = { vm.stopRecording() },
                ) { Text("Stop") }
            }

            if (state.recording) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            if (state.diagnostics.speechActive) "VAD: Speech detected" else "VAD: Listening for speech",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.diagnostics.speechActive) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        val firstTxt = state.firstPartialMs?.let { "1st: ${it}ms" } ?: ""
                        val finalTxt = state.finalizationMs?.let { "Final: ${it}ms" } ?: ""
                        Text(
                            listOf(firstTxt, finalTxt).filter { it.isNotEmpty() }.joinToString(" • "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LinearProgressIndicator(progress = { animatedLevel }, modifier = Modifier.fillMaxWidth())
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("TRANSCRIPT", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        if (visibleText.isNotBlank()) {
                            val words = visibleText.trim().split(Regex("\\s+")).size
                            Text("$words words", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 300.dp)
                            .verticalScroll(transcriptScroll),
                    ) {
                        SelectionContainer {
                            Text(
                                text = visibleText.ifBlank { "Your recognized speech will appear here." },
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (visibleText.isBlank()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = visibleText.isNotBlank(),
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Sae STT transcript", visibleText))
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            },
                        ) { Text("Copy") }

                        OutlinedButton(
                            enabled = visibleText.isNotBlank(),
                            onClick = {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, visibleText)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share transcript"))
                            },
                        ) { Text("Share") }

                        OutlinedButton(
                            enabled = visibleText.isNotBlank(),
                            onClick = { vm.clearTranscript() },
                        ) { Text("Clear") }
                    }
                }
            }
        }
    }

    @Composable
    private fun MicTestTab(
        vm: SaeSttViewModel,
        state: SaeSttUiState,
        onStartClick: (Boolean) -> Unit,
    ) {
        val level by animateFloatAsState(targetValue = state.diagnostics.level, label = "diagLevel")
        val peak by animateFloatAsState(targetValue = state.diagnostics.peak, label = "diagPeak")

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("MICROPHONE & VAD DIAGNOSTICS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Hardware Noise Suppressor")
                            Text(
                                if (state.noiseSuppressionAvailable) "Available on this device" else "Not reported by device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.noiseSuppressionEnabled,
                            enabled = state.noiseSuppressionAvailable && !state.recording,
                            onCheckedChange = { vm.setNoiseSuppression(it) },
                        )
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Save audio as 16 kHz WAV", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = state.saveRecordingWav,
                            enabled = !state.recording,
                            onCheckedChange = { vm.setSaveRecordingWav(it) },
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            enabled = state.modelReady && !state.recording && !state.initializingEngine,
                            onClick = { onStartClick(false) },
                        ) { Text("Start mic test") }
                        OutlinedButton(
                            enabled = state.recording,
                            onClick = { vm.stopRecording() },
                        ) { Text("Stop") }
                    }

                    Text("Live RMS level", style = MaterialTheme.typography.labelSmall)
                    LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())

                    Text("Peak hold", style = MaterialTheme.typography.labelSmall)
                    LinearProgressIndicator(progress = { peak }, modifier = Modifier.fillMaxWidth())

                    Text(
                        "Noise floor: ${String.format(Locale.US, "%.4f", state.diagnostics.noiseFloor)} • " +
                            "VAD: ${if (state.diagnostics.speechActive) "SPEECH" else "SILENCE"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.diagnostics.clipping) {
                        Text("Warning: Input is clipping (move mic slightly further away)", color = MaterialTheme.colorScheme.error)
                    } else if (state.diagnostics.tooQuiet && state.recording) {
                        Text("Warning: Input is very quiet", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    @Composable
    private fun VocabularyTab(vm: SaeSttViewModel, state: SaeSttUiState) {
        var pattern by remember { mutableStateOf("") }
        var replacement by remember { mutableStateOf("") }
        var isRegex by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ADD CORRECTION RULE", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Editable file: ${vm.vocabularyFilePath()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = { pattern = it },
                        label = { Text("Recognized phrase or regex") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = replacement,
                        onValueChange = { replacement = it },
                        label = { Text("Corrected text") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isRegex, onCheckedChange = { isRegex = it })
                        Text("Use regex")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = pattern.isNotBlank() && replacement.isNotBlank(),
                            onClick = {
                                vm.addVocabularyRule(pattern, replacement, isRegex, "Custom")
                                pattern = ""
                                replacement = ""
                            },
                        ) { Text("Add rule") }
                        OutlinedButton(onClick = { vm.resetVocabularyRules() }) { Text("Reset defaults") }
                    }
                }
            }

            state.vocabularyRules.forEach { rule ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${rule.pattern}  →  ${rule.replacement}", fontWeight = FontWeight.SemiBold)
                            if (rule.note.isNotBlank()) {
                                Text(rule.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        TextButton(onClick = { vm.deleteVocabularyRule(rule.id) }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun BenchmarkTab(
        vm: SaeSttViewModel,
        state: SaeSttUiState,
        onStartClick: (Boolean) -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("STEP 1: RECORD TEST CLIP", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Record one of the benchmark sentences once, then compare both installed models on the exact same WAV.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.referenceText,
                        onValueChange = { vm.setReferenceText(it) },
                        label = { Text("Reference sentence (for WER)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = state.modelReady && !state.recording && !state.benchmarking,
                            onClick = { onStartClick(true) },
                        ) { Text("Record WAV clip") }
                        OutlinedButton(
                            enabled = state.recording,
                            onClick = { vm.stopRecording() },
                        ) { Text("Stop") }
                        OutlinedButton(onClick = {
                            val idx = BenchmarkSuite.prompts.indexOfFirst { it.text == state.referenceText }
                            val next = BenchmarkSuite.prompts[(idx + 1) % BenchmarkSuite.prompts.size]
                            vm.setReferenceText(next.text)
                        }) { Text("Next prompt") }
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("STEP 2: SELECT WAV & COMPARE MODELS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    if (state.recordings.isEmpty()) {
                        Text(
                            "No WAV recordings yet in ${vm.recordingsDirectoryPath()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.recordings.take(5).forEach { file ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = file.absolutePath == state.selectedWavPath,
                                    onClick = { vm.selectRecording(file.absolutePath) },
                                )
                                Text(
                                    "${file.name} (${ModelManager.formatBytes(file.length())})",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(onClick = { vm.deleteRecording(file) }) {
                                    Text("Del", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    Button(
                        enabled = state.selectedWavPath != null && !state.benchmarking && !state.recording,
                        onClick = { vm.runBenchmark() },
                    ) {
                        if (state.benchmarking) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Benchmarking…")
                        } else {
                            Text("Run benchmark (${state.installedModels.size} installed)")
                        }
                    }
                }
            }

            state.benchmarkResults.forEach { res ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(res.model.displayName, fontWeight = FontWeight.Bold)
                        val werStr = res.wer?.let {
                            String.format(Locale.US, "WER: %.1f%% (S:%d D:%d I:%d)", it.werPercent, it.substitutions, it.deletions, it.insertions)
                        } ?: "WER: n/a"
                        Text(werStr, color = MaterialTheme.colorScheme.primary)
                        Text(
                            String.format(
                                Locale.US,
                                "Wall: %d ms • CPU: %d ms • RTF: %.2fx • Native RAM: %.1f MB",
                                res.wallMs,
                                res.cpuMs,
                                res.rtf,
                                res.peakNativeRamMb,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (res.targetNamesMatched.isNotEmpty()) {
                            Text(
                                "Names matched: ${res.targetNamesMatched.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        Text("Output: ${res.correctedTranscript}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

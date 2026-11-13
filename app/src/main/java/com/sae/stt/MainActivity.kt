package com.sae.stt

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                SaeSttScreen()
            }
        }
    }

    @Composable
    private fun SaeSttScreen(vm: SaeSttViewModel = viewModel()) {
        val context = LocalContext.current
        val view = LocalView.current
        val state = vm.uiState
        val visibleText = state.visibleTranscript

        DisposableEffect(state.recording) {
            view.keepScreenOn = state.recording
            onDispose { view.keepScreenOn = false }
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                vm.startRecording()
            } else {
                val permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO,
                )
                vm.onPermissionDenied(permanentlyDenied)
            }
        }

        val animatedLevel by animateFloatAsState(targetValue = state.audioLevel, label = "micLevel")
        val transcriptScroll = rememberScrollState()

        LaunchedEffect(visibleText) {
            if (visibleText.isNotEmpty()) {
                transcriptScroll.scrollTo(transcriptScroll.maxValue)
            }
        }

        Scaffold { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "Sae STT",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Offline English speech-to-text tester",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "MODEL STATUS",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            if (state.modelSizeText.isNotBlank()) {
                                Text(
                                    state.modelSizeText,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(state.status)
                        if (state.downloading) {
                            LinearProgressIndicator(
                                progress = { state.downloadProgress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("${state.downloadProgress}%", style = MaterialTheme.typography.bodySmall)
                        }
                        if (!state.modelReady && !state.downloading) {
                            Button(onClick = { vm.downloadModel() }) { Text("Download / retry") }
                        }
                        if (state.modelReady) {
                            Text(
                                "Model directory:",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(vm.modelDirectoryPath(), style = MaterialTheme.typography.bodySmall)
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "First download only. Works fully offline.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    enabled = !state.recording && !state.initializingEngine,
                                    onClick = { vm.redownloadModel() },
                                ) {
                                    Text("Re-download")
                                }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            enabled = state.modelReady && !state.downloading && !state.initializingEngine && !state.recording,
                            onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    vm.startRecording()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                        ) {
                            if (state.initializingEngine) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Loading…")
                            } else {
                                Text("Start recording")
                            }
                        }
                        OutlinedButton(
                            enabled = state.recording,
                            onClick = { vm.stopRecording() },
                        ) { Text("Stop") }
                    }

                    if (state.recording) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Microphone level", style = MaterialTheme.typography.labelSmall)
                            LinearProgressIndicator(
                                progress = { animatedLevel },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "TRANSCRIPT",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            if (visibleText.isNotBlank()) {
                                val words = visibleText.trim().split(Regex("\\s+")).size
                                Text(
                                    "$words words",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 320.dp)
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
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Sae STT transcript", visibleText))
                                    Toast.makeText(context, "Transcript copied", Toast.LENGTH_SHORT).show()
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

                if (state.error.isNotBlank()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Error: ${state.error}",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            if (state.permissionPermanentlyDenied) {
                                TextButton(
                                    onClick = {
                                        val intent = Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null),
                                        )
                                        context.startActivity(intent)
                                    },
                                ) {
                                    Text("Open App Settings")
                                }
                            }
                        }
                    }
                }

                Text(
                    "English Zipformer 20M INT8 • 16 kHz mono • CPU • 2 threads",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Model files: Android/data/com.sae.stt/files/models/",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

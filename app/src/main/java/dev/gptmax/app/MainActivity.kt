package dev.gptmax.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: GpTMaxViewModel

    private val modelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importAndLoadModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[GpTMaxViewModel::class.java]
        setContent {
            val state by viewModel.state.collectAsState()
            MaterialTheme {
                GpTMaxHome(
                    state = state,
                    onPromptChange = viewModel::updatePrompt,
                    onTemperatureChange = viewModel::updateTemperature,
                    onPickModel = { modelPicker.launch(arrayOf("*/*")) },
                    onGenerate = viewModel::generate,
                    onCancel = viewModel::cancelGeneration,
                    onRefresh = viewModel::refreshRuntimeInfo,
                    onDeleteModel = viewModel::deleteModel,
                    onUnloadModel = viewModel::unloadModel,
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun GpTMaxHome(
    state: InferenceUiState,
    onPromptChange: (String) -> Unit,
    onTemperatureChange: (Float) -> Unit,
    onPickModel: () -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onRefresh: () -> Unit,
    onDeleteModel: (String) -> Unit,
    onUnloadModel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("GpTMax", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Local-first AI coding studio",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Native inference runtime", style = MaterialTheme.typography.titleLarge)
                    Text(state.nativeStatus, style = MaterialTheme.typography.bodyMedium)
                    Text(state.buildInfo, style = MaterialTheme.typography.bodySmall)
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Local GGUF model", style = MaterialTheme.typography.titleLarge)
                    Text(state.modelDetails, style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = onPickModel,
                        enabled = !state.isBusy && !state.isGenerating,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.isBusy) "Please wait…" else "Import and load GGUF model")
                    }
                    if (state.modelReady) {
                        Button(
                            onClick = onUnloadModel,
                            enabled = !state.isBusy && !state.isGenerating,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Unload model (keep file)") }
                    }
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Local inference", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = state.prompt,
                        onValueChange = onPromptChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Prompt") },
                        minLines = 3,
                        enabled = !state.isGenerating,
                    )
                    Text("Temperature: ${"%.1f".format(state.temperature)}")
                    Slider(
                        value = state.temperature,
                        onValueChange = onTemperatureChange,
                        valueRange = 0f..1.5f,
                        enabled = !state.isGenerating,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onGenerate,
                            enabled = state.modelReady && !state.isBusy && !state.isGenerating && state.prompt.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) { Text("Generate") }
                        Button(
                            onClick = onCancel,
                            enabled = state.isGenerating,
                            modifier = Modifier.weight(1f),
                        ) { Text("Stop") }
                    }
                    Text("Output", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.generatedText.ifBlank { "Generated tokens will appear here as the model responds." },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Stored models", style = MaterialTheme.typography.titleLarge)
                    if (state.storedModels.isEmpty()) {
                        Text("No imported models.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        state.storedModels.forEach { model ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(model.displayName, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${model.sizeBytes} bytes · ${model.sha256.take(12)}…",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (model.sha256 == state.activeModelSha256) {
                                        Text("Currently loaded", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Button(
                                    onClick = { onDeleteModel(model.sha256) },
                                    enabled = !state.isBusy && !state.isGenerating,
                                ) { Text("Delete") }
                            }
                        }
                    }
                }
            }
            Text("Platform foundations", style = MaterialTheme.typography.titleMedium)
            FeatureRow("Model inference", "llama.cpp · native generation")
            FeatureRow("Agent runtime", "Not connected yet")
            FeatureRow("Code workspace", "Not implemented yet")
            FeatureRow("Managed terminal", "Not implemented yet")
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onRefresh,
                enabled = !state.isBusy && !state.isGenerating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Refresh native status")
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun FeatureRow(title: String, status: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

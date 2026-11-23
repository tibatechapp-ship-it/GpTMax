package dev.gptmax.app

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import java.util.Locale
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: GpTMaxViewModel
    private var pendingExpectedModelSha256: String? = null

    private val workspacePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.selectWorkspace(uri)
        }
    }

    private val modelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val expectedSha256 = pendingExpectedModelSha256
        pendingExpectedModelSha256 = null
        if (uri != null) viewModel.importAndLoadModel(uri, expectedSha256)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[GpTMaxViewModel::class.java]
        setContent {
            val state by viewModel.state.collectAsState()
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                MaterialTheme {
                    GpTMaxHome(
                    state = state,
                    onPromptChange = viewModel::updatePrompt,
                    onTemperatureChange = viewModel::updateTemperature,
                    onPickModel = {
                        pendingExpectedModelSha256 = null
                        modelPicker.launch(arrayOf("*/*"))
                    },
                    onPickVerifiedTestModel = {
                        pendingExpectedModelSha256 = TestModelCatalog.SHA256
                        modelPicker.launch(arrayOf("*/*"))
                    },
                    onDownloadTestModel = {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TestModelCatalog.DOWNLOAD_URL)))
                    },
                    onGenerate = viewModel::generate,
                    onCancel = viewModel::cancelGeneration,
                    onRefresh = viewModel::refreshRuntimeInfo,
                    onDeleteModel = viewModel::deleteModel,
                    onLoadModel = viewModel::loadStoredModel,
                    onUnloadModel = viewModel::unloadModel,
                    onAgentRequestChange = viewModel::updateAgentRequest,
                    onPickWorkspace = { workspacePicker.launch(null) },
                    onRunAgent = viewModel::runAgent,
                    onStopAgent = viewModel::stopAgent,
                    onAgentWriteToggle = viewModel::setAgentWriteEnabled,
                    )
                }
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
    onPickVerifiedTestModel: () -> Unit,
    onDownloadTestModel: () -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onRefresh: () -> Unit,
    onDeleteModel: (String) -> Unit,
    onLoadModel: (String) -> Unit,
    onUnloadModel: () -> Unit,
    onAgentRequestChange: (String) -> Unit,
    onPickWorkspace: () -> Unit,
    onRunAgent: () -> Unit,
    onStopAgent: () -> Unit,
    onAgentWriteToggle: (Boolean) -> Unit,
) {
    var pendingDeletion by remember { mutableStateOf<StoredModel?>(null) }
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
                    Text("Recommended M1 test model", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${TestModelCatalog.DISPLAY_NAME} · 491 MB · Apache-2.0",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "The model is downloaded separately. Import verifies the published SHA-256 before native loading.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onDownloadTestModel,
                        enabled = !state.isBusy && !state.isGenerating,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Download test model") }
                    Button(
                        onClick = onPickVerifiedTestModel,
                        enabled = !state.isBusy && !state.isGenerating,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Import and verify downloaded Qwen GGUF") }
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
                    Text("Temperature: ${String.format(Locale.US, "%.1f", state.temperature)}")
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
                                Column {
                                    TextButton(
                                        onClick = { onLoadModel(model.sha256) },
                                        enabled = !state.isBusy && !state.isGenerating &&
                                            model.sha256 != state.activeModelSha256,
                                    ) {
                                        Text(if (model.sha256 == state.activeModelSha256) "Loaded" else "Load")
                                    }
                                    TextButton(
                                        onClick = { pendingDeletion = model },
                                        enabled = !state.isBusy && !state.isGenerating,
                                    ) { Text("Delete") }
                                }
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
        pendingDeletion?.let { model ->
            AlertDialog(
                onDismissRequest = { pendingDeletion = null },
                title = { Text("Delete stored model?") },
                text = {
                    Text(
                        "This permanently deletes ${model.displayName} " +
                            "(${model.sizeBytes} bytes). The file cannot be recovered."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDeleteModel(model.sha256)
                            pendingDeletion = null
                        },
                        enabled = !state.isBusy && !state.isGenerating,
                    ) { Text("Delete permanently") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeletion = null }) { Text("Cancel") }
                },
            )
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

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var nativeStatus by mutableStateOf("Initializing native runtime…")
    private var buildInfo by mutableStateOf("Reading backend version…")
    private var modelDetails by mutableStateOf("No model selected")
    private var isBusy by mutableStateOf(false)

    private val modelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) importAndLoadModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { NativeBridge.nativeBuildInfo() }
                    .getOrElse { "Native backend unavailable: ${it.message ?: "unknown error"}" }
            }
            buildInfo = info
            nativeStatus = withContext(Dispatchers.IO) {
                runCatching { NativeBridge.backendStatus() }
                    .getOrElse { "Native backend unavailable: ${it.message ?: "unknown error"}" }
            }
        }
        setContent {
            MaterialTheme {
                GpTMaxHome(
                    nativeStatus = nativeStatus,
                    buildInfo = buildInfo,
                    modelDetails = modelDetails,
                    isBusy = isBusy,
                    onPickModel = { modelPicker.launch(arrayOf("*/*")) },
                    onRefresh = { refreshNativeStatus() },
                )
            }
        }
    }

    private fun refreshNativeStatus() {
        lifecycleScope.launch {
            nativeStatus = withContext(Dispatchers.IO) {
                runCatching { NativeBridge.backendStatus() }
                    .getOrElse { "Native backend error: ${it.message ?: "unknown error"}" }
            }
        }
    }

    private fun importAndLoadModel(uri: Uri) {
        lifecycleScope.launch {
            isBusy = true
            modelDetails = "Importing GGUF securely into app-private storage…"
            try {
                val imported = ModelFileImporter.import(this@MainActivity, uri)
                modelDetails = buildString {
                    append(imported.displayName)
                    append("\nSize: ")
                    append(imported.sizeBytes)
                    append(" bytes\nSHA-256: ")
                    append(imported.sha256)
                }
                nativeStatus = "Loading model and creating inference context…"
                val loadResult = withContext(Dispatchers.IO) {
                    NativeBridge.nativeLoadModel(imported.file.absolutePath)
                }
                nativeStatus = if (loadResult == "MODEL_READY") {
                    "GGUF loaded; inference context ready."
                } else {
                    "Model load failed: $loadResult"
                }
            } catch (error: Exception) {
                nativeStatus = "Model import/load failed: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                isBusy = false
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun GpTMaxHome(
    nativeStatus: String,
    buildInfo: String,
    modelDetails: String,
    isBusy: Boolean,
    onPickModel: () -> Unit,
    onRefresh: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
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
                    Text(nativeStatus, style = MaterialTheme.typography.bodyMedium)
                    Text(buildInfo, style = MaterialTheme.typography.bodySmall)
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
                    Text(modelDetails, style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = onPickModel,
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (isBusy) "Please wait…" else "Import and load GGUF model")
                    }
                }
            }
            Text("Platform foundations", style = MaterialTheme.typography.titleMedium)
            FeatureRow("Model inference", "llama.cpp · Android ARM64")
            FeatureRow("Agent runtime", "Not connected yet")
            FeatureRow("Code workspace", "Not implemented yet")
            FeatureRow("Managed terminal", "Not implemented yet")
            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = onRefresh, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
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

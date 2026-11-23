package com.tibatech.gptmax.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tibatech.gptmax.data.repo.DownloadState
import java.util.Locale

@Composable
fun ModelsScreen(viewModel: ModelsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val installed by viewModel.installed.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val engineLoaded by viewModel.engineLoaded.collectAsState()
    val engineError by viewModel.engineError.collectAsState()
    val loadedModel by viewModel.loadedModel.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---- engine status ------------------------------------------------------
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (engineLoaded) "Engine: loaded" else "Engine: not loaded",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (loadedModel.isNotBlank()) {
                        Text(
                            loadedModel.substringAfterLast('/'),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (engineError.isNotBlank()) {
                        Text(engineError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (engineLoaded) {
                        OutlinedButton(onClick = { viewModel.unload() }) { Text("Unload engine") }
                    }
                }
            }
        }

        // ---- installed ----------------------------------------------------------
        if (installed.isNotEmpty()) {
            item { SectionTitle("Installed GGUF files") }
            items(installed.size) { i ->
                val file = installed[i]
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Text(
                                formatGb(file.length()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!engineLoaded) {
                            Button(onClick = { viewModel.load(file) }) { Text("Load") }
                        }
                        OutlinedButton(onClick = { viewModel.delete(file) }) { Text("Del") }
                    }
                }
            }
        }

        // ---- catalog ------------------------------------------------------------
        item { SectionTitle("Catalog — Qwen2.5-Coder / SmolLM2 (Q4_K_M)") }
        items(viewModel.catalog.size) { i ->
            val entry = viewModel.catalog[i]
            val state = downloads[entry.id]
            val isInstalled = installed.any { it.name.equals(entry.fileName, ignoreCase = true) }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entry.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${entry.role} · ≈${formatGb(entry.sizeBytes)} · needs ${entry.minRamGb}GB+ RAM",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (state) {
                        is DownloadState.Progress -> {
                            LinearProgressIndicator(
                                progress = { state.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${formatGb(state.bytes)} / ${formatGb(state.total)}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        is DownloadState.Error -> Text(
                            "download failed: ${state.message}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        else -> {
                            if (!isInstalled) {
                                Button(onClick = { viewModel.download(entry) }) {
                                    Text("Download")
                                }
                            } else {
                                Text(
                                    "installed",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                "Beyond ~8GB RAM: route plan/validate tasks to your BYOK cloud key " +
                    "(Settings) — GLM Coding Plan, DeepSeek, OpenRouter or an Ollama PC on LAN. " +
                    "Agentic-scale models such as Qwen3-Coder-30B-A3B (MoE, 3.3B active) run on " +
                    "that tier, not on the phone itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 6.dp),
    )
}

private fun formatGb(bytes: Long): String =
    String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)

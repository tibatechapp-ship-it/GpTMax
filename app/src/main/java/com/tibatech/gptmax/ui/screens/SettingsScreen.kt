package com.tibatech.gptmax.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tibatech.gptmax.core.router.ProviderConfig

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val state by viewModel.state.collectAsState()
    val presets = viewModel.presets

    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var nCtx by remember { mutableStateOf("4096") }
    var nThreads by remember { mutableStateOf("4") }

    LaunchedEffect(state) {
        state?.let { s ->
            name = s.provider.name
            baseUrl = s.provider.baseUrl
            apiKey = s.provider.apiKey
            model = s.provider.model
            nCtx = s.nCtx.toString()
            nThreads = s.nThreads.toString()
        }
    }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let { viewModel.applyWorkspace(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---- workspace ---------------------------------------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Workspace", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Grant access to a project folder (SAF). Agent tools and the editor " +
                        "operate only inside it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { treePicker.launch(null) }) { Text("Choose folder…") }
            }
        }

        // ---- BYOK cloud bridge ---------------------------------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cloud bridge — BYOK", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { preset ->
                        OutlinedButton(
                            onClick = {
                                name = preset.name
                                baseUrl = preset.baseUrl
                                model = preset.model
                            },
                        ) { Text(preset.name.substringBefore(' ')) }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Provider name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL (OpenAI-compatible)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key (stays on device)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model id") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        viewModel.saveProvider(
                            ProviderConfig(
                                name = name.ifBlank { "Custom" },
                                baseUrl = baseUrl.trim(),
                                apiKey = apiKey.trim(),
                                model = model.trim(),
                            ),
                        )
                    },
                ) { Text("Save provider") }
            }
        }

        // ---- routing switches ------------------------------------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Routing", style = MaterialTheme.typography.titleMedium)
                SwitchRow(
                    title = "Cloud-first planning & validation",
                    subtitle = "Plan/validate steps use the BYOK provider; chat stays local.",
                    checked = state?.cloudFirst ?: false,
                    onChange = viewModel::setCloudFirst,
                )
                SwitchRow(
                    title = "Auto-approve file writes",
                    subtitle = "Off (recommended): every write/delete waits for your approval.",
                    checked = state?.autoApproveWrites ?: false,
                    onChange = viewModel::setAutoApprove,
                )
            }
        }

        // ---- engine tuning -----------------------------------------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Local engine", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nCtx,
                        onValueChange = { nCtx = it.filter { c -> c.isDigit() } },
                        label = { Text("Context") },
                        modifier = Modifier.width(140.dp),
                    )
                    OutlinedTextField(
                        value = nThreads,
                        onValueChange = { nThreads = it.filter { c -> c.isDigit() } },
                        label = { Text("Threads") },
                        modifier = Modifier.width(140.dp),
                    )
                }
                Button(
                    onClick = {
                        val ctx = nCtx.toIntOrNull()?.coerceIn(1024, 32768) ?: 4096
                        val thr = nThreads.toIntOrNull()?.coerceIn(1, 12) ?: 4
                        viewModel.setEngineTuning(ctx, thr)
                    },
                ) { Text("Apply") }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

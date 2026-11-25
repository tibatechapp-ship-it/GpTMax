package com.tibatech.gptmax.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun EditorScreen(viewModel: EditorViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val files by viewModel.files.collectAsState()
    val openFile by viewModel.openFile.collectAsState()
    val content by viewModel.content.collectAsState()
    val dirty by viewModel.dirty.collectAsState()
    val hasRoot by viewModel.hasRoot.collectAsState()
    // Phase 6 (external review): save failures MUST be visible — a silent
    // failure made a lost edit look saved.
    val saveError by viewModel.saveError.collectAsState()
    val saving by viewModel.saving.collectAsState()

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        if (!hasRoot) {
            Card(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                Text(
                    "No workspace yet.\nOpen Settings → Workspace and grant access to a project " +
                        "folder. The agent and the editor operate inside that folder only.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = openFile.ifBlank { "no file open" },
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            if (openFile.isNotBlank()) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.padding(6.dp).height(24.dp))
                }
                Button(onClick = { viewModel.save() }, enabled = dirty && !saving) {
                    Text(if (dirty) "Save" else "Saved")
                }
            }
        }

        // Phase 6: explicit, dismissible failure banner — the unsaved flag
        // stays true until the user saves successfully.
        if (saveError.isNotBlank()) {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                onClick = { viewModel.dismissSaveError() },
            ) {
                Text(
                    "SAVE FAILED: $saveError (tap to dismiss)",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        if (openFile.isBlank()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(files.size) { i ->
                    val f = files[i]
                    Card(onClick = { viewModel.open(f.path) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            f.path,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = content,
                onValueChange = viewModel::onContentChange,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            )
            Button(
                onClick = {
                    viewModel.openFile.value = ""
                },
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
            ) { Text("Close file") }
        }
    }
}

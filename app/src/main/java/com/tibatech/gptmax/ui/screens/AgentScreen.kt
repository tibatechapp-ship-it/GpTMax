package com.tibatech.gptmax.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tibatech.gptmax.core.agent.AgentPhase
import com.tibatech.gptmax.core.agent.AgentStep
import com.tibatech.gptmax.core.agent.ResumableRun

@Composable
fun AgentScreen(viewModel: AgentViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val phase by viewModel.phase.collectAsState()
    val steps by viewModel.steps.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val approval by viewModel.approvalRequest.collectAsState()
    val running by viewModel.running.collectAsState()
    val perf by viewModel.perf.collectAsState()
    val resumable by viewModel.resumable.collectAsState()
    var goal by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().imePadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---- goal composer -----------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = goal,
                onValueChange = { goal = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Describe a coding task…") },
                maxLines = 3,
            )
            Button(
                onClick = {
                    viewModel.start(goal)
                    goal = ""
                },
                enabled = phase == AgentPhase.IDLE || phase == AgentPhase.DONE ||
                    phase == AgentPhase.FAILED || phase == AgentPhase.PARTIAL ||
                    phase == AgentPhase.BLOCKED || phase == AgentPhase.VERIFY_FAILED,
            ) { Text("Run") }
            // Review #5: a visible Stop control wired to per-request cancellation.
            OutlinedButton(
                onClick = { viewModel.stop() },
                enabled = running,
            ) { Text("Stop") }
        }

        PhaseChip(phase)

        // ---- perf measurements (review #6): newest few, compact ---------------
        if (perf.isNotEmpty()) {
            Text(
                perf.takeLast(4).joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---- resume banner (phase 3, resumability) -------------------------
            if (!running) {
                resumable?.let { snap ->
                    item {
                        ResumableRunCard(
                            snapshot = snap,
                            onResume = { viewModel.resume() },
                            onDiscard = { viewModel.dismissResume() },
                        )
                    }
                }
            }

            // ---- approval gate ---------------------------------------------------
            approval?.let { req ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Pending, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    " Approval required — ${req.step.tool}",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                            Text(
                                req.diffPreview,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(8.dp),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.respond(true) }) { Text("Approve") }
                                OutlinedButton(onClick = { viewModel.respond(false) }) { Text("Reject") }
                            }
                        }
                    }
                }
            }

            // ---- plan steps -------------------------------------------------------
            items(steps.size) { i ->
                val step = steps[i]
                StepCard(step)
            }

            // ---- log ---------------------------------------------------------------
            items(logs.size) { i ->
                Text(
                    logs[i],
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ResumableRunCard(
    snapshot: ResumableRun,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Interrupted run found — " +
                    "${snapshot.steps.count { it.status == "DONE" }}/${snapshot.steps.size} steps done",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                snapshot.goal.take(120),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onResume) { Text("Resume") }
                OutlinedButton(onClick = onDiscard) { Text("Discard") }
            }
        }
    }
}

@Composable
private fun PhaseChip(phase: AgentPhase) {
    val color = when (phase) {
        AgentPhase.DONE -> Color(0xFF66BB6A)
        AgentPhase.FAILED -> MaterialTheme.colorScheme.error
        AgentPhase.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        // Phase 5 (M5 honest outcomes): non-success terminal states are
        // visually distinct from DONE — amber for partial/unverified, red
        // for blocked and verification-failed.
        AgentPhase.PARTIAL -> Color(0xFFFFB74D)
        AgentPhase.BLOCKED -> Color(0xFFE57373)
        AgentPhase.VERIFY_FAILED -> Color(0xFFFFA726)
        else -> MaterialTheme.colorScheme.primary
    }
    Text(
        text = "phase: ${phase.name.lowercase().replace('_', ' ')}",
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.padding(start = 14.dp),
    )
}

@Composable
private fun StepCard(step: AgentStep) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = when (step.status) {
                AgentStep.Status.PENDING -> Icons.Filled.Pending
                AgentStep.Status.RUNNING -> Icons.Filled.Pending
                AgentStep.Status.DONE -> Icons.Filled.CheckCircle
                AgentStep.Status.NEEDS_APPROVAL -> Icons.Filled.Pending
                AgentStep.Status.REJECTED -> Icons.Filled.Block
                AgentStep.Status.FAILED -> Icons.Filled.Error
            }
            val tint = when (step.status) {
                AgentStep.Status.DONE -> Color(0xFF66BB6A)
                AgentStep.Status.FAILED -> MaterialTheme.colorScheme.error
                AgentStep.Status.REJECTED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
            Icon(icon, contentDescription = null, tint = tint)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (step.isWrite) {
                        Icon(
                            imageVector = when (step.tool) {
                                "delete_file" -> Icons.Filled.Delete
                                "run_command" -> Icons.Filled.Terminal
                                else -> Icons.Filled.Edit
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    Text(step.description, style = MaterialTheme.typography.bodyMedium)
                }
                if (step.result.isNotBlank()) {
                    Text(
                        step.result.take(160),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

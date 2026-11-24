package com.tibatech.gptmax.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tibatech.gptmax.core.router.Source

@Composable
fun ChatScreen(viewModel: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val messages by viewModel.messages.collectAsState()
    val streamText by viewModel.streamText.collectAsState()
    val streaming by viewModel.streaming.collectAsState()
    val badge by viewModel.badge.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, streamText.length) {
        val count = messages.size + if (streaming && streamText.isNotEmpty()) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        // ---- streaming bubble ------------------------------------------------
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty() && !streaming) {
                item {
                    Text(
                        "GpTMax — Local-first AI coding studio.\n" +
                            "Chat runs fully offline once a GGUF model is loaded (Models tab). " +
                            "Cloud answers appear through your BYOK key when configured.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(messages) { m ->
                MessageBubble(role = m.role, content = m.content, source = m.source)
            }
            if (streaming && streamText.isNotEmpty()) {
                item { MessageBubble(role = "assistant", content = streamText, streaming = true) }
            }
        }

        // ---- composer ----------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceBadge(badge)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask GpTMax…") },
                maxLines = 5,
            )
            IconButton(
                onClick = {
                    if (streaming) {
                        viewModel.cancel()
                    } else {
                        viewModel.send(input)
                        input = ""
                    }
                },
            ) {
                Icon(
                    imageVector = if (streaming) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (streaming) "Stop" else "Send",
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(role: String, content: String, source: String = "", streaming: Boolean = false) {
    val isUser = role == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
            shape = RoundedCornerShape(14.dp, 14.dp, if (isUser) 4.dp else 14.dp, if (isUser) 14.dp else 4.dp),
        ) {
            Box(modifier = Modifier.widthIn(max = 320.dp).padding(12.dp)) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (isUser) FontFamily.Default else FontFamily.Default,
                )
            }
        }
        if (!isUser && (source.isNotEmpty() || streaming)) {
            Text(
                text = when {
                    source == Source.LOCAL.name -> "local engine"
                    source == Source.CLOUD.name -> "cloud (BYOK)"
                    streaming -> "streaming…"
                    else -> ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, start = 6.dp),
            )
        }
    }
}

@Composable
private fun SourceBadge(source: Source?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Icon(
            imageVector = if (source == Source.CLOUD) Icons.Filled.Cloud else Icons.Filled.Memory,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = when (source) {
                Source.CLOUD -> "cloud"
                Source.LOCAL -> "local"
                null -> "auto"
            },
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

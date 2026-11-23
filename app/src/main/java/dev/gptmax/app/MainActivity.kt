package dev.gptmax.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                GpTMaxHome()
            }
        }
    }
}

@Composable
private fun GpTMaxHome() {
    val nativeStatus = remember {
        mutableStateOf(
            runCatching { NativeBridge.backendStatus() }
                .getOrElse { "Native backend unavailable: ${it.message ?: "unknown error"}" },
        )
    }
    val buildInfo = remember {
        mutableStateOf(
            runCatching { NativeBridge.nativeBuildInfo() }
                .getOrElse { "Build information unavailable" },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
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
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Native runtime foundation", style = MaterialTheme.typography.titleLarge)
                    Text(nativeStatus.value, style = MaterialTheme.typography.bodyMedium)
                    Text(buildInfo.value, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Foundation modules", style = MaterialTheme.typography.titleMedium)
            FeatureRow("Model inference", "Native JNI boundary prepared")
            FeatureRow("Agent runtime", "Planned — not connected yet")
            FeatureRow("Code workspace", "Planned — editor foundation")
            FeatureRow("Terminal tools", "Planned — managed execution")
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    nativeStatus.value = runCatching { NativeBridge.backendStatus() }
                        .getOrElse { "Native backend error: ${it.message ?: "unknown error"}" }
                    buildInfo.value = runCatching { NativeBridge.nativeBuildInfo() }
                        .getOrElse { "Build information unavailable" }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Refresh native status")
            }
        }
    }
}

@Composable
private fun FeatureRow(title: String, status: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(status, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

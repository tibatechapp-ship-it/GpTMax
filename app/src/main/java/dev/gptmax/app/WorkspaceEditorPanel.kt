package dev.gptmax.app

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class BrowserItem(val name: String, val path: String, val isDirectory: Boolean, val size: Long)

@Composable
internal fun WorkspaceEditorPanel(treeUri: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var directory by remember(treeUri) { mutableStateOf("") }
    var refreshKey by remember(treeUri) { mutableIntStateOf(0) }
    var entries by remember(treeUri) { mutableStateOf(emptyList<BrowserItem>()) }
    var selectedPath by remember(treeUri) { mutableStateOf<String?>(null) }
    var editorText by remember(treeUri) { mutableStateOf("") }
    var originalText by remember(treeUri) { mutableStateOf("") }
    var dirty by remember(treeUri) { mutableStateOf(false) }
    var busy by remember(treeUri) { mutableStateOf(false) }
    var status by remember(treeUri) { mutableStateOf("Select a project folder to browse files.") }
    var showCreateDialog by remember(treeUri) { mutableStateOf(false) }
    var newFileName by remember(treeUri) { mutableStateOf("") }

    LaunchedEffect(treeUri, directory, refreshKey) {
        if (treeUri == null) {
            entries = emptyList()
            status = "Choose a project folder first."
        } else {
            status = "Loading directory…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                        ?: error("Invalid project folder.")
                    var folder = root
                    if (directory.isNotBlank()) {
                        directory.split('/').forEach { segment ->
                            folder = folder.findFile(segment) ?: error("Folder not found.")
                        }
                    }
                    require(folder.isDirectory) { "Selected path is not a directory." }
                    folder.listFiles()
                        .filter { item ->
                            val name = item.name.orEmpty()
                            name.isNotBlank() &&
                                name !in setOf(".git", ".gradle", "node_modules", "build", ".idea") &&
                                !name.endsWith(".pem", true) &&
                                !name.endsWith(".key", true) &&
                                name.lowercase() !in setOf(".env", ".env.local", ".npmrc", ".netrc", "credentials.json", "secrets.json")
                        }
                        .sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name.orEmpty().lowercase() })
                        .take(300)
                        .map { item ->
                            val name = item.name.orEmpty()
                            BrowserItem(name, if (directory.isBlank()) name else directory + "/" + name, item.isDirectory, item.length())
                        }
                }
            }
            result.onSuccess { entries = it; status = it.size.toString() + " entries loaded." }
                .onFailure { status = "Browse failed: " + (it.message ?: it.javaClass.simpleName) }
        }
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Code workspace", style = MaterialTheme.typography.titleLarge)
            Text(directory.ifBlank { "/" }, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (directory.isNotBlank()) directory = directory.substringBeforeLast('/', "") },
                    enabled = directory.isNotBlank() && !busy,
                    modifier = Modifier.weight(1f)
                ) { Text("Up") }
                TextButton(onClick = { directory = "" }, enabled = directory.isNotBlank() && !busy) { Text("Root") }
                TextButton(onClick = { newFileName = ""; showCreateDialog = true }, enabled = treeUri != null && !busy) { Text("New file") }
            }

            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                items(entries, key = { it.path }) { entry ->
                    TextButton(
                        onClick = {
                            if (entry.isDirectory) {
                                if (!dirty) directory = entry.path
                                else status = "Save or close current edits before changing folders."
                            } else if (!dirty) {
                                scope.launch {
                                    busy = true
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            require(entry.size <= 256 * 1024L) { "File exceeds 256 KiB editor limit." }
                                            val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri!!)) ?: error("Invalid project folder.")
                                            var file = root
                                            entry.path.split('/').forEach { file = file.findFile(it) ?: error("File not found.") }
                                            require(file.isFile) { "Not a regular file." }
                                            val input = context.contentResolver.openInputStream(file.uri) ?: error("Cannot read file.")
                                            val value = input.use { it.readBytes().toString(Charsets.UTF_8) }
                                            require(!value.contains('\u0000')) { "Binary files cannot be opened." }
                                            value
                                        }
                                    }
                                    result.onSuccess { editorText = it; originalText = it; selectedPath = entry.path; dirty = false; status = "Opened " + entry.path }
                                        .onFailure { status = "Open failed: " + (it.message ?: it.javaClass.simpleName) }
                                    busy = false
                                }
                            } else status = "Save or close current edits before opening another file."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text((if (entry.isDirectory) "▸ " else "  ") + entry.name + (if (entry.isDirectory) "/" else " · " + entry.size + " B"))
                    }
                }
            }

            selectedPath?.let { filePath ->
                Text("Editing: " + filePath, style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = editorText,
                    onValueChange = { editorText = it; dirty = true },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 18,
                    enabled = !busy,
                    label = { Text("File content") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                status = "Saving " + filePath + "…"
                                val snapshot = editorText
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        require(snapshot.length <= 256 * 1024 && !snapshot.contains('\u0000')) { "Content is invalid or exceeds 256 KiB." }
                                        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri!!)) ?: error("Invalid project folder.")
                                        var parent = root
                                        val parts = filePath.split('/')
                                        parts.dropLast(1).forEach { parent = parent.findFile(it) ?: error("Parent folder missing.") }
                                        val target = parent.findFile(parts.last()) ?: error("File was removed.")
                                        require(target.isFile) { "Target is not a file." }
                                        val tempName = parts.last() + ".gptmax-tmp-" + System.nanoTime()
                                        val temp = parent.createFile("text/plain", tempName) ?: error("Cannot create temporary save file.")
                                        try {
                                            val output = context.contentResolver.openOutputStream(temp.uri, "wt") ?: error("Cannot write temporary file.")
                                            output.use { it.write(snapshot.toByteArray(Charsets.UTF_8)) }
                                            val backupName = parts.last() + ".gptmax-backup-" + System.nanoTime()
                                            val backup = if (target.renameTo(backupName)) target else null
                                            if (!temp.renameTo(parts.last())) {
                                                backup?.renameTo(parts.last())
                                                error("Provider could not finalize save; original restored when possible.")
                                            }
                                            backup?.delete()
                                        } finally {
                                            if (temp.exists()) temp.delete()
                                        }
                                    }
                                }
                                result.onSuccess { originalText = snapshot; dirty = false; status = "Saved " + filePath; refreshKey++ }
                                    .onFailure { status = "Save failed: " + (it.message ?: it.javaClass.simpleName) }
                                busy = false
                            }
                        },
                        enabled = dirty && !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (busy) "Saving…" else "Save") }
                    TextButton(onClick = { selectedPath = null; editorText = ""; originalText = ""; dirty = false }, enabled = !busy) { Text("Close") }
                }
            }

            if (dirty) {
                Text("Review diff before saving", style = MaterialTheme.typography.titleSmall)
                Text(buildEditorDiff(originalText, editorText), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text("Unsaved changes", color = MaterialTheme.colorScheme.error)
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) showCreateDialog = false },
            title = { Text("Create source file") },
            text = {
                OutlinedTextField(value = newFileName, onValueChange = { newFileName = it }, label = { Text("File name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    val name = newFileName.trim()
                    scope.launch {
                        busy = true
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                require(name.isNotBlank() && name.length <= 120 && !name.contains('/') && !name.contains('\\') && name != "." && name != "..") { "Invalid file name." }
                                require(name.lowercase() !in setOf(".env", ".env.local", ".npmrc", ".netrc", "credentials.json", "secrets.json") && !name.endsWith(".key", true) && !name.endsWith(".pem", true)) {
                                    "Creating secret files through this editor is blocked."
                                }
                                val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri!!)) ?: error("Invalid project folder.")
                                var parent = root
                                if (directory.isNotBlank()) directory.split('/').forEach { parent = parent.findFile(it) ?: error("Folder not found.") }
                                require(parent.findFile(name) == null) { "A file with this name already exists." }
                                parent.createFile("text/plain", name) ?: error("Could not create file.")
                                if (directory.isBlank()) name else directory + "/" + name
                            }
                        }
                        result.onSuccess {
                            selectedPath = it
                            editorText = ""
                            originalText = ""
                            dirty = false
                            status = "Created " + it
                            refreshKey++
                            showCreateDialog = false
                            newFileName = ""
                        }.onFailure { status = "Create failed: " + (it.message ?: it.javaClass.simpleName) }
                        busy = false
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }, enabled = !busy) { Text("Cancel") } }
        )
    }
}

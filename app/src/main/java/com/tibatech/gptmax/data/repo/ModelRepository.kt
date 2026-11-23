package com.tibatech.gptmax.data.repo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * GGUF model catalog + downloader. Sizes are the Q4_K_M artifacts; download
 * goes to app-private storage (`files/models`), no storage permission needed.
 *
 * The catalog matches the report's on-device tiering:
 *   0.5B instant assistant -> 1.5B light coder -> 3B default coder ->
 *   7B flagship devices -> (cloud tier beyond that).
 */
data class ModelCatalogEntry(
    val id: String,
    val displayName: String,
    val repo: String,
    val fileName: String,
    val sizeBytes: Long,
    val minRamGb: Int,
    val role: String,
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/main/$fileName"
}

object ModelCatalog {
    val entries = listOf(
        ModelCatalogEntry(
            id = "qwen25-coder-0.5b",
            displayName = "Qwen2.5-Coder-0.5B-Instruct",
            repo = "bartowski/Qwen2.5-Coder-0.5B-Instruct-GGUF",
            fileName = "Qwen2.5-Coder-0.5B-Instruct-Q4_K_M.gguf",
            sizeBytes = 400_000_000L,
            minRamGb = 1,
            role = "Instant chat + autocomplete",
        ),
        ModelCatalogEntry(
            id = "qwen25-coder-1.5b",
            displayName = "Qwen2.5-Coder-1.5B-Instruct",
            repo = "bartowski/Qwen2.5-Coder-1.5B-Instruct-GGUF",
            fileName = "Qwen2.5-Coder-1.5B-Instruct-Q4_K_M.gguf",
            sizeBytes = 1_090_000_000L,
            minRamGb = 2,
            role = "Light local coder",
        ),
        ModelCatalogEntry(
            id = "qwen25-coder-3b",
            displayName = "Qwen2.5-Coder-3B-Instruct",
            repo = "bartowski/Qwen2.5-Coder-3B-Instruct-GGUF",
            fileName = "Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf",
            sizeBytes = 1_930_000_000L,
            minRamGb = 4,
            role = "Default local coder (recommended)",
        ),
        ModelCatalogEntry(
            id = "qwen25-coder-7b",
            displayName = "Qwen2.5-Coder-7B-Instruct",
            repo = "bartowski/Qwen2.5-Coder-7B-Instruct-GGUF",
            fileName = "Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf",
            sizeBytes = 4_680_000_000L,
            minRamGb = 8,
            role = "Flagship devices — strongest fully-local tier",
        ),
        ModelCatalogEntry(
            id = "smollm2-1.7b",
            displayName = "SmolLM2-1.7B-Instruct",
            repo = "bartowski/SmolLM2-1.7B-Instruct-GGUF",
            fileName = "SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
            sizeBytes = 1_100_000_000L,
            minRamGb = 2,
            role = "Ultra-light general assistant",
        ),
    )

    fun byId(id: String): ModelCatalogEntry? = entries.firstOrNull { it.id == id }
}

sealed class DownloadState {
    data class Progress(val bytes: Long, val total: Long) : DownloadState() {
        val fraction: Float get() = if (total > 0) bytes.toFloat() / total else 0f
    }
    data class Done(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class ModelRepository(private val context: Context) {

    private val client = OkHttpClient.Builder().build()

    val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    fun installed(): List<File> = modelsDir.listFiles { f -> f.extension == "gguf" }
        ?.sortedByDescending { it.length() } ?: emptyList()

    fun installedPathFor(entry: ModelCatalogEntry): File? =
        installed().firstOrNull { it.name.equals(entry.fileName, ignoreCase = true) }

    /** Streams the GGUF to app-private storage with progress events. */
    fun download(entry: ModelCatalogEntry): Flow<DownloadState> = flow {
        val target = File(modelsDir, entry.fileName)
        val tmp = File(modelsDir, "${entry.fileName}.part")
        try {
            val request = Request.Builder().url(entry.url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(DownloadState.Error("HTTP ${response.code} — ${entry.url}"))
                    return@flow
                }
                val total = response.body?.contentLength() ?: entry.sizeBytes
                val input = response.body?.byteStream()
                    ?: run { emit(DownloadState.Error("empty body")); return@flow }
                input.use { stream ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        var bytes = 0L
                        var lastEmit = 0L
                        while (true) {
                            val n = stream.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            bytes += n
                            if (bytes - lastEmit > 8 * 1024 * 1024) {
                                lastEmit = bytes
                                emit(DownloadState.Progress(bytes, total))
                            }
                        }
                    }
                }
            }
            if (tmp.renameTo(target)) {
                emit(DownloadState.Done(target))
            } else {
                emit(DownloadState.Error("could not finalize ${target.name}"))
            }
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            emit(DownloadState.Error(t.message ?: t.javaClass.simpleName))
        }
    }.flowOn(Dispatchers.IO)

    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)
}

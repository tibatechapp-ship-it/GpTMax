package dev.gptmax.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal data class ImportedModel(
    val displayName: String,
    val file: File,
    val sizeBytes: Long,
    val sha256: String,
)

internal object ModelFileImporter {
    suspend fun import(context: Context, uri: Uri): ImportedModel = withContext(Dispatchers.IO) {
        val metadata = queryMetadata(context, uri)
        require(metadata.first.endsWith(".gguf", ignoreCase = true)) {
            "Select a GGUF model file."
        }
        require(metadata.second == null || metadata.second > 0L) {
            "The selected file is empty."
        }

        val modelsDirectory = File(context.filesDir, "models")
        check(modelsDirectory.exists() || modelsDirectory.mkdirs()) {
            "Could not create private model directory."
        }
        val usableBytes = modelsDirectory.usableSpace
        if (metadata.second != null && metadata.second > usableBytes) {
            throw IllegalStateException("Not enough app storage for this model.")
        }

        val safeBaseName = metadata.first
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
            .ifBlank { "model.gguf" }
        val destination = File(modelsDirectory, "${UUID.randomUUID()}-$safeBaseName")
        val temporary = File(modelsDirectory, "${destination.name}.partial")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L

        try {
            val input = requireNotNull(context.contentResolver.openInputStream(uri)) {
                "Android could not open the selected document."
            }
            input.use { source ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        copied += count
                        if (copied > usableBytes) {
                            throw IllegalStateException("Model exceeds available app storage.")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }

            require(copied > 0L) { "The selected GGUF file is empty." }
            if (metadata.second != null && metadata.second != copied) {
                throw IllegalStateException("File size changed during import; incomplete copy rejected.")
            }
            check(temporary.renameTo(destination)) {
                "Could not finalize imported model file."
            }
            ImportedModel(
                displayName = metadata.first,
                file = destination,
                sizeBytes = copied,
                sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            )
        } catch (error: Exception) {
            temporary.delete()
            destination.delete()
            throw error
        }
    }

    private fun queryMetadata(context: Context, uri: Uri): Pair<String, Long?> {
        var name = "model.gguf"
        var size: Long? = null
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex).takeIf { it >= 0L }
                }
            }
        }
        return name to size
    }
}

package dev.gptmax.app

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties

internal data class StoredModel(
    val displayName: String,
    val file: File,
    val sizeBytes: Long,
    val sha256: String,
)

internal object ModelStore {
    private val shaPattern = Regex("^[a-f0-9]{64}$")

    fun list(directory: File): List<StoredModel> {
        if (!directory.exists()) return emptyList()
        require(directory.isDirectory) { "Model storage path is not a directory." }
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
            .mapNotNull { file ->
                runCatching {
                    val namedSha = file.nameWithoutExtension.lowercase()
                        .takeIf { shaPattern.matches(it) }
                    val sha = namedSha ?: sha256(file)
                    val metadata = readMetadata(directory, sha)
                    StoredModel(
                        displayName = metadata.getProperty("displayName") ?: file.name,
                        file = file,
                        sizeBytes = file.length(),
                        sha256 = sha,
                    )
                }.getOrNull()
            }
            .groupBy { it.sha256 }
            .values
            .map { duplicates -> duplicates.first() }
            .sortedBy { it.displayName.lowercase() }
    }

    fun findBySha256(directory: File, sha256: String): StoredModel? {
        require(shaPattern.matches(sha256)) { "Invalid model SHA-256." }
        val contentAddressed = File(directory, "$sha256.gguf")
        if (contentAddressed.isFile) {
            return StoredModel(
                displayName = readMetadata(directory, sha256).getProperty("displayName")
                    ?: contentAddressed.name,
                file = contentAddressed,
                sizeBytes = contentAddressed.length(),
                sha256 = sha256,
            )
        }
        return list(directory).firstOrNull { it.sha256 == sha256 }
    }

    fun writeMetadata(directory: File, sha256: String, displayName: String, sizeBytes: Long) {
        require(shaPattern.matches(sha256)) { "Invalid model SHA-256." }
        require(sizeBytes > 0L) { "Model size must be positive." }
        val properties = Properties().apply {
            setProperty("displayName", displayName)
            setProperty("sizeBytes", sizeBytes.toString())
            setProperty("sha256", sha256)
        }
        val destination = File(directory, "$sha256.meta")
        val temporary = File(directory, "$sha256.meta.partial")
        try {
            FileOutputStream(temporary).use { output ->
                properties.store(output, "GpTMax model metadata")
                output.fd.sync()
            }
            check(temporary.renameTo(destination) || destination.exists()) {
                "Could not persist model metadata."
            }
            if (temporary.exists()) temporary.delete()
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    fun cleanupIncomplete(directory: File): Int {
        if (!directory.exists()) return 0
        require(directory.isDirectory) { "Model storage path is not a directory." }
        var removed = 0
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".partial") }
            .forEach { if (it.delete()) removed++ }
        return removed
    }

    fun delete(directory: File, sha256: String, activeModelFile: File? = null) {
        require(shaPattern.matches(sha256)) { "Invalid model SHA-256." }
        val canonicalDirectory = directory.canonicalFile
        val matchingFiles = directory.listFiles().orEmpty()
            .filter { file ->
                file.isFile && file.extension.equals("gguf", ignoreCase = true) &&
                    runCatching { sha256(file) == sha256 }.getOrDefault(false)
            }
        require(matchingFiles.isNotEmpty()) {
            "Model is not present in private storage."
        }
        matchingFiles.forEach { file ->
            require(file.canonicalFile.parentFile == canonicalDirectory) {
                "Refusing to delete a file outside model storage."
            }
            if (activeModelFile != null &&
                file.canonicalFile == activeModelFile.canonicalFile
            ) {
                throw IllegalStateException("Unload the active model before deleting it.")
            }
        }
        matchingFiles.forEach { file ->
            check(file.delete()) { "Could not delete model file." }
        }
        File(directory, "$sha256.meta").delete()
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun readMetadata(directory: File, sha256: String): Properties {
        val file = File(directory, "$sha256.meta")
        if (!file.isFile) return Properties()
        return runCatching {
            Properties().also { properties ->
                FileInputStream(file).use { input -> properties.load(input) }
            }
        }.getOrDefault(Properties())
    }
}

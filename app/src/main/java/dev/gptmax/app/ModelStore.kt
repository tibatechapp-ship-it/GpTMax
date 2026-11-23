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
                    val sha = sha256(file)
                    val metadata = readMetadata(directory, sha)
                    StoredModel(
                        displayName = metadata.getProperty("displayName") ?: file.name,
                        file = file,
                        sizeBytes = file.length(),
                        sha256 = sha,
                    )
                }.getOrNull()
            }
            .sortedBy { it.displayName.lowercase() }
            .toList()
    }

    fun findBySha256(directory: File, sha256: String): StoredModel? {
        require(shaPattern.matches(sha256)) { "Invalid model SHA-256." }
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
        val model = findBySha256(directory, sha256)
            ?: throw IllegalArgumentException("Model is not present in private storage.")
        require(model.file.canonicalFile.parentFile == canonicalDirectory) {
            "Refusing to delete a file outside model storage."
        }
        if (activeModelFile != null &&
            model.file.canonicalFile == activeModelFile.canonicalFile
        ) {
            throw IllegalStateException("Unload the active model before deleting it.")
        }
        check(model.file.delete()) { "Could not delete model file." }
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
        return Properties().also { properties ->
            FileInputStream(file).use(properties::load)
        }
    }
}

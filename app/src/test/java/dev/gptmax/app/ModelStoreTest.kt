package dev.gptmax.app

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelStoreTest {
    @Test
    fun listsModelsWithPersistedMetadata() {
        val directory = Files.createTempDirectory("gptmax-models").toFile()
        try {
            val model = File(directory, "model.gguf").apply { writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46)) }
            val sha = ModelStore.sha256(model)
            ModelStore.writeMetadata(directory, sha, "Arabic Model.gguf", model.length())

            val listed = ModelStore.list(directory)

            assertEquals(1, listed.size)
            assertEquals("Arabic Model.gguf", listed.single().displayName)
            assertEquals(sha, listed.single().sha256)
            assertEquals(model, listed.single().file)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun refusesToDeleteActiveModel() {
        val directory = Files.createTempDirectory("gptmax-models-active").toFile()
        try {
            val model = File(directory, "model.gguf").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val sha = ModelStore.sha256(model)

            assertThrows(IllegalStateException::class.java) {
                ModelStore.delete(directory, sha, model)
            }
            assertTrue(model.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun deletesInactiveModelAndMetadata() {
        val directory = Files.createTempDirectory("gptmax-models-delete").toFile()
        try {
            val model = File(directory, "model.gguf").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val sha = ModelStore.sha256(model)
            ModelStore.writeMetadata(directory, sha, "Model.gguf", model.length())

            ModelStore.delete(directory, sha)

            assertFalse(model.exists())
            assertFalse(File(directory, "$sha.meta").exists())
            assertTrue(ModelStore.list(directory).isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun deduplicatesIdenticalModelFilesAndDeletesAllCopies() {
        val directory = Files.createTempDirectory("gptmax-models-duplicates").toFile()
        try {
            val bytes = byteArrayOf(1, 2, 3, 4, 5)
            val first = File(directory, "first.gguf").apply { writeBytes(bytes) }
            val second = File(directory, "second.gguf").apply { writeBytes(bytes) }
            val sha = ModelStore.sha256(first)
            ModelStore.writeMetadata(directory, sha, "Shared Model.gguf", bytes.size.toLong())

            assertEquals(1, ModelStore.list(directory).size)
            ModelStore.delete(directory, sha)
            assertFalse(first.exists())
            assertFalse(second.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun malformedMetadataDoesNotHideModel() {
        val directory = Files.createTempDirectory("gptmax-models-metadata").toFile()
        try {
            val model = File(directory, "model.gguf").apply { writeBytes(byteArrayOf(9, 8, 7, 6)) }
            val sha = ModelStore.sha256(model)
            File(directory, "$sha.meta").writeText("bad=\\uZZZZ")
            assertEquals(1, ModelStore.list(directory).size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsContentAddressedFileWhoseBytesDoNotMatchItsName() {
        val directory = Files.createTempDirectory("gptmax-models-integrity").toFile()
        try {
            val original = byteArrayOf(1, 2, 3, 4, 5, 6)
            val expectedSha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(original)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val tampered = File(directory, "$expectedSha.gguf").apply {
                writeBytes(byteArrayOf(6, 5, 4, 3, 2, 1))
            }

            assertThrows(IllegalArgumentException::class.java) {
                ModelStore.findBySha256(directory, expectedSha)
            }
            assertTrue(tampered.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun removesOnlyIncompletePartialFiles() {
        val directory = Files.createTempDirectory("gptmax-models-cleanup").toFile()
        try {
            val partial = File(directory, "unfinished.partial").apply { writeText("partial") }
            val complete = File(directory, "complete.gguf").apply { writeText("GGUF") }

            assertEquals(1, ModelStore.cleanupIncomplete(directory))
            assertFalse(partial.exists())
            assertTrue(complete.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}

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

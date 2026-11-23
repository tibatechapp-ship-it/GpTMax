package dev.gptmax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TestModelCatalogTest {
    @Test
    fun catalogPinsOfficialQwenArtifact() {
        assertEquals("qwen2.5-0.5b-instruct-q4_k_m.gguf", TestModelCatalog.FILE_NAME)
        assertEquals(64, TestModelCatalog.SHA256.length)
        assertEquals("Apache-2.0", TestModelCatalog.LICENSE)
    }

    @Test
    fun acceptsExactPublishedSha256() {
        verifyExpectedModelSha256(TestModelCatalog.SHA256, TestModelCatalog.SHA256)
    }

    @Test
    fun rejectsWrongSha256ForVerifiedModel() {
        assertThrows(IllegalArgumentException::class.java) {
            verifyExpectedModelSha256("0".repeat(64), TestModelCatalog.SHA256)
        }
    }

    @Test
    fun acceptsGenericImportWithoutExpectedDigest() {
        verifyExpectedModelSha256("a".repeat(64), null)
    }

    @Test
    fun rejectsMalformedCalculatedDigest() {
        assertThrows(IllegalArgumentException::class.java) {
            verifyExpectedModelSha256("not-a-digest", null)
        }
    }
}

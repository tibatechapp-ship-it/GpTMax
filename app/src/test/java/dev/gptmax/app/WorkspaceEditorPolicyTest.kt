package dev.gptmax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceEditorPolicyTest {
    @Test
    fun allowsOrdinarySourceFilesAndDirectories() {
        assertTrue(isSafeWorkspaceEditorEntry("MainActivity.kt", false))
        assertTrue(isSafeWorkspaceEditorEntry("src", true))
        assertEquals(null, validateWorkspaceEditorFileName("NewFeature.kt"))
    }

    @Test
    fun hidesSecretFilesAndSensitiveDirectories() {
        listOf(".env", ".env.production", "id_rsa", "secrets.json", "client.pem", "signing.jks")
            .forEach { assertFalse(it, isSafeWorkspaceEditorEntry(it, false)) }
        listOf(".git", ".ssh", ".aws", "node_modules")
            .forEach { assertFalse(it, isSafeWorkspaceEditorEntry(it, true)) }
    }

    @Test
    fun rejectsTraversalAndInvalidNames() {
        listOf("", ".", "..", "../outside.kt", "dir/file.kt", "dir\\\\file.kt", "bad\\u0000name")
            .forEach { assertTrue(validateWorkspaceEditorFileName(it) != null) }
    }
}

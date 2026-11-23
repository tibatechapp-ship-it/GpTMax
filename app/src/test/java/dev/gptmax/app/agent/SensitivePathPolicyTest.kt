package dev.gptmax.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Security review priority #1: one policy must protect every tool surface. */
class SensitivePathPolicyTest {

    @Test
    fun envFileVariantsAreBlockedEverywhere() {
        listOf(
            ".env", ".env.local", ".env.production", ".env.development",
            ".env.staging", ".env.test", ".ENV.TEST", ".envrc",
        ).forEach { name ->
            assertFalse("'$name' must be blocked", SensitivePathPolicy.isAllowedFileName(name))
        }
    }

    @Test
    fun credentialNamesAndKeyExtensionsAreBlocked() {
        listOf(
            "id_rsa", "ID_RSA", "id_ed25519", "id_ecdsa", "id_dsa",
            "known_hosts", "authorized_keys",
            ".npmrc", ".netrc", ".git-credentials", ".htpasswd", ".pgpass",
            "credentials.json", "credentials.yaml", "secrets.json", "secrets.yaml",
            "service-account.json", "keystore.jks",
            "server.pem", "server.PEM", "cert.p12", "cert.pfx",
            "server.key", "release.keystore", "vault.kdbx",
        ).forEach { name ->
            assertFalse("'$name' must be blocked", SensitivePathPolicy.isAllowedFileName(name))
        }
    }

    @Test
    fun normalSourceFilesStayAllowed() {
        listOf(
            "App.kt", "README.md", "main.py", "index.tsx",
            "build.gradle.kts", "environment.dart", "keynote.txt", "keys.md",
        ).forEach { name ->
            assertTrue("'$name' must stay allowed", SensitivePathPolicy.isAllowedFileName(name))
        }
    }

    @Test
    fun credentialDirectoriesAreBlockedForEveryTool() {
        listOf(
            ".git", ".ssh", ".aws", ".gcloud", ".azure", ".gnupg",
            ".gradle", "node_modules", ".gptmax", ".AWS", ".SSH",
        ).forEach { name ->
            assertFalse("'$name' must be blocked", SensitivePathPolicy.isAllowedDirectoryName(name))
            assertFalse("'$name' must also be skipped by wide traversals",
                SensitivePathPolicy.isTraversalAllowedDirectoryName(name))
        }
        assertTrue(SensitivePathPolicy.isAllowedDirectoryName("src"))
        assertTrue(SensitivePathPolicy.isAllowedDirectoryName("scripts"))
    }

    @Test
    fun generatedDirectoriesAreSkippedOnlyInWideTraversals() {
        assertFalse(SensitivePathPolicy.isTraversalAllowedDirectoryName("build"))
        assertFalse(SensitivePathPolicy.isTraversalAllowedDirectoryName(".idea"))
        assertFalse(SensitivePathPolicy.isTraversalAllowedDirectoryName("__pycache__"))
        // Direct, explicitly requested access to a generated directory keeps working.
        assertTrue(SensitivePathPolicy.isAllowedDirectoryName("build"))
        assertTrue(SensitivePathPolicy.isAllowedDirectoryName(".idea"))
    }

    @Test
    fun relativePathValidationAcceptsNormalPaths() {
        assertEquals(listOf("src", "Main.kt"), SensitivePathPolicy.validateRelativePath("src/Main.kt"))
        assertEquals(listOf("App.kt"), SensitivePathPolicy.validateRelativePath("App.kt"))
        assertEquals(listOf("docs", "guide", "intro.md"),
            SensitivePathPolicy.validateRelativePath("docs\\guide\\intro.md"))
    }

    @Test
    fun relativePathValidationRejectsUnsafePaths() {
        listOf(
            "../outside.txt",
            "/abs.txt",
            "src//Main.kt",
            "src/./Main.kt",
            "src/../Main.kt",
            ".ssh/id_rsa",
            "project/.aws/config.json",
            "project/.ssh/id_ed25519",
            "src/.env.development",
            "config/server.key",
            "deep/nested/.git-credentials",
            "",
        ).forEach { path ->
            val result = runCatching { SensitivePathPolicy.validateRelativePath(path) }
            assertTrue("'$path' must be rejected", result.isFailure)
        }
    }

    @Test
    fun blockedDirectoriesMatchCaseInsensitively() {
        val result = runCatching { SensitivePathPolicy.validateRelativePath(".SSH/id_rsa") }
        assertTrue(result.isFailure)
        val aws = runCatching { SensitivePathPolicy.validateRelativePath(".AWS/config.json") }
        assertTrue(aws.isFailure)
    }
}

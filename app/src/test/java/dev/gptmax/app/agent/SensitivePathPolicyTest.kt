package dev.gptmax.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Security review priority #1: one policy must protect every tool surface. */
class SensitivePathPolicyTest {

    @Test
    fun envVariantsAreForbiddenEverywhere() {
        listOf(".env", ".env.local", ".env.production", ".env.development", ".ENV.TEST")
            .forEach { assertTrue("$it must be forbidden", SensitivePathPolicy.isForbiddenFileName(it)) }
    }

    @Test
    fun credentialNamesAndExtensionsAreForbidden() {
        listOf(
            "id_rsa", "ID_ED25519", "known_hosts", "authorized_keys",
            ".npmrc", ".netrc", ".git-credentials", ".htpasswd", ".pgpass",
            "credentials.json", "secrets.yaml", "service-account.json",
            "server.pem", "a.p12", "b.pfx", "c.key", "d.jks", "e.keystore", "f.kdbx",
        ).forEach { assertTrue("$it must be forbidden", SensitivePathPolicy.isForbiddenFileName(it)) }
    }

    @Test
    fun normalSourceFilesStayAllowed() {
        listOf("MainActivity.kt", "README.md", "notes.txt", "build.gradle.kts", "main.py")
            .forEach { assertFalse("$it must stay allowed", SensitivePathPolicy.isForbiddenFileName(it)) }
    }

    @Test
    fun secretDirectoriesAreForbiddenEvenForDirectPaths() {
        listOf(".git", ".ssh", ".aws", ".gnupg", ".gptmax", ".AWS")
            .forEach { assertTrue("$it must be forbidden", SensitivePathPolicy.isForbiddenDirectory(it)) }
    }

    @Test
    fun noiseDirectoriesAreCrawlBlockedButDirectlyReachable() {
        listOf("build", "node_modules", ".gradle", ".idea", "dist", "__pycache__").forEach {
            assertTrue("$it must not be crawled", SensitivePathPolicy.isCrawlBlockedDirectory(it))
            assertFalse("$it stays reachable via an explicit path", SensitivePathPolicy.isForbiddenDirectory(it))
        }
    }

    @Test
    fun relativePathValidationAcceptsNormalPaths() {
        assertEquals(listOf("src", "Main.kt"), SensitivePathPolicy.validateRelativePath("src/Main.kt"))
    }

    @Test
    fun relativePathValidationRejectsTraversalAndSecrets() {
        listOf(
            "../outside.txt", "/absolute.txt", "src//Main.kt", "src/./Main.kt", "src/../Main.kt",
            ".ssh/id_rsa", ".aws/config", "src/.env.local", "docs/notes.key",
        ).forEach { path ->
            val result = runCatching { SensitivePathPolicy.validateRelativePath(path) }
            assertTrue("'$path' must be rejected", result.isFailure)
        }
    }
}

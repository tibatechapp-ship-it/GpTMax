package com.tibatech.gptmax.core.guardrails

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review #1: ONE blocklist must guard every tool surface. These tests pin the
 * policy semantics: secret directories and files are blocked even by direct
 * path, .env* variants are blocked by prefix, and traversal is impossible.
 */
class SensitivePathPolicyTest {

    private fun blocked(path: String): Boolean =
        runCatching { SensitivePathPolicy.validateRelativePath(path) }.isFailure

    @Test
    fun `secret directories are blocked even by direct path`() {
        listOf(
            ".ssh/id_rsa",
            ".aws/credentials",
            ".git/config",
            ".gnupg/private-keys-v1.d/secret",
            ".azure/profile.json",
            ".gcloud/credentials.db",
            ".gptmax/ledger.jsonl",
        ).forEach { path ->
            assertTrue("should block $path", blocked(path))
        }
    }

    @Test
    fun `env variants are blocked by prefix`() {
        listOf(".env", ".env.local", ".env.production", "config/.ENV.PROD")
            .forEach { path -> assertTrue("should block $path", blocked(path)) }
    }

    @Test
    fun `secret files are blocked`() {
        listOf(
            "id_rsa",
            "deploy/id_ed25519",
            "credentials.json",
            "keystore.jks",
            "service-account.json",
            "terraform.tfvars",
            ".npmrc",
            ".netrc",
        ).forEach { path -> assertTrue("should block $path", blocked(path)) }
    }

    @Test
    fun `key and keystore extensions are blocked`() {
        listOf("server.pem", "cert.p12", "vault.pfx", "signing.key", "db.kdbx")
            .forEach { path -> assertTrue("should block $path", blocked(path)) }
    }

    @Test
    fun `traversal and absolute paths are blocked`() {
        listOf("../outside", "a/../../b", "/etc/passwd", "a//b", "a/./b")
            .forEach { path -> assertTrue("should block $path", blocked(path)) }
    }

    @Test
    fun `normal project paths are allowed`() {
        listOf("README.md", "src/main/java/App.kt", "docs/notes.md", "build.gradle.kts")
            .forEach { path -> assertFalse("should allow $path", blocked(path)) }
    }

    @Test
    fun `crawl noise is reachable directly but never crawled`() {
        // Direct access stays possible for build outputs (validateRelativePath).
        assertFalse(blocked("build/generated/Output.kt"))
        // ...but traversals must skip them.
        assertTrue(SensitivePathPolicy.isCrawlBlockedDirectory("build"))
        assertTrue(SensitivePathPolicy.isCrawlBlockedDirectory("node_modules"))
        assertTrue(SensitivePathPolicy.isCrawlBlockedDirectory(".gradle"))
        assertTrue(SensitivePathPolicy.isCrawlBlockedDirectory("__pycache__"))
        assertFalse(SensitivePathPolicy.isCrawlBlockedDirectory("src"))
    }

    @Test
    fun `isForbiddenDirectory is case-insensitive and exact`() {
        assertTrue(SensitivePathPolicy.isForbiddenDirectory(".ssh"))
        assertTrue(SensitivePathPolicy.isForbiddenDirectory(".SSH"))
        assertFalse(SensitivePathPolicy.isForbiddenDirectory("ssh"))
        assertFalse(SensitivePathPolicy.isForbiddenDirectory(".gitignore"))
    }
}

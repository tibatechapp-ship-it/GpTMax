package com.tibatech.gptmax.core.guardrails

/**
 * Fix #1 (code review): one shared policy for credential and key material
 * so it can never leak into the model context. Previously `list`, `read`
 * and `grep` each applied their own (or no) filtering and `.aws`, `.ssh`
 * and `.env*` files slipped through into prompts.
 *
 * Pure Kotlin on purpose: unit-testable on the JVM without Android.
 */
object SensitivePathFilter {

    /** Directory names excluded wholesale from listing, reading and grep. */
    private val SENSITIVE_DIRS = setOf(
        ".aws", ".ssh", ".gnupg", ".gpg", ".kube", ".docker",
        ".secrets", "secrets", "gcloud",
    )

    /** Exact file names that are credentials regardless of extension. */
    private val SENSITIVE_FILE_NAMES = setOf(
        ".netrc", ".npmrc", ".pypirc", ".htpasswd",
        "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
        "credentials", "credentials.json", "credentials.properties",
        "secrets.json", "secrets.yaml", "secrets.yml",
        "service-account.json",
    )

    /** Extensions that are key, keystore or encrypted-container material. */
    private val SENSITIVE_EXTENSIONS = listOf(
        ".pem", ".key", ".jks", ".keystore", ".p12", ".pfx", ".kdbx", ".asc", ".gpg",
    )

    /** .env forms that are meant to be committed and stay readable. */
    private val ENV_EXEMPT = setOf(
        ".env.example", ".env.sample", ".env.template",
        ".env.test", ".env.test.example",
    )

    fun isSensitiveDir(name: String): Boolean = name.lowercase() in SENSITIVE_DIRS

    fun isSensitiveFile(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in ENV_EXEMPT) return false
        if (lower.startsWith(".env")) return true
        if (lower in SENSITIVE_FILE_NAMES) return true
        return SENSITIVE_EXTENSIONS.any { lower.endsWith(it) }
    }

    /** True when any path segment is a sensitive directory or the file itself is sensitive. */
    fun isSensitive(path: String): Boolean {
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return false
        if (parts.dropLast(1).any { isSensitiveDir(it) }) return true
        return isSensitiveFile(parts.last())
    }

    /**
     * Narrow predicate for writes: irreversible key/keystore/credential
     * material must never be overwritten by the agent, even behind the
     * approval gate. Regular files (and committable .env variants) pass.
     */
    fun isProtectedFromWrite(path: String): Boolean {
        val name = path.trim('/').split('/').lastOrNull()?.lowercase() ?: return false
        if (name in SENSITIVE_FILE_NAMES) return true
        return SENSITIVE_EXTENSIONS.any { name.endsWith(it) }
    }
}

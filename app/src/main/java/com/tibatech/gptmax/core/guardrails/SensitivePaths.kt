package com.tibatech.gptmax.core.guardrails

/**
 * Single source of truth for paths that must never reach the model context
 * (review item #1). Repo map, digests, grep and tool reads all consult this
 * guard so credentials, SSH identities, env files and keystores never leave
 * the device even when the model asks for them.
 */
object SensitivePaths {

    /** File/directory names that are excluded wherever they appear in a path. */
    private val NAMES = setOf(
        ".aws", ".ssh", ".gnupg", ".gpg", ".kube", ".docker", ".netrc",
        ".npmrc", ".pypirc", ".htpasswd", ".env",
        "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
        "known_hosts", "authorized_keys",
        "credentials", "credentials.json", "service-account.json",
        "client_secret.json", "secrets.json", "secrets.properties",
        "keystore.properties", "secrets",
    )

    /** Name prefixes (.env.local, .env.production, id_rsa.github …). */
    private val NAME_PREFIXES = listOf(".env", "id_rsa", "id_ed25519", "id_ecdsa", "id_dsa")

    /** Extensions that are keys, certs or vaults by definition. */
    private val EXTENSIONS = listOf(
        ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".kdbx",
        ".asc", ".p8", ".ovpn", ".mobileprovision",
    )

    /** Matched against directory components only, so UI files like
     *  CredentialsScreen.kt stay readable while secrets/ dirs stay closed. */
    private val DIR_FRAGMENTS = listOf("credential", "secret", "private_key", "privatekey")

    /** Committed example templates are safe to read and write (#1 follow-up). */
    private val EXEMPT_NAMES = setOf(
        ".env.example", ".env.sample", ".env.template",
        ".env.test", ".env.test.example", ".env.development.example",
    )

    /** True when [path] (or anything inside it) must not reach the model. */
    fun isSensitive(path: String): Boolean {
        val clean = path.trim().trim('/').lowercase()
        if (clean.isBlank()) return false
        val parts = clean.split('/')
        // A sensitive parent directory always wins over an exempt file name:
        // .ssh/.env.example stays closed even though the name alone is benign.
        val parentSensitive = parts.dropLast(1).any { part ->
            part in NAMES || NAME_PREFIXES.any { part.startsWith(it) } ||
                DIR_FRAGMENTS.any { part.contains(it) }
        }
        if (parentSensitive) return true
        if (parts.last() in EXEMPT_NAMES) return false
        if (parts.any { it in NAMES }) return true
        if (parts.any { part -> NAME_PREFIXES.any { part.startsWith(it) } }) return true
        if (EXTENSIONS.any { clean.endsWith(it) }) return true
        val dirs = parts.dropLast(1)
        return dirs.any { dir -> DIR_FRAGMENTS.any { dir.contains(it) } }
    }

    /**
     * Review #2 follow-up: irreversible key/keystore/credential material must
     * never be overwritten or deleted by the agent, even behind the approval
     * gate. Read exclusion alone is not enough for destructive operations.
     */
    fun isProtectedFromWrite(path: String): Boolean {
        val name = path.trim().trim('/').lowercase().substringAfterLast('/')
        if (name in NAMES) return true
        return EXTENSIONS.any { name.endsWith(it) }
    }

    fun describe(path: String): String =
        "blocked: $path contains or looks like credentials and never leaves the device"

    fun describeWrite(path: String): String =
        "blocked: $path is key or credential material — the agent never overwrites or deletes it"
}

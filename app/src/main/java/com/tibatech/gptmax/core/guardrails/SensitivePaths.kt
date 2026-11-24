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

    /** True when [path] (or anything inside it) must not reach the model. */
    fun isSensitive(path: String): Boolean {
        val clean = path.trim().trim('/').lowercase()
        if (clean.isBlank()) return false
        val parts = clean.split('/')
        if (parts.any { it in NAMES }) return true
        if (parts.any { part -> NAME_PREFIXES.any { part.startsWith(it) } }) return true
        if (EXTENSIONS.any { clean.endsWith(it) }) return true
        val dirs = parts.dropLast(1)
        return dirs.any { dir -> DIR_FRAGMENTS.any { dir.contains(it) } }
    }

    fun describe(path: String): String =
        "blocked: $path contains or looks like credentials and never leaves the device"
}

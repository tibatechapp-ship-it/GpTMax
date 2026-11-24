package dev.gptmax.app.agent

/**
 * Review fix #1 — single source of truth for sensitive workspace paths.
 *
 * DocumentTreeWorkspaceTools (read/search/write guards) and
 * ProjectContextTools (context indexing) previously maintained two divergent
 * blocklists; this object is the unified policy that both layers must consult
 * so credentials, SSH identities, cloud CLI state, env files and keystores
 * can never leak into model context through one surface while another allows
 * it. Every future workspace surface must consult this object as well.
 */
object SensitivePaths {

    /** Directories never traversed, indexed, searched, or written into. */
    val blockedDirectories = setOf(
        ".git", ".ssh", ".aws", ".gnupg", ".gpg", ".kube", ".docker",
        ".gradle", ".idea", "node_modules", "build", "dist", "target",
        ".next", ".venv", "venv", "__pycache__",
    )

    /** Exact file names (lower-cased) that hold credentials or identities. */
    private val sensitiveNames = setOf(
        ".env", ".npmrc", ".netrc", ".git-credentials", ".htpasswd",
        "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
        "known_hosts", "authorized_keys",
        "credentials.json", "secrets.json", "secrets.properties",
        "service-account.json", "client_secret.json", "keystore.properties",
    )

    /** Extensions that are keys, certificates, or vaults by definition. */
    private val sensitiveExtensions = listOf(
        ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".kdbx",
        ".asc", ".p8", ".ovpn", ".mobileprovision",
    )

    /** True when this file name must never be read, indexed, or written. */
    fun isSensitiveName(name: String): Boolean {
        val lower = name.lowercase()
        return lower in sensitiveNames ||
            lower.startsWith(".env") ||
            sensitiveExtensions.any { lower.endsWith(it) }
    }

    /** True when this directory must never be traversed or indexed. */
    fun isBlockedDirectory(name: String): Boolean = name.lowercase() in blockedDirectories

    fun describe(): String = "Credential, identity, and secret files are blocked."
}

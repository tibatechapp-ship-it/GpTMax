package dev.gptmax.app.agent

/**
 * Single source of truth for which workspace paths the agent may traverse, read, search, index, or write.
 *
 * Every tool surface (direct read, list, search traversal, overview walk, context index, write) must route
 * its decisions through this object, so a path that is invisible to one tool is invisible to all of them.
 * Before this object existed, the direct-read blocklist, the search traversal list, the index crawl list,
 * and the overview walk list disagreed with each other, which let secret-bearing directories such as
 * `.ssh` or `.aws` be reached through search and index even though direct reads rejected them.
 */
object SensitivePathPolicy {

    /** Directories that must never be traversed, listed, read, searched, indexed, or written. */
    val securityBlockedDirectories: Set<String> = setOf(
        ".git", ".ssh", ".aws", ".gcloud", ".azure", ".gnupg",
        ".gradle", "node_modules", ".gptmax",
    )

    /**
     * Directories additionally skipped by wide traversals (search, overview, context index) because they
 * hold generated artifacts or IDE state. Direct path access to them stays allowed so a developer-driven
 * agent can still inspect a specific build output when explicitly asked.
     */
    val traversalNoiseDirectories: Set<String> = setOf(
        "build", "dist", "target", ".next", ".idea", ".venv", "venv", "__pycache__",
        ".pytest_cache", ".mypy_cache", ".ruff_cache",
    )

    /** Exact file names blocked everywhere (case-insensitive). */
    private val blockedExactFileNames: Set<String> = setOf(
        ".npmrc", ".netrc", ".git-credentials", ".htpasswd", ".pgpass", ".dockercfg",
        "id_rsa", "id_ed25519", "id_ecdsa", "id_dsa",
        "known_hosts", "authorized_keys",
        "credentials.json", "credentials.yaml", "secrets.json", "secrets.yaml", "secrets.properties",
        "keystore.jks", "service-account.json", "terraform.tfvars", ".terraformrc",
    )

    /** Any file whose lower-cased name starts with one of these prefixes is blocked.
     *  Covers `.env`, `.env.local`, `.env.production`, `.env.development`, `.env.staging`, `.envrc`, ... */
    private val blockedNamePrefixes: List<String> = listOf(".env")

    /** File extensions blocked everywhere (case-insensitive). */
    private val blockedExtensions: List<String> = listOf(
        ".pem", ".p12", ".pfx", ".key", ".jks", ".keystore", ".kdbx", ".mobileprovision",
    )

    /** True when the agent may read, search, index, or write a file with this name. */
    fun isAllowedFileName(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.isBlank()) return false
        if (lower in blockedExactFileNames) return false
        if (blockedNamePrefixes.any { lower.startsWith(it) }) return false
        if (blockedExtensions.any { lower.endsWith(it) }) return false
        return true
    }

    /** True when the agent may access anything inside a directory with this name via an explicit path. */
    fun isAllowedDirectoryName(name: String): Boolean = name.lowercase() !in securityBlockedDirectories

    /** Directory filter for wide traversals: security blocklist plus generated/IDE noise. */
    fun isTraversalAllowedDirectoryName(name: String): Boolean =
        isAllowedDirectoryName(name) && name.lowercase() !in traversalNoiseDirectories

    /**
     * Validates a relative workspace path and returns its segments.
     * Throws [IllegalArgumentException] when the path is absolute, escapes the workspace, crosses a
     * blocked directory, or targets a blocked file name.
     */
    fun validateRelativePath(path: String): List<String> {
        require(path.isNotBlank()) { "Path is required." }
        require(!path.startsWith("/") && !path.startsWith("\\")) {
            "Absolute paths are not allowed."
        }
        val parts = path.split('/', '\\')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) {
            "Path traversal or empty path segment is not allowed."
        }
        val blockedDir = parts.firstOrNull { !isAllowedDirectoryName(it) }
        require(blockedDir == null) {
            "Access to credential, VCS metadata, and generated dependency directories is blocked: $blockedDir"
        }
        require(isAllowedFileName(parts.last())) {
            "Credential and secret files are blocked."
        }
        return parts
    }
}

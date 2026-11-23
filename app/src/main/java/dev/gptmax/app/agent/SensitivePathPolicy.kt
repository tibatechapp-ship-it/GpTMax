package dev.gptmax.app.agent

/**
 * Single source of truth for which workspace paths the agent may reach.
 *
 * Review finding #1: direct reads, search traversal, overview walks, and the context index each kept
 * their own exclusion lists, and the lists disagreed: `.ssh` and `.aws` were blocked for direct reads
 * but still crawled by file.search and context.index, so secrets could reach the model context
 * indirectly. Every tool surface now routes its decisions through this object.
 */
internal object SensitivePathPolicy {

    /** Secret-bearing directories: never read, crawled, indexed, listed, or written, even by direct path. */
    val forbiddenDirectories = setOf(
        ".git", ".ssh", ".aws", ".gnupg", ".gptmax", ".azure", ".gcloud",
    )

    /** Generated or IDE-only directories: reachable via an explicit direct path, but never crawled. */
    val crawlNoiseDirectories = setOf(
        ".gradle", ".idea", "build", "dist", "target", ".next", "node_modules",
        "venv", ".venv", "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache",
    )

    /** Exact secret file names (case-insensitive). */
    private val forbiddenFileNames = setOf(
        ".env", ".npmrc", ".netrc", ".git-credentials", ".htpasswd", ".pgpass", ".docker",
        "id_rsa", "id_ed25519", "id_ecdsa", "id_dsa",
        "known_hosts", "authorized_keys",
        "credentials.json", "credentials.yaml", "secrets.json", "secrets.yaml", "secrets.properties",
        "keystore.jks", "keystore", "service-account.json", "terraform.tfvars",
    )

    /** Name prefixes treated as secrets: any `.env*` variant (`.env.local`, `.env.production`, ...). */
    private val forbiddenNamePrefixes = listOf(".env")

    /** Extensions that are keys, certificates, or keystores. */
    private val forbiddenExtensions = listOf(".pem", ".p12", ".pfx", ".key", ".jks", ".keystore", ".kdbx")

    fun isForbiddenFileName(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in forbiddenFileNames) return true
        if (forbiddenNamePrefixes.any { lower.startsWith(it) }) return true
        if (forbiddenExtensions.any { lower.endsWith(it) }) return true
        return false
    }

    fun isForbiddenDirectory(name: String): Boolean = name.lowercase() in forbiddenDirectories

    /** Directory filter for search, index, and overview traversals. */
    fun isCrawlBlockedDirectory(name: String): Boolean =
        isForbiddenDirectory(name) || name.lowercase() in crawlNoiseDirectories

    /** Validates a relative workspace path; throws IllegalArgumentException when it is unsafe. */
    fun validateRelativePath(path: String): List<String> {
        require(path.isNotBlank()) { "Path is required." }
        require(!path.startsWith("/") && !path.startsWith("\\")) { "Absolute paths are not allowed." }
        val parts = path.split('/', '\\')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) {
            "Path traversal or empty path segment is not allowed."
        }
        val blockedDir = parts.firstOrNull { isForbiddenDirectory(it) }
        require(blockedDir == null) {
            "Access to credential, VCS metadata, and secret directories is blocked: $blockedDir"
        }
        require(!isForbiddenFileName(parts.last())) { "Credential and secret files are blocked." }
        return parts
    }
}

package dev.gptmax.app

private val editorBlockedNames = setOf(
    ".npmrc", ".netrc", ".git-credentials", "id_rsa", "id_ed25519",
    "credentials.json", "secrets.json", "keystore.jks"
)

private val editorBlockedDirectories = setOf(
    ".git", ".ssh", ".aws", ".gradle", "node_modules", "build", ".idea"
)

internal fun isSafeWorkspaceEditorEntry(name: String, isDirectory: Boolean): Boolean {
    val normalized = name.lowercase()
    if (name.isBlank() || name.contains('\u0000')) return false
    if (isDirectory && normalized in editorBlockedDirectories) return false
    if (!isDirectory && (
        normalized.startsWith(".env") ||
            normalized in editorBlockedNames ||
            normalized.endsWith(".pem") ||
            normalized.endsWith(".p12") ||
            normalized.endsWith(".key") ||
            normalized.endsWith(".jks") ||
            normalized.endsWith(".keystore")
        )
    ) return false
    return true
}

internal fun validateWorkspaceEditorFileName(name: String): String? {
    if (name.isBlank() || name.length > 120) return "File name must contain 1-120 characters."
    if (name == "." || name == ".." || name.contains('/') || name.contains('\\') || name.contains('\u0000')) {
        return "Invalid file name."
    }
    if (!isSafeWorkspaceEditorEntry(name, isDirectory = false)) {
        return "Creating credential or secret files through this editor is blocked."
    }
    return null
}

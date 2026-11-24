package com.tibatech.gptmax.core.guardrails

/**
 * Containment guard (review item #6): every path argument that comes from the
 * model must stay inside the granted workspace. Absolute paths, drive-letter
 * syntax and `..` traversal segments are rejected before any storage call.
 */
object PathGuard {

    /** True when [path] is safe to hand to the workspace layer. */
    fun isAllowed(path: String): Boolean {
        val clean = path.trim()
        if (clean.isBlank() || clean == "." || clean == "/") return true // workspace root
        if (clean.startsWith("/")) return false // absolute path escape
        if (clean.length > 1 && clean[1] == ':') return false // windows-style drive
        if (clean.contains('\u0000')) return false // NUL injection
        return clean.split('/').none { it == ".." } // traversal escape
    }

    fun describe(): String = "blocked: path must stay inside the workspace"
}

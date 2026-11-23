package dev.gptmax.app

/**
 * Reproducible external smoke-test model. The model is downloaded separately;
 * it is never bundled into the application APK.
 */
internal object TestModelCatalog {
    const val DISPLAY_NAME = "Qwen2.5-0.5B-Instruct Q4_K_M"
    const val FILE_NAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
    const val REPOSITORY = "Qwen/Qwen2.5-0.5B-Instruct-GGUF"
    // Pin the Hub revision so a future change to the repository's main branch
    // cannot silently change the bytes expected by this SHA-256.
    const val REVISION = "9217f5db79a29953eb74d5343926648285ec7e67"
    const val DOWNLOAD_URL =
        "https://huggingface.co/$REPOSITORY/resolve/$REVISION/$FILE_NAME"
    const val SHA256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
    const val LICENSE = "Apache-2.0"
}

internal fun verifyExpectedModelSha256(actual: String, expected: String?) {
    require(actual.matches(Regex("^[a-fA-F0-9]{64}$"))) {
        "Calculated model SHA-256 is invalid."
    }
    if (expected == null) return
    require(expected.matches(Regex("^[a-fA-F0-9]{64}$"))) {
        "Expected model SHA-256 is invalid."
    }
    require(actual.equals(expected, ignoreCase = true)) {
        "Model SHA-256 mismatch. The selected file is not the verified Qwen smoke-test artifact."
    }
}

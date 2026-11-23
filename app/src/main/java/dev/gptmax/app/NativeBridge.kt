package dev.gptmax.app

/**
 * Small JNI boundary. The real inference backend is added in M1 after the upstream
 * backend revision and native API have been pinned and reviewed.
 */
internal object NativeBridge {
    init {
        System.loadLibrary("gptmax_native")
    }

    external fun backendStatus(): String
    external fun nativeBuildInfo(): String
}

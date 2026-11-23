package dev.gptmax.app

/**
 * Narrow JNI boundary for the pinned llama.cpp backend.
 * Model paths must point to app-private files prepared by the Android storage layer.
 */
internal object NativeBridge {
    init {
        System.loadLibrary("gptmax_native")
    }

    external fun backendStatus(): String
    external fun nativeBuildInfo(): String
    external fun nativeLoadModel(modelPath: String): String
    external fun nativeUnloadModel()
    external fun nativeShutdown()
}

package com.tibatech.gptmax.bridge

/**
 * JNI facade over the native llama.cpp engine (libgptmax.so).
 *
 * All calls are blocking and serialized on the native side by a mutex —
 * call [nativeGenerate] from Dispatchers.Default, never from the main thread.
 */
interface OnTokenListener {
    /** Called for each complete UTF-8 sequence produced by the engine. */
    fun onToken(piece: String)
}

object LlamaBridge {

    init {
        System.loadLibrary("gptmax")
    }

    /** Loads a GGUF file into a llama.cpp context. Returns false on failure. */
    external fun nativeInit(modelPath: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Boolean

    /**
     * Blocking streaming generation. Returns the full completion text;
     * deltas are delivered through [listener] as they are produced.
     */
    external fun nativeGenerate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        listener: OnTokenListener?,
    ): String

    /** Cooperative stop: the running generation unwinds at the next token. */
    external fun nativeStop()

    external fun nativeIsLoaded(): Boolean

    external fun nativeLastError(): String

    external fun nativeFree()
}

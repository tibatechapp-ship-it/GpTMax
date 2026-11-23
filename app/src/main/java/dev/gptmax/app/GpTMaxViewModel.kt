package dev.gptmax.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class InferenceUiState(
    val nativeStatus: String = "Initializing native runtime…",
    val buildInfo: String = "Reading backend version…",
    val modelDetails: String = "No model selected",
    val prompt: String = "Write a short Python function that checks whether a number is prime.",
    val generatedText: String = "",
    val temperature: Float = 0.7f,
    val isBusy: Boolean = false,
    val isGenerating: Boolean = false,
    val modelReady: Boolean = false,
)

internal class GpTMaxViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(InferenceUiState())
    val state: StateFlow<InferenceUiState> = mutableState.asStateFlow()

    init {
        refreshRuntimeInfo()
    }

    fun updatePrompt(value: String) {
        mutableState.update { it.copy(prompt = value) }
    }

    fun updateTemperature(value: Float) {
        mutableState.update { it.copy(temperature = value) }
    }

    fun refreshRuntimeInfo() {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { NativeBridge.nativeBuildInfo() }
                    .getOrElse { "Native backend unavailable: ${it.message ?: "unknown error"}" }
            }
            val status = withContext(Dispatchers.IO) {
                runCatching { NativeBridge.backendStatus() }
                    .getOrElse { "Native backend unavailable: ${it.message ?: "unknown error"}" }
            }
            mutableState.update { current ->
                current.copy(
                    buildInfo = info,
                    nativeStatus = status,
                    // The JNI backend is process-scoped. Reconcile UI readiness from its
                    // authoritative status rather than assuming a newly composed screen
                    // means that the native model was unloaded.
                    modelReady = status.contains("model loaded", ignoreCase = true) ||
                        status.contains("inference context ready", ignoreCase = true),
                )
            }
        }
    }

    fun importAndLoadModel(uri: Uri) {
        if (mutableState.value.isBusy || mutableState.value.isGenerating) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isBusy = true,
                    modelReady = false,
                    nativeStatus = "Importing GGUF securely into app-private storage…",
                )
            }
            try {
                val imported = ModelFileImporter.import(getApplication(), uri)
                mutableState.update {
                    it.copy(
                        modelDetails = buildString {
                            append(imported.displayName)
                            append("\\nSize: ")
                            append(imported.sizeBytes)
                            append(" bytes\\nSHA-256: ")
                            append(imported.sha256)
                        },
                        nativeStatus = "Loading model and creating inference context…",
                    )
                }
                val result = withContext(Dispatchers.IO) {
                    NativeBridge.nativeLoadModel(imported.file.absolutePath)
                }
                val ready = result == "MODEL_READY"
                mutableState.update {
                    it.copy(
                        modelReady = ready,
                        nativeStatus = if (ready) "GGUF loaded; inference context ready."
                            else "Model load failed: $result",
                    )
                }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        modelReady = false,
                        nativeStatus = "Model import/load failed: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            } finally {
                mutableState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun generate() {
        val snapshot = mutableState.value
        if (snapshot.isBusy || snapshot.isGenerating || !snapshot.modelReady || snapshot.prompt.isBlank()) return
        mutableState.update {
            it.copy(generatedText = "", isGenerating = true, nativeStatus = "Generating locally…")
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    NativeBridge.nativeGenerate(
                        snapshot.prompt,
                        256,
                        snapshot.temperature,
                        object : TokenCallback {
                            override fun onToken(text: String) {
                                mutableState.update { current ->
                                    current.copy(generatedText = current.generatedText + text)
                                }
                            }
                        },
                    )
                }.getOrElse { "Generation error: ${it.message ?: it.javaClass.simpleName}" }
            }
            mutableState.update { current ->
                current.copy(
                    isGenerating = false,
                    nativeStatus = when {
                        result == "CANCELLED" -> "Generation cancelled."
                        result.startsWith("GENERATION_COMPLETE:") ->
                            "Local generation complete · ${result.substringAfter(':')} tokens."
                        else -> result
                    },
                )
            }
        }
    }

    fun cancelGeneration() {
        NativeBridge.nativeCancelGeneration()
    }

    override fun onCleared() {
        // Do not unload the process-scoped native model on Activity recreation.
        // Cancellation is non-blocking; process teardown owns final native cleanup.
        NativeBridge.nativeCancelGeneration()
        super.onCleared()
    }
}

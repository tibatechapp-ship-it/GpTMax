package dev.gptmax.app

import android.app.Application
import android.net.Uri
import java.io.File
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
    val storedModels: List<StoredModel> = emptyList(),
    val activeModelSha256: String? = null,
)

internal class GpTMaxViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(InferenceUiState())
    val state: StateFlow<InferenceUiState> = mutableState.asStateFlow()

    private val sessionPreferences = application.getSharedPreferences("inference_session", Application.MODE_PRIVATE)

    init {
        refreshRuntimeInfo()
        refreshModels()
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
            val ready = status.contains("model loaded", ignoreCase = true) ||
                status.contains("inference context ready", ignoreCase = true)
            val activeSha = if (ready) {
                sessionPreferences.getString("active_model_sha256", null)
            } else {
                sessionPreferences.edit().remove("active_model_sha256").apply()
                null
            }
            mutableState.update { current ->
                current.copy(
                    buildInfo = info,
                    nativeStatus = status,
                    modelReady = ready,
                    activeModelSha256 = activeSha,
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
                            append("\nSize: ")
                            append(imported.sizeBytes)
                            append(" bytes\nSHA-256: ")
                            append(imported.sha256)
                        },
                        nativeStatus = "Loading model and creating inference context…",
                    )
                }
                val result = withContext(Dispatchers.IO) {
                    NativeBridge.nativeLoadModel(imported.file.absolutePath)
                }
                val ready = result == "MODEL_READY"
                val storedModels = withContext(Dispatchers.IO) {
                    ModelStore.list(File(getApplication<Application>().filesDir, "models"))
                }
                if (ready) {
                    sessionPreferences.edit().putString("active_model_sha256", imported.sha256).apply()
                } else {
                    sessionPreferences.edit().remove("active_model_sha256").apply()
                }
                mutableState.update {
                    it.copy(
                        modelReady = ready,
                        activeModelSha256 = if (ready) imported.sha256 else null,
                        nativeStatus = if (ready) "GGUF loaded; inference context ready."
                            else "Model load failed: $result",
                        storedModels = storedModels,
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

    fun refreshModels() {
        viewModelScope.launch {
            val models = withContext(Dispatchers.IO) {
                val directory = File(getApplication<Application>().filesDir, "models")
                ModelStore.cleanupIncomplete(directory)
                ModelStore.list(directory)
            }
            mutableState.update { it.copy(storedModels = models) }
        }
    }

    fun deleteModel(sha256: String) {
        val snapshot = mutableState.value
        if (snapshot.isBusy || snapshot.isGenerating) return
        if (snapshot.modelReady && snapshot.activeModelSha256 == null) {
            mutableState.update {
                it.copy(nativeStatus = "Loaded model identity is unknown. Unload the runtime before deleting stored files.")
            }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(isBusy = true, nativeStatus = "Removing stored model…") }
            try {
                withContext(Dispatchers.IO) {
                    val directory = File(getApplication<Application>().filesDir, "models")
                    if (snapshot.activeModelSha256 == sha256) {
                        NativeBridge.nativeUnloadModel()
                    }
                    ModelStore.delete(directory, sha256)
                }
                if (snapshot.activeModelSha256 == sha256) {
                    sessionPreferences.edit().remove("active_model_sha256").apply()
                }
                mutableState.update { current ->
                    current.copy(
                        modelReady = if (current.activeModelSha256 == sha256) false else current.modelReady,
                        activeModelSha256 = if (current.activeModelSha256 == sha256) null else current.activeModelSha256,
                        nativeStatus = if (current.activeModelSha256 == sha256) "Model unloaded and deleted."
                            else "Stored model deleted.",
                    )
                }
                refreshModels()
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(nativeStatus = "Could not delete model: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                mutableState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun unloadModel() {
        val snapshot = mutableState.value
        if (snapshot.isBusy || snapshot.isGenerating || !snapshot.modelReady) return
        viewModelScope.launch {
            mutableState.update { it.copy(isBusy = true, nativeStatus = "Unloading native model…") }
            try {
                withContext(Dispatchers.IO) { NativeBridge.nativeUnloadModel() }
                sessionPreferences.edit().remove("active_model_sha256").apply()
                mutableState.update {
                    it.copy(
                        modelReady = false,
                        activeModelSha256 = null,
                        nativeStatus = "Model unloaded; stored file retained.",
                    )
                }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(nativeStatus = "Model unload failed: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                mutableState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun generate() {
        val snapshot = mutableState.value
        if (snapshot.isBusy || snapshot.isGenerating || !snapshot.modelReady || snapshot.prompt.isBlank()) return
        NativeBridge.nativeBeginGeneration()
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

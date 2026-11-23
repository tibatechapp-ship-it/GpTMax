package dev.gptmax.app

import android.app.Application
import android.net.Uri
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicBoolean
import dev.gptmax.app.agent.AgentRunLimits
import dev.gptmax.app.agent.AgentRuntime
import dev.gptmax.app.agent.LocalLlamaAgentModel
import dev.gptmax.app.agent.ToolRisk
import dev.gptmax.app.agent.ToolExecutionStatus
import dev.gptmax.app.agent.createDocumentTreeToolRegistry
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
    val workspaceTreeUri: String? = null,
    val workspaceLabel: String = "No project folder selected",
    val agentRequest: String = "Inspect this project, identify its architecture and highest-priority issues, and report evidence. Do not edit files.",
    val agentOutput: String = "Select a project folder and load a GGUF model to start the coding agent.",
    val agentToolLog: List<String> = emptyList(),
    val agentRunning: Boolean = false,
    val agentWriteEnabled: Boolean = false,
)

internal class GpTMaxViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(InferenceUiState())
    val state: StateFlow<InferenceUiState> = mutableState.asStateFlow()

    private val sessionPreferences = application.getSharedPreferences("inference_session", Application.MODE_PRIVATE)
    private var agentCancellation: AtomicBoolean? = null

    init {
        val savedWorkspace = sessionPreferences.getString("workspace_tree_uri", null)
        if (savedWorkspace != null) {
            mutableState.update { it.copy(workspaceTreeUri = savedWorkspace, workspaceLabel = Uri.parse(savedWorkspace).lastPathSegment ?: "Selected project") }
        }
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
            val ready = isNativeModelReady(status)
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

    fun importAndLoadModel(uri: Uri, expectedSha256: String? = null) {
        if (mutableState.value.isBusy || mutableState.value.isGenerating || mutableState.value.agentRunning) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isBusy = true,
                    modelReady = false,
                    nativeStatus = "Importing GGUF securely into app-private storage…",
                )
            }
            try {
                val imported = ModelFileImporter.import(getApplication(), uri, expectedSha256)
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

    fun loadStoredModel(sha256: String) {
        val snapshot = mutableState.value
        if (snapshot.isBusy || snapshot.isGenerating || snapshot.agentRunning) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(isBusy = true, nativeStatus = "Preparing stored GGUF model…")
            }
            try {
                val model = withContext(Dispatchers.IO) {
                    val directory = File(getApplication<Application>().filesDir, "models")
                    ModelStore.findBySha256(directory, sha256)
                        ?: throw IllegalArgumentException("Stored model was not found.")
                }
                mutableState.update {
                    it.copy(
                        modelDetails = buildString {
                            append(model.displayName)
                            append("\nSize: ")
                            append(model.sizeBytes)
                            append(" bytes\nSHA-256: ")
                            append(model.sha256)
                        },
                        nativeStatus = "Loading stored model and creating inference context…",
                    )
                }
                val result = withContext(Dispatchers.IO) {
                    NativeBridge.nativeLoadModel(model.file.absolutePath)
                }
                val ready = result == "MODEL_READY"
                if (ready) {
                    sessionPreferences.edit().putString("active_model_sha256", model.sha256).apply()
                } else {
                    sessionPreferences.edit().remove("active_model_sha256").apply()
                }
                mutableState.update {
                    it.copy(
                        modelReady = ready,
                        activeModelSha256 = if (ready) model.sha256 else null,
                        nativeStatus = if (ready) "Stored GGUF loaded; inference context ready."
                            else "Stored model load failed: $result",
                    )
                }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        nativeStatus = "Could not load stored model: ${error.message ?: error.javaClass.simpleName}"
                    )
                }
            } finally {
                mutableState.update { it.copy(isBusy = false) }
            }
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
        if (snapshot.isBusy || snapshot.isGenerating || snapshot.agentRunning || !snapshot.modelReady) return
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


    fun updateAgentRequest(value: String) {
        mutableState.update { it.copy(agentRequest = value) }
    }

    fun setAgentWriteEnabled(enabled: Boolean) {
        mutableState.update { it.copy(agentWriteEnabled = enabled) }
    }

    fun selectWorkspace(uri: Uri) {
        val label = uri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: "Selected project folder"
        sessionPreferences.edit().putString("workspace_tree_uri", uri.toString()).apply()
        mutableState.update {
            it.copy(
                workspaceTreeUri = uri.toString(),
                workspaceLabel = label,
                agentOutput = "Project folder selected. Load a GGUF model to begin.",
            )
        }
    }

    fun runAgent() {
        val snapshot = mutableState.value
        if (snapshot.agentRunning || snapshot.isBusy || snapshot.isGenerating) return
        if (!snapshot.modelReady) {
            mutableState.update { it.copy(agentOutput = "Load a GGUF model before starting the coding agent.") }
            return
        }
        val treeUri = snapshot.workspaceTreeUri
        if (treeUri == null) {
            mutableState.update { it.copy(agentOutput = "Choose a project folder first.") }
            return
        }
        if (snapshot.agentRequest.isBlank()) return

        val cancellation = AtomicBoolean(false)
        agentCancellation = cancellation
        mutableState.update {
            it.copy(agentRunning = true, agentOutput = "Agent is inspecting the selected project…", agentToolLog = emptyList())
        }
        viewModelScope.launch {
            try {
                val registry = createDocumentTreeToolRegistry(getApplication(), Uri.parse(treeUri))
                val capabilities = buildSet {
                    add(ToolRisk.READ_ONLY)
                    if (snapshot.agentWriteEnabled) add(ToolRisk.WORKSPACE_WRITE)
                }
                val result = AgentRuntime(
                    model = LocalLlamaAgentModel(maxOutputTokens = 384),
                    registry = registry,
                    limits = AgentRunLimits(maxToolCalls = 8, maxObservationChars = 6_000),
                ).run(
                    request = snapshot.agentRequest,
                    grantedCapabilities = capabilities,
                    cancellation = cancellation,
                )
                val log = result.toolResults.map { tool ->
                    "${tool.toolName} · ${tool.status}\n${tool.output.take(2_500)}"
                }
                mutableState.update {
                    it.copy(
                        agentOutput = "${result.status}:\n${result.answer}",
                        agentToolLog = log,
                    )
                }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(agentOutput = "Agent failed: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                agentCancellation = null
                mutableState.update { it.copy(agentRunning = false) }
            }
        }
    }

    fun stopAgent() {
        agentCancellation?.set(true)
        NativeBridge.nativeCancelGeneration()
        mutableState.update { it.copy(agentOutput = "Cancellation requested…") }
    }

    fun generate() {
        val snapshot = mutableState.value
        if (snapshot.isBusy || snapshot.isGenerating || snapshot.agentRunning || !snapshot.modelReady || snapshot.prompt.isBlank()) return
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
        agentCancellation?.set(true)
        NativeBridge.nativeCancelGeneration()
        super.onCleared()
    }
}

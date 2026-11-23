package com.tibatech.gptmax.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tibatech.gptmax.core.agent.AgentEvent
import com.tibatech.gptmax.core.agent.AgentPhase
import com.tibatech.gptmax.core.agent.AgentStep
import com.tibatech.gptmax.core.agent.AgentTask
import com.tibatech.gptmax.core.prompts.PromptTemplates
import com.tibatech.gptmax.core.router.ChatMessage
import com.tibatech.gptmax.core.router.LlmRequest
import com.tibatech.gptmax.core.router.Source
import com.tibatech.gptmax.data.ServiceLocator
import com.tibatech.gptmax.data.db.AgentRunEntity
import com.tibatech.gptmax.data.db.MessageEntity
import com.tibatech.gptmax.data.repo.DownloadState
import com.tibatech.gptmax.data.repo.ModelCatalogEntry
import com.tibatech.gptmax.data.repo.WorkspaceRepository
import com.tibatech.gptmax.data.settings.GpTMaxSettings
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Chat — local-first answers with a cloud fallback badge.
// ---------------------------------------------------------------------------

class ChatViewModel : ViewModel() {

    private val db = ServiceLocator.database
    private val router = ServiceLocator.router
    private val sessionId = "main"

    private val _streamText = MutableStateFlow("")
    val streamText: StateFlow<String> = _streamText.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _badge = MutableStateFlow<Source?>(null)
    val badge: StateFlow<Source?> = _badge.asStateFlow()

    val messages: StateFlow<List<MessageEntity>> =
        db.messageDao().observe(sessionId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var streamJob: Job? = null

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _streaming.value) return
        streamJob = viewModelScope.launch {
            _streaming.value = true
            _streamText.value = ""
            db.messageDao().insert(MessageEntity(sessionId = sessionId, role = "user", content = trimmed))

            val history = messages.value.takeLast(20).map {
                ChatMessage(role = it.role, content = it.content)
            }
            val client = router.forTask(com.tibatech.gptmax.core.router.TaskKind.CHAT)
            _badge.value = client.source

            val result = runCatching {
                client.stream(
                    LlmRequest(
                        messages = listOf(ChatMessage("system", PromptTemplates.chatSystem())) +
                            history +
                            ChatMessage("user", trimmed),
                        maxTokens = 1200,
                    ),
                ) { delta -> _streamText.value += delta }
            }.onFailure { _streamText.value += "\n[error] ${it.message}" }

            val answer = _streamText.value
            db.messageDao().insert(
                MessageEntity(
                    sessionId = sessionId,
                    role = "assistant",
                    content = answer,
                    source = _badge.value?.name ?: "",
                ),
            )
            _streamText.value = ""
            _streaming.value = false
        }
    }

    /** Per-request cancellation (review #5): cancels the stream job, not just the engine. */
    fun cancel() {
        streamJob?.cancel()
        ServiceLocator.localEngine.cancel()
        _streaming.value = false
    }
}

// ---------------------------------------------------------------------------
// Agent — Plan -> Gather -> Act -> Validate (+ approval gate).
// ---------------------------------------------------------------------------

class AgentViewModel : ViewModel() {

    private val core = ServiceLocator.agentCore
    private val approval = ServiceLocator.approvalGate
    private val db = ServiceLocator.database
    private val runState = ServiceLocator.agentRunState
    private var runRowId: Long = 0
    private var runJob: Job? = null

    val phase = MutableStateFlow<AgentPhase>(AgentPhase.IDLE)
    val steps = MutableStateFlow<List<AgentStep>>(emptyList())
    val logs = MutableStateFlow<List<String>>(emptyList())
    val approvalRequest = approval.pending

    /** Shared flag other screens (Models) guard destructive actions on (review #3). */
    val agentRunning = runState.isRunning

    init {
        viewModelScope.launch {
            core.events.collect { e ->
                when (e) {
                    is AgentEvent.PhaseChanged -> phase.value = e.phase
                    is AgentEvent.StepStarted -> upsert(e.step)
                    is AgentEvent.StepFinished -> upsert(e.step)
                    is AgentEvent.ApprovalRequired -> appendLog(
                        "approval needed: ${e.step.tool} ${e.step.description}",
                    )
                    is AgentEvent.Log -> appendLog(e.message)
                    is AgentEvent.Cancelled -> appendLog("run cancelled by user")
                    is AgentEvent.Completed -> {
                        appendLog("completed: ${e.summary} (${e.durationMs} ms)")
                        if (runRowId != 0L) {
                            db.agentRunDao().finish(
                                runRowId, AgentPhase.DONE.name, e.summary,
                                System.currentTimeMillis(),
                            )
                        }
                    }
                    is AgentEvent.Failed -> {
                        appendLog("failed: ${e.error}")
                        if (runRowId != 0L) {
                            db.agentRunDao().finish(
                                runRowId, AgentPhase.FAILED.name, e.error,
                                System.currentTimeMillis(),
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun start(goal: String) {
        val trimmed = goal.trim()
        if (trimmed.isEmpty()) return
        if (runState.isRunning.value) return // one run at a time (review #5)
        steps.value = emptyList()
        logs.value = emptyList()
        runJob = viewModelScope.launch {
            runState.start()
            try {
                runRowId = db.agentRunDao().insert(AgentRunEntity(goal = trimmed, phase = "PLANNING"))
                core.run(AgentTask(id = UUID.randomUUID().toString(), goal = trimmed))
            } finally {
                runState.stop()
            }
        }
    }

    /** Per-request cancellation (review #5): stops the run at the next step boundary. */
    fun cancel() {
        runJob?.cancel()
    }

    fun respond(approved: Boolean) = approval.respond(approved)

    private fun upsert(step: AgentStep) {
        val list = steps.value.toMutableList()
        val idx = list.indexOfFirst { it.index == step.index }
        if (idx >= 0) list[idx] = step else list += step
        steps.value = list.sortedBy { it.index }
    }

    private fun appendLog(message: String) {
        logs.value = (logs.value + message).takeLast(30)
    }
}

// ---------------------------------------------------------------------------
// Models — GGUF catalog, download progress, engine load.
// ---------------------------------------------------------------------------

class ModelsViewModel : ViewModel() {

    private val models = ServiceLocator.models
    private val local = ServiceLocator.localEngine
    private val settings = ServiceLocator.settings

    val catalog = com.tibatech.gptmax.data.repo.ModelCatalog.entries

    val installed = MutableStateFlow<List<File>>(emptyList())
    val downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val engineLoaded = MutableStateFlow(false)
    val engineError = MutableStateFlow("")
    val loadedModel = MutableStateFlow("")

    init {
        refresh()
        engineLoaded.value = local.isLoaded
        viewModelScope.launch {
            loadedModel.value = settings.snapshot().localModelPath
        }
    }

    fun refresh() {
        installed.value = models.installed()
    }

    fun download(entry: ModelCatalogEntry) {
        if (downloads.value[entry.id] is DownloadState.Progress) return
        viewModelScope.launch {
            models.download(entry).collect { state ->
                downloads.value = downloads.value + (entry.id to state)
                if (state is DownloadState.Done) refresh()
            }
        }
    }

    fun load(file: File) {
        viewModelScope.launch {
            val s: GpTMaxSettings = settings.snapshot()
            val ok = local.load(file.absolutePath, s.nCtx, s.nThreads)
            engineLoaded.value = ok && local.isLoaded
            if (ok) {
                engineError.value = ""
                loadedModel.value = file.absolutePath
                settings.setLocalModel(file.absolutePath)
            } else {
                engineError.value = local.lastError.ifBlank { "load failed" }
            }
        }
    }

    fun unload() {
        // Review #3: never release the model under an active agent session.
        if (ServiceLocator.agentRunState.isRunning.value) {
            engineError.value = "cannot unload: the coding agent is running — stop the task first"
            return
        }
        local.unload()
        engineLoaded.value = false
        loadedModel.value = ""
    }

    fun delete(file: File) {
        // Review #3: deleting model files mid-run unloads the native model
        // underneath an active session — refuse and tell the user why.
        if (ServiceLocator.agentRunState.isRunning.value) {
            engineError.value =
                "cannot delete ${file.name}: the coding agent is running — stop the task first"
            return
        }
        models.delete(file)
        refresh()
    }
}

// ---------------------------------------------------------------------------
// Editor — workspace files over SAF.
// ---------------------------------------------------------------------------

class EditorViewModel : ViewModel() {

    private val workspace = ServiceLocator.workspace

    val files = MutableStateFlow<List<WorkspaceRepository.FileNode>>(emptyList())
    val openFile = MutableStateFlow("")
    val content = MutableStateFlow("")
    val dirty = MutableStateFlow(false)

    /** Reason the last save was blocked (policy or write guard) — shown by the editor UI. */
    val saveError = MutableStateFlow("")

    val hasRoot: StateFlow<Boolean> =
        workspace.root.map { it != null }
            .stateIn(viewModelScope, SharingStarted.Eagerly, workspace.root.value != null)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { files.value = collectFiles(maxDepth = 4) }
    }

    fun open(path: String) {
        viewModelScope.launch {
            content.value = workspace.read(path) ?: ""
            openFile.value = path
            dirty.value = false
            saveError.value = ""
        }
    }

    fun onContentChange(value: String) {
        content.value = value
        dirty.value = true
    }

    fun save() {
        val path = openFile.value
        if (path.isBlank()) return
        viewModelScope.launch {
            when (val result = workspace.write(path, content.value)) {
                is WorkspaceRepository.WriteResult.Written -> {
                    dirty.value = false
                    saveError.value = ""
                }
                is WorkspaceRepository.WriteResult.Blocked -> {
                    saveError.value = result.reason
                }
            }
        }
    }

    private suspend fun collectFiles(maxDepth: Int): List<WorkspaceRepository.FileNode> {
        val out = mutableListOf<WorkspaceRepository.FileNode>()
        suspend fun walk(path: String, depth: Int) {
            if (depth > maxDepth || out.size > 400) return
            workspace.list(path).forEach { node ->
                if (node.isDir) walk(node.path, depth + 1) else out += node
            }
        }
        walk("")
        return out.sortedBy { it.path }
    }
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

class SettingsViewModel : ViewModel() {

    private val settings = ServiceLocator.settings

    val state: StateFlow<GpTMaxSettings?> =
        settings.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val presets = com.tibatech.gptmax.core.router.CloudBridge.PRESETS

    fun saveProvider(config: com.tibatech.gptmax.core.router.ProviderConfig) {
        viewModelScope.launch { settings.updateProvider(config) }
    }

    fun setCloudFirst(value: Boolean) {
        viewModelScope.launch { settings.setCloudFirst(value) }
    }

    fun setAutoApprove(value: Boolean) {
        viewModelScope.launch { settings.setAutoApprove(value) }
    }

    fun setEngineTuning(nCtx: Int, nThreads: Int) {
        viewModelScope.launch { settings.setEngineTuning(nCtx, nThreads) }
    }

    fun applyWorkspace(uri: android.net.Uri): Boolean =
        ServiceLocator.workspace.setRoot(uri)
}

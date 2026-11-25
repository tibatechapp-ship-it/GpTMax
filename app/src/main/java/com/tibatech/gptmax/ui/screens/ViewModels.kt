package com.tibatech.gptmax.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tibatech.gptmax.core.agent.AgentEvent
import com.tibatech.gptmax.core.agent.AgentPhase
import com.tibatech.gptmax.core.agent.AgentStep
import com.tibatech.gptmax.core.agent.AgentTask
import com.tibatech.gptmax.core.agent.ResumableRun
import com.tibatech.gptmax.core.agent.SaveTransaction
import com.tibatech.gptmax.core.agent.SessionPinner
import com.tibatech.gptmax.core.agent.toAgentSteps
import com.tibatech.gptmax.core.guardrails.InferenceSafety
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Chat — local-first answers with a cloud fallback badge.
// ---------------------------------------------------------------------------

class ChatViewModel : ViewModel() {

    private val db = ServiceLocator.database
    private val router = ServiceLocator.router

    // Phase 1 (external review): the chat session is pinned to the granted
    // workspace — switching projects mints a fresh session instead of
    // appending new-project messages to the old project's thread.
    private val workspace = ServiceLocator.workspace
    private val pinner = SessionPinner()
    private val _sessionId = MutableStateFlow(
        pinner.bind(workspace.root.value?.uri?.toString()),
    )

    private val _streamText = MutableStateFlow("")
    val streamText: StateFlow<String> = _streamText.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _badge = MutableStateFlow<Source?>(null)
    val badge: StateFlow<Source?> = _badge.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<MessageEntity>> =
        _sessionId.flatMapLatest { sid -> db.messageDao().observe(sid) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Per-request cancellation (review #5): the job is cancelled, not just the engine. */
    private var streamJob: Job? = null

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _streaming.value) return
        // Re-bind BEFORE writing: a workspace switch isolates this message
        // in a brand-new session (phase 1 session pinning).
        val sid = pinner.bind(workspace.root.value?.uri?.toString())
        if (sid != _sessionId.value) _sessionId.value = sid
        streamJob = viewModelScope.launch {
            _streaming.value = true
            _streamText.value = ""
            db.messageDao().insert(MessageEntity(sessionId = sid, role = "user", content = trimmed))

            val history = messages.value.takeLast(20).map {
                ChatMessage(role = it.role, content = it.content)
            }
            val client = router.forTask(com.tibatech.gptmax.core.router.TaskKind.CHAT)
            _badge.value = client.source

            runCatching {
                client.stream(
                    LlmRequest(
                        messages = listOf(ChatMessage("system", PromptTemplates.chatSystem())) +
                            history +
                            ChatMessage("user", trimmed),
                        maxTokens = 1200,
                    ),
                ) { delta ->
                    // Phase 2: the accumulated chat text is hard-capped so a
                    // runaway provider cannot grow the buffer without bound.
                    if (_streamText.value.length < STREAM_CAP_CHARS) {
                        _streamText.value += delta
                    }
                }
            }.onFailure { _streamText.value += "\n${InferenceSafety.failure("stream error: ${it.message}")}" }

            // Phase 2: control characters and overlong answers never enter
            // the persisted history.
            val answer = InferenceSafety.sanitize(_streamText.value)
            db.messageDao().insert(
                MessageEntity(
                    sessionId = sid,
                    role = "assistant",
                    content = answer,
                    source = _badge.value?.name ?: "",
                ),
            )
            _streamText.value = ""
            _streaming.value = false
        }
    }

    fun cancel() {
        streamJob?.cancel()
        streamJob = null
        ServiceLocator.localEngine.cancel()
        _streaming.value = false
    }

    private companion object {
        /** Phase 2: hard cap on the accumulated streaming text (chars). */
        const val STREAM_CAP_CHARS = 64_000
    }
}

// ---------------------------------------------------------------------------
// Agent — Plan -> Gather -> Act -> Validate (+ approval gate).
// ---------------------------------------------------------------------------

class AgentViewModel : ViewModel() {

    private val core = ServiceLocator.agentCore
    private val approval = ServiceLocator.approvalGate
    private val db = ServiceLocator.database

    // Phase 1 (external review): runs are pinned to the workspace they
    // started on; a mid-run switch cancels the run (and any pending
    // approval) instead of letting tools operate on a different project.
    private val workspace = ServiceLocator.workspace
    private var pinnedWorkspaceUri: String? = null
    private var runRowId: Long = 0

    // Phase 3 (external review, resumability): an interrupted run leaves a
    // persisted snapshot; the screen offers Resume / Discard until cleared.
    private val resumeStore = ServiceLocator.resumeStore
    val resumable = MutableStateFlow<ResumableRun?>(resumeStore.load())

    val phase = MutableStateFlow<AgentPhase>(AgentPhase.IDLE)
    val steps = MutableStateFlow<List<AgentStep>>(emptyList())
    val logs = MutableStateFlow<List<String>>(emptyList())

    /** Live view of the agent's running flag — screens disable destructive actions when true. */
    val running: StateFlow<Boolean> = core.running

    /** Perf measurements (review #6): "label 123ms" lines, newest last. */
    val perf = MutableStateFlow<List<String>>(emptyList())

    val approvalRequest = approval.pending

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
                    is AgentEvent.Metrics -> {
                        perf.value = (perf.value + "${e.label} ${e.millis}ms").takeLast(20)
                        appendLog("perf: ${e.label} ${e.millis}ms")
                    }
                    is AgentEvent.Resumed -> appendLog(
                        "resumed: ${e.fromStep}/${e.totalSteps} steps already done",
                    )
                    is AgentEvent.Completed -> {
                        appendLog("completed: ${e.summary}")
                        resumable.value = null // phase 3: done — snapshot cleared
                        if (runRowId != 0L) {
                            db.agentRunDao().finish(
                                runRowId, AgentPhase.DONE.name, e.summary,
                                System.currentTimeMillis(),
                            )
                        }
                    }
                    is AgentEvent.Failed -> {
                        appendLog("failed: ${e.error}")
                        // Phase 3: a cancelled/failed run stays resumable.
                        resumable.value = resumeStore.load()
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
        viewModelScope.launch {
            var seen: String? = workspace.root.value?.uri?.toString()
            workspace.root.collect { root ->
                val now = root?.uri?.toString()
                if (now != seen) {
                    seen = now
                    if (core.running.value) {
                        core.cancel()
                        approval.cancelPending()
                        appendLog("workspace changed mid-run — agent cancelled")
                    }
                }
            }
        }
    }

    fun start(goal: String) {
        val trimmed = goal.trim()
        if (trimmed.isEmpty() || core.running.value) return
        pinnedWorkspaceUri = workspace.root.value?.uri?.toString()
        steps.value = emptyList()
        logs.value = emptyList()
        perf.value = emptyList()
        viewModelScope.launch {
            runRowId = db.agentRunDao().insert(AgentRunEntity(goal = trimmed, phase = "PLANNING"))
            core.run(
                AgentTask(
                    id = UUID.randomUUID().toString(),
                    goal = trimmed,
                    // Phase 3: the run is pinned to its workspace so a later
                    // resume refuses a different project.
                    workspaceUri = pinnedWorkspaceUri,
                ),
            )
        }
    }

    /**
     * Phase 3 (resumability): resumes the persisted interrupted run. The
     * workspace is checked FIRST — a snapshot from project A is never
     * replayed against project B. Completed steps are skipped by the core;
     * every pending write re-passes the approval gate.
     */
    fun resume() {
        val snapshot = resumable.value ?: return
        if (core.running.value) return
        val current = workspace.root.value?.uri?.toString()
        if (snapshot.workspaceUri != null && snapshot.workspaceUri != current) {
            appendLog("cannot resume: the run was pinned to a different workspace")
            return
        }
        steps.value = snapshot.toAgentSteps().sortedBy { it.index }
        logs.value = emptyList()
        perf.value = emptyList()
        pinnedWorkspaceUri = current
        viewModelScope.launch {
            runRowId = db.agentRunDao().insert(AgentRunEntity(goal = snapshot.goal, phase = "ACTING"))
            core.run(
                AgentTask(
                    id = snapshot.taskId,
                    goal = snapshot.goal,
                    workspaceUri = snapshot.workspaceUri,
                ),
                resume = snapshot,
            )
        }
    }

    /** Phase 3: the user declines to resume — the snapshot is discarded. */
    fun dismissResume() {
        resumeStore.clear()
        resumable.value = null
    }

    /**
     * Phase 1 (external review): cancel must also END an in-flight approval
     * wait — the run may be suspended inside the ApprovalGate, where the
     * cancelled flag alone would never take effect.
     *
     * Phase 2: the cancel also reaches the NATIVE engine — a plan/validate
     * generation stuck in a 10-minute local call stops instead of burning
     * CPU for minutes after the user pressed Stop.
     */
    fun stop() {
        core.cancel()
        approval.cancelPending()
        ServiceLocator.localEngine.cancel()
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
    private val agent = ServiceLocator.agentCore

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
        // Review #3: refuse while the agent runs OR the engine is mid-generation
        // (chat streaming) — swapping underneath a native read would crash.
        if (agent.running.value || local.busy) {
            engineError.value = "cannot swap models: a generation is running"
            return
        }
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

    /** Review #3: refuse to unload while the agent or a chat stream holds the engine. */
    fun unload() {
        if (agent.running.value || local.busy) {
            engineError.value = "cannot unload: a generation is running"
            return
        }
        local.unload()
        engineLoaded.value = false
        loadedModel.value = ""
    }

    /** Review #3: refuse to delete the model file a run or stream is reading. */
    fun delete(file: File) {
        if (agent.running.value || local.busy) {
            engineError.value = "cannot delete: a generation is running on this model"
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

    // Phase 1 (external review, high severity): save is a verified
    // transaction — the "unsaved" flag clears ONLY after the write is
    // confirmed by read-back; failures keep the edits unsaved and surface
    // an error instead of silently reporting success.
    private val saveTransaction = SaveTransaction(workspace)

    val files = MutableStateFlow<List<WorkspaceRepository.FileNode>>(emptyList())
    val openFile = MutableStateFlow("")
    val content = MutableStateFlow("")
    val dirty = MutableStateFlow(false)
    val saving = MutableStateFlow(false)
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
        }
    }

    fun onContentChange(value: String) {
        content.value = value
        dirty.value = true
    }

    fun save() {
        val path = openFile.value
        if (path.isBlank() || saving.value) return
        viewModelScope.launch {
            saving.value = true
            saveError.value = ""
            val outcome = saveTransaction.save(path, content.value)
            saving.value = false
            if (outcome.confirmed) {
                dirty.value = false
            } else {
                // The unsaved flag stays TRUE: the user's edits are not on
                // disk yet, and the reason is surfaced instead of swallowed.
                saveError.value = outcome.error
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
        walk("", 0)
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

package com.tibatech.gptmax.data

import android.content.Context
import com.tibatech.gptmax.core.agent.AgentCore
import com.tibatech.gptmax.core.agent.ToolRegistry
import com.tibatech.gptmax.core.context.ContextBuilder
import com.tibatech.gptmax.core.guardrails.ApprovalGate
import com.tibatech.gptmax.core.router.CloudBridge
import com.tibatech.gptmax.core.router.InferenceRouter
import com.tibatech.gptmax.core.router.LocalEngine
import com.tibatech.gptmax.data.db.AppDatabase
import com.tibatech.gptmax.data.repo.ModelRepository
import com.tibatech.gptmax.data.repo.WorkspaceRepository
import com.tibatech.gptmax.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Minimal service locator — no DI framework by design, so the whole project
 * builds with zero extra configuration.
 */
object ServiceLocator {

    lateinit var scope: CoroutineScope
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var database: AppDatabase
        private set
    lateinit var workspace: WorkspaceRepository
        private set
    lateinit var models: ModelRepository
        private set
    lateinit var localEngine: LocalEngine
        private set
    lateinit var cloudBridge: CloudBridge
        private set
    lateinit var router: InferenceRouter
        private set
    lateinit var approvalGate: ApprovalGate
        private set
    lateinit var agentCore: AgentCore
        private set

    fun init(context: Context) {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        settings = SettingsStore(context.applicationContext)
        database = AppDatabase.build(context.applicationContext)
        workspace = WorkspaceRepository(context.applicationContext)
        models = ModelRepository(context.applicationContext)
        localEngine = LocalEngine()
        cloudBridge = CloudBridge(configProvider = { settings.snapshot().provider })
        router = InferenceRouter(settings, localEngine, cloudBridge)
        approvalGate = ApprovalGate(autoApproveProvider = {
            runCatching { settings.snapshot().autoApproveWrites }.getOrDefault(false)
        })
        agentCore = AgentCore(
            router = router,
            tools = ToolRegistry(workspace),
            context = ContextBuilder(workspace),
            approval = approvalGate,
        )
    }
}

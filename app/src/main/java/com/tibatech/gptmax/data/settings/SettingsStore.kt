package com.tibatech.gptmax.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tibatech.gptmax.core.router.CloudBridge
import com.tibatech.gptmax.core.router.ProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "gptmax_settings")

/** Immutable snapshot used by the router on every request. */
data class GpTMaxSettings(
    val provider: ProviderConfig,
    val cloudFirst: Boolean,
    val cloudFirstAct: Boolean,
    val autoApproveWrites: Boolean,
    val localModelPath: String,
    val nCtx: Int,
    val nThreads: Int,
) {
    val cloudApiKey: String get() = provider.apiKey
}

/**
 * DataStore-backed settings. The API key stays on-device only; the cloud
 * bridge reads it per request (BYOK — never uploaded anywhere else).
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val CLOUD_NAME = stringPreferencesKey("cloud_name")
        val CLOUD_BASE_URL = stringPreferencesKey("cloud_base_url")
        val CLOUD_API_KEY = stringPreferencesKey("cloud_api_key")
        val CLOUD_MODEL = stringPreferencesKey("cloud_model")
        val CLOUD_TEMP = floatPreferencesKey("cloud_temperature")
        val CLOUD_MAX_TOKENS = intPreferencesKey("cloud_max_tokens")
        val CLOUD_FIRST = booleanPreferencesKey("cloud_first")
        val CLOUD_FIRST_ACT = booleanPreferencesKey("cloud_first_act")
        val AUTO_APPROVE = booleanPreferencesKey("auto_approve_writes")
        val LOCAL_MODEL_PATH = stringPreferencesKey("local_model_path")
        val N_CTX = intPreferencesKey("n_ctx")
        val N_THREADS = intPreferencesKey("n_threads")
    }

    val settingsFlow: Flow<GpTMaxSettings> = context.dataStore.data.map { p ->
        GpTMaxSettings(
            provider = ProviderConfig(
                name = p[Keys.CLOUD_NAME] ?: CloudBridge.PRESETS.first().name,
                baseUrl = p[Keys.CLOUD_BASE_URL] ?: CloudBridge.PRESETS.first().baseUrl,
                apiKey = p[Keys.CLOUD_API_KEY] ?: "",
                model = p[Keys.CLOUD_MODEL] ?: CloudBridge.PRESETS.first().model,
                temperature = p[Keys.CLOUD_TEMP] ?: 0.2f,
                maxTokens = p[Keys.CLOUD_MAX_TOKENS] ?: 8192,
            ),
            cloudFirst = p[Keys.CLOUD_FIRST] ?: false,
            cloudFirstAct = p[Keys.CLOUD_FIRST_ACT] ?: false,
            autoApproveWrites = p[Keys.AUTO_APPROVE] ?: false,
            localModelPath = p[Keys.LOCAL_MODEL_PATH] ?: "",
            nCtx = p[Keys.N_CTX] ?: 4096,
            nThreads = p[Keys.N_THREADS]
                ?: (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2),
        )
    }

    suspend fun snapshot(): GpTMaxSettings = settingsFlow.first()

    suspend fun updateProvider(config: ProviderConfig) = context.dataStore.edit {
        it[Keys.CLOUD_NAME] = config.name
        it[Keys.CLOUD_BASE_URL] = config.baseUrl
        it[Keys.CLOUD_API_KEY] = config.apiKey
        it[Keys.CLOUD_MODEL] = config.model
    }

    suspend fun setCloudFirst(value: Boolean) = context.dataStore.edit {
        it[Keys.CLOUD_FIRST] = value
    }

    suspend fun setCloudFirstAct(value: Boolean) = context.dataStore.edit {
        it[Keys.CLOUD_FIRST_ACT] = value
    }

    suspend fun setAutoApprove(value: Boolean) = context.dataStore.edit {
        it[Keys.AUTO_APPROVE] = value
    }

    suspend fun setLocalModel(path: String) = context.dataStore.edit {
        it[Keys.LOCAL_MODEL_PATH] = path
    }

    suspend fun setEngineTuning(nCtx: Int, nThreads: Int) = context.dataStore.edit {
        it[Keys.N_CTX] = nCtx
        it[Keys.N_THREADS] = nThreads
    }
}

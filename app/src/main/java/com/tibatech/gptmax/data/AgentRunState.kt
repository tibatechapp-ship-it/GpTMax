package com.tibatech.gptmax.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared "coding agent is running" flag so destructive actions — deleting or
 * unloading the local model files — can guard themselves (review #3). The
 * AgentViewModel raises it for the duration of a run; ModelsViewModel checks
 * it before touching the engine.
 */
class AgentRunState {

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun start() {
        _isRunning.value = true
    }

    fun stop() {
        _isRunning.value = false
    }
}

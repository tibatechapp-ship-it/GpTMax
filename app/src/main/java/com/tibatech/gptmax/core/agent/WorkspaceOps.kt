package com.tibatech.gptmax.core.agent

import com.tibatech.gptmax.data.repo.WorkspaceRepository

/**
 * Storage-agnostic workspace surface. Production wires the SAF-backed
 * [WorkspaceRepository]; JVM unit tests use an in-memory fake so the whole
 * guard/journal layer is testable without an Android device.
 */
interface WorkspaceOps {
    suspend fun read(path: String): String?
    suspend fun write(path: String, content: String): Boolean
    suspend fun delete(path: String): Boolean
    suspend fun list(path: String): List<WorkspaceRepository.FileNode>
    suspend fun grep(pattern: String): List<WorkspaceRepository.GrepHit>
}

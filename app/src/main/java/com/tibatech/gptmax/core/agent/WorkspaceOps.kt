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

    /**
     * Recursive content search. [maxFiles] caps how many text files are
     * opened (scan budget); the default keeps small workspaces responsive.
     * The interface carries the default so implementations override without
     * repeating it and callers can omit it.
     */
    suspend fun grep(pattern: String, maxFiles: Int = 500): List<WorkspaceRepository.GrepHit>
}

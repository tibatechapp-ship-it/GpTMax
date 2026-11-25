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
     * Phase 1 (external review, release blocker): lets the delete paths
     * refuse directories — a SAF delete on a folder wipes the whole subtree
     * with no undo. Default `false` keeps JVM fakes file-shaped; the
     * production [WorkspaceRepository] resolves the real node type.
     */
    suspend fun isDirectory(path: String): Boolean = false

    /**
     * [maxFiles] bounds the recursive walk so a huge workspace cannot stall
     * the agent loop. Overrides must not redeclare the default value; the
     * production [WorkspaceRepository] already implements the two-arg form.
     */
    suspend fun grep(pattern: String, maxFiles: Int = 400): List<WorkspaceRepository.GrepHit>
}

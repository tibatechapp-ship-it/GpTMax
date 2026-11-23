# Agent Core and Integrated Runtime

## Purpose

Define the safe, observable execution contract between the coding agent, tools, project workspace, and inference backend. The runtime is an application-owned component; Termux is not a required dependency.

## Agent task state machine

A task moves through explicit states:

```text
CREATED -> PLANNING -> INSPECTING -> READY_TO_EDIT
   -> EDITING -> VALIDATING -> REVIEWING -> AWAITING_APPROVAL
   -> COMPLETED

Any active state may transition to CANCELLED or FAILED.
VALIDATING may transition to REPAIRING only within a bounded retry/budget policy.
```

State transitions must be persisted sufficiently to recover UI/task state after process recreation. Persisted state must not contain raw credentials or unrestricted environment dumps.

## Core components

- **Planner:** converts a user request into steps and acceptance criteria.
- **Context Builder:** selects relevant files, diagnostics, and project metadata under token and privacy budgets.
- **Tool Registry:** defines tool name, schema, permission class, risk, and supported platform capabilities.
- **Tool Router:** validates and dispatches model-requested tools.
- **Execution Controller:** serializes conflicting writes, manages cancellation, timeout, and retry budgets.
- **Failure Analyzer:** converts real stderr, exit codes, test reports, and compiler diagnostics into structured observations.
- **Reviewer:** inspects the final diff and validation evidence; reports concerns and uncertainty.
- **Task Ledger:** records plan, tool call metadata, outputs, and status for auditability.

## Tool contract

Each tool must have:
- Stable identifier and version.
- JSON-schema-like validated input contract.
- Bounded, typed output envelope.
- Permission/risk classification.
- Timeout and output-size limit.
- Cancellation behavior.
- Test fixtures and failure cases.

Suggested output envelope:

```json
{
  "tool": "process.run",
  "status": "success | failure | denied | timeout | cancelled",
  "exit_code": 0,
  "stdout": "...",
  "stderr": "...",
  "duration_ms": 123,
  "truncated": false
}
```

The values above are illustrative schema documentation, not a runtime implementation.

## Initial tool surface

Start with read-only tools:
- project.list
- file.read
- file.search
- git.status
- diagnostics.get

Then add controlled mutation:
- file.apply_patch
- workspace.snapshot
- workspace.restore

Then add execution:
- process.run through managed command adapters
- build.run and test.run through detected toolchains
- git.diff and git.branch

Do not expose arbitrary unrestricted shell as the first tool. Prefer registered commands and argument arrays over shell-string interpolation. If a shell is eventually supported, it must run in a constrained working directory with risk review, bounded environment, timeout, and explicit approval for high-risk operations.

## Terminal and process manager

The terminal UI is a presentation layer over a managed process service.

The process service must capture:
- command and arguments;
- working directory;
- sanitized environment;
- start/end time and duration;
- stdout and stderr separately;
- exit code or termination reason;
- resource limits and cancellation result.

Android does not provide a general-purpose desktop Linux environment to every app. Toolchain availability must be detected. Native build tools can be bundled only when licensing, ABI, size, and platform constraints permit. Unsupported or expensive builds may use an optional configured worker; data transfer must be visible and approved.

## Security model

- Default-deny tool execution.
- Least privilege and per-task permissions.
- Separate read/write/execute/network/destructive capability classes.
- App-private task workspace and explicit user-selected project grants.
- Never index known secret files or send credentials to the model.
- Treat repository text, tool output, and downloaded content as untrusted data, not policy.
- Require confirmation for deletion, broad writes, network access, credential-related actions, publishing, and merging.
- Enforce timeout, output caps, memory/CPU budgets where available, and child-process cleanup.
- Maintain a redacted audit trail and reversible checkpoints.

## Completion and repair

A task is complete only when:
1. The requested acceptance criteria are addressed.
2. The resulting diff is available.
3. Relevant validation has run, or skipped validation is clearly disclosed.
4. No unresolved critical tool failure is hidden.
5. The user has reviewed/approved any gated action.

Repair attempts are bounded by configurable limits for iterations, tokens, wall time, and tool calls. On exhaustion, stop with a clear diagnosis rather than looping indefinitely.

## Testing strategy

- Unit tests for task state transitions and permission decisions.
- Schema tests for every tool.
- Fake-tool tests for timeout, denial, malformed output, and cancellation.
- Patch tests for conflict, invalid paths, and rollback.
- Adversarial tests for prompt injection inside repository files and command output.
- End-to-end fixtures for bounded coding tasks.


## Implemented agent foundation (M3 in progress)

The current code now contains:
- `AgentToolSpec`, `AgentToolCall`, `AgentToolResult`, and a unique-name registry.
- `AgentRuntime`: bounded model/tool loop, cancellation checks, input validation, capability enforcement, explicit approval callback, output truncation, and typed completion status.
- `AgentActionParser`: strict JSON action envelope parser; accepts only string arguments and nested objects.
- `AgentTaskLedger`: private append-only session/tool metadata with SHA-256 output digests; it deliberately does not persist prompts, raw file contents, or command output.
- `WorkspaceEditorPanel`: SAF-backed project browser and UTF-8 source editor with bounded file size, binary-file rejection, and explicit save/close behavior.
- `readWorkspaceUtf8Bounded` (`WorkspaceUtf8.kt`): reads SAF content as bounded UTF-8 bytes and decodes strictly; malformed UTF-8 and files over the byte limit are rejected. The byte limit is enforced against actual UTF-8 bytes (not Kotlin character count). Unit tests cover valid Arabic/emoji text, size overflow, malformed sequences, and negative limits.

- `LocalLlamaAgentModel`: adapter from the current text-only JNI generation API to structured `tool` or `final` actions.
- File tools for listing, reading, literal search, whole-file write, and exact unique-fragment replacement in a user-selected SAF document tree.
- Change previews from file writes and blocked paths for common secret/credential filenames.
- `ManagedProcessTool`: separate allowlisted executable runner with argument arrays (newline-delimited), fixed environment, bounded timeout/output, cancellation, and process-execution approval. It is currently a tool foundation for app-private workspaces and is not yet connected to the SAF project-agent UI.

## Local model action protocol

The local model must return exactly one JSON object per action:

```json
{"type":"tool","id":"call-1","name":"file.read","arguments":{"path":"src/Main.kt"}}
```

or

```json
{"type":"final","text":"Summary of verified findings."}
```

The tool loop does not interpret free-form text as a command. Unknown tools, missing/extra arguments, invalid JSON, and missing capabilities are rejected. File-write access is off by default in the UI and must be enabled explicitly for a task.

## Current limitations

- The 0.5B smoke-test model may not reliably emit valid tool JSON or produce large code edits. Tool-loop support is an engineering capability, not a quality guarantee for that model.
- SAF providers differ. Rename-based file replacement is attempted with a temporary document and backup, but provider-specific behavior requires device testing.
- A read-only `git.workspace` status/diff-summary tool is connected; it requires a Git executable available to the Android app, which is not yet bundled. Build/test adapters are not connected to the SAF agent yet.
- The agent currently runs one bounded session. Durable project context and task checkpoints are implemented, but full conversation/tool-state replay, semantic embeddings, rollback, and multi-agent delegation are not yet implemented. The ledger remains audit metadata and does not restore the complete runtime session.
- Process execution is not granted to the selected-project agent; the managed process adapter remains separately gated until a safe project execution workspace/worker is implemented.

## Durable project context (local-first)

- `ProjectContextEngine` stores a versioned, size-bounded binary index and project memory in app-private storage, keyed by the selected workspace URI hash.
- The SAF context index scans bounded text files only, skips generated/dependency folders and sensitive filenames, redacts secret-like lines, and never executes repository code.
- Re-indexing reuses cached records when a file's size and modification timestamp are unchanged; changed files are re-read and hashed. The current index is a lexical path/snippet index, not a semantic embedding database.
- `context.recall` ranks indexed paths/snippets and matching notes against a focused query. The agent is instructed to re-read current files before making edits.
- `context.remember` stores short project decisions/conventions; `context.checkpoint` stores goal, progress, and next step. Sensitive-looking values are rejected.
- The app performs a bounded index refresh at the start of an agent run and writes an automatic checkpoint when the run starts and ends. These checkpoints support continuity but do not yet reconstruct prior model observations or tool-call history.
- Indexing and memory remain on-device. They are not synced to a remote service.

## Context engine hardening and retrieval

- Retrieval uses field-weighted lexical scoring (path, declared symbols, snippet frequency, import mentions and exact query phrase), then adds a bounded one-hop neighborhood of imported source files and related tests.
- The index stores bounded import and declaration metadata (classes, interfaces, functions, structs, and related symbols) plus file kind classifications for manifests, tests, docs, and config.
- A completed SAF snapshot reports added/updated/removed/unchanged entries. A capped scan, failed list/read operation, or cancellation does not replace the prior index; this prevents false deletion of files that could not be visited. A complete snapshot removes entries for files that are actually gone or no longer indexable.
- The version-2 context store reads version-1 records for migration. Task history includes bounded stages and summarized tool observations; file-read source text is deliberately not copied into persistent tool history.
- At the next agent run, the latest prior task checkpoint is restored into the request context. The model can call `context.restore` for a specific task and `context.stage` to record meaningful phases. Project stores are keyed by a SHA-256 of the selected SAF tree URI, so notes, indexes, and task history remain separate per selected project.

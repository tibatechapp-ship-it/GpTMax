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
- No Git diff/status or build/test adapter is connected to the SAF agent yet.
- The agent currently runs one bounded session; persistent task ledger, resumable sessions, checkpoints, multi-agent delegation, and rollback are pending.
- Process execution is not granted to the selected-project agent; the managed process adapter remains separately gated until a safe project execution workspace/worker is implemented.

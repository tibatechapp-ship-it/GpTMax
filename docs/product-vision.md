# GpTMax Product Vision

## North star

**GpTMax turns an engineering request into a reviewable, validated software change—on an Android-first, local-first development environment.**

The product is not a chat window with a terminal button. It is a development workspace where the agent understands the project, uses explicit tools, observes real execution results, and keeps the developer in control.

## User experience

A user should be able to:

1. Open or create a project.
2. Browse and edit source files in a capable code editor.
3. Ask the agent to explain, implement, debug, refactor, or test a change.
4. Watch the task plan and tool activity as it progresses.
5. Inspect edits as a diff and interrupt execution at any time.
6. Run supported commands in the integrated terminal.
7. Review actual build/test evidence and known gaps.
8. Approve, reject, or roll back the result.

## Product principles

### Agent-first, editor-native
The agent works with the same project, selection, diagnostics, and diff that the user sees. It does not operate as a detached chatbot.

### Local-first, not local-only
Local inference and local tools are the default. Optional external models or build workers may extend capability only through explicit configuration, clear data-flow disclosure, and revocable permissions.

### Evidence over confidence
Completion is determined by acceptance criteria and observed tool results. A generated explanation is not proof of a successful build or test.

### Human control
The user can stop tasks, inspect actions, approve risky operations, and restore checkpoints. Publish, merge, destructive changes, credential access, and untrusted network operations are gated.

### Capability honesty
GpTMax detects tool availability and reports unsupported operations. It never pretends Android is a full unrestricted Linux workstation or that every project can be built locally.

## Differentiation strategy

GpTMax should compete through a combination of:
- Android-native, touch-optimized engineering workflow.
- Local inference with transparent resource management.
- Reliable tool execution and task recovery.
- Project-aware retrieval with privacy controls.
- Validation evidence and reversible changes.
- A portable model/runtime interface that avoids vendor lock-in.

These are strategic goals, not current product claims. They must be measured through real-device and coding-task benchmarks.

## Initial target user

Developers who want to inspect, edit, and automate software projects from Android, with local inference where the model and device allow it, and optional build capacity for heavier workloads.

## Non-goals for the first release

- Train a foundation model from scratch.
- Support every programming language or build system.
- Run arbitrary commands with unrestricted device privileges.
- Promise desktop-level builds on every Android device.
- Launch a large multi-agent swarm before one agent is reliable.
- Port MLX runtime directly to Android.
- Claim superiority without reproducible comparative benchmarks.

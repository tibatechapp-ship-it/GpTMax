# ADR 0002: GpTMax as a local-first agentic coding studio

- Status: Accepted as product direction; implementation remains pending
- Date: 2026-11-23

## Context

The initial repository scope focused on Android-native inference and quantization. The product direction is now broader: GpTMax should provide a Cursor-like AI coding workflow with a project editor, code-aware agent, integrated terminal/tool runtime, and build/test feedback, while retaining local inference and Android as its first platform.

A terminal UI alone is insufficient. The agent needs a controlled runtime, context retrieval, task state, validation, and review. Android also cannot be assumed to support every desktop/Linux development toolchain.

## Decision

1. Define GpTMax as a local-first AI coding studio and Android-native inference platform.
2. Keep M1 Android ARM64 inference as the first implementation proof.
3. Build a stable GpTMax Inference API so agent orchestration is not coupled to llama.cpp internals.
4. Treat the editor, Agent Core, Code Intelligence, and managed Runtime as separate components with explicit contracts.
5. Make the integrated terminal a managed process/tool service; Termux is not a product dependency.
6. Use registered tools, least privilege, task-scoped workspaces, bounded execution, and user approval for high-risk actions.
7. Allow an optional build worker for capabilities unavailable or impractical on-device, with explicit configuration and data-transfer consent.
8. Keep quantization research separate until baseline inference and measurement are established.
9. Add specialist/multi-agent workflows only after one agent passes repeatable coding-task benchmarks.

## Consequences

- Product scope and roadmap now include editor and autonomous coding capabilities.
- The initial implementation remains narrow and testable; the repository does not claim these features exist.
- Android capability detection and device testing are mandatory.
- Coding-agent quality will be measured using task success, regressions, validation evidence, latency, and resource cost.
- Local-first does not mean every task can run offline or on-device; capability limits must be disclosed.

# GpTMax System Architecture

## Product scope

GpTMax is planned as a **local-first AI coding studio** for Android, combining an editor, project-aware coding agent, managed terminal/tool runtime, validation workflow, and native local inference. The initial engineering proof is still Android ARM64 inference. No application/runtime capability is considered implemented until code and evidence are merged.

The architecture must support two modes without conflating them:

1. **Local mode:** inference and supported tools run on the device, with project data kept local by default.
2. **Optional build-worker mode:** explicitly configured remote or external build capacity for tasks that Android cannot reliably perform. It is not required for basic local inference or file editing, and must never receive project content without user configuration and consent.

## Layered architecture

```text
GpTMax Studio (Kotlin / Jetpack Compose)
  ├── Project Explorer / Editor / Search
  ├── Agent Panel / Task Timeline
  ├── Diff / Git Changes / Terminal UI
  └── Model Library / Diagnostics
                 |
                 v
Application Services
  ├── Project and Workspace Manager
  ├── Model and Session Manager
  ├── Permissions / SAF / Lifecycle
  └── Checkpoint and Task Ledger
                 |
                 v
Agent Orchestration
  ├── Task Planner and State Machine
  ├── Context Builder
  ├── Tool Router / Execution Controller
  ├── Failure Analyzer / Repair Policy
  └── Independent Reviewer
          |                     |
          v                     v
Code Intelligence          Tool Runtime
  ├── File inventory      ├── File/Search tools
  ├── Symbol index        ├── Managed process runner
  ├── Context retrieval   ├── Git tools
  └── Project memory      ├── Build/Test adapters
                          └── Optional build-worker client
          |                     |
          +----------+----------+
                     v
            GpTMax Inference API
              ├── Model Adapter
              ├── Prompt/Template Adapter
              ├── Token Streaming
              ├── Cancellation
              ├── Context/Resource Manager
              └── Model Registry
                     |
                     v
          Native C++ Adapter / JNI
                     |
                     v
         Pinned llama.cpp + GGUF
                     |
                     v
        Workspace / Security Boundary
```

## Architectural contracts

### UI and application services

- Kotlin owns Android lifecycle, UI state, document-provider access, permissions, and user confirmation.
- Compose screens observe application state; they do not own native model handles or process lifetimes.
- Model loading and generation are explicit state machines with cancellation and cleanup.
- The app uses Storage Access Framework for user-selected files/folders where applicable; broad storage access is not assumed.

### Inference boundary

- Agent code calls a stable GpTMax Inference API, not llama.cpp internals.
- Native handles have explicit ownership and deterministic release.
- JNI exposes a small typed surface; avoid passing complex object graphs.
- Streaming, stop requests, errors, and resource metrics are first-class.
- Backend revisions and build flags are pinned and recorded.
- GPU/Vulkan acceleration is capability-gated and not claimed until verified on target devices.

### Agent boundary

- The agent requests tools through a registry with validated schemas.
- The model never directly receives an unrestricted shell or filesystem capability.
- Tool outputs are treated as untrusted data and cannot override system policy.
- Each task has an explicit state, budget, working directory, permissions, and cancellation token.
- Writes are patch-based and reviewed; destructive or external actions require confirmation.

### Workspace and runtime

- Default workspace is app-private and task-scoped.
- The runtime reports command, arguments, working directory, exit code, elapsed time, and bounded output.
- Commands have timeouts, resource limits, and cleanup behavior.
- Android capability detection determines which tools are available.
- A build worker is an optional adapter with explicit trust, network, and data-transfer settings.

## Data and privacy boundaries

- Model weights are not committed to the repository.
- User projects, prompts, and source files remain local unless the user explicitly enables an external service or worker.
- Secrets, signing keys, and credential files are excluded from indexing and model context.
- Logs redact credentials and have bounded retention.
- Project memory is visible, scoped, and removable.

## Quantization research

LookAtMyQuant remains a research source, not an Android runtime. Audit portable algorithms separately from MLX-specific execution. Any GpTMax quantization/export result must be validated by loading it with the target backend and comparing quality, format compatibility, memory, latency, thermal behavior, and energy against a fixed baseline.

## Performance and evidence

Every inference benchmark records device, Android version, ABI, backend commit, build flags, model checksum, quantization, context length, warm-up, generated tokens, peak memory, first-token latency, throughput, and thermal conditions.

Every coding-agent benchmark records task fixture/version, initial repository state, allowed tools, model and prompt configuration, tests run, task success criteria, diff, regressions, tool failures, latency, and resource cost.

## Dependency and license policy

Pin upstream revisions and review licenses independently for source code, native libraries, model weights, tokenizers, datasets, and copied assets. Preserve notices for adapted code. Do not commit model weights or signing secrets.

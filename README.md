# GpTMax

**Local-first AI coding studio and Android-native inference platform.**

GpTMax is being designed as an AI development environment inspired by modern agentic code editors: project explorer, code editor, integrated agent, controlled terminal/runtime, Git-aware changes, and build/test feedback—powered first by on-device GGUF inference on Android.

> **Current implementation:** Android app scaffold, Jetpack Compose UI, C++17 JNI bridge, pinned llama.cpp source, SAF import into app-private storage with SHA-256 and GGUF magic validation, native model/context loading, prompt tokenization, prompt evaluation, sampling, token-to-text conversion, streaming callback, and cooperative cancellation. Android CI validates compilation and unit tests. Physical-device generation, memory/thermal profiling, editor, managed terminal, and coding-agent execution are not yet proven or complete.

## Product north star

Turn a natural-language engineering task into a **reviewable, validated change**:

1. Understand the request and inspect the repository.
2. Build a relevant, permission-aware context.
3. Plan a sequence of small, observable actions.
4. Read and edit files inside a task workspace.
5. Run approved tools, builds, and tests where the environment supports them.
6. Diagnose failures and attempt bounded repairs.
7. Review the resulting diff and validation evidence.
8. Present the result for user approval; never silently publish or merge.

## Product pillars

- **AI Code Studio:** editor, project explorer, search, tabs, diff, Git changes, and an agent workspace designed for Android.
- **Agent Core:** task planner, context builder, tool router, execution controller, failure analyzer, reviewer, and task ledger.
- **Local Inference:** GGUF loading and token generation through a pinned native backend, initially llama.cpp, behind a stable GpTMax inference interface.
- **Integrated Runtime:** an application-owned terminal/process and tool layer. Termux is not a product dependency. Runtime capabilities must be verified on Android; unsupported or resource-heavy builds may use an optional, explicitly configured build worker.
- **Code Intelligence:** ignore-aware file inventory, symbol-aware retrieval, semantic search, dependency relationships, and project decisions.
- **Trust and evidence:** scoped permissions, task snapshots, cancellation, resource limits, visible diffs, and honest test status.

## Architecture and plan

- [Product vision](docs/product-vision.md)
- [System architecture](docs/architecture.md)
- [Agent and runtime design](docs/agent-runtime.md)
- [M1 implementation notes](docs/m1-implementation.md)
- [M1 physical-device validation runbook](docs/m1-device-validation.md)
- [Android device compatibility](docs/device-compatibility.md)
- [Roadmap](ROADMAP.md)
- [Architecture decision records](docs/decision-records/)
- [Quality gates](docs/quality-gates.md)
- [Threat model](docs/threat-model.md)

## Build locally

The current project uses JDK 17 and Gradle 8.9, with Android SDK 35, NDK 27.0.12077973, and CMake 3.22.1.

```bash
gradle --no-daemon assembleDebug testDebugUnitTest
```

Install the Android SDK components listed in `.github/workflows/android-build.yml` before building locally. A Gradle wrapper will be checked in once its wrapper JAR is generated and validated; do not rely on a globally installed Gradle for a future release.

## Inference and optimization

M1 includes a native generation path that applies a model-embedded GGUF chat template when available, tokenizes the formatted prompt, evaluates it, samples tokens, streams decoded text to Compose, and accepts cancellation requests. The app includes a verified-import flow for the official Qwen2.5-0.5B-Instruct Q4_K_M smoke-test model; model weights remain external to the APK. Follow the [physical-device validation runbook](docs/m1-device-validation.md). The code must still be exercised with the model on physical devices. Model optimization and LookAtMyQuant-inspired quantization research remain a separate, measured track after a stable baseline exists. MLX-specific runtime code is not an Android backend and will not be transplanted directly.

## Platform principles

- Kotlin / Jetpack Compose for Android UI and lifecycle.
- C++ / Android NDK / CMake for native inference.
- A small JNI boundary and a stable internal inference API.
- SAF/document-provider access for user-selected projects and model files.
- App-private workspace by default; explicit permission for broader access.
- No model weights committed to this repository.
- No arbitrary unrestricted shell execution by the model.
- No claim of GPU support, universal model compatibility, or performance until verified.

## Engineering governance

- [Contributing guide](CONTRIBUTING.md)
- [Security policy](SECURITY.md)
- [Dependency and supply-chain policy](DEPENDENCY_POLICY.md)
- [Threat model](docs/threat-model.md)
- [Quality gates](docs/quality-gates.md)
- [Support](SUPPORT.md)

Repository CI checks documentation and repository hygiene. Android CI assembles the debug app and runs unit tests; neither workflow proves local inference, agent behavior, or device performance.

## License

GpTMax project materials are licensed under the MIT License unless a file or third-party component states otherwise. Third-party code, model weights, tokenizer assets, and datasets retain their own licenses. See [LICENSE](LICENSE).

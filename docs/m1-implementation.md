# M1 Implementation Notes — Android ARM64 Foundation

## Current scope

The repository now includes a real Android application module, a Compose entry screen, a C++17 JNI shared library, pinned Gradle/Android toolchain configuration, unit-test wiring, and an Android CI build.

The first CI attempt exposed that Google removed the deprecated Android SDK `tools` package. The workflow now uses the maintained `android-actions/setup-android@v4` action and installs only current, explicit SDK components.

This is **not yet an inference proof**. llama.cpp is not linked, GGUF loading is not implemented, and no device generation benchmark exists.

## Toolchain baseline

- JDK: 17
- Gradle: 8.9 (CI-installed; wrapper will be added when its binary is generated and checked in)
- Android Gradle Plugin: 8.7.3
- Kotlin: 2.0.21
- Android compile/target SDK: 35
- Minimum SDK: 26
- NDK: 27.0.12077973
- CMake: 3.22.1

Versions are pinned in the build files and workflow to make the first CI build reproducible. Review upgrades through dependency policy and CI.

## Native boundary

The Kotlin object `NativeBridge` loads `gptmax_native` and exposes only small status/build-info calls. This deliberately validates the app-to-native packaging and JNI symbol path before introducing third-party inference code.

Next native integration steps:
1. Resolve and pin an immutable llama.cpp upstream commit.
2. Review its Android example, API, license, and required build flags at that exact revision.
3. Integrate via CMake and preserve upstream notices.
4. Add explicit native model/session ownership, cancellation, token streaming, and error mapping.
5. Add a model picker using Android document APIs and test with a separately supplied compatible GGUF.

## Agent foundation

The initial Kotlin agent foundation includes:
- A strict task lifecycle state machine with terminal-state protection.
- A capability/risk policy that denies missing grants and requires per-action approval for process execution, network, destructive, and publish actions.
- Unit tests for valid transitions, repair paths, terminal states, and permission decisions.

These components are policy primitives only. There is not yet a model-driven planner, tool registry, process manager, or executing agent.

## Validation required for M1 completion

- CI assembles the debug APK and passes unit tests.
- APK contains the native library for arm64-v8a.
- Install and launch on a real Android ARM64 device.
- Load a compatible GGUF from user-selected storage.
- Generate a deterministic smoke prompt and capture actual output.
- Record device model, Android version, ABI, backend commit, model checksum/quantization, context size, warm-up, peak memory, first-token latency, tokens/sec, and thermal conditions.
- Verify cancellation, unload, low-memory behavior, and repeated load/unload.

CI build success alone is not the inference proof.

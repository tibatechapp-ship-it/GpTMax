# llama.cpp Android integration

## Pinned upstream source

- Repository: https://github.com/ggml-org/llama.cpp
- Revision: `f4f6f2e7db76e98c95cc9b7fc608ea669c806044`
- Revision URL: https://github.com/ggml-org/llama.cpp/commit/f4f6f2e7db76e98c95cc9b7fc608ea669c806044
- Upstream license: MIT (`LICENSE` at the pinned revision).
- Fetch method: CMake FetchContent using the immutable full commit SHA.

The pin is intentionally explicit and must not be replaced with a moving branch or tag. Updates require reviewing the upstream diff, license, Android/NDK compatibility, and the public C API before changing the SHA.

## Android build configuration

GpTMax builds the upstream `llama` library as a static dependency and links it into `gptmax_native`. The Android build disables tools, examples, server, subprocess support, OpenSSL, optional desktop/GPU backends, and runtime backend loading. The CPU backend uses the portable ARMv8-A baseline; architecture-specific runtime tuning is deferred until device capability detection is implemented.

## Current native contract

- `nativeLoadModel(path)`: initializes llama backend once, loads a GGUF model using mmap, and creates a bounded 2048-token context.
- `nativeUnloadModel()`: frees context before model.
- `nativeShutdown()`: unloads resources and shuts down the backend.
- JNI owns model/context state behind a mutex; callers must not run load/unload concurrently with generation.

## Deliberate limitations

This commit establishes a pinned native dependency and model/context loading boundary. It does **not** yet provide tokenization, prompt evaluation, sampling, token streaming, cancellation, model metadata UI, or a device-level inference proof. Those are subsequent M1 tasks. Do not describe this stage as completed local generation.

GGUF model weights are not stored in this repository. The Android layer must copy selected documents into app-private storage before passing a filesystem path to JNI. Model integrity validation and SAF flow are still pending.

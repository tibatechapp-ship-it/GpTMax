# M1 Implementation Notes — Android ARM64 Inference

## Verified implementation

- Android app and native C++17 JNI library build in CI.
- Native inference dependency is pinned to llama.cpp commit `f4f6f2e7db76e98c95cc9b7fc608ea669c806044`.
- Android build is restricted to `arm64-v8a` for the M1 target.
- JNI initializes llama.cpp, loads a GGUF from an app-private path, and creates a context.
- Android Storage Access Framework opens a user-selected document. The app copies it into `filesDir/models`; it never passes a `content://` URI to JNI.
- Import rejects non-`.gguf` names, empty files, insufficient known free space, size mismatches, and files without the GGUF magic header.
- Copy is streamed to a temporary file, SHA-256 is calculated while copying, output is synced, and the file is renamed only after validation. Failed imports clean up temporary and destination files.
- Import and native model loading run off the UI thread.

## Toolchain baseline

- JDK 17; Gradle 8.9
- Android Gradle Plugin 8.7.3; Kotlin 2.0.21
- Android compile/target SDK 35; minimum SDK 26
- NDK 27.0.12077973; CMake 3.22.1
- ABI: arm64-v8a

## Current native boundary

The JNI bridge exposes backend status, build information, model load, unload, and shutdown. Model loading uses the API at the pinned llama.cpp revision and requests mmap loading. The context currently uses a conservative fixed configuration (2048 context tokens, batch 512, four threads); this is a bring-up default, not a device-optimized profile.

## Not yet implemented or proven

- Tokenization, prompt evaluation, sampling, token-to-piece decoding, and text streaming.
- Cooperative generation cancellation and safe concurrent unload during generation.
- Actual GGUF load and generation on a physical Android ARM64 device.
- Memory/thermal profiling, repeated load/unload stress, and low-memory behavior.
- Coding agent, workspace editor, project indexing, and managed terminal execution.

A successful CI build proves compilation and packaging only. It does not prove that a selected model fits device RAM or that inference works on physical hardware.

## Required M1 completion evidence

Record device model, Android version, ABI, backend commit, model architecture/quantization, file size and SHA-256, context configuration, memory before/after/peak, model load latency, time to first token, tokens/second, thermal state, and an actual generated sample. Verify cancellation, unload, repeated load/unload, and low-memory failure handling before declaring M1 complete.

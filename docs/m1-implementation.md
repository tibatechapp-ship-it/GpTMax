# M1 Implementation Notes — Android ARM64 Inference

## Implemented

- Android app and native C++17 JNI library build in CI; the preceding generation implementation compiled and packaged successfully. The latest JNI UTF-16 input conversion correction is undergoing its own CI run.
- Native inference dependency is pinned to llama.cpp commit `f4f6f2e7db76e98c95cc9b7fc608ea669c806044`.
- Android build is restricted to `arm64-v8a` for the M1 target.
- JNI initializes llama.cpp, loads a GGUF from an app-private path, and creates a context.
- Android Storage Access Framework opens a user-selected document. The app copies it into `filesDir/models`; it never passes a `content://` URI to JNI.
- Import rejects non-`.gguf` names, empty files, insufficient known free space, size mismatches, and files without the GGUF magic header.
- Copy is streamed to a temporary file, SHA-256 is calculated while copying, output is synced, and the file is renamed only after validation. Failed imports clean up temporary and destination files.
- Import and native model loading run off the UI thread.
- Native generation path converts Java UTF-16 prompt input to standard UTF-8 (including surrogate pairs), tokenizes the prompt, evaluates it with llama.cpp in batches bounded by `llama_n_batch`, samples output tokens, decodes token pieces, and streams complete UTF-8 text chunks through a JNI callback.
- Native model paths use the same explicit UTF-16-to-UTF-8 conversion rather than relying on JNI Modified UTF-8, avoiding corrupted supplementary Unicode characters.
- The UI exposes a prompt, temperature control, generated output, and a stop action.
- Generation cancellation is signaled atomically and wired to llama.cpp's abort callback. Model unload/shutdown signals cancellation before waiting for the native operation lock, preventing context destruction during active generation.

## Toolchain baseline

- JDK 17; Gradle 8.9
- Android Gradle Plugin 8.7.3; Kotlin 2.0.21
- Android compile/target SDK 35; minimum SDK 26
- NDK 27.0.12077973; CMake 3.22.1
- ABI: arm64-v8a

## Current native boundary and constraints

The JNI bridge exposes backend status, build information, model load, generation, cancellation, unload, and shutdown. Model loading uses the API at the pinned llama.cpp revision and requests mmap loading. The context currently uses a fixed bring-up configuration (2048 context tokens, batch 512, four threads); this is not device optimized.

The current generation path uses the pinned revision's batch and sampler APIs. Java strings are converted from UTF-16 to standard UTF-8 explicitly, including valid surrogate pairs, before llama.cpp tokenization. Prompt and output token counts are checked against the context window. Sampling uses greedy mode at near-zero temperature, otherwise top-k/top-p/temperature with a deterministic seed. The native generation lock serializes generation, load, unload, and shutdown; cancellation is a lock-free atomic request observed by the backend abort callback and generation loop. This favors safe lifecycle behavior over concurrent requests.

## Not yet proven or complete

- Successful Android CI build of the latest JNI Unicode-input correction (check the current workflow run before treating it as passed).
- Actual GGUF load and generated text on a physical Android ARM64 device.
- Memory/thermal profiling, repeated load/unload stress, and low-memory behavior.
- Robust device-specific context/thread configuration and latency optimization.
- Coding agent, workspace editor, project indexing, and managed terminal execution.
- Stress tests for repeated cancellation and Activity recreation while native generation is active.

A successful CI build proves compilation and packaging only. It does not prove that a selected model fits device RAM or that inference works on physical hardware.

## Required M1 completion evidence

Record device model, Android version, ABI, backend commit, model architecture/quantization, file size and SHA-256, context configuration, memory before/after/peak, model load latency, time to first token, tokens/second, thermal state, and an actual generated sample. Verify cancellation, unload, repeated load/unload, and low-memory failure handling before declaring M1 complete.

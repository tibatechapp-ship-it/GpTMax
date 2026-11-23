# GpTMax

**Android-native local AI inference and model-optimization platform.**

GpTMax is an engineering project to bring efficient large-language-model inference to Android devices, with a long-term goal of adapting selected quantization techniques from [LookAtMyQuant](https://github.com/ShinMegamiBoson/lookatmyquant) for mobile hardware.

> Status: repository bootstrap / architecture phase. No Android runtime, quantizer, APK, or benchmark is currently released. Do not treat planned capabilities as implemented.

## Project goals

- Run compatible local GGUF models on Android ARM64.
- Provide a reliable native inference layer through C++ and JNI.
- Manage model files safely: checksums, storage, cancellation, and resumable downloads where supported.
- Evaluate mobile-friendly quantization and model-quality trade-offs.
- Measure latency, memory use, thermal behavior, and energy consumption on real devices.
- Keep inference local by default; network access should be limited to user-requested model downloads or explicitly configured services.

## Planned architecture

- **Android app:** Kotlin, Jetpack Compose, Android lifecycle and storage APIs.
- **Native layer:** C++ with Android NDK, CMake, and JNI.
- **Inference backend:** llama.cpp / GGUF, subject to pinned upstream versions and license review.
- **Optimization research:** a separately testable quantization component; MLX-specific code is not an Android runtime and will not be transplanted directly.

See [Architecture](docs/architecture.md) and [Roadmap](ROADMAP.md).

## Device requirements

The initial target is Android ARM64. Supported Android versions, RAM requirements, GPU backends, and model-size recommendations will be documented only after builds and device tests establish them. Model file size is not equal to runtime RAM usage; KV cache and runtime buffers add overhead.

## Safety and privacy

- Never execute downloaded model files as code.
- Verify model metadata and integrity where checksums are available.
- Show model source, license, size, and required storage before download when known.
- Do not upload prompts or model contents without explicit user configuration and consent.
- Treat third-party model weights and repository code as separately licensed assets.

## Development status

This repository is being initialized. The first milestone is a reproducible Android ARM64 build of the inference backend and a minimal local prompt-generation test. Quantization-on-device is a later research milestone, not a prerequisite for the first runtime.

## Contributing

Please open an issue before major architectural changes. Include device model, Android version, ABI, backend, model identifier, and reproducible steps for performance or correctness reports. Never include private prompts, tokens, or personal data in issue reports.

## License

GpTMax project materials are licensed under the MIT License unless a file or third-party component states otherwise. Third-party dependencies and model weights retain their own licenses. See [LICENSE](LICENSE).

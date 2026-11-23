# GpTMax Roadmap

Roadmap items are planned until implementation and tests are merged.

## M0 — Repository and engineering baseline

- [x] Create public repository and document scope
- [x] Define architecture and staged milestones
- [x] Add privacy, security, and licensing guidance
- [ ] Establish CI and repository quality gates
- [ ] Pin toolchain and dependency versions

## M1 — Android ARM64 inference proof

- [ ] Build pinned llama.cpp backend with Android NDK/CMake
- [ ] Create minimal Kotlin app and JNI boundary
- [ ] Load one compatible small GGUF model from user-selected storage
- [ ] Run prompt completion and stream output safely
- [ ] Handle cancellation, errors, lifecycle, and memory pressure
- [ ] Add device test and reproducible build instructions

## M2 — Model management

- [ ] Model catalog metadata format
- [ ] User-confirmed downloads with progress and cancellation
- [ ] SHA-256 verification and storage accounting
- [ ] Safe deletion and model unload lifecycle
- [ ] Display model provenance and license information

## M3 — Mobile performance

- [ ] Baseline CPU benchmark on representative ARM64 devices
- [ ] Evaluate supported Arm optimizations
- [ ] Evaluate Vulkan backend where supported
- [ ] Measure RAM, tokens/sec, first-token latency, thermals, and battery
- [ ] Define regression thresholds based on repeatable measurements

## M4 — Quantization research integration

- [ ] Audit LookAtMyQuant modules and dependencies
- [ ] Separate platform-independent algorithms from MLX-specific runtime code
- [ ] Define portable intermediate quantization-plan format
- [ ] Validate GGUF export compatibility and numerical correctness
- [ ] Compare model quality against baseline quantization
- [ ] Prototype limited on-device calibration/quantization for small models

## M5 — Release readiness

- [ ] Threat model and dependency/license audit
- [ ] Accessibility, localization, and UX review
- [ ] Crash and performance testing across devices
- [ ] Signed release process and provenance
- [ ] Publish verified APK and release notes

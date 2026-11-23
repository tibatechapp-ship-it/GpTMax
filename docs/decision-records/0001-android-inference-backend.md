# ADR 0001: Android inference backend and initial scope

- Status: Accepted for the first proof of concept; subject to build and device validation
- Date: 2026-11-23

## Context

GpTMax must run local language models on Android ARM64. The project references LookAtMyQuant for future optimization research, but its MLX-specific runtime is not an Android backend. A quantization research port would not prove that users can run inference on-device.

## Decision

1. Establish a working Android ARM64 inference path first using a pinned llama.cpp revision and compatible GGUF model.
2. Keep Android UI and lifecycle in Kotlin; use a small JNI boundary and native C++ adapter.
3. Keep quantization research isolated until baseline inference, test fixtures, and measurements exist.
4. Do not commit model weights or claim GPU support before target-device validation.

## Consequences

- The first milestone is narrower and testable.
- Backend and dependency revisions must be recorded.
- Quantization work can be compared against a known baseline.
- Supported devices and performance claims remain unknown until measured.

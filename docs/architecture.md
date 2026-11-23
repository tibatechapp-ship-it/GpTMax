# GpTMax Architecture

## Scope

GpTMax targets local language-model inference on Android ARM64. The initial product milestone is inference of existing compatible GGUF files. Model compression and optimization research are separate milestones because they have substantially different compute, memory, and validation requirements.

## High-level components

```text
Android UI (Kotlin / Jetpack Compose)
  ├── Model Library and Storage
  ├── Chat / Generation Session
  └── Settings and Diagnostics
          │
          ▼
Application Services (Kotlin)
  ├── Model metadata and integrity checks
  ├── Lifecycle / cancellation
  └── Permission and document-provider integration
          │
          ▼
JNI boundary (small, explicit API)
          │
          ▼
Native C++ inference adapter
  └── Pinned llama.cpp backend + GGUF

Future, separately validated:
Quantization research / plan compiler / export validation
```

## Runtime principles

1. Keep UI and Android lifecycle logic in Kotlin.
2. Keep tensor execution and inference in native code.
3. Expose a small JNI API; do not pass complex Kotlin object graphs into native code.
4. Ensure cancellation and native resource cleanup are deterministic.
5. Never run untrusted repository or model content as executable code.
6. Do not promise GPU acceleration unless a backend is built and verified on the target device.

## Quantization integration

LookAtMyQuant is a research source, not an Android-ready runtime. Its MLX-specific execution path is Apple-oriented. GpTMax will audit algorithmic components, dependencies, and licensing before porting. A portable quantization-plan representation should describe tensor/row allocation without embedding platform-specific runtime assumptions.

Any exported model must pass:

- format and metadata validation;
- load test using the target backend;
- deterministic smoke tests where feasible;
- quality comparison on a fixed evaluation set;
- size, RAM, latency, and thermal measurements.

## Performance reporting

Every benchmark should record device/model, Android version, ABI, backend/build flags, model revision and checksum, quantization type, context length, warm-up policy, token count, peak memory, first-token latency, generation throughput, and thermal conditions. Avoid comparing results gathered under materially different conditions.

## Dependency and license policy

Pin upstream revisions. Review licenses for code, native libraries, model weights, and tokenizer assets independently. Preserve required notices for any adapted third-party source. No model weights are committed to this repository.

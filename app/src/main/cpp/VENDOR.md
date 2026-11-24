# GpTMax — vendoring llama.cpp

The CMake build expects llama.cpp sources at:

```
app/src/main/cpp/llama.cpp
```

It is git-ignored on purpose: pin one upstream commit so the JNI layer below
keeps compiling against a stable API surface. The tree is **not** a submodule —
it is materialized from the pinned-commit tarball.

## Automatic vendoring (CI)

`.github/workflows/android-build.yml` vendors the pin automatically: every
Gradle job (android / release / device) downloads the pinned-commit tarball
right after checkout, unpacks it into `app/src/main/cpp/llama.cpp`, and
sanity-checks `CMakeLists.txt` + `include/llama.h` before Gradle runs.

## Steps (local)

```bash
git clone https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
git -C app/src/main/cpp/llama.cpp checkout 1fb8a26
```

## Recommended pin

**Verified pin: `1fb8a26` (ggml-org/llama.cpp master, 2026-11-22 — commit
"opencl: add coopmat GEMM for Adreno 850").** This is the commit
`.github/workflows/android-build.yml` vendors automatically.

The JNI bridge in `gptmax_jni.cpp` is written against this API surface:

- `llama_model_load_from_file(path, mparams)` / `llama_model_free`
- `llama_model_get_vocab(model)` -> `const llama_vocab*`
- `llama_init_from_model(model, cparams)` / `llama_free`
- `llama_tokenize(vocab, ..., add_special, parse_special)`
- `llama_decode(ctx, batch)` (deprecated-but-compilable in the pin)
- `llama_batch_get_one(tokens, n)` — takes a **non-const** `llama_token*`
- `llama_sampler_chain_init` + `llama_sampler_init_*` chain API
  (`llama_sampler_init_dist(LLAMA_DEFAULT_SEED)` — note `llama_sampler_seed()`
  does not exist in the pin; the getter is `llama_sampler_get_seed()`)
- `llama_vocab_is_eog(vocab, token)`
- `llama_token_to_piece(vocab, token, buf, len, lstrip, special)`
  (the pin has no `llama_vocab_token_to_piece`)

After changing the pin, rebuild and run `nativeIsLoaded()` smoke test before
releasing.

## CMake knobs

The build disables upstream examples/tests/server (`LLAMA_BUILD_EXAMPLES=OFF`
etc.). Android runs CPU-only by default here; `nGpuLayers` is accepted by
`nativeInit` for future Vulkan/OpenCL backend builds — pass 0 today.

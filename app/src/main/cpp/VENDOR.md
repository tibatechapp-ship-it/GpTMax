# GpTMax — vendoring llama.cpp

The CMake build expects llama.cpp sources at:

```
app/src/main/cpp/llama.cpp
```

It is git-ignored on purpose: pin one upstream commit so the JNI layer below
keeps compiling against a stable API surface.

## Steps

```bash
cd app/src/main/cpp
git submodule add https://github.com/ggml-org/llama.cpp llama.cpp
cd llama.cpp
git checkout <PINNED_COMMIT>
cd -
git add .gitmodules app/src/main/cpp/llama.cpp
```

## Recommended pin

**Verified pin: `1fb8a26` (ggml-org/llama.cpp master, 2026-11-22 — commit
"opencl: add coopmat GEMM for Adreno 850").** This is the commit the bundled
GitHub Actions workflow (`.github/workflows/build.yml`) vendors automatically.

```bash
git clone https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
git -C app/src/main/cpp/llama.cpp checkout 1fb8a26
```

The JNI bridge in `gptmax_jni.cpp` is written against this API surface:

- `llama_model_load_from_file(path, mparams)` / `llama_model_free`
- `llama_model_get_vocab(model)` -> `const llama_vocab*`
- `llama_init_from_model(model, cparams)` / `llama_free`
- `llama_tokenize(vocab, ..., add_special, parse_special)`
- `llama_decode(ctx, batch)`
- `llama_sampler_chain_init` + `llama_sampler_init_*` chain API
- `llama_vocab_is_eog(vocab, token)`
- `llama_vocab_token_to_piece(vocab, token, buf, len, lstrip, special)`

After changing the pin, rebuild and run `nativeIsLoaded()` smoke test before
releasing.

## CMake knobs

The build disables upstream examples/tests/server (`LLAMA_BUILD_EXAMPLES=OFF`
etc.). Android runs CPU-only by default here; `nGpuLayers` is accepted by
`nativeInit` for future Vulkan/OpenCL backend builds — pass 0 today.

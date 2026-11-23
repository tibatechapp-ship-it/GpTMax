# GpTMax — Build Guide

## Requirements

- Android Studio Ladybug (2024.2) or newer
- JDK 17 (bundled with recent Studio)
- Android SDK 35, NDK r27+, CMake 3.22.1 (install via SDK Manager)
- Gradle is **not** required on the host: the Gradle Wrapper (8.10.2) ships
  with this project (`./gradlew` / `gradlew.bat`)
- A device/emulator: **arm64-v8a**, Android 8.0 (API 26) or newer
- ~4 GB free storage for the default model

## Steps

```bash
# 1) Vendor llama.cpp (single command — see app/src/main/cpp/VENDOR.md)
cd app/src/main/cpp
git submodule add https://github.com/ggml-org/llama.cpp llama.cpp
cd llama.cpp && git checkout <PINNED_COMMIT> && cd -

# 2) Build & run
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Or simply open the folder in Android Studio and press Run. First native build
compiles llama.cpp (~5–10 minutes on a laptop).

## First run on device

1. **Models tab** — download `Qwen2.5-Coder-3B-Instruct Q4_K_M` (≈1.9 GB).
   Wi-Fi recommended; progress is per-file with resume-safe temp naming.
2. Tap **Load** — status card shows "Engine: loaded" or the native error.
3. **Settings tab** — choose a workspace folder (SAF grant), optionally add a
   BYOK provider (GLM / DeepSeek / OpenRouter / Ollama-on-PC) and enable
   "Cloud-first planning & validation" if you want the agent's brain on cloud
   while edits stay local.
4. **Agent tab** — describe a task; approve every write from the gate card.

## Verify the native layer

```bash
adb logcat -s GpTMaxNative
```

You should see `model loaded: ... (ctx=4096 threads=N gpuLayers=0)` after a
successful Load, and streamed tokens in the Chat tab.

## Troubleshooting

| Symptom | Fix |
|---|---|
| CMake can't find llama.cpp | Vendor step 1 was skipped |
| OOM during Load | Use the 1.5B/0.5B model, or lower context in Settings |
| Slow generation (3B) | Keep context ≤4096, threads = big cores count |
| Cloud error 401 | Re-check the API key in Settings (it never leaves the device) |
| SAF grant lost after reboot | Re-choose the folder (persistable permission re-take) |

## Releasing

- `./gradlew :app:assembleRelease` with your signing config.
- Ship at least one small GGUF path working out of the box (0.5B) so the
  app is usable before any download completes.
- Respect each base model's license when bundling GGUFs in a store build.

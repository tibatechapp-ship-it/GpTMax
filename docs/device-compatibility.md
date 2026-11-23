# Android Device Compatibility

## Compatibility target

GpTMax aims to install and run on the broadest practical range of Android devices, not only one ARM64 handset. Native runtime packages are being expanded to:

| Android ABI | Device class | Status |
| --- | --- | --- |
| `arm64-v8a` | Most modern 64-bit ARM phones/tablets | Existing M1 target |
| `armeabi-v7a` | Legacy 32-bit ARM phones/tablets | Added to multi-ABI build; CI/device validation required |
| `x86_64` | 64-bit Intel/AMD Android devices and emulators | Added to multi-ABI build; CI/device validation required |
| `x86` | 32-bit Intel Android devices | Not included; very uncommon and needs separate backend validation |

Minimum Android API remains 26 (Android 8.0). This is a deliberate current baseline; supporting older Android versions requires auditing dependencies, storage APIs, and native runtime behavior before lowering it.

## What multi-ABI packaging does and does not guarantee

Android chooses the matching native library for a supported ABI during installation. A device with an ABI not packaged by GpTMax will not be able to load the JNI inference library.

Multi-ABI packaging is not a guarantee that every GGUF model will fit or perform well. Model size, quantization, context length, available RAM, CPU features, thermal limits, and vendor firmware all affect whether inference can run safely.

## Required release gates

A compatibility release is not complete until CI and representative physical devices establish:

1. Native library compilation for every packaged ABI.
2. APK/AAB contains the correct `lib/<abi>/libgptmax_native.so` entries.
3. App installs, launches, imports a small valid GGUF, and generates text on each ABI family.
4. Low-memory model loading fails gracefully without crashing the process.
5. Cancellation, unload, reload, and background/foreground lifecycle are exercised.
6. Device profile and selected inference parameters are visible and recorded.

## Model selection policy

The app must not present one large model as universally suitable. The planned model advisor will classify models by file size, architecture, quantization, context requirement, and a conservative estimate of working memory. It should recommend smaller models on constrained devices and explain when local inference is not recommended.

Do not infer GPU acceleration from device brand or marketing name. Only enable an accelerator after its backend is implemented, detected, and tested.

## Packaging

The debug APK is a universal multi-ABI APK and therefore larger than an ABI-specific build. Production distribution should use Android App Bundle ABI splits (or verified ABI-specific APKs) so each device downloads only its native library. Keep a universal APK available for direct testing where practical.

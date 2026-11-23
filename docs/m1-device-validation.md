# M1 Physical-device validation runbook

This runbook is the acceptance procedure for the first real GGUF inference on Android ARM64. CI success and APK packaging are prerequisites only; neither is a device inference result.

## 1. Test artifact

- Model: Qwen2.5-0.5B-Instruct GGUF
- Quantization: Q4_K_M
- File: `qwen2.5-0.5b-instruct-q4_k_m.gguf`
- Published size: 491 MB
- License: Apache-2.0
- Official repository: https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF
- Direct download: https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf
- Expected SHA-256: `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db`

The APK does not include model weights. The app's verified-import action checks the complete file digest before it stores or loads this specific test artifact. Generic user-selected GGUF imports still calculate and record their own digest but do not claim to match the Qwen artifact.

## 2. Installation and import

1. Install the latest `gptmax-debug-apk` artifact from the successful Android Build run.
2. Open **Download test model** in GpTMax. The browser downloads the official 491 MB GGUF file.
3. Return to GpTMax and choose **Import and verify downloaded Qwen GGUF**.
4. Select the downloaded `qwen2.5-0.5b-instruct-q4_k_m.gguf` file.
5. Wait for SHA-256 verification, private-storage import, native model loading, and context creation.
6. Confirm the native runtime status reports that the GGUF model and inference context are ready.

A digest mismatch, invalid GGUF magic, incomplete download, or insufficient storage must fail closed. Do not bypass the verified-import action for this smoke test.

## 3. Generation checks

Run these prompts separately and preserve the complete outputs.

### English functional prompt

```text
Write a Python function named is_prime(n).
Return True if n is prime and False otherwise.
Explain the algorithm briefly.
```

### Arabic Unicode prompt

```text
اكتب دالة Python تتحقق مما إذا كان العدد أوليًا، واشرح طريقة عملها باختصار باللغة العربية.
```

Acceptance:
- Output begins streaming without a JNI exception or app crash.
- Arabic characters and mixed Arabic/English text are not corrupted.
- The model's embedded chat template is applied by the native llama.cpp bridge.
- Generation stops at an end-of-generation token or the configured token limit.
- Stop cancels generation and the UI returns to an idle state.

## 4. Lifecycle and negative checks

- Launch with no model: Generate remains disabled.
- Select a non-GGUF file: import is rejected.
- Select a truncated/corrupt GGUF: import is rejected or native load fails with a visible error; no false ready state.
- Select a modified Qwen file: expected SHA-256 verification rejects it.
- Generate with an empty prompt: no native generation starts.
- Submit a prompt exceeding the configured context: a clear context-window error is shown.
- Stop during prompt evaluation and during token generation.
- Unload model: native context/model are released while the stored GGUF remains.
- Reload from Stored models: the app verifies the stored content digest before native load.
- Delete an inactive model: file and metadata are removed.
- Attempt to delete the active model: the runtime unloads it first through the UI flow.
- Repeat load/unload at least five times and watch for crashes or steadily increasing memory use.
- Rotate the device during generation and confirm the Activity recreation does not corrupt session state.

## 5. Evidence to record

| Field | Value |
|---|---|
| Device model | |
| Android version/build | |
| ABI (`arm64-v8a`) | |
| Available RAM before load | |
| Free app storage before import | |
| APK commit SHA | |
| llama.cpp pinned SHA | |
| Model SHA-256 | |
| Context / batch / threads | |
| Model load duration | |
| Time to first token | |
| Generated token count | |
| Generation duration | |
| Tokens per second | |
| Peak memory (PSS/RSS) | |
| Device temperature / thermal status | |
| Cancellation latency | |
| English output captured | |
| Arabic output captured | |
| Lifecycle checks passed | |

For log capture with Android Debug Bridge:

```bash
adb logcat -c
adb logcat -v time -s GpTMaxNative:I llama.cpp:I AndroidRuntime:E '*:S'
```

Do not record M1 as complete until the device, model digest, actual generated text, lifecycle checks, and measurements are filled in. Do not extrapolate device performance from CI.

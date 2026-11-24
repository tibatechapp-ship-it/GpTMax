# GpTMax — Model Selection (on-device GGUF + BYOK cloud)

## On-device catalog (Q4_K_M, llama.cpp)

| Entry | Model | Size | Min RAM | Role |
|---|---|---|---|---|
| `qwen25-coder-0.5b` | Qwen2.5-Coder-0.5B-Instruct | ≈0.4 GB | 1 GB | Instant chat, autocomplete |
| `qwen25-coder-1.5b` | Qwen2.5-Coder-1.5B-Instruct | ≈1.1 GB | 2 GB | Light local coder |
| `qwen25-coder-3b`   | Qwen2.5-Coder-3B-Instruct   | ≈1.9 GB | 4 GB | **Default local coder** |
| `qwen25-coder-7b`   | Qwen2.5-Coder-7B-Instruct   | ≈4.7 GB | 8 GB | Flagship devices |
| `smollm2-1.7b`      | SmolLM2-1.7B-Instruct       | ≈1.1 GB | 2 GB | Ultra-light assistant |

Facts verified from the Qwen2.5-Coder release (Nov 2024):

- The family ships six sizes: 0.5B / 1.5B / 3B / 7B / 14B / 32B — Apache 2.0.
- **Qwen2.5-Coder-32B-Instruct** is the SOTA open code model of its release:
  it matches GPT-4o on EvalPlus / LiveCodeBench / BigCodeBench, scores
  **73.7 on Aider (code repair)** and 65.9 on McEval across 40+ languages.
- Everything ≥0.5B uses the ChatML template — already implemented in
  `LocalEngine.ChatML`.

## Cloud tier (BYOK, OpenAI-compatible)

| Provider | Base URL | Model id | Role |
|---|---|---|---|
| GLM Coding Plan | `https://open.bigmodel.cn/api/paas/v4` | `glm-4.6` | Plan/validate brain |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` | Cheap long-context |
| OpenRouter | `https://openrouter.ai/api/v1` | `anthropic/claude-sonnet-4` | Frontier quality |
| Ollama on PC (LAN) | `http://<pc-ip>:11434/v1` | `qwen2.5-coder:7b` | Free heavy tier |

## Agentic scale — on a PC, not the phone

**Qwen3-Coder-30B-A3B-Instruct** (verified specs): MoE with **30.5B total /
3.3B active** parameters, 128 experts (8 active), native **256K context**
(YaRN-extendable to 1M), agentic function-calling format, non-thinking mode.
Run it behind the Ollama preset — GpTMax phones just route to it.

## Compressed quantization — the LookAtMyQuant path (Phase 5)

Verified from `github.com/ShinMegamiBoson/lookatmyquant` (MIT, 625★):

> "A quantization compiler that delivers **12× lower KL than Q4_K_M at the
> same file size**."

- It is a **quantization compiler**: it measures per-block KL damage against
  a calibration set, allocates bits by Fisher information, and emits recipes
  (Trellis codes) compiled to **GGUF files that run in llama.cpp unmodified**.
- This is exactly GpTMax's Phase 5 "quantization lab": compile the default
  coder at **half-size with near-standard quality** (e.g. a ≈1.0 GB 3B-class
  coder), then measure against Q4_K_M at equal size before shipping.
- License note: the compiler is MIT; the base model's license still governs
  redistribution of each GGUF.

## Adding a model

1. Put the GGUF in `files/models/` (via Models tab download or adb push).
2. Tap **Load** — the engine reports errors in the card.
3. Tune context/threads in Settings if the device struggles.

# Model Selection Guide

This document defines the concrete model matrix behind the execution plan: a local GGUF baseline per device tier, the BYOK cloud package, and the quantization track. It is a planning input for P2 (router) and P5 (quantization lab); nothing here is a shipped capability claim.

- Status: Proposed
- Date: 2026-11-23

## Local GGUF baseline (per device tier)

Weights always stay external to the APK and are verified by the existing SHA-256 import flow.

| Device tier | RAM | Recommended model | Quantization | File size | Notes |
| --- | --- | --- | --- | --- | --- |
| Entry | 3–4 GB | Qwen2.5-0.5B-Instruct | Q4_K_M | 491 MB | M1 smoke-test model; published SHA-256 `74a4da8c…` verified at import |
| Mid | 4–6 GB | Qwen2.5-1.5B-Instruct | Q4_K_M | ~1.1 GB | Verify the digest from the official HF repo at import time |
| Upper-mid | 6–8 GB | Qwen2.5-3B-Instruct | Q4_K_M | ~1.9 GB | Practical ceiling for most mid-tier devices |
| Flagship | ≥ 8 GB | Qwen2.5-3B-Instruct | Q5_K_M / Q6_K | ~2.3–2.6 GB | Only after P0/P5 measurements on the actual device |

Sizing rules: peak RSS must stay below ~60% of device RAM at the configured context length; context length is set device-adaptively in P0, not hardcoded.

## BYOK cloud package (P2 router)

- Contract: OpenAI-compatible chat-completions endpoints only; provider quirks live in adapter code.
- Candidates (user-supplied keys, none bundled or endorsed): GLM Coding Plan, DeepSeek V3.x class, Claude Sonnet class, Qwen-Max class.
- Routing policy (ADR 0003): local first; escalate on context ceiling, structured-output quality threshold, or user choice; every escalation requires explicit per-task consent and is recorded in the task ledger with redacted metadata.
- Keys: Android Keystore; never in logs, ledger, or model context.

## Quantization track — LookAtMyQuant (`lamq`)

- What it is: an MIT-licensed quantization compiler (upstream: `ShinMegamiBoson/lookatmyquant`) that allocates bits per row using Fisher information and trellis codes, then applies bounded post-distillation, minimizing KL damage per file-size budget. GGUF export is demonstrated upstream on Gemma 4 E2B with roughly 12× lower KL than Q4_K_M at equal file size (upstream README measurements).
- Integration gate (M8/P5): audit algorithms, dependencies, and license → separate portable logic from MLX-specific runtime → export GGUF for the Android target models above → validate loadability with the pinned llama.cpp backend → compare against the Q4_K_M baseline on the same devices.
- Candidate coding models for export: the strongest openly available coding family with official GGUF support (Qwen2.5-Coder series), prioritized by device tier.
- Policy: quantization results stay reproducible and separate from coding-agent quality claims (see [architecture.md](architecture.md)).

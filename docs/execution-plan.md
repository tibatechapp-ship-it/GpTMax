# GpTMax Execution Plan (P0–P6)

This document translates [ROADMAP.md](../ROADMAP.md) (M0–M9) into a sequenced, executable plan with per-phase tasks, acceptance criteria, KPIs, risks, and role mapping. It follows existing governance: a phase is complete only when implementation, tests, and evidence are merged, and no broad multi-agent or quantization implementation starts before M1 establishes a measured baseline.

- Status: Proposed for adoption (the BYOK bridge decision is recorded in [ADR 0003](decision-records/0003-byok-cloud-bridge.md))
- Date: 2026-11-23
- Horizon: 12–18 months solo; compressible to ~10 months with one C++ and one Compose engineer joining before P2
- Tracking: phase issues P0–P6 in the repository issue tracker

## Phase overview

| Phase | Milestone target | Title | Duration | Hard exit criterion |
| --- | --- | --- | --- | --- |
| P0 | M1 | Physical-device inference proof | 2 weeks | Documented generation on real ARM64 devices with full evidence table; `v0.1-alpha` tagged |
| P1 | M2 | Studio foundation | 4–6 weeks | Open project, browse/edit files, search, inspect diff — no agent |
| P2 | M3 | Agent Core completion + BYOK cloud bridge | 6 weeks | One bounded repo task completes locally with an auditable result; cloud escalation gated by consent |
| P3 | M4+M5 | Autonomous loop and managed runtime | 6–8 weeks | Multi-file edit + validation + bounded repair with evidence and diff |
| P4 | M6 | Code intelligence and project memory | 4 weeks | Ranked retrieval measured against a baseline with privacy controls |
| P5 | M8 | Quantization and performance lab | 6 weeks | Reproducible GGUF quantization comparison vs Q4_K_M baseline |
| P6 | M9 (+M7 gate) | Production release | 4 weeks | Signed release with provenance, device matrix, honest capability list |

## P0 — Close M1: physical-device inference proof (2 weeks)

M1 implementation is nearly complete (pinned llama.cpp `f4f6f2e7db76e98c95cc9b7fc608ea669c806044`, native generation with chat templates, streaming, cancellation, SHA-256 verified model import, model registry). What is missing is device evidence, not code.

Tasks:

- [ ] Run [m1-device-validation.md](m1-device-validation.md) on two physical ARM64 devices: one flagship (≥ 8 GB RAM) and one mid-tier (4–6 GB)
- [ ] Record the full evidence table: device model, Android version, ABI, backend commit, model architecture/quantization, file size + SHA-256, context configuration, memory before/after/peak, load latency, time to first token, tokens/second, thermal state, generated sample
- [ ] Verify cancellation, unload, repeated load/unload, and low-memory failure handling on device
- [ ] Add instrumented tests: import, model reload, delete, low-memory behavior, Activity recreation during active generation
- [ ] Replace the fixed bring-up context configuration (2048 tokens / batch 512 / 4 threads) with device-adaptive values driven by measured RAM
- [ ] Tag `v0.1-alpha` with an APK artifact and release notes listing verified vs unverified capabilities

Acceptance criteria:

- Generation documented on both devices with complete evidence tables committed to `docs/`
- Time to first token ≤ 3 s and sustained ≥ 5 tokens/s on the flagship device with Qwen2.5-0.5B-Instruct Q4_K_M
- No ANR or crash during a 30-minute stress run (repeated load/generate/cancel/unload cycles)
- `v0.1-alpha` published with honest capability notes

Update the M1 checkboxes in ROADMAP.md only when the above evidence is merged.

## P1 — Studio foundation (M2 completion, 4–6 weeks)

Deliver the no-agent studio loop.

Tasks:

- [ ] Project explorer UI (tree, file icons, ignore-aware listing)
- [ ] Editor with syntax highlighting and multi-tab state
- [ ] Dedicated file search UI over the existing literal search tool
- [ ] Git status/diff view (read-only Git inspection foundation already merged)
- [ ] App-private task workspace and snapshot strategy
- [ ] Accessibility, RTL, and small-screen UX baseline
- [ ] Audit the recent context-engine merges (persistent project context, bounded SAF reader, secret filtering, repository overview, task checkpoints) and check off completed M2/M6 items with evidence

Acceptance: a user opens a project, browses, edits, searches, and inspects changes without invoking the agent; RTL verified on an Arabic-locale device.

## P2 — Agent Core completion + BYOK cloud bridge (6 weeks)

Close the last M3 items and introduce the Inference Router (see [ADR 0003](decision-records/0003-byok-cloud-bridge.md)).

Tasks:

- [ ] Context builder with explicit token/budget limits (M3)
- [ ] Session resume from the task ledger (M3)
- [ ] Inference Router: local default, per-task cloud escalation with explicit consent
- [ ] BYOK provider adapters (OpenAI-compatible endpoints); keys in Android Keystore, never in logs or the task ledger
- [ ] Escalation triggers defined: context ceiling, structured-output quality threshold, user choice; every escalation recorded in the task ledger with redacted metadata
- [ ] Mock cloud endpoints in CI; live-provider verification manual

Acceptance: one bounded repository task completes using local tools with an auditable ledger; a second task demonstrates consented escalation to a BYOK provider without leaking project content.

## P3 — Autonomous loop and managed runtime (M4+M5, 6–8 weeks)

Tasks:

- [ ] Plan → inspect → patch → validate → diagnose → repair loop (M5)
- [ ] Patch validation and conflict detection (M5)
- [ ] Build/test result parser and failure summarization (M5)
- [ ] Independent review pass with explicit uncertainty (M5)
- [ ] User-visible checkpoints, stop control, and rollback (M5)
- [ ] Approval gates for destructive, network, credential, publish, and merge actions (M5)
- [ ] Per-task working directory and environment policy (M4)
- [ ] Resource limits and cleanup of child processes (M4)
- [ ] Safe Git and language-tool integrations; capability detection for Android-compatible tools (M4)
- [ ] Optional build-worker protocol for tasks that exceed device capability (M4)

Acceptance: the agent modifies multiple files, runs available validation, completes a bounded failure/repair cycle, and presents evidence plus a diff; high-risk actions require approval and are visible in the task timeline.

## P4 — Code intelligence and project memory (M6, 4 weeks)

Tasks:

- [ ] Ignore rules for VCS, generated, binary, secret, and oversized files (M6)
- [ ] File inventory and language detection (M6)
- [ ] Symbol indexing where language tooling supports it (M6)
- [ ] Search and ranked context retrieval with a measured relevance baseline (M6)
- [ ] Optional local semantic index with deletion/rebuild controls (M6)
- [ ] Dependency/reference graph where available (M6)
- [ ] Project decisions and task memory with user visibility and removal (M6)

Acceptance: retrieval relevance measured against a fixed baseline fixture; all project memory visible, scoped, and removable.

## P5 — Quantization and performance lab (M8, 6 weeks)

Follow the quantization track defined in [models.md](models.md) and the research policy in [architecture.md](architecture.md).

Tasks:

- [ ] Audit LookAtMyQuant (`lamq`) algorithms, dependencies, and license
- [ ] Separate portable research logic from MLX-specific runtime code
- [ ] Define the portable quantization plan and GGUF export validation procedure
- [ ] Export GGUF builds for the Android target models at tier-matched file sizes
- [ ] Validate exported builds load and generate with the pinned llama.cpp backend
- [ ] Compare quality (KL/perplexity plus a coding benchmark), size, RAM, latency, thermal behavior, and energy against the Q4_K_M baseline on the same devices
- [ ] Keep optimization results reproducible and separate from coding-agent claims

Acceptance: a reproducible comparison table committed to `docs/`; at least one exported GGUF passes load + generation parity; results never bundled into agent-quality claims.

## P6 — Production release (M9, 4 weeks)

Tasks:

- [ ] Security, dependency, and license review (M9)
- [ ] CI build, tests, lint, and artifact provenance (M9)
- [ ] Device matrix and stability testing (M9)
- [ ] Privacy, accessibility, localization, and UX review (M9)
- [ ] Signed release process without committed secrets (M9)
- [ ] Release notes, checksums, known limitations, and verified capability list (M9)
- [ ] M7 specialist workflows remain gated until single-agent benchmark repeatability (milestone policy)

## Risk register

| Risk | Likelihood | Impact | Mitigation |
| --- | --- | --- | --- |
| Single-contributor bus factor | High | High | This plan, docs, CI; recruit 1–2 contributors before P2 |
| Device fragmentation | High | Medium | Capability detection, device matrix, conservative defaults |
| Thermal/RAM limits on mid-tier devices | Medium | High | Tiered model matrix (models.md), device-adaptive context config in P0 |
| BYOK credential leakage | Low | High | Keystore storage, centralized secret filtering (merged), ledger redaction |
| Quantized-model quality regression | Medium | Medium | Measured track only, fixed baselines, separate claims policy |

## KPIs

- Local time-to-first-token ≤ 3 s; ≥ 5 tokens/s sustained on the 0.5B reference tier
- Coding-task success ≥ 50% on a 10-task reference fixture set by end of P3; ≥ 70% by P6
- Context build p95 ≤ 2 s on a 10k-file repository fixture
- Crash-free sessions ≥ 99.5% across the device matrix
- 100% of cloud escalations require explicit per-task consent (audited via the task ledger)

## Roles

| Role | Responsibility | Now | Recruitment target |
| --- | --- | --- | --- |
| Owner/architect | Direction, review, phase gates | tibatechapp-ship-it | — |
| C++ inference engineer | Native backend, quantization lab | Owner (part-time) | 1 before P2 |
| Android/Compose engineer | Studio UI, runtime | Owner (part-time) | 1 before P2 |
| CI/release | Workflows, provenance, releases | Owner | Community |

## Phase-gate review

At each phase exit: re-read the acceptance criteria, update ROADMAP.md checkboxes only with merged evidence, and record a short gate note in the phase issue before starting the next phase.

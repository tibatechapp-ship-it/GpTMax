# ADR 0003: BYOK cloud bridge behind the GpTMax Inference API

- Status: Accepted as direction; implementation planned for P2
- Date: 2026-11-23

## Context

On-device models cap coding-agent quality on mid-tier Android devices. Comparable products (Cursor, ZCode) escape that ceiling by escalating to cloud models. GpTMax's local-first positioning means the cloud path must be opt-in, user-owned (bring your own key), and auditable, without weakening the existing privacy boundaries: project data stays local unless the user explicitly enables an external service. The architecture already separates local mode from an optional build-worker mode, and the Inference API boundary exists so backends can change without touching agent orchestration. Recent merges added centralized secret filtering and a private task ledger, which are the right hooks for consent and redaction.

## Decision

1. Add an Inference Router behind the existing GpTMax Inference API: local backend by default, cloud escalation per task.
2. The cloud path is BYOK only, over OpenAI-compatible endpoints. No provider is bundled, configured, or affiliated; provider-specific behavior lives in adapter code only.
3. API keys are stored in the Android Keystore and never written to logs, the task ledger, or model context; the centralized secret filter applies to every tool output and escalation payload.
4. Escalation triggers are explicit: context window ceiling, structured-output quality threshold, or direct user choice. Every escalation requires per-task consent and is recorded in the task ledger with redacted metadata.
5. Cloud mode never receives project content without explicit per-task user enablement; tool-permission scope mirrors the local contract.
6. The router is an adapter behind the stable Inference API; agent orchestration remains backend-agnostic.

## Consequences

- M3 exit criteria extend to include router acceptance tests (local task plus a consented escalation).
- The privacy posture is preserved and must be disclosed honestly in UI and release notes.
- CI uses mock cloud endpoints; live-provider checks are manual and optional.
- A new failure class (network, provider errors) enters the task state machine and must degrade gracefully to local operation.

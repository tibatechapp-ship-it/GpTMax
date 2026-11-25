# Phase 2 (Inference Safety) & Phase 3 (Resumability)

Continuation of the external-review roadmap. Phase 1 (data protection: tool-layer
lockdown, workspace pinning, verified saves) landed in `de8b6fb` + `27e4c1a`;
this document covers the next two phases of the same review.

## Phase 2 — Inference safety

Goal: nothing a model or a remote provider sends back can crash, exhaust, or
deceive the app, and no client failure is ever mistaken for a model answer.

| # | Risk before | Fix | Where |
| --- | --- | --- | --- |
| 1 | Cloud SSE loop accumulated text with NO cap and NO deadline — a runaway provider could OOM the phone or hold the app forever | Hard output cap (`InferenceSafety.MAX_OUTPUT_CHARS`) + total-stream deadline (6 min) enforced inside the read loop, in addition to the per-read socket timeout | `CloudBridge.stream()` |
| 2 | Error strings ("[cloud error 401] …", "local engine not loaded") looked exactly like model output and were parsed as plans / shown as answers | ONE typed failure marker: `InferenceSafety.FAILURE_PREFIX = "[gptmax-failure]"`; legacy sentinels stay recognized. `PlanParser` never sees a failure (AgentCore.plan returns empty first) | `InferenceSafety`, `CloudBridge`, `LocalEngine`, `AgentCore` |
| 3 | "Graceful fallback" existed in docs but NOT in code — the router picked one client and died with it | `InferenceRouter.completeWithFallback()`: primary source → typed failure → one retry on the other source → else the primary failure is surfaced. Used for PLAN and VALIDATE | `InferenceRouter`, `AgentCore` |
| 4 | An API key could be sent over plain `http://` | `InferenceSafety.validateEndpoint()`: https required whenever a key is attached; key-less LAN endpoints (Ollama/LM Studio) may stay http — checked BEFORE any network call, so the key never leaves | `CloudBridge.stream()` |
| 5 | Unbounded `temperature` / `max_tokens` from settings reached the providers | Clamped to [0,2] and [1, 8192] | `InferenceSafety.clamp*` |
| 6 | A huge prompt could pin phone memory inside the native engine | Rendered prompt > 96k chars is refused with a typed failure before `nativeGenerate` | `LocalEngine.stream()` |
| 7 | Provider text with control characters entered history/UI; chat stream could grow without bound | `InferenceSafety.sanitize()` applied to chat answers and step results; chat stream hard-capped at 64k chars | `ChatViewModel`, `AgentCore` |
| 8 | Cancel only flagged the coroutine — a 10-minute local plan kept burning CPU after Stop | `AgentViewModel.stop()` now also calls `localEngine.cancel()` (native cooperative stop), matching the chat path | `AgentCore`, `ViewModels` |

## Phase 3 — Resumability

Goal: a run interrupted by process death, a crash, or the user's Stop is
resumable from the exact boundary — without re-executing completed work.

- **`RunResumeStore`** (new, file-backed, atomic): one JSON snapshot
  (`filesDir/agent-resume/resumable-run.json`) holding goal, task id, the
  pinned workspace URI, and every step's tool/args/status/result.
  Written tmp-file + rename; corrupted files self-heal to null; oversized
  step args are capped (64k chars) and the whole snapshot has a 1M-char
  budget — beyond it resumability is dropped, never a crash.
- **`AgentCore.run(task, resume)`**: restores persisted steps, skips every
  step already DONE (its result still feeds validation), re-asks the approval
  gate for pending writes, and emits `AgentEvent.Resumed(done, total)`.
- Persistence points: after planning, after EVERY step (done / rejected /
  failed), and at cancellation — so the snapshot always reflects the last
  safe boundary.
- **Completed run → snapshot cleared. Cancelled or failed run → kept.**
- **`AgentViewModel`**: loads the snapshot at construction; `resume()` refuses
  a workspace mismatch (a run pinned to project A is never replayed against
  project B); `dismissResume()` discards; the Failed handler refreshes the
  banner from disk.
- **`AgentScreen`**: resume banner card ("Interrupted run found — X/Y steps
  done") with Resume / Discard, shown only while no run is active.
- `AgentTask` now carries `workspaceUri` so the pin survives restarts.

## Tests

- `Phase2InferenceSafetyTests.kt` — sanitize/caps, failure markers (typed +
  legacy), clamps, endpoint rules, unconfigured/insecure cloud behavior, and
  router fallback (primary fails → cloud answers; both fail → primary failure
  surfaced; healthy primary untouched).
- `Phase3ResumabilityTests.kt` — snapshot roundtrip, corrupted/missing
  snapshot handling, args truncation, unknown-status degradation, resume
  skipping DONE steps (verified via event stream), cancelled-run snapshot
  retention, completion clearing the snapshot.

All JVM unit tests run without Android: `./gradlew testDebugUnitTest`.

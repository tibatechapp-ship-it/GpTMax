# Phase 5 — Adaptive agent: honest outcomes, precise patching, real review evidence

Closes the M5 "autonomous coding loop" gaps the external review called out:
the agent could report success while steps had failed or been rejected, the
reviewer judged changes from file names instead of the actual diff, and the
only edit primitive was a whole-file rewrite.

## What landed

### 1. `patch_file` — unified-diff edits with fail-closed validation

`core/agent/UnifiedDiff.kt` (new) is a self-contained unified-diff engine:

- **apply()** computes the ENTIRE patched content before any mutation. Any
  hunk whose context does not match the file aborts the whole patch — the
  on-disk file is untouched and no journal entry is created, so a stale or
  hallucinated diff costs nothing (M5: *patch validation and conflict
  detection*).
- Hunks are located by CONTENT: first at the stated line, then in a bounded
  search window (±200 lines), so drifted line numbers still land while
  genuinely stale context is rejected with an actionable error.
- CRLF files are matched by content (trailing `\r` ignored); context lines
  keep their original bytes. `\ No newline at end of file` markers are
  honored on both sides; the trailing-newline state of the result follows
  GNU marker semantics.
- Multi-file diffs, binary patches and malformed hunk lines are rejected
  outright — one file per `patch_file` call.
- **render()** produces a unified diff between two snapshots. It is the
  review-evidence source (below) and round-trips through `apply()` exactly.

The tool is write-class: PathGuard + SensitivePaths containment, the user
approval gate (the preview IS the patch, not a description), the rollback
journal, and the same read-back fingerprint verification as `write_file`.
Both write tools now share one `writeVerified` tail (journal → write →
read-back → rollback on divergence).

### 2. Honest completion statuses — DONE is earned, not assumed

`AgentCore` now tracks outcomes instead of always emitting success
(M5: *independent review pass with explicit uncertainty*):

| Status | Trigger |
| --- | --- |
| `DONE` | every step succeeded, plan complete, reviewer verdict APPROVED |
| `PARTIAL` | any step failed, plan truncated by maxSteps, plan ended without finish, steps never ran, or the review was inconclusive (results UNVERIFIED) |
| `BLOCKED` | the user rejected a write the plan needed |
| `VERIFY_FAILED` | all steps ran but the reviewer verdict was CHANGES_NEEDED |

`ReviewClassifier` (new) reads the reviewer's verdict: the phase-5 validate
prompt requires a `VERDICT: APPROVED | CHANGES_NEEDED` line; the legacy
one-word "APPROVED" is still recognized; explicit negative language maps to
CHANGES_NEEDED; anything else is INCONCLUSIVE — and inconclusive is treated
as unverified, never as success.

`AgentPhase` gained the three non-success terminal states; the UI chip
colors them distinctly from DONE and the Run button accepts them as a
starting point for the next attempt.

### 3. Real review evidence

The validator request previously contained file names and trimmed
observations. It now contains:

- the unified diff of every applied change (per step, carried on
  `ToolResult.diff` — no shared-journal lookups, no stale-entry risk),
- every failed/rejected step with its tool, path and result,
- the bounded observations (unchanged budget discipline).

### 4. Resumability follows honesty

Only a `DONE` run clears the snapshot. `PARTIAL` / `BLOCKED` /
`VERIFY_FAILED` runs stay resumable: completed steps are never re-executed,
failed and rejected ones retry from the exact boundary, and every pending
write re-passes the approval gate. The run ledger records the terminal
phase per status instead of always `DONE`.

## What this phase does NOT claim

- The loop is not yet re-planning on failure (M5 "diagnose → repair" remains
  open); it now refuses to hide failure, which is the precondition for it.
- Build/test result parsing (M5) is still future work — `run_command` is
  read-only today.

## Tests

`Phase5AdaptiveAgentTests` (JVM): UnifiedDiff apply/drift/atomicity/CRLF/
EOF-marker/roundtrip/malformed-input; ReviewClassifier protocol + legacy +
negatives + inconclusive; `patch_file` tool paths (apply+journal, stale
reject untouched, missing file, sensitive path, write-class gate); agent
statuses (DONE, PARTIAL, BLOCKED, VERIFY_FAILED, unverified, truncated);
review evidence contains the real diff; patch preview shows the hunks.

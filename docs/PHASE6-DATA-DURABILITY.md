# Phase 6 — Data Durability & Session Integrity

External-review priority 1 ("close the data risks before features"): every
byte the agent or the editor touches now has a durable, full-fidelity undo
path, resume can prove what already landed, and stale async work can no
longer masquerade as success.

## Defects fixed (from the external review of `fcdfe8d`)

| # | Defect | Root cause | Fix |
|---|--------|-----------|-----|
| 1 | Rollback could restore a **truncated** file | `WriteJournal` capped `previousContent` at 64k chars while fingerprinting the FULL content | Journal keeps **full byte copies** of before/after content — never truncated |
| 2 | Journal died with the process | entries lived in an in-memory `ArrayDeque` only | Entries persist to `filesDir/agent-journal/` (`jnl-<seq>.meta/.before/.after`); a new process rebuilds the index from disk |
| 3 | Resume could **duplicate** a write | process death between executing a write and logging its DONE left the step PENDING | `AgentCore.verifyResumedWrites` compares the current file fingerprint against the durable journal: match-after ⇒ step marked DONE (skipped); match-before ⇒ safe re-run; neither ⇒ explicit drift warning |
| 4 | Capped step args produced **invalid JSON** on resume | blind `argsJson.take(n)` cut mid-string | `RunResumeStore.capArgs` parses the JSON, caps per string field, re-serializes — always valid JSON; capped values carry `[GPTMAX-RESUME-TRUNCATED]` |
| 5 | A capped write would be **executed as a partial overwrite** | the tool layer saw only the truncated content | `ToolRegistry` refuses any write/patch whose content carries the truncation sentinel (fail-closed) |
| 6 | External modification during the approval window was **clobbered** | write happened against the state read at plan time | Every mutation re-reads the file right before writing and **refuses** on divergence (TOCTOU closure for write/patch/delete) |
| 7 | Old chat history could leak into a new project's request | history came from a UI flow that may still hold the previous session | `ChatViewModel` reads history from the DB for the CURRENT session id, before inserting the new user message |
| 8 | A stale save could clear the unsaved flag over **newer** edits | save completion ignored concurrent edits | `EditorViewModel.save` binds to the text generation + workspace at call time; newer edits keep the flag; a workspace switch mid-save is an error |
| 9 | Save failures were **invisible** | `saveError` was never rendered | EditorScreen shows a dismissible error banner + saving spinner; the Save button stays disabled while saving |

Also: auto-approve (`autoApproveWrites`) **never covers `run_command`** —
external process execution always requires an explicit human decision
(`ApprovalGate.request`).

## Journal on disk

```
filesDir/agent-journal/
  jnl-<seq>.meta     — JSON: path, kind, fingerprints(before/after),
                       stepIndex, timestamps   (the COMMIT point)
  jnl-<seq>.before   — FULL previous content (optional)
  jnl-<seq>.after    — FULL intended content (optional)
```

Contents are written FIRST, meta LAST: a torn write can only leave orphan
side files, which the index scan deletes. Only the newest 20 entries are
kept; pruning deletes the files.

## Resume verification decision table

| current fingerprint | journal says | action |
|---|---|---|
| == `fingerprintAfter` | write already landed | step → DONE, skipped, log "already applied" |
| == `fingerprintBefore` | write never ran | stays PENDING, approval re-asks, safe re-run |
| neither | file drifted | stays PENDING + explicit warning; TOCTOU recheck / patch context check catch any stale mutation |

## Acceptance tests

`Phase6DataDurabilityTests` (JVM, real child-free fakes):

1. journal survives "process restart" with byte-exact 300k-char copies
2. in-memory journal keeps full copies (old 64k-truncation regression)
3. journal prunes past maxEntries and rebuilds a bounded index on reboot
4. oversized snapshot args load as VALID JSON ending with the sentinel
5. tool layer refuses a resume-capped write_file / patch_file — nothing written
6. write/delete refused when the file changed between journaling and execution
7. resumed run skips a journal-proven already-applied patch (no duplication)
8. resumed run surfaces a drifted file instead of silently rewriting it
9. auto-approve covers writes but never `run_command`
10. blank-path writes are refused

Updated: `Phase3ResumabilityTests` now asserts the new valid-JSON capping
semantics.

## Deliberately out of scope here

- App-private snapshot strategy / project pinning of every subsystem
  (ROADMAP M2) — next data-safety step.
- SHA-256 model download verification, local-only network mode, HTTP
  cancellation binding (release-gate items).

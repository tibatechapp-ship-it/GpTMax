# Phase 4 (Command Sandbox: Device Gate + Enablement)

Continuation of the external-review roadmap. Phases 1–3 landed the data-safety
lockdown, inference safety and resumability (`docs/PHASE2-PHASE3.md`). Phase 4
closes the loop on `run_command`: phase 1 disabled command execution "until the
sandbox passes its device gate" — this phase builds that gate, proves the
sandbox end-to-end, and ships the tool enabled with a retained kill switch.

## Goal

Approved commands execute in a managed process environment and return
structured output; unsafe commands are blocked or require approval — the M4
exit condition of the product roadmap — with the sandbox mechanics PROVEN by
tests instead of asserted by comments.

## What changed

| # | Before | After | Where |
| --- | --- | --- | --- |
| 1 | The spawn/drain/kill mechanics were a private method of `ToolRegistry`, testable only through the whole registry — there was nothing concrete to gate the re-enablement on | Extracted into `CommandSandbox` (pure Kotlin + `ProcessBuilder`): per-run scratch dir, bare env, capped concurrent drain, timeout kill, reported truncation | `core/guardrails/CommandSandbox.kt` |
| 2 | The sandbox used ONE shared scratch dir (`cacheDir/cmd-sandbox`) reused across runs and never cleaned up — leftovers of one command (or a crashed run) could feed the next one | A UNIQUE `run-<nanos>-<n>` scratch dir per execution, deleted in a `finally` whatever the outcome (per-task working directory policy) | `CommandSandbox.execute` |
| 3 | Output truncation was silent (`readBounded` returned only text) | `readBoundedCounted` reports `reachedCap`; the sandbox propagates it as `SandboxResult.truncated`. Reading exactly up to the cap is reported as reached — overreporting is a visible note, underreporting would be a silent lie | `CommandIsolation` |
| 4 | `run_command` was hard-disabled ("temporarily disabled…") | `commandsEnabled` defaults to TRUE, gated by the device gate below; the kill switch remains — `false` restores the phase-1 refusal verbatim | `ToolRegistry` |
| 5 | Shell substitution syntax (`$( … )`, `<( … )`) was inert (no shell) but accepted | Denied up-front as defense-in-depth so a future execution change cannot resurrect it silently | `CommandIsolation.validate` |
| 6 | Timeout semantics documented only in `ToolRegistry` KDoc | Documented + owned by `CommandSandbox`: drain on a daemon thread with a hard byte cap concurrent with `waitFor(timeout)`; a timed-out child is `destroyForcibly`ed; `exitCode` is null on timeout (never taken from a killed process) | `CommandSandbox` |

Known boundary (documented, accepted): `destroyForcibly` kills the direct
child only. The allowlist contains no binary that spawns grandchildren, and
every spawn requires explicit user approval, so an orphan process tree is out
of the threat model for this tool.

## The device gate

`app/src/androidTest/.../CommandSandboxDeviceTest.kt` runs in the CI emulator
job on EVERY push and proves, against real toybox processes:

1. `CommandIsolation` denials behave identically on-device (path escape,
   absolute binary path, injection characters, shell substitution);
2. the working directory is pinned to a fresh per-run scratch dir (`pwd`);
3. the child environment is bare — exactly `PATH` and `HOME` (`printenv`);
4. endless output (`yes`) is capped, reported truncated, and the process is
   killed by the timeout;
5. a hung process (`sleep 5`) is killed;
6. no scratch directory outlives the call.

If any of these fails, the push stays red and the enablement must not ship.

## Tests

- `Phase4CommandSandboxTests.kt` (JVM, real child processes on the CI Linux
  runner): cwd pin, bare env (2-line `printenv`), exit-code propagation
  (`true`/`false`), cap + kill of `yes`, kill of `sleep 5`, char cap after
  byte cap, scratch cleanup, honest cap reporting, argument hygiene.
- `CommandIsolationTest.kt`: + shell-substitution denials.
- `Phase1DataSafetyTests.kt`: the default-off test became the kill-switch
  test (execution is on by default since this phase).
- `CommandSandboxDeviceTest.kt`: the device gate above.

All JVM unit tests run without Android: `./gradlew testDebugUnitTest`.
The device gate runs in the emulator job of `.github/workflows/android-build.yml`.

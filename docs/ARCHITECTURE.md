# GpTMax — Architecture

The codebase mirrors the layered architecture from the report (page "بنية
النظام") and the A+ strategy: local-first inference, BYOK cloud as a bridge —
never a dependency.

## Layers

```
┌────────────────────────────────────────────────────────────┐
│ UI (Jetpack Compose, Material 3, RTL)                      │
│   ChatScreen · AgentScreen · EditorScreen                  │
│   ModelsScreen · SettingsScreen                            │
├────────────────────────────────────────────────────────────┤
│ ViewModels (StateFlow)                                     │
├────────────────────────────────────────────────────────────┤
│ Agent Core (com.tibatech.gptmax.core.agent)                │
│   PlanParser · ToolRegistry · ApprovalGate                 │
├────────────────────────────────────────────────────────────┤
│ InferenceRouter (core.router)                              │
│   LocalEngine ── JNI ── llama.cpp (libgptmax.so, arm64)    │
│   CloudBridge ── OkHttp SSE ── OpenAI-compatible endpoints │
├────────────────────────────────────────────────────────────┤
│ Data (com.tibatech.gptmax.data)                            │
│   Room (messages, agent_runs) · DataStore (BYOK keys)      │
│   WorkspaceRepository (SAF) · ModelRepository (GGUF dl)    │
└────────────────────────────────────────────────────────────┘
```

## The agent loop

1. **PLANNING** — `ContextBuilder.repoMap()` builds a compact tree; the
   PLAN model returns a JSON array of tool steps (`PlanParser` tolerates
   fenced or bare JSON).
2. **GATHERING** — files referenced by the plan are preloaded (`digest()`),
   truncated to a token budget.
3. **ACTING** — steps run through `ToolRegistry`. Read tools execute
   immediately; write tools (`write_file`, `delete_file`) suspend at the
   `ApprovalGate` until the user approves in the UI.
4. **VALIDATING** — a diff summary goes to the VALIDATE model which returns
   an approval or concrete risks. The result is logged to the run timeline.
5. **DONE / FAILED** — persisted to `agent_runs` for history.

## Routing rules (strategy matrix)

| Task      | Local (GGUF) | Cloud (BYOK)      |
|-----------|--------------|-------------------|
| CHAT      | default      | fallback          |
| PLAN      | default      | first when switch on + key set |
| ACT       | default      | only if `cloudFirstAct` |
| VALIDATE  | local        | first when switch on + key set |

Failure or a missing key falls back to the other source; the chat badge shows
which engine answered (`local engine` / `cloud (BYOK)`).

## Native engine contract

`LlamaBridge` (Kotlin) ↔ `gptmax_jni.cpp` (C++17):

- `nativeInit(path, nCtx, nThreads, nGpuLayers)` — load GGUF
- `nativeGenerate(prompt, maxTokens, temperature, topP, topK, listener)`
  — blocking streaming generation; UTF-8-safe incremental deltas
- `nativeStop()` — cooperative cancel, unwinds at the next token
- `nativeFree()` — unload

One generation at a time (native mutex) — phone RAM is the constraint.
ChatML formatting matches Qwen chat templates.

## Security notes

- API keys live only in app-private DataStore; requests attach them per-call.
- Workspace access is a single SAF tree grant — no broad storage permission.
- The agent cannot touch anything outside the granted folder.

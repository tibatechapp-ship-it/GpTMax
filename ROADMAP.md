# GpTMax Roadmap

This roadmap defines the product sequence for GpTMax as a **local-first AI coding studio**, with Android-native inference as its foundational capability. A checkbox is complete only when implementation, tests, and evidence are merged. Planned items are not shipped features.

## M0 — Repository and architecture baseline

- [x] Create public repository and define project scope
- [x] Add engineering governance, threat model, and quality checks
- [x] Establish repository CI
- [x] Define AI coding studio product direction and component boundaries
- [x] Record initial Android/Gradle/Kotlin/NDK/CMake toolchain versions
- [x] Establish a baseline task state model and tool permission policy
- [ ] Establish release criteria and issue/task tracking labels

## M1 — Android ARM64 inference proof

**Exit condition:** a reproducible native build and successful local generation on a real Android ARM64 device.

- [ ] Pin llama.cpp to an immutable upstream revision; record license and provenance
- [x] Pin Android Gradle Plugin, Gradle, Kotlin, NDK, CMake, and SDK configuration
- [x] Create minimal Kotlin/Compose application and small JNI API
- [ ] Implement native adapter behind the GpTMax Inference API
- [ ] Select/load a compatible GGUF using Android document APIs
- [ ] Generate and stream tokens; support cancellation and unload
- [ ] Handle errors, lifecycle, and memory pressure deterministically
- [x] Add unit-test wiring and Android build workflow
- [ ] Run a documented device smoke test and record model checksum, context, RAM, latency, and tokens/sec

## M2 — GpTMax Code Studio foundation

**Exit condition:** user can open a project, browse/edit files, and inspect changes without an agent.

- [x] Android application shell and Compose foundation screen
- [ ] Project explorer and safe document-provider integration
- [ ] Editor integration with syntax highlighting and multi-tab state
- [ ] File search and basic project-wide search
- [ ] Git status/diff view
- [ ] App-private task workspace and snapshot strategy
- [ ] Accessibility, RTL, and small-screen UX baseline

## M3 — Agent Core and tool contract

**Exit condition:** one agent completes a bounded repository task using registered tools and produces an auditable result.

- [x] Task state machine foundation
- [x] Initial risk/capability permission policy
- [ ] Context builder with explicit token/budget limits
- [ ] Tool registry with schemas, permission levels, and result envelopes
- [ ] File read/search and patch-based write tools
- [ ] Tool router and execution controller
- [ ] Streaming, cancellation, timeout, and bounded retry policy
- [ ] Task ledger with inputs, actions, outputs, and failure states
- [ ] Agent evaluation fixtures and deterministic mock-tool tests

## M4 — Integrated Runtime and terminal

**Exit condition:** approved commands execute in a managed process environment and return structured output; unsafe commands are blocked or require approval.

- [ ] Process manager with stdin/stdout/stderr, exit code, timeout, and cancellation
- [ ] Command/tool allowlist and risk classification
- [ ] Per-task working directory and environment policy
- [ ] Resource limits and cleanup of child processes
- [ ] Safe Git and language-tool integrations
- [ ] Capability detection for Android-compatible tools
- [ ] Optional build-worker protocol for tasks that exceed device capability
- [ ] Documented security boundaries; no assumption of unrestricted Linux shell

## M5 — Autonomous coding loop

**Exit condition:** agent modifies multiple files, runs available validation, handles a bounded failure/repair cycle, and presents evidence plus a diff.

- [ ] Plan → inspect → patch → validate → diagnose → repair loop
- [ ] Patch validation and conflict detection
- [ ] Build/test result parser and failure summarization
- [ ] Independent review pass with explicit uncertainty
- [ ] User-visible checkpoints, stop control, and rollback
- [ ] Approval required for destructive, network, credential, publish, or merge actions
- [ ] Task-level acceptance criteria and coding benchmark

## M6 — Code intelligence and project memory

**Exit condition:** context retrieval is relevant, ignore-aware, privacy-conscious, and measured against a baseline.

- [ ] Ignore rules for VCS, generated, binary, secret, and oversized files
- [ ] File inventory and language detection
- [ ] Symbol indexing where language tooling supports it
- [ ] Search and ranked context retrieval
- [ ] Optional local semantic index with deletion/rebuild controls
- [ ] Dependency/reference graph where available
- [ ] Project decisions and task memory with user visibility and removal

## M7 — Specialist workflows and controlled delegation

- [ ] Architect, implementer, test, and reviewer roles as bounded workflows
- [ ] Parallelize only independent tasks with explicit workspace ownership
- [ ] Merge agent outputs through patch review, not shared uncontrolled writes
- [ ] Measure quality/cost against single-agent baseline before enabling by default

## M8 — Quantization and performance lab

- [ ] Audit LookAtMyQuant algorithms, dependencies, and license
- [ ] Separate portable research logic from MLX-specific runtime
- [ ] Define portable quantization plan and export validation
- [ ] Compare model quality, size, RAM, latency, thermal behavior, and energy
- [ ] Keep optimization results reproducible and separate from coding-agent claims

## M9 — Production release

- [ ] Security, dependency, and license review
- [ ] CI build, tests, lint, and artifact provenance
- [ ] Device matrix and stability testing
- [ ] Privacy, accessibility, localization, and UX review
- [ ] Signed release process without committed secrets
- [ ] Release notes, checksums, known limitations, and verified capability list

## Milestone policy

Do not start broad multi-agent orchestration or quantization implementation before M1 establishes a measured inference baseline and M2/M3 establish the product and tool boundaries. If local Android cannot support a build, report the limitation and use an optional build worker only with user-configured connectivity and permissions.

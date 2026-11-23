# Dependency and Supply-Chain Policy

GpTMax is a native Android project with a high-risk boundary between app code, native libraries, and model assets.

## Rules

1. Pin toolchains and dependencies to explicit versions or immutable revisions.
2. Prefer maintained upstream projects and minimize dependency count.
3. Review license, maintenance status, build scripts, and transitive dependencies before adoption.
4. Do not run dependency-provided scripts from untrusted sources without reviewing them.
5. Keep model weights, tokenizer assets, and native libraries outside the source repository unless their licensing and distribution terms are explicitly approved.
6. Preserve required notices and record upstream origin plus the exact revision for adapted code.
7. Upgrade deliberately; record compatibility and test results in the PR.

## Required records for runtime dependencies

For each material runtime dependency, document its name, pinned version/revision, source URL, license, purpose, and known platform constraints. A software bill of materials will be generated before release.

# Contributing to GpTMax

Thank you for helping improve GpTMax. The project prioritizes correctness, device safety, reproducibility, privacy, and maintainability over shipping unverified features.

## Before starting

1. Read [README.md](README.md), [architecture](docs/architecture.md), and [quality gates](docs/quality-gates.md).
2. Open or comment on an issue before making a substantial change.
3. Keep changes focused. Separate refactors from behavior changes where practical.
4. Do not commit model weights, private data, API keys, signing material, or generated build outputs.

## Engineering expectations

- Kotlin owns Android UI, permissions, storage providers, and lifecycle.
- C++ owns native inference and must expose a small, documented JNI surface.
- Pin third-party dependencies and upstream revisions.
- Do not execute repository contents or model files as code.
- Add tests for behavior and regressions; include device-level evidence for native/runtime changes.
- Document limitations and unsupported devices honestly.
- Preserve third-party license notices and model provenance.

## Pull requests

A pull request should include:

- problem and proposed solution;
- scope and design trade-offs;
- tests run and their results;
- device/ABI/backend details for Android or native changes;
- security, privacy, performance, and compatibility impact;
- documentation and release-note updates when applicable.

Do not mark checks complete unless they were run. CI is required to pass before merge. Reviewers may request changes or split an oversized PR.

# Engineering Quality Gates

These gates define the minimum standard for changes. Passing repository checks alone does not establish that the Android runtime works.

## Repository gate (current)

- Required project documentation exists.
- Required Markdown files have a title and no trailing whitespace.
- No model weights, common private-key files, or oversized binaries are tracked.
- Git whitespace checks pass.
- CI runs on pushes and pull requests.

## Android application gate (required when app code is added)

- Reproducible Gradle build with pinned Android Gradle Plugin, Gradle wrapper, Kotlin, and NDK.
- Unit tests and Android lint pass.
- Debug APK installs and launches on a declared emulator/device.
- Release build uses no committed signing secrets.
- ABI and minimum Android API are explicitly declared.

## Native inference gate (required for inference changes)

- Native build succeeds for declared ABI(s).
- JNI error handling and ownership are tested.
- Model load, prompt generation, cancellation, unload, and lifecycle paths are covered.
- No native crash or memory corruption in the test scenario.
- Report model checksum, backend revision, build flags, device, RAM, context length, and measured performance.

## Release gate

- Dependency and license audit complete.
- Threat model reviewed.
- Accessibility and localization reviewed.
- Crash/performance checks completed on representative hardware.
- Release artifacts have checksums and provenance.
- Release notes describe verified functionality and known limitations.

A gate may be waived only through a documented maintainer decision explaining the risk. Do not represent a waived or skipped check as passed.

# GpTMax Threat Model

## Assets

- User prompts and generated text
- Local model files and metadata
- Download integrity and provenance
- Device storage, memory, battery, and thermal capacity
- Application preferences and any future credentials

## Trust boundaries

1. Remote model repositories and download hosts → local storage
2. Android/Kotlin application → JNI/native inference
3. Third-party native dependencies → application process
4. User-selected files and document providers → model loader
5. Future optional network services → local prompt data

## Threats and controls

| Threat | Required control |
|---|---|
| Tampered or incomplete model download | Verify SHA-256 when supplied; validate file size/metadata; never claim authenticity from a checksum alone |
| Malicious or malformed model file | Treat as untrusted input; validate bounds and format; use maintained parsers; fuzz native parsing paths |
| Native crash or memory exhaustion | Bound context and thread settings; handle allocation failures; cancellation and deterministic cleanup; show resource estimates |
| Prompt or model data leakage | Local-first defaults; no telemetry or prompt upload by default; explicit consent and clear disclosure for any network feature |
| Path traversal or unsafe file access | Use Android Storage Access Framework; avoid trusting display names or raw paths; constrain app-managed paths |
| Dependency compromise | Pin revisions, review build scripts, preserve provenance, automate dependency checks before release |
| Secret or model accidentally committed | Ignore patterns, CI checks, repository secret scanning before public release |

## Out of scope for the initial milestone

- Protection against a rooted or fully compromised device
- Guarantees that every third-party model is safe, accurate, or licensed for every use
- Cloud inference or account synchronization

## Review triggers

Update this document when adding remote services, telemetry, model marketplace features, custom native kernels, external plugins, or distribution of model weights.

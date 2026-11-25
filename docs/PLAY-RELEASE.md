# Google Play Release Readiness — GpTMax

Working checklist for publishing GpTMax on Google Play. Repo-side items are
done or pointed to; Console-side items are the exact answers/form entries
that mirror the app's real behavior. Keep this file in sync when behavior
changes — Play policy requires the declarations to be truthful.

## 1. Technical compliance (repo side)

| Requirement | Status | Where |
|---|---|---|
| Target API level | targetSdk 35 (meets current Play requirement) | `app/build.gradle.kts` |
| Min SDK | 26 (Android 8.0) | `app/build.gradle.kts` |
| Permission minimization | Only `INTERNET`; unused `POST_NOTIFICATIONS` removed | `app/src/main/AndroidManifest.xml` |
| Sensitive permissions | None (no location/contacts/camera/mic/storage) | manifest |
| File access | Scoped SAF user grant only (no `MANAGE_EXTERNAL_STORAGE`) | `WorkspaceRepository` |
| Release build passes CI | R8/minified `assembleRelease` gate green | CI `release` job |
| Privacy policy URL | `landing/privacy-policy.html` (host on Vercel — see `landing/README.md`) | Play Console → App content |
| No committed secrets | CI-enforced repo quality checks; API keys live in user DataStore only | `.github/workflows/repository-quality.yml` |
| Data deleted on uninstall | All user data is app-private; `allowBackup=false` | manifest |

## 2. App signing

- Use **Play App Signing**: Google holds the release key; you upload an
  **upload key** with every release bundle.
- Generate the upload keystore locally and NEVER commit it:

  ```bash
  keytool -genkeypair -v -keystore gptmax-upload.jks \
    -alias gptmax-upload -keyalg RSA -keysize 4096 -validity 10000
  ```

- Wire it without committing secrets (CI-safe): keep `release` unsigned in
  Gradle and sign at upload time (Android Studio / `apksigner`), or provide
  signing via `keystore.properties` that is `.gitignore`d.
- Enroll in Play App Signing in Console → Release → Setup → App signing.

## 3. Data safety form (Play Console → App content → Data safety)

Answer exactly as the app behaves — no collection by the developer:

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | Yes (one shared type, see next rows) — choose "Yes" only because cloud mode can transmit user content; otherwise all developer-side answers are "No". |
| Data collected | **No data collected** by the developer (no analytics/ads/SDKs) |
| Data shared (transmitted off device) | **App activity → Other user content** (chat/agent prompt text) — shared ONLY when the user enables the optional cloud bridge; recipient = the user-chosen inference provider (BigModel / DeepSeek / OpenRouter / custom endpoint) |
| Is the shared data encrypted in transit? | Yes (HTTPS enforced by the app) |
| Can users request that data be deleted? | The developer holds nothing server-side; user deletes everything by uninstalling. Answer per Console wording: data is not retained by the developer. |
| API key handling | Key is entered by the user, stored app-privately, sent only to the user-configured endpoint. Declare under "App activity → Other" if Console requires an entry; otherwise covered by the shared-data row. |

Also in App content:
- **Privacy policy URL**: the deployed `/privacy-policy` URL (Vercel).
- **Ads**: no ads.
- **Content rating questionnaire**: no violence/sex/gambling/health claims;
  sharable user content = no (agent output is local-only); expect **Everyone
  / PEGI 3**.
- **Target audience**: 13+ (developer tooling; not designed for children).
- **News apps / COVID / government apps**: no.
- **Financial features**: none.

## 4. Store listing (draft copy)

- **App name** (30 chars max): `GpTMax: Local AI Coding Studio` (30 chars)
- **Short description** (80 chars max, EN):
  `On-device AI code editor & agent. Local-first, private, bring-your-own-key.`
  (77 chars) — Arabic alternative:
  `محرر أكواد ووكيل ذكي يعمل على جهازك — خصوصية كاملة ومحلي أولاً.`
- **Full description**: adapt from `README.md` (EN + AR sections already
  mirror the landing copy); lead with the local-first privacy claim.
- **Graphics**: 512×512 icon (from `ic_launcher`), min 2 phone screenshots
  (Chat, Editor, Agent screens), 1024×500 feature graphic.
- **App category**: Productivity (or Tools). Tags: coding, AI, privacy.

## 5. Release track plan

1. **Internal testing** first: upload the signed AAB, verify install on a
   real ARM64 device, run a model download + local chat once (record the
   pre-launch report results).
2. **Closed testing** (small group) — collect feedback, watch the pre-launch
   report for permission/privacy flags.
3. **Production** with staged rollout 20% → 100%.

Versioning: bump `versionCode` every upload; move `versionName` off
`0.1.0-alpha` (e.g. `0.2.0`) when opening closed testing. Release notes
template:

```
- On-device GGUF inference (llama.cpp, ARM64)
- Coding agent with approval gate, rollback journal, resumable runs
- Optional BYOK cloud bridge (BigModel / DeepSeek / OpenRouter / custom)
- Bug fixes and stability improvements
```

## 6. Known honest limitations (keep in listing/known-issues)

- No SHA-256 verification of downloaded model files yet (release-gate item
  on the roadmap); downloads are direct HTTPS from huggingface.co.
- Device test matrix still small — CI emulator gate covers x86_64; real
  ARM64 device smoke test documented in `docs/m1-device-validation.md`.

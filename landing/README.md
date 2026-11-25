# GpTMax landing site (Vercel)

Static, dependency-free landing page + privacy policy for GpTMax. This is the
site to attach as the **Privacy Policy URL** in Google Play Console
(Policy → App content → Privacy policy) — Play requires a working,
non-repository-hosted URL.

## What's here

| File | Purpose |
|---|---|
| `index.html` | Product landing page (English + Arabic section) |
| `privacy-policy.html` | Full privacy policy (English + Arabic summary), served at `/privacy-policy` |
| `vercel.json` | Static config: clean URLs (`/privacy-policy`), security headers |

No build step, no external fonts/CDN/tracker — the site itself makes zero
third-party requests, matching the app's privacy posture.

## Deploy to Vercel (one-time, ~2 minutes)

1. Sign in at [vercel.com](https://vercel.com) with the GitHub account that
   owns `tibatechapp-ship-it/GpTMax` (or invite the org).
2. **Add New… → Project → Import Git Repository** → select `GpTMax`.
3. Configure:
   - **Root Directory:** `landing`
   - **Framework Preset:** Other (static) — no build command, no output dir override needed
   - Project name suggestion: `gptmax-site`
4. **Deploy.** Vercel watches the repo, so future changes to `landing/`
   redeploy automatically.
5. Copy the production URL (e.g. `https://gptmax-site.vercel.app`) and verify:
   - `/` renders the landing page
   - `/privacy-policy` renders the privacy policy
6. In **Google Play Console → Policy → App content → Privacy policy**, paste:
   `https://<your-vercel-domain>/privacy-policy`

### CLI alternative

```bash
npx vercel --prod          # from inside landing/  (needs `vercel login` once)
```

Any Vercel token used for automation must be scoped and stored outside the
repository — never committed.

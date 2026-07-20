# Build And Deploy Fast Path

This repo already has the right underlying build tasks, but they were spread across Gradle, Docker Compose, and store checklists. That creates extra Codex approval prompts and encourages slow commands like `docker compose up --build` on every cycle.

The repo-level entry point is `scripts/dev.ps1`. Use it as the stable surface for build, backend, and release workflows.

## Why this helps

- One script path is easier to pre-approve than many ad hoc commands.
- `backend-up` does **not** rebuild images unless you pass `-BuildImages`.
- Android release commands use the exact variant names already documented in the store checklists.
- Optional `-SkipCrashlyticsUploads` keeps release packaging usable in restricted network environments.

## Common commands

Run all commands from the repo root in PowerShell.

```powershell
.\scripts\dev.ps1 -Target dev-google-tv
.\scripts\dev.ps1 -Target dev-google-tv -BuildImages
.\scripts\dev.ps1 -Target dev-amazon-tv
.\scripts\dev.ps1 -Target android-google-mobile-release -SkipCrashlyticsUploads
.\scripts\dev.ps1 -Target android-release-all -SkipCrashlyticsUploads
.\scripts\dev.ps1 -Target backend-test
.\scripts\dev.ps1 -Target backend-migrate
```

## Recommended approval strategy for Codex

If you want Codex to stop stalling on sandbox prompts, pre-approve one of these approaches:

1. Narrow repo-specific approval:
   Approve `.\scripts\dev.ps1` as the main command surface for this repo.
2. Lower-level approvals if you prefer more visibility:
   Approve `.\\gradlew.bat`, `docker compose`, and `python -m pytest`.

The first option is usually better because it keeps approvals stable even if the exact underlying tasks change.

## Practical workflow

For daily Android work:

1. First run after dependency or Dockerfile changes:
   `.\scripts\dev.ps1 -Target dev-google-tv -BuildImages`
2. Normal inner loop:
   `.\scripts\dev.ps1 -Target dev-google-tv`
3. Release packaging:
   `.\scripts\dev.ps1 -Target android-release-all`

For backend-only work:

1. Start services once:
   `.\scripts\dev.ps1 -Target backend-up`
2. Run tests as needed:
   `.\scripts\dev.ps1 -Target backend-test`
3. Rebuild only after dependency or image changes:
   `.\scripts\dev.ps1 -Target backend-up -BuildImages`

## Scope note

iOS archive and TestFlight/App Store submission still require macOS and Xcode. This Windows repo fast path intentionally focuses on Android and the Python backend, which are the parts Codex can drive directly here.

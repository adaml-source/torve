# Smoke Run

| Field         | Value                              |
| ------------- | ---------------------------------- |
| Date / time   | YYYY-MM-DDTHH:MM±TZ                |
| Operator      |                                    |
| Mobile build  | versionName / versionCode (output of capture-version.sh) |
| TV build      | versionName / versionCode          |
| Desktop build | semver / git sha                   |
| Backend env   | api.torve.app (prod) / staging     |

## Case results

| #  | Case                                       | Result | Notes / log path |
| -- | ------------------------------------------ | ------ | ---------------- |
| 1  | Mobile sign-in (existing account)          |        |                  |
| 2  | New-account setup                          |        |                  |
| 3  | Phone signs in TV via QR                   |        |                  |
| 4  | Credential transfer desktop→TV             |        |                  |
| 5  | LAN playback                               |        |                  |
| 6  | Cellular guard                             |        |                  |
| 7  | TV couch QR readability                    |        |                  |
| 8  | Windows clean install / playback / update  |        |                  |

Result: PASS / FAIL / SKIP.

## Failures

Per-failure block:

```
Case #N — <case name>
What went wrong: <one paragraph>
Steps to reproduce: <numbered>
Logs: <path under smoke-results/logs/>
Screenshots: <paths>
```

## Sign-off

- [ ] All P1 cases pass
- [ ] Failure logs collected
- [ ] Result file committed alongside build tag

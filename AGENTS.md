# Open Freellee — Agent Instructions

## Project Overview

Android app (Kotlin + Jetpack Compose) for pairing with Ollee Smart Watch boards and syncing step/health data to Android Health Connect. Install via ADB.

**Package**: `link.hector.freellee` | **Min SDK**: 34 | **Theme**: AMOLED dark-only

## Architecture

```
ble/                      # BLE layer: OAP over Nordic UART, verified against the firmware
  OapProtocol.kt          #   framing, command table, safety policy
  OapCodecs.kt            #   per-command payload codecs
  ActivityRecord.kt       #   activity record model
  WatchGattClient.kt      #   BLE GATT transport (connect / notify / write)
  WatchRepository.kt      #   typed high-level watch API
data/                     # DataStore persistence + Health Connect client
ui/screens/               # Dashboard + Connection screens
ui/theme/                 # Dark theme
util/                     # Helpers (interval ID hashing)
viewmodel/                # OlleeViewModel + DeviceViewModel
```

Top-level files: `FreelleeApp.kt` (DI), `Constants.kt` (BLE timeouts, defaults), `MainActivity.kt` (entry point).

## Key Directives

- **Never push without explicit permission** — commits are fine; pushes require approval.
- **Treat warnings as errors** — fix the root cause, don't silence them without permission.

## Release Procedure

When performing a new release (new tag), follow these steps in order:

1. **Bump the version** — Edit `app/build.gradle.kts` and increment `versionCode` by 1. Set `versionName` to the new version string (e.g. `0.0.3`).

2. **Write a user-facing changelog** — Create `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`. The text should be concise and highlight the changes that matter to end users (new features, bug fixes, breaking changes). Avoid implementation details like dependency bumps or CI tweaks unless they have a visible effect.

3. **Commit the version bump** — Commit the changes to `app/build.gradle.kts` and the new changelog file. Do **not** push yet.

4. **Update F-Droid metadata** — Edit `metadata.link.hector.freellee.yml`: update `CurrentVersion` / `CurrentVersionCode`, and add a `Builds` entry with the commit hash of the release commit. Commit this update. Do **not** push yet.

5. **User review** — Present the full diff and the tag message to the user for review. Do not push or tag until explicit approval is given.

6. **Push and tag** — Once approved, push to `master`, then create an annotated (signed) tag with `git tag -s v<versionName> -m "Release v<versionName>"` (e.g. `v0.0.3`) and push it with `git push origin v<versionName>`. This triggers the GitHub Actions Release workflow, which builds the signed APK, generates a SHA-256 checksum, and publishes a GitHub Release. F-Droid will pick up the new version on its next scan.

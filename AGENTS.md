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

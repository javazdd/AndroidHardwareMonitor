# Android Hardware Monitor

An Android foreground service that continuously collects hardware and system metrics from the device and ships them to a compatible observability backend every 30 seconds.

Supports **Samsung Galaxy S23 Ultra** (main branch) and **Honeywell CT47** (`ct47` branch).

---

## What it tracks

| Metric | Description |
|---|---|
| `device.battery.level_pct` | Battery percentage |
| `device.temperature.battery` | Battery temperature in °C |
| `device.temperature.cpu` | CPU die temperature in °C (sysfs thermal zone) |
| `device.signal.strength_dbm` | Signal strength in dBm — prefers 5G NR, falls back to LTE → WCDMA → GSM |
| `device.connectivity.online` | `1` when connected, `0` when offline |
| `device.memory.ram_free_mb` | Free RAM in MB |
| `device.storage.free_gb` | Free internal storage in GB |
| `device.network.bytes_tx` | Bytes transmitted since last poll |
| `device.network.bytes_rx` | Bytes received since last poll |
| `device.wifi.rssi_dbm` | Wi-Fi RSSI in dBm |
| `device.thermal.headroom` | Thermal headroom (0.0–1.0) |
| `device.sensor.pressure_hpa` | Barometric pressure in hPa |
| `device.sensor.ambient_light_lux` | Ambient light in lux |
| `device.sensor.steps` | Step count since last reboot |

### Events

| Event | Alert type | Trigger |
|---|---|---|
| **Connectivity Lost** | `error` | Internet connection dropped |
| **Connectivity Restored** | `success` | Internet connection came back |
| **Device Rebooted** | `warning` | Service detected a fresh boot |
| **Fall Detected** | `warning` | Accelerometer threshold exceeded (free-fall + impact) |

All metrics and events carry a configurable tag, e.g. `device:samsung_s23_ultra` or `device:honeywell_ct47`.

---

## Project structure

```
AndroidHardwareMonitor/
├── app/
│   ├── build.gradle                        # App-level Gradle (OkHttp, Coroutines, AppCompat)
│   └── src/main/
│       ├── AndroidManifest.xml             # Permissions + service/receiver declarations
│       ├── java/com/s23ultra/monitor/
│       │   ├── AppConfig.kt                # SharedPreferences-backed runtime config
│       │   ├── DatadogClient.kt            # HTTP client — POSTs to metrics and events endpoints
│       │   ├── MonitoringService.kt        # Foreground service — polls every 30 s
│       │   ├── SensorCollector.kt          # Accelerometer-based fall/impact detection
│       │   ├── RebootTracker.kt            # Detects reboots via persisted boot epoch
│       │   ├── MainActivity.kt             # Status UI, permission requests, service start
│       │   ├── SettingsActivity.kt         # Runtime config: site, API key, device tag
│       │   ├── BootReceiver.kt             # Auto-starts service after device reboot
│       │   └── HoneywellCapabilities.kt   # CT47-specific extension stubs
│       └── res/
│           ├── layout/activity_main.xml
│           ├── layout/activity_settings.xml
│           └── values/{strings,colors}.xml
├── build.gradle                            # Root Gradle (Kotlin + AGP versions)
├── settings.gradle
└── gradle.properties
```

---

## Setup

### 1. Configure the backend

On first launch, tap the **⚙ gear icon** in the top-right corner. You will be prompted for:

- **Backend Site** — select the regional endpoint that matches your backend
- **API Key** — paste your backend API key
- **Device Tag** — unique identifier for this device (e.g. `samsung_s23_ultra`)

Configuration is stored in `SharedPreferences` and **persists across reboots**. The service reads it on every poll cycle.

### 2. Build

Open the project in Android Studio (Hedgehog or newer) and let it sync, **or** build from the command line:

```bash
./gradlew assembleDebug
```

### 3. Install on device

Enable **USB Debugging** (`Settings → Developer options → USB debugging`), connect via USB, then:

```bash
./gradlew installDebug
```

Or sideload the APK directly via `adb install`.

### 4. Grant runtime permissions

On first launch the app requests:

- `READ_PHONE_STATE` — access cellular signal strength via `TelephonyManager`
- `ACCESS_FINE_LOCATION` — required by Android 12+ to read cell info
- `POST_NOTIFICATIONS` — required by Android 13+ to show the persistent notification

Grant all three so signal-strength metrics are collected.

---

## Auto-start on boot

`BootReceiver` listens for `ACTION_BOOT_COMPLETED` and starts `MonitoringService` automatically — no manual app launch required after reboot. The only prerequisite is that the app must have been opened at least once after install (Android requires this to activate boot receivers).

---

## Configuration persistence

All settings (API key, backend site, device tag) are written to Android `SharedPreferences`, which survives:

- App restarts and crashes
- Device reboots
- OS updates

Settings are only cleared by uninstalling the app or tapping **Clear data** in Android Settings.

---

## Device tag format

Tags use the `key:value` format, e.g.:

```
device:samsung_s23_ultra
device:honeywell_ct47
```

Every metric series and event payload includes this tag, so you can filter or group data by device in your dashboard.

---

## Requirements

| Item | Minimum |
|---|---|
| Android SDK | 31 (Android 12) |
| Target SDK | 34 (Android 14) |
| Kotlin | 1.9.22 |
| AGP | 8.2.2 |
| Gradle | 8.4 |

---

## How it works

```
[Boot / App launch]
        │
        ▼
  MainActivity
  ├─ requests permissions
  └─ calls startForegroundService(MonitoringService)
        │
        ▼
  MonitoringService (foreground, START_STICKY)
  ├─ SensorCollector    → fall / impact events
  ├─ RebootTracker      → reboot events
  ├─ ConnectivityCallback → connectivity lost / restored events
  └─ coroutine loop every 30 s
           reads battery, CPU temp, signal, RAM, storage, network, sensors
           → HTTP POST /api/v2/series  (metrics)
           → HTTP POST /api/v1/events  (events)
           → updates persistent notification

  BootReceiver
  └─ ACTION_BOOT_COMPLETED → startForegroundService(MonitoringService)
```

---

## Branches

| Branch | Target device |
|---|---|
| `main` | Samsung Galaxy S23 Ultra |
| `ct47` | Honeywell CT47 — adjusted thermal zone scanning and fall-detection thresholds |

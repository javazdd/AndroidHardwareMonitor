# S23UltraMonitor

An Android foreground service that continuously monitors a Samsung Galaxy S23 Ultra and ships metrics + events to Datadog every 30 seconds.

## What it tracks

| Metric | Description |
|---|---|
| `device.temperature.battery` | Battery temperature in °C (from `BatteryManager`) |
| `device.temperature.cpu` | CPU die temperature in °C (from `/sys/class/thermal/thermal_zone0/temp`) |
| `device.signal.strength_dbm` | Signal strength in dBm — prefers 5G NR, falls back to LTE → WCDMA → GSM |
| `device.connectivity.online` | `1` when connected to the internet, `0` when offline |

### Connectivity events

| Event | Alert type | Trigger |
|---|---|---|
| **Connectivity Lost** | `error` | Internet connection dropped — cellular provider may be down |
| **Connectivity Restored** | `success` | Internet connection came back |

All metrics and events carry the tag `device:samsung_s23_ultra`.

---

## Project structure

```
S23UltraMonitor/
├── app/
│   ├── build.gradle                        # App-level Gradle (OkHttp, Coroutines, AppCompat)
│   └── src/main/
│       ├── AndroidManifest.xml             # Permissions + service/receiver declarations
│       ├── java/com/s23ultra/monitor/
│       │   ├── AppConfig.kt                # API key, site, device tag — edit before building
│       │   ├── DatadogClient.kt            # OkHttp POSTs to /api/v2/series and /api/v1/events
│       │   ├── MonitoringService.kt        # Foreground service — polls every 30 s
│       │   ├── MainActivity.kt             # Simple status UI, requests permissions, starts service
│       │   └── BootReceiver.kt             # Restarts service after reboot
│       └── res/
│           ├── layout/activity_main.xml
│           └── values/{strings,colors}.xml
├── build.gradle                            # Root Gradle (Kotlin + AGP versions)
├── settings.gradle
└── gradle.properties
```

---

## Setup

### 1. Add your Datadog keys

Open `app/src/main/java/com/s23ultra/monitor/AppConfig.kt` and replace the placeholders:

```kotlin
const val DATADOG_API_KEY: String = "YOUR_DATADOG_API_KEY"
const val DATADOG_APP_KEY: String = "YOUR_DATADOG_APP_KEY"
```

- API key: <https://app.datadoghq.com/organization-settings/api-keys>
- App key: <https://app.datadoghq.com/organization-settings/application-keys>

If your Datadog account is on a non-US site (e.g. EU), change `DATADOG_SITE` to `datadoghq.eu`.

### 2. Build

Open the project in Android Studio (Hedgehog or newer) and let it sync, **or** build from the command line:

```bash
./gradlew assembleDebug
```

### 3. Install on device

Enable **USB Debugging** on the S23 Ultra (`Settings → Developer options → USB debugging`), connect via USB, then:

```bash
./gradlew installDebug
```

### 4. Grant runtime permissions

On first launch the app will request:

- `READ_PHONE_STATE` — needed to call `TelephonyManager.getAllCellInfo()`
- `ACCESS_FINE_LOCATION` — required by Android 12+ to access cell info
- `POST_NOTIFICATIONS` — required by Android 13+ to show the persistent notification

Grant all three so signal-strength metrics are collected.

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
  ├─ registers ConnectivityManager.NetworkCallback
  │        fires "Connectivity Lost/Restored" events via DatadogClient
  └─ coroutine loop every 30 s
           reads battery temp, CPU temp, signal dBm, online status
           → DatadogClient.sendMetrics()   POST /api/v2/series
           → updates persistent notification

  BootReceiver
  └─ ACTION_BOOT_COMPLETED → startForegroundService(MonitoringService)
```

---

## Querying metrics in Datadog

```
avg:device.temperature.battery{device:samsung_s23_ultra}
avg:device.temperature.cpu{device:samsung_s23_ultra}
avg:device.signal.strength_dbm{device:samsung_s23_ultra}
sum:device.connectivity.online{device:samsung_s23_ultra}
```

Events appear in the **Events Explorer** under source `api`.

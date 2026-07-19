---
name: run-airmedradar
description: Build, install, launch, and drive the AirMed Radar Android app on a connected device or emulator. Use when asked to run AirMed Radar, build the app, take a screenshot of the map UI, smoke-test a change, or interact with the running app (search, tap markers, check notifications).
---

AirMed Radar is a native Kotlin/Jetpack Compose Android app (Gradle project, no separate backend). It's driven via `adb` — there is no web/CLI surface. Drive it through `.claude/skills/run-airmedradar/driver.ps1`, a PowerShell wrapper around `gradlew` + `adb` that every command below was actually run against.

All paths below are relative to the repo root.

## Prerequisites

- Android SDK with `platform-tools` (`adb`) installed. The driver auto-detects it from `$env:ANDROID_HOME`, `$env:ANDROID_SDK_ROOT`, or the default `$env:LOCALAPPDATA\Android\Sdk`.
- At least one connected device or running emulator (`adb devices` shows it as `device`, not `unauthorized`/`offline`).
- Windows + PowerShell (this project builds/runs on Windows; there is no Linux/WSL path verified here).

```powershell
.\.claude\skills\run-airmedradar\driver.ps1 devices
```

## Build

```powershell
.\.claude\skills\run-airmedradar\driver.ps1 build
```

Wraps `.\gradlew.bat :app:assembleDebug --console=plain`. Output APK: `app\build\outputs\apk\debug\app-debug.apk`.

## Run (agent path)

Use the driver. If multiple devices are connected, pass `-Device <serial>` to every call (otherwise it picks the first one `adb devices` reports).

```powershell
.\.claude\skills\run-airmedradar\driver.ps1 build
.\.claude\skills\run-airmedradar\driver.ps1 install
.\.claude\skills\run-airmedradar\driver.ps1 launch
.\.claude\skills\run-airmedradar\driver.ps1 screenshot my_check
```

Screenshots land in `.claude\skills\run-airmedradar\out\<name>.png` (gitignored — throwaway verification artifacts, not committed).

For a one-shot build+install+launch+screenshot+crash-check:

```powershell
.\.claude\skills\run-airmedradar\driver.ps1 full-smoke
```

| command | what it does |
|---|---|
| `devices` | Lists connected devices/emulators (`adb devices`). |
| `build` | `gradlew :app:assembleDebug`. |
| `install` | Installs the built debug APK (`-r`, i.e. reinstall/keep data). |
| `launch` | Force-stops the app, clears logcat, starts `MainActivity`, confirms the process is alive (throws if not). |
| `stop` | Force-stops the app. |
| `tap <x> <y>` | `adb shell input tap` — raw device pixel coordinates (not dp; multiply displayed screenshot coords by the device's scale factor, e.g. 1.5x on a 1344×2992 physical device). |
| `type <text>` | `adb shell input text`, with spaces auto-encoded as `%s`. Pass the text as one argument (quote it). |
| `key <KEYCODE>` | `adb shell input keyevent <KEYCODE>` (e.g. `KEYCODE_ENTER`). |
| `back` | Shortcut for `key KEYCODE_BACK`. |
| `screenshot [name]` | Captures + pulls a PNG to `out/`. |
| `logcat` | Dumps the last 200 lines of the full log. |
| `crashcheck` | Dumps the crash-buffer (`logcat -b crash`) and reports whether the process is still alive. **This is the primary regression check** — run it after any interaction. |
| `notifications` | Expands the notification shade, screenshots it to `out/notifications.png`, collapses it. Used to verify the foreground-service tracking notification and HEMS alert channel. |
| `full-smoke` | `build` → `install` → `launch` → `screenshot` → `crashcheck` in sequence. |

**A representative interaction** (search → autocomplete → select → verify), exactly as run to validate this app:

```powershell
.\.claude\skills\run-airmedradar\driver.ps1 launch
.\.claude\skills\run-airmedradar\driver.ps1 tap 675 312          # focus the top search bar
Start-Sleep -Seconds 1
.\.claude\skills\run-airmedradar\driver.ps1 type "10444 Cole Ln Aurora IN"
Start-Sleep -Seconds 2
.\.claude\skills\run-airmedradar\driver.ps1 screenshot suggestions   # confirm the Places dropdown appeared
.\.claude\skills\run-airmedradar\driver.ps1 tap 675 495          # tap the first suggestion
Start-Sleep -Seconds 3
.\.claude\skills\run-airmedradar\driver.ps1 screenshot lz_dropped    # confirm the amber LZ pin + camera reframe
.\.claude\skills\run-airmedradar\driver.ps1 crashcheck
```

## Run (human path)

Open the project in Android Studio and hit Run, or:

```powershell
.\gradlew.bat installDebug
```

then launch "AirMed Radar" from the device's app drawer. Not useful for an agent — no programmatic feedback loop.

## Test

No test suite exists yet in this project (only the stock `ExampleUnitTest`/`ExampleInstrumentedTest` template files) — `crashcheck` after driving the app is the closest thing to a regression check.

---

## Gotchas

- **`adb shell input tap` coordinates are raw device pixels, not the `dp` values Compose uses, and not the resized coordinates a screenshot viewer shows you.** This physical test device is 1344×2992 px but screenshots often display scaled down (e.g. 898×2000) — multiply displayed coordinates by the scale factor (here 1.5×) to get real tap coordinates. Get it wrong and you tap the wrong UI element with no error — you just won't see the effect you expected.
- **`adb shell input text` requires literal spaces to be encoded as `%s`.** The driver handles this automatically (`type` action) — don't call `adb shell input text` directly with spaces, it silently truncates at the first space.
- **`MarkerComposable`'s on-map labels can go visually stale** if the underlying maps-compose version doesn't reliably re-bake the marker bitmap on content-only recomposition (see `MainActivity.kt`'s `AircraftEtaLabel` — it's keyed on the label text for exactly this reason). If you're screenshotting a moving-aircraft label and it looks frozen, that's a real class of bug here, not a screenshot timing fluke — check it's actually keyed correctly, don't just assume the screenshot was too early.
- **`$pid` is a read-only automatic variable in PowerShell.** Don't name a variable `$pid` in scripts targeting this app (or any PowerShell script) — assignment throws `Cannot overwrite variable PID because it is read-only or constant`. Use `$appPid` or similar.
- **`adb shell "pidof <pkg>"` returns nothing (not an error string) when the process isn't running**, and calling `.Trim()` directly on that in PowerShell throws `You cannot call a method on a null-valued expression`. Guard with a null check before `.Trim()`.
- **The foreground tracking service keeps running even after the app is swiped away from Recents** — `stop`/`am force-stop` is the reliable way to actually kill it for a clean re-launch, not just backgrounding.
- **There is no simulated aircraft anymore** (MOCK911 was fully decommissioned) — every marker on the map is real ADS-B traffic from adsb.lol, polled every ~12s. Aircraft come and go between polls as real helicopters move in and out of the 75nm radius, so don't expect a specific aircraft to still be visible a screenshot or two later — that's normal, not a bug. If you need a marker on screen for a screenshot, search a target near wherever the most recent screenshot showed one, and expect some misses.
- **The ongoing tracking notification (id 1001, `hems_tracking_service` channel) is LOW importance**, so Android collapses it into the shade's "Silent" aggregate section rather than showing it as an individual card — `.\driver.ps1 notifications` won't visibly show it in the screenshot even though it's actively posted. Confirm it with `adb shell dumpsys notification --noredact | Select-String com.rf.airmedradar` instead of trusting the screenshot alone.
- **The rotating aircraft icon is a custom vector (`ic_helicopter.xml`) converted to a `BitmapDescriptor` via an off-screen Canvas draw**, not `BitmapDescriptorFactory.fromResource()`. At the default map zoom (9.5) it's small — zoom into the screenshot (crop + upscale, e.g. via `System.Drawing` in PowerShell) before concluding anything about its shape/rotation from a full-frame screenshot.

## Troubleshooting

- **`Cannot overwrite variable PID because it is read-only or constant`**: a script (or this driver, before it was fixed) assigned to `$pid`. Rename to `$appPid`.
- **`You cannot call a method on a null-valued expression` after `stop` then `crashcheck`/`launch`**: `pidof` returned null because the process really isn't running. Guard the `.Trim()` call — see driver.ps1's `Get-TargetDevice`-adjacent pidof handling for the pattern.
- **`No connected device/emulator found`**: run `.\driver.ps1 devices` — if empty, start an emulator or reconnect/re-authorize the physical device (check for an "Allow USB debugging" prompt on the device itself).
- **App installs but `launch` throws "App did not stay running"**: run `.\driver.ps1 crashcheck` immediately — it dumps the crash-buffer log, which will show the actual exception (this is how a real init-order `NullPointerException` in the ViewModel was caught and fixed during development).

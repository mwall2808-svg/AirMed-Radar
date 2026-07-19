<#
.SYNOPSIS
  Build, install, launch, and drive AirMed Radar on a connected device/emulator.

.DESCRIPTION
  Thin wrapper around gradlew + adb. Every subcommand here was run directly
  against a real device during development — this is not speculative.

.EXAMPLE
  .\driver.ps1 build
  .\driver.ps1 install
  .\driver.ps1 launch
  .\driver.ps1 tap 675 312
  .\driver.ps1 type "10444 Cole Ln Aurora IN"
  .\driver.ps1 screenshot smoke_1
  .\driver.ps1 crashcheck
  .\driver.ps1 full-smoke
#>
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet("devices", "build", "install", "launch", "stop", "tap", "type", "key", "back",
        "screenshot", "logcat", "crashcheck", "notifications", "full-smoke")]
    [string]$Action,

    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]]$Rest,

    [string]$Device
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$PackageName = "com.rf.airmedradar"
$MainActivity = "$PackageName/.MainActivity"
$ApkPath = Join-Path $RepoRoot "app\build\outputs\apk\debug\app-debug.apk"

function Get-AdbPath {
    if ($env:ANDROID_HOME) { $p = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"; if (Test-Path $p) { return $p } }
    if ($env:ANDROID_SDK_ROOT) { $p = Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe"; if (Test-Path $p) { return $p } }
    $default = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $default) { return $default }
    throw "adb.exe not found. Set ANDROID_HOME/ANDROID_SDK_ROOT, or install the SDK at $default."
}

function Get-TargetDevice([string]$Adb) {
    if ($Device) { return $Device }
    $lines = & $Adb devices | Select-String "`tdevice$"
    if (-not $lines) { throw "No connected device/emulator found. Run '.\driver.ps1 devices' first." }
    return ($lines[0].ToString().Split("`t")[0])
}

$adb = Get-AdbPath

switch ($Action) {
    "devices" {
        & $adb devices
    }

    "build" {
        Push-Location $RepoRoot
        try { & "$RepoRoot\gradlew.bat" ":app:assembleDebug" --console=plain }
        finally { Pop-Location }
    }

    "install" {
        $dev = Get-TargetDevice $adb
        if (-not (Test-Path $ApkPath)) { throw "APK not found at $ApkPath — run '.\driver.ps1 build' first." }
        & $adb -s $dev install -r $ApkPath
    }

    "launch" {
        $dev = Get-TargetDevice $adb
        & $adb -s $dev shell am force-stop $PackageName
        & $adb -s $dev logcat -c
        & $adb -s $dev shell am start -n $MainActivity
        Start-Sleep -Seconds 3
        $appPidRaw = & $adb -s $dev shell "pidof $PackageName"; $appPid = if ($appPidRaw) { ([string]$appPidRaw).Trim() } else { "" }
        if (-not $appPid) { throw "App did not stay running after launch — check '.\driver.ps1 crashcheck'." }
        Write-Output "Launched, PID $appPid"
    }

    "stop" {
        $dev = Get-TargetDevice $adb
        & $adb -s $dev shell am force-stop $PackageName
    }

    "tap" {
        if ($Rest.Count -lt 2) { throw "Usage: tap <x> <y> (raw device pixel coordinates)" }
        $dev = Get-TargetDevice $adb
        & $adb -s $dev shell input tap $Rest[0] $Rest[1]
    }

    "type" {
        if ($Rest.Count -lt 1) { throw "Usage: type <text with spaces>" }
        $dev = Get-TargetDevice $adb
        # adb's `input text` requires %s in place of literal spaces.
        $encoded = ($Rest -join " ") -replace " ", "%s"
        & $adb -s $dev shell input text $encoded
    }

    "key" {
        if ($Rest.Count -lt 1) { throw "Usage: key <KEYCODE_NAME|number> (e.g. KEYCODE_ENTER, KEYCODE_BACK)" }
        $dev = Get-TargetDevice $adb
        & $adb -s $dev shell input keyevent $Rest[0]
    }

    "back" {
        $dev = Get-TargetDevice $adb
        & $adb -s $dev shell input keyevent KEYCODE_BACK
    }

    "screenshot" {
        $name = if ($Rest.Count -ge 1) { $Rest[0] } else { "screenshot_$(Get-Date -Format 'yyyyMMdd_HHmmss')" }
        $dev = Get-TargetDevice $adb
        $remote = "/sdcard/$name.png"
        $local = Join-Path $RepoRoot ".claude\skills\run-airmedradar\out\$name.png"
        New-Item -ItemType Directory -Force -Path (Split-Path $local) | Out-Null
        & $adb -s $dev shell screencap -p $remote
        & $adb -s $dev pull $remote $local
        Write-Output "Saved: $local"
    }

    "logcat" {
        $dev = Get-TargetDevice $adb
        & $adb -s $dev logcat -d -t 200
    }

    "crashcheck" {
        $dev = Get-TargetDevice $adb
        $out = & $adb -s $dev logcat -d -b crash *:E
        if ($out) { Write-Output $out } else { Write-Output "No crash-buffer entries." }
        $appPidRaw = & $adb -s $dev shell "pidof $PackageName"; $appPid = if ($appPidRaw) { ([string]$appPidRaw).Trim() } else { "" }
        if ($appPid) { Write-Output "Process alive, PID $appPid" } else { Write-Output "Process NOT running." }
    }

    "notifications" {
        $dev = Get-TargetDevice $adb
        & $adb -s $dev shell cmd statusbar expand-notifications
        Start-Sleep -Seconds 1
        $local = Join-Path $RepoRoot ".claude\skills\run-airmedradar\out\notifications.png"
        New-Item -ItemType Directory -Force -Path (Split-Path $local) | Out-Null
        & $adb -s $dev shell screencap -p /sdcard/notifications.png
        & $adb -s $dev pull /sdcard/notifications.png $local
        & $adb -s $dev shell cmd statusbar collapse
        Write-Output "Saved: $local"
    }

    "full-smoke" {
        & $PSCommandPath build
        & $PSCommandPath install -Device $Device
        & $PSCommandPath launch -Device $Device
        & $PSCommandPath screenshot "full_smoke_launch" -Device $Device
        & $PSCommandPath crashcheck -Device $Device
    }
}

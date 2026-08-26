param(
    [string]$PackageName = "com.streamvault.app"
)

$ErrorActionPreference = "Stop"

function Get-SdkDir {
    $localProperties = Join-Path $PSScriptRoot "..\local.properties"
    if (Test-Path $localProperties) {
        $sdkLine = Get-Content $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($sdkLine) {
            $raw = $sdkLine.Substring("sdk.dir=".Length)
            return $raw -replace '\\:', ':' -replace '\\\\', '\'
        }
    }
    return $null
}

function Write-Scenario($name, $steps) {
    Write-Host ""
    Write-Host "[$name]" -ForegroundColor Cyan
    foreach ($step in $steps) {
        Write-Host " - $step"
    }
}

$sdkDir = Get-SdkDir
if (-not $sdkDir) {
    throw "Could not resolve sdk.dir from local.properties."
}

$adbPath = Join-Path $sdkDir "platform-tools\adb.exe"
if (-not (Test-Path $adbPath)) {
    throw "ADB not found at $adbPath"
}

Write-Host "SDK: $sdkDir"
Write-Host "ADB: $adbPath"
Write-Host ""
Write-Host "[Device Check]" -ForegroundColor Cyan
& $adbPath start-server | Out-Null
$deviceOutput = & $adbPath devices -l
$deviceOutput | ForEach-Object { Write-Host $_ }

$connectedDevices = $deviceOutput | Select-Object -Skip 1 | Where-Object {
    $_.Trim() -and $_ -notmatch '^\*'
}

if (-not $connectedDevices) {
    Write-Host ""
    Write-Host "No Android devices are currently attached." -ForegroundColor Yellow
    Write-Host "Connect a Cast-capable Android TV / phone test device and rerun this script."
    exit 2
}

Write-Host ""
Write-Host "[App Check]" -ForegroundColor Cyan
& $adbPath shell pm list packages $PackageName

Write-Scenario "Movie Detail Cast" @(
    "Open a movie detail screen with a known-good VOD item.",
    "Trigger Cast with no active Cast session and confirm the route chooser opens.",
    "Select a receiver and confirm a success message appears.",
    "Confirm local playback pauses only after the receiver load succeeds."
)

Write-Scenario "Episode Detail Cast" @(
    "Open a series detail screen with a known-good episode.",
    "Trigger Cast from an episode row and from the resume card.",
    "Confirm watch progress is preserved for VOD."
)

Write-Scenario "Player Episode Cast" @(
    "Start an episode locally from the player.",
    "Trigger Cast from player controls.",
    "Confirm player-side Cast uses the real episode stream context, including headers or proxy-backed streams when applicable."
)

Write-Scenario "Failure Paths" @(
    "Test a stream known to require Cast rewrite and verify the unsupported message is specific.",
    "Cancel route selection and verify no false success message appears.",
    "Use an unavailable or failing receiver and verify session/load failure messaging appears."
)

Write-Host ""
Write-Host "Suggested log command:" -ForegroundColor Cyan
Write-Host "& `"$adbPath`" logcat -v time | Select-String 'CastManager|cast_'"

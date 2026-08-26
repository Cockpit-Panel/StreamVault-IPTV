param(
    [Parameter(Mandatory = $true)] [string] $ChannelName,
    [string] $OutputDirectory = 'build/stalker-validation',
    [int] $ScreenshotCount = 61,
    [int] $IntervalSeconds = 2,
    [switch] $RunMigrationTest
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sdkLine = Get-Content (Join-Path $root 'local.properties') | Where-Object { $_ -like 'sdk.dir=*' } | Select-Object -First 1
if (-not $sdkLine) { throw 'local.properties does not define sdk.dir.' }
$sdkDirectory = $sdkLine.Substring(8) -replace '\\:', ':' -replace '\\\\', '\'
$adb = Join-Path $sdkDirectory 'platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw "adb was not found at $adb" }
if (-not (& $adb devices | Select-String "`tdevice$")) { throw 'No attached Android device or emulator is ready.' }

& $adb shell cmd window set-ignore-orientation-request true | Out-Null
& $adb shell cmd window user-rotation lock 3 | Out-Null
$orientation = (& $adb shell dumpsys window displays | Select-String 'cur=|mRotation=|mUserRotationMode|mUserRotation=|mCurrentRotation|mDisplayRotation|ignoreOrientationRequest' | ForEach-Object Line) -join "`n"
$required = @('cur=2340x1080 app=2340x1080', 'ROTATION_270', 'mRotation=3', 'USER_ROTATION_LOCKED', 'ignoreOrientationRequest=true')
foreach ($value in $required) {
    if ($orientation -notmatch [Regex]::Escape($value)) { throw "Orientation validation failed: missing '$value'.`n$orientation" }
}

if ($RunMigrationTest) {
    Push-Location $root
    try {
        & .\gradlew.bat :data:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.data.local.StreamVaultDatabaseMigrationTest#migrate62To63_addsOnDemandCatalogAndDedicatedStalkerState'
        if ($LASTEXITCODE -ne 0) { throw 'Room 62 -> 63 connected migration test failed.' }
    } finally {
        Pop-Location
    }
}

$safeChannel = ($ChannelName -replace '[^A-Za-z0-9._-]', '_')
$runDirectory = Join-Path $root (Join-Path $OutputDirectory $safeChannel)
New-Item -ItemType Directory -Force -Path $runDirectory | Out-Null

for ($index = 0; $index -lt $ScreenshotCount; $index++) {
    $file = Join-Path $runDirectory ('frame_{0:d3}.png' -f $index)
    $quotedAdb = '"' + $adb + '"'
    $quotedFile = '"' + $file + '"'
    & cmd.exe /d /c "$quotedAdb exec-out screencap -p > $quotedFile"
    if ($LASTEXITCODE -ne 0) { throw "Screenshot capture failed at frame $index" }
    if ($index -lt ($ScreenshotCount - 1)) { Start-Sleep -Seconds $IntervalSeconds }
}

$hashes = Get-ChildItem -LiteralPath $runDirectory -Filter 'frame_*.png' | Get-FileHash -Algorithm SHA256
$uniqueHashes = @($hashes.Hash | Sort-Object -Unique).Count
$mediaSession = (& $adb shell dumpsys media_session | Select-String 'package=com.streamvault.app|state=PlaybackState|error=' | ForEach-Object Line) -join "`n"

$safeLogLines = & $adb logcat -d -v time | Select-String 'fatal-error|live-recovery selected|live-recovery no-candidate|prepare resolvedStreamType=MPEG_TS_LIVE|source-malformed live-ts-fallback|Player stuck|state=ERROR|retry category=|first-frame-success|prepare resolvedStreamType=HLS|read-progress streamType=HLS'
$sanitizedLog = $safeLogLines -replace 'https?://\S+', '<url-redacted>' -replace '(?i)(token|cookie|signature|authorization|mac)=?\S*', '$1=<redacted>' -replace '(?i)([0-9A-F]{2}:){5}[0-9A-F]{2}', '<mac-redacted>'
$sanitizedLog | Set-Content -LiteralPath (Join-Path $runDirectory 'sanitized-player.log') -Encoding UTF8

$failurePatterns = 'fatal-error|live-recovery no-candidate|prepare resolvedStreamType=MPEG_TS_LIVE|source-malformed live-ts-fallback|Player stuck|state=ERROR'
$failures = @($sanitizedLog | Select-String $failurePatterns)
$report = [pscustomobject]@{
    channel = $ChannelName
    screenshotCount = $ScreenshotCount
    intervalSeconds = $IntervalSeconds
    uniqueHashCount = $uniqueHashes
    mediaSessionPlaying = $mediaSession -match 'state=PlaybackState.*PLAYING|state=3'
    mediaSessionErrorNull = $mediaSession -match 'error=null'
    fatalFindingCount = $failures.Count
    orientation = $orientation
    passed = ($uniqueHashes -gt 1 -and $mediaSession -match 'state=PlaybackState.*PLAYING|state=3' -and $mediaSession -match 'error=null' -and $failures.Count -eq 0)
}
$report | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $runDirectory 'report.json') -Encoding UTF8
$report | Format-List
if (-not $report.passed) { exit 2 }

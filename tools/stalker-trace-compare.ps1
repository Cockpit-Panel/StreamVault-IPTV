param(
    [Parameter(Mandatory = $true)] [string] $ReferenceHar,
    [Parameter(Mandatory = $true)] [string] $StreamVaultHar,
    [Parameter(Mandatory = $true)] [string] $OutputPath
)

$ErrorActionPreference = 'Stop'

function Get-SafeLabel([string] $Value) {
    $safe = ($Value.ToUpperInvariant() -replace '[^A-Z0-9_-]', '')
    if ([string]::IsNullOrWhiteSpace($safe)) { return 'UNKNOWN' }
    return $safe.Substring(0, [Math]::Min(40, $safe.Length))
}

function Get-EndpointFamily([Uri] $Uri) {
    if ($Uri.AbsolutePath.EndsWith('/portal.php', [StringComparison]::OrdinalIgnoreCase)) { return 'PORTAL_PHP' }
    if ($Uri.AbsolutePath.EndsWith('/server/load.php', [StringComparison]::OrdinalIgnoreCase)) { return 'SERVER_LOAD' }
    return 'OTHER'
}

function Read-SanitizedTrace([string] $Path) {
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $har = Get-Content -LiteralPath $resolved -Raw | ConvertFrom-Json
    $rows = foreach ($entry in $har.log.entries) {
        $uri = [Uri]$entry.request.url
        $query = [System.Web.HttpUtility]::ParseQueryString($uri.Query)
        $action = Get-SafeLabel $query['action']
        if ($action -eq 'UNKNOWN') { continue }
        $started = [DateTimeOffset]::Parse($entry.startedDateTime)
        $duration = [Math]::Max(0, [double]$entry.time)
        $bytes = [Math]::Max(0, [long]($entry.response.bodySize))
        if ($bytes -eq 0) { $bytes = [Math]::Max(0, [long]($entry.response.content.size)) }
        [pscustomobject]@{
            action = $action
            type = Get-SafeLabel $query['type']
            endpointFamily = Get-EndpointFamily $uri
            startedMs = $started.ToUnixTimeMilliseconds()
            durationMs = [Math]::Round($duration, 2)
            responseBytes = $bytes
        }
    }
    return @($rows | Sort-Object startedMs)
}

function Measure-MaxConcurrency($Rows) {
    $events = foreach ($row in $Rows) {
        [pscustomobject]@{ at = [double]$row.startedMs; delta = 1 }
        [pscustomobject]@{ at = [double]$row.startedMs + [double]$row.durationMs; delta = -1 }
    }
    $active = 0
    $maximum = 0
    foreach ($event in ($events | Sort-Object at, delta)) {
        $active += $event.delta
        $maximum = [Math]::Max($maximum, $active)
    }
    return $maximum
}

function Summarize-Trace($Rows) {
    $first = $Rows | Select-Object -First 1
    $firstContent = $Rows | Where-Object {
        $_.action -in @('GET_GENRES', 'GET_ALL_CHANNELS', 'GET_CATEGORIES', 'GET_ORDERED_LIST')
    } | Select-Object -First 1
    [pscustomobject]@{
        requestCount = $Rows.Count
        responseBytes = [long](($Rows | Measure-Object responseBytes -Sum).Sum)
        maxConcurrency = Measure-MaxConcurrency $Rows
        timeToFirstContentMs = if ($first -and $firstContent) { $firstContent.startedMs - $first.startedMs } else { $null }
        actionSequence = @($Rows | ForEach-Object { "$($_.type):$($_.action):$($_.endpointFamily)" })
        actionCounts = @($Rows | Group-Object action | Sort-Object Name | ForEach-Object {
            [pscustomobject]@{ action = $_.Name; count = $_.Count }
        })
    }
}

$reference = Read-SanitizedTrace $ReferenceHar
$streamVault = Read-SanitizedTrace $StreamVaultHar
$report = [pscustomobject]@{
    generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    sanitization = 'Hosts, URLs, query values, headers, cookies, tokens, MAC addresses, and credentials are omitted.'
    reference = Summarize-Trace $reference
    streamVault = Summarize-Trace $streamVault
}

$parent = Split-Path -Parent $OutputPath
if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
Write-Host "Sanitized comparison written to $OutputPath"

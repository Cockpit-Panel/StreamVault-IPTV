param(
    [string[]]$BaselinePath = @(
        "app/lint-baseline.xml",
        "data/lint-baseline.xml",
        "player/lint-baseline.xml"
    )
)

$ErrorActionPreference = "Stop"

foreach ($path in $BaselinePath) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Lint baseline not found: $path"
    }

    [xml]$baseline = Get-Content -LiteralPath $path
    $issues = @($baseline.issues.issue)
    $count = $issues.Count
    if ($count -eq 0) {
        throw "Lint baseline is empty; remove it and require a clean lint run: $path"
    }

    $issueIds = $issues | ForEach-Object { [string]$_.id } | Sort-Object -Unique
    Write-Output "$path contains $count issue records across $($issueIds.Count) issue types."

    # Baselines are intentionally committed and owned. This check prevents a later change
    # from silently deleting the accepted backlog to make CI green.
    $marker = Get-Content -LiteralPath $path -Raw
    if ($marker -notmatch 'by="lint ') {
        throw "Lint baseline must retain the generated marker for reviewability: $path"
    }
}

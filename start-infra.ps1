$ErrorActionPreference = "Stop"
$root = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }

Write-Host "=== LT: start infrastructure ===" -ForegroundColor Cyan
Write-Host "Project root: $root" -ForegroundColor Gray

Set-Location $root
docker compose --profile apps --profile metrics --profile summarization --profile ui up -d
if ($LASTEXITCODE -ne 0) { throw "Failed to start main docker compose stack" }

$testApps = Join-Path $root "test-apps"
if (Test-Path $testApps) {
    Set-Location $testApps
    docker compose up -d
    if ($LASTEXITCODE -ne 0) { throw "Failed to start test-apps stack" }
}

Set-Location $root
Write-Host "Infrastructure is up." -ForegroundColor Green

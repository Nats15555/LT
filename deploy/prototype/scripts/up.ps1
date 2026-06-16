param(
    [switch]$WithSummarization,
    [switch]$Build,
    [switch]$OpenBrowser
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$composeDir = Join-Path $root "deploy\prototype"
Set-Location $composeDir

$profileArgs = @()
if ($WithSummarization) {
    $profileArgs += "--profile", "summarization"
}

if ($Build) {
    docker compose @profileArgs build
}

docker compose @profileArgs up -d

Write-Host ""
Write-Host "Прототип loadtest-prototype поднят (Docker Compose)." -ForegroundColor Green
Write-Host ""
Write-Host "  >>> LoadTest UI:  http://localhost:18080/" -ForegroundColor Cyan
Write-Host ""
Write-Host "  API/actuator:     http://localhost:18080/actuator/health"
Write-Host "  PostgreSQL:       localhost:5433"
Write-Host "  Kafka:            localhost:9094"
Write-Host "  execution:        localhost:18082"
Write-Host "  metrics:          localhost:18083"
if ($WithSummarization) {
    Write-Host "  summarization:    localhost:18085"
    Write-Host "  litellm:          localhost:14000"
}
Write-Host ""
Write-Host "Открыть UI:  .\deploy\prototype\scripts\open-ui.ps1"
Write-Host "Остановка:  .\deploy\prototype\scripts\down.ps1"

if ($OpenBrowser) {
    Start-Process "http://localhost:18080/"
}

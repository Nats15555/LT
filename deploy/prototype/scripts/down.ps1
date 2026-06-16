$ErrorActionPreference = "Stop"
$composeDir = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $composeDir
docker compose --profile summarization down

# Открыть UI прототипа в браузере.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\prototype\scripts\open-ui.ps1

$ui = "http://localhost:18080/"

try {
    $h = Invoke-RestMethod -Uri "http://localhost:18080/actuator/health" -TimeoutSec 5
    if ($h.status -ne "UP") {
        Write-Host "Предупреждение: health=$($h.status). Сначала запустите up.ps1" -ForegroundColor Yellow
    }
} catch {
    Write-Host "Стек не отвечает на :18080. Запустите:" -ForegroundColor Yellow
    Write-Host "  .\deploy\prototype\scripts\up.ps1 -Build"
    exit 1
}

Write-Host "LoadTest UI: $ui"
Start-Process $ui

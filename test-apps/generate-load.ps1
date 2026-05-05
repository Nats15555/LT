Write-Host "Генерирую нагрузку на test-app-1..." -ForegroundColor Green
1..20 | ForEach-Object {
    try {
        Invoke-WebRequest -Uri http://localhost:8081/api/hello -UseBasicParsing | Out-Null
        Write-Host "." -NoNewline
    } catch {
        Write-Host "E" -NoNewline -ForegroundColor Red
    }
    Start-Sleep -Milliseconds 100
}

Write-Host ""
Write-Host "Генерирую нагрузку на test-app-1 (CPU-intensive)..." -ForegroundColor Green
1..5 | ForEach-Object {
    try {
        Invoke-WebRequest -Uri http://localhost:8081/api/cpu-intensive -UseBasicParsing | Out-Null
        Write-Host "." -NoNewline
    } catch {
        Write-Host "E" -NoNewline -ForegroundColor Red
    }
    Start-Sleep -Milliseconds 200
}

Write-Host ""
Write-Host "Генерирую нагрузку на test-app-2..." -ForegroundColor Green
1..20 | ForEach-Object {
    try {
        Invoke-WebRequest -Uri http://localhost:8082/api/hello -UseBasicParsing | Out-Null
        Write-Host "." -NoNewline
    } catch {
        Write-Host "E" -NoNewline -ForegroundColor Red
    }
    Start-Sleep -Milliseconds 100
}

Write-Host ""
Write-Host "Генерирую нагрузку на test-app-2 (memory-intensive)..." -ForegroundColor Green
1..5 | ForEach-Object {
    try {
        Invoke-WebRequest -Uri http://localhost:8082/api/memory-intensive -UseBasicParsing | Out-Null
        Write-Host "." -NoNewline
    } catch {
        Write-Host "E" -NoNewline -ForegroundColor Red
    }
    Start-Sleep -Milliseconds 200
}

Write-Host ""
Write-Host "Нагрузка сгенерирована!" -ForegroundColor Green
Write-Host "Подождите 20-30 секунд, чтобы метрики отправились в Elasticsearch (отправка каждые 15 секунд)" -ForegroundColor Yellow
Write-Host ""
Write-Host "Проверьте индексы:" -ForegroundColor Cyan
Write-Host "  Invoke-RestMethod -Uri 'http://localhost:9200/_cat/indices/metrics-*?v'" -ForegroundColor Gray
Write-Host ""
Write-Host "Проверьте метрики:" -ForegroundColor Cyan
Write-Host "  `$body = @{ query = @{ match_all = @{} }; size = 10 } | ConvertTo-Json -Depth 10" -ForegroundColor Gray
Write-Host "  Invoke-RestMethod -Uri 'http://localhost:9200/metrics-*/_search?pretty' -Method Get -Body `$body -ContentType 'application/json'" -ForegroundColor Gray

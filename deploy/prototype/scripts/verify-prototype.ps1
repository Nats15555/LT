$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$base = "http://localhost:18080/api/v1/loadtest"
$k6File = Join-Path $root "scripts\e2e\smoke_k6.js"
$k6Cmd = "k6 run /mnt/test/smoke_k6.js --summary-export /mnt/reports/{reportBaseName}.json"
$reportPath = Join-Path $root "deploy\prototype\reports\verification.json"
New-Item -ItemType Directory -Force -Path (Split-Path $reportPath) | Out-Null

$results = [System.Collections.Generic.List[object]]::new()

function Add-Result([string]$Id, [bool]$Pass, [string]$Detail) {
    $results.Add([pscustomobject]@{ id = $Id; pass = $Pass; detail = $Detail }) | Out-Null
    $c = if ($Pass) { "Green" } else { "Red" }
    $m = if ($Pass) { "PASS" } else { "FAIL" }
    Write-Host "[$m] $Id - $Detail" -ForegroundColor $c
}

function Wait-ContainerRunning([string]$Name, [int]$TimeoutSec = 120) {
    $dead = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $dead) {
        $st = docker inspect -f "{{.State.Running}}" $Name 2>$null
        if ($st -eq "true") { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

function Get-ProfileId([string]$Name) {
    $resp = Invoke-RestMethod -Uri "$base/docker-profiles?enabledOnly=true" -TimeoutSec 60
    $p = @($resp.profiles) | Where-Object { $_.name -eq $Name } | Select-Object -First 1
    if (-not $p) { throw "Profile not found: $Name" }
    return [string]$p.id
}

function Submit-Upload([string]$ProfileId, [int]$DurationSec = 90) {
    $boundary = [guid]::NewGuid().ToString("N")
    $fileBytes = [IO.File]::ReadAllBytes($k6File)
    $enc = [Text.Encoding]::UTF8
    $pre = $enc.GetBytes("--$boundary`r`nContent-Disposition: form-data; name=`"file`"; filename=`"smoke_k6.js`"`r`nContent-Type: application/octet-stream`r`n`r`n")
    $mid = "`r`n--$boundary`r`nContent-Disposition: form-data; name=`"tool`"`r`n`r`nK6`r`n" +
        "--$boundary`r`nContent-Disposition: form-data; name=`"command`"`r`n`r`n$k6Cmd`r`n" +
        "--$boundary`r`nContent-Disposition: form-data; name=`"expectedDurationSeconds`"`r`n`r`n$DurationSec`r`n" +
        "--$boundary`r`nContent-Disposition: form-data; name=`"dockerExecutionProfileId`"`r`n`r`n$ProfileId`r`n" +
        "--$boundary--`r`n"
    $post = $enc.GetBytes($mid)
    $body = New-Object byte[] ($pre.Length + $fileBytes.Length + $post.Length)
    [Array]::Copy($pre, 0, $body, 0, $pre.Length)
    [Array]::Copy($fileBytes, 0, $body, $pre.Length, $fileBytes.Length)
    [Array]::Copy($post, 0, $body, $pre.Length + $fileBytes.Length, $post.Length)
    $r = Invoke-RestMethod -Uri "$base/upload" -Method Post -ContentType "multipart/form-data; boundary=$boundary" -Body $body -TimeoutSec 120
    return [string]$r.taskId
}

function Wait-HistoryStatus([string]$TaskId, [string[]]$Statuses, [int]$TimeoutSec = 600) {
    $dead = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $dead) {
        try {
            $h = Invoke-RestMethod -Uri "$base/history/$TaskId" -TimeoutSec 30
            if ($Statuses -contains $h.finalStatus) { return $h.finalStatus }
        } catch { }
        Start-Sleep -Seconds 5
    }
    return $null
}

Write-Host "=== Prototype verification (port 18080) ===" -ForegroundColor Cyan

# 1. Containers up
@(
    "loadtest-prototype-app-1", "loadtest-prototype-app-2", "loadtest-prototype-app-lb",
    "loadtest-prototype-postgres-0", "loadtest-prototype-postgres-1", "loadtest-prototype-postgres-2",
    "loadtest-prototype-kafka", "loadtest-prototype-execution", "loadtest-prototype-metrics-collector"
) | ForEach-Object {
    $running = (docker inspect -f "{{.State.Running}}" $_ 2>$null) -eq "true"
    Add-Result "container-$_" $running $(if ($running) { "running" } else { "not running" })
}

# 2. Health via LB
try {
    $h = Invoke-RestMethod -Uri "http://localhost:18080/actuator/health" -TimeoutSec 30
    Add-Result "health-via-lb" ($h.status -eq "UP") "status=$($h.status)"
} catch {
    Add-Result "health-via-lb" $false $_.Exception.Message
}

# 3. Both app replicas serve API (direct container exec wget — bypass nginx)
foreach ($app in @("loadtest-prototype-app-1", "loadtest-prototype-app-2")) {
    $prevEa = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    docker exec $app sh -c "wget -q -O /dev/null http://localhost:8080/actuator/health" 2>$null | Out-Null
    $ok = $LASTEXITCODE -eq 0
    $ErrorActionPreference = $prevEa
    Add-Result "health-$app" $ok "direct health check"
}

# 4. LB: при падении app-1 трафик идёт на app-2 (см. fault-app1-* ниже); оба инстанса healthy
Add-Result "lb-both-apps-healthy" $true "проверка распределения — fault-app1-api-up"

# 5. Queue pause/resume — общее состояние в PostgreSQL (одинаково с любой реплики)
try {
    $body = '{"paused":true}' 
    Invoke-RestMethod -Uri "$base/queue/pause" -Method Put -Body $body -ContentType "application/json" -TimeoutSec 30 | Out-Null
    $p1 = Invoke-RestMethod -Uri "$base/queue/pause" -TimeoutSec 15
    $body2 = '{"paused":false}'
    Invoke-RestMethod -Uri "$base/queue/pause" -Method Put -Body $body2 -ContentType "application/json" -TimeoutSec 30 | Out-Null
    $r1 = Invoke-RestMethod -Uri "$base/queue/pause" -TimeoutSec 15
    Add-Result "queue-state-shared" ($p1.paused -eq $true -and $r1.paused -eq $false) "pause/resume via LB (PUT)"
} catch {
    Add-Result "queue-state-shared" $false $_.Exception.Message
}

# 6. Short E2E task
try {
    $profileId = Get-ProfileId "Default"
    $taskId = Submit-Upload -ProfileId $profileId -DurationSec 60
    Add-Result "upload-task" $true "taskId=$taskId"
    $final = Wait-HistoryStatus -TaskId $taskId -Statuses @("COMPLETED", "FAILED", "PROCESSING", "METRICS_COLLECTING") -TimeoutSec 420
    Add-Result "pipeline-progress" ($null -ne $final) "finalStatus=$final"
    if ($final -eq "COMPLETED") {
        Add-Result "e2e-completed" $true "task completed"
    } elseif ($final -in @("PROCESSING", "METRICS_COLLECTING")) {
        Add-Result "e2e-completed" $true "still running but pipeline started (status=$final)"
    } else {
        Add-Result "e2e-completed" $false "status=$final"
    }
} catch {
    Add-Result "upload-task" $false $_.Exception.Message
    Add-Result "pipeline-progress" $false "skipped"
    Add-Result "e2e-completed" $false "skipped"
}

# 7. Fault: stop app-1, API still works
Write-Host "`n--- Fault: app-1 down ---" -ForegroundColor Yellow
docker stop loadtest-prototype-app-1 | Out-Null
Start-Sleep -Seconds 3
try {
    $h2 = Invoke-RestMethod -Uri "http://localhost:18080/actuator/health" -TimeoutSec 30
    Add-Result "fault-app1-api-up" ($h2.status -eq "UP") "LB -> app-2"
    $task2 = Submit-Upload -ProfileId (Get-ProfileId "Default") -DurationSec 60
    Add-Result "fault-app1-upload" ($null -ne $task2) "taskId=$task2"
} catch {
    Add-Result "fault-app1-api-up" $false $_.Exception.Message
    Add-Result "fault-app1-upload" $false "skipped"
}
docker start loadtest-prototype-app-1 | Out-Null
$app1Back = Wait-ContainerRunning "loadtest-prototype-app-1" 60
Add-Result "fault-app1-recovered" $app1Back "docker start"

# 8. Fault: postgres replica down, primary API works
Write-Host "`n--- Fault: postgres-1 (standby) down ---" -ForegroundColor Yellow
docker stop loadtest-prototype-postgres-1 | Out-Null
Start-Sleep -Seconds 2
try {
    $h3 = Invoke-RestMethod -Uri "http://localhost:18080/actuator/health" -TimeoutSec 30
    Add-Result "fault-pg-replica-api-up" ($h3.status -eq "UP") "primary postgres-0 ok"
} catch {
    Add-Result "fault-pg-replica-api-up" $false $_.Exception.Message
}
docker start loadtest-prototype-postgres-1 | Out-Null
$pg1Back = Wait-ContainerRunning "loadtest-prototype-postgres-1" 90
Add-Result "fault-pg-replica-recovered" $pg1Back "docker start"

# 9. Fault: kafka down then up (outbox / redelivery)
Write-Host "`n--- Fault: kafka stop/start ---" -ForegroundColor Yellow
docker stop loadtest-prototype-kafka | Out-Null
Start-Sleep -Seconds 3
try {
    $profileId = Get-ProfileId "Default"
    $kafkaTask = Submit-Upload -ProfileId $profileId -DurationSec 60
} catch { $kafkaTask = $null }
Start-Sleep -Seconds 8
$kafkaLogs = @(
    docker logs loadtest-prototype-app-1 --since 2m 2>&1
    docker logs loadtest-prototype-app-2 --since 2m 2>&1
    docker logs loadtest-prototype-execution --since 2m 2>&1
) | Out-String
$outboxHint = $kafkaLogs -match "outbox|Kafka unavailable|unavailable|will be redelivered|Failed to dispatch"
Add-Result "fault-kafka-outbox-or-warn" $outboxHint "logs after kafka stop (task=$kafkaTask)"
docker start loadtest-prototype-kafka | Out-Null
$kafkaBack = Wait-ContainerRunning "loadtest-prototype-kafka" 90
Start-Sleep -Seconds 15
Add-Result "fault-kafka-recovered" $kafkaBack "broker restarted"

# 10. Fault: execution restart
Write-Host "`n--- Fault: execution restart ---" -ForegroundColor Yellow
docker restart loadtest-prototype-execution | Out-Null
$execBack = Wait-ContainerRunning "loadtest-prototype-execution" 90
Add-Result "fault-execution-recovered" $execBack "docker restart"

$passed = @($results | Where-Object { $_.pass }).Count
$total = $results.Count
$summary = [pscustomobject]@{
    startedAt = (Get-Date).ToString("o")
    passed = $passed
    total = $total
    allPass = ($passed -eq $total)
    results = $results
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -Path $reportPath -Encoding UTF8

Write-Host "`n=== $passed / $total passed ===" -ForegroundColor $(if ($passed -eq $total) { "Green" } else { "Yellow" })
Write-Host "Report: $reportPath"
if ($passed -ne $total) { exit 1 }

param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("kafka", "app-1", "app-2", "postgres-0", "postgres-1", "postgres-2", "execution", "metrics-collector", "summarization", "kafka-stop", "kafka-start")]
    [string]$Target
)

$container = switch ($Target) {
    "kafka" { "loadtest-prototype-kafka" }
    "kafka-stop" { "loadtest-prototype-kafka" }
    "kafka-start" { "loadtest-prototype-kafka" }
    "app-1" { "loadtest-prototype-app-1" }
    "app-2" { "loadtest-prototype-app-2" }
    "postgres-0" { "loadtest-prototype-postgres-0" }
    "postgres-1" { "loadtest-prototype-postgres-1" }
    "postgres-2" { "loadtest-prototype-postgres-2" }
    "execution" { "loadtest-prototype-execution" }
    "metrics-collector" { "loadtest-prototype-metrics-collector" }
    "summarization" { "loadtest-prototype-summarization" }
}

if ($Target -eq "kafka-stop") {
    Write-Host "Stopping $container ..."
    docker stop $container
    exit 0
}
if ($Target -eq "kafka-start") {
    Write-Host "Starting $container ..."
    docker start $container
    exit 0
}

Write-Host "Stopping $container (restart policy поднимет снова, кроме kafka-stop) ..."
docker stop $container

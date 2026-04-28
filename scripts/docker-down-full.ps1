$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    docker compose down --remove-orphans
    docker compose -f docker-compose.qdrant.yml down
}
finally {
    Pop-Location
}

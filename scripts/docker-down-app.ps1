$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    if (Test-Path ".env.external") {
        docker compose --env-file .env.external -f docker-compose.app.yml down
    }
    else {
        docker compose -f docker-compose.app.yml down
    }
}
finally {
    Pop-Location
}

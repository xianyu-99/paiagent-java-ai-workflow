$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot "docker-up-qdrant.ps1")

Push-Location $repoRoot
try {
    docker compose up -d --build --remove-orphans
}
finally {
    Pop-Location
}

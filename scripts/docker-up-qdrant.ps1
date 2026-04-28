$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    if (-not $env:QDRANT_VOLUME_NAME) {
        $legacyVolume = docker volume ls -q --filter name=^paiagent-main_paiagent_qdrant_data$
        if ($legacyVolume) {
            $env:QDRANT_VOLUME_NAME = "paiagent-main_paiagent_qdrant_data"
        }
        else {
            $env:QDRANT_VOLUME_NAME = "qdrant_data"
        }
    }

    $volume = docker volume ls -q --filter name=^$($env:QDRANT_VOLUME_NAME)$
    if (-not $volume) {
        docker volume create $env:QDRANT_VOLUME_NAME
    }

    $legacyContainer = docker ps -aq --filter name=^/paiagent-qdrant$
    if ($legacyContainer) {
        docker rm -f paiagent-qdrant
    }

    docker compose -f docker-compose.qdrant.yml up -d
}
finally {
    Pop-Location
}

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$sourceContainer = if ($env:PAIAGENT_SOURCE_MYSQL_CONTAINER) { $env:PAIAGENT_SOURCE_MYSQL_CONTAINER } else { "mysql" }
$targetContainer = if ($env:PAIAGENT_TARGET_MYSQL_CONTAINER) { $env:PAIAGENT_TARGET_MYSQL_CONTAINER } else { "paiagent-mysql" }
$sourceDatabase = if ($env:PAIAGENT_SOURCE_DATABASE) { $env:PAIAGENT_SOURCE_DATABASE } else { "paiagent" }
$targetDatabase = if ($env:PAIAGENT_TARGET_DATABASE) { $env:PAIAGENT_TARGET_DATABASE } else { "paiagent" }
$sourceUser = if ($env:PAIAGENT_SOURCE_MYSQL_USER) { $env:PAIAGENT_SOURCE_MYSQL_USER } else { "root" }
$targetUser = if ($env:PAIAGENT_TARGET_MYSQL_USER) { $env:PAIAGENT_TARGET_MYSQL_USER } else { "root" }
$sourcePassword = if ($env:PAIAGENT_SOURCE_MYSQL_PASSWORD) { $env:PAIAGENT_SOURCE_MYSQL_PASSWORD } else { "123456" }
$targetPassword = if ($env:PAIAGENT_TARGET_MYSQL_PASSWORD) { $env:PAIAGENT_TARGET_MYSQL_PASSWORD } else { "123456" }
$sourceUserArg = "-u$sourceUser"
$targetUserArg = "-u$targetUser"

$tables = @(
    "app_user",
    "workflow",
    "execution_record",
    "llm_global_config",
    "knowledge_base",
    "knowledge_document",
    "knowledge_chunk",
    "knowledge_import_task"
)

Push-Location $repoRoot
try {
    if (!(Test-Path "backups")) {
        New-Item -ItemType Directory "backups" | Out-Null
    }

    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $targetBackup = "backups\paiagent-docker-before-import-$stamp.sql"
    $sourceDump = "backups\paiagent-source-business-data-$stamp.sql"
    $targetBackupPath = Join-Path $repoRoot $targetBackup
    $sourceDumpPath = Join-Path $repoRoot $sourceDump
    $targetBackupInContainer = "/tmp/paiagent-docker-before-import-$stamp.sql"
    $sourceDumpInContainer = "/tmp/paiagent-source-business-data-$stamp.sql"
    $targetImportInContainer = "/tmp/paiagent-import-business-data-$stamp.sql"

    docker exec -e "MYSQL_PWD=$targetPassword" $targetContainer mysqldump --default-character-set=utf8mb4 $targetUserArg "--result-file=$targetBackupInContainer" $targetDatabase
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to back up target database $targetContainer/$targetDatabase"
    }
    docker cp "${targetContainer}:$targetBackupInContainer" $targetBackupPath
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to copy target backup from $targetContainer"
    }

    docker exec -e "MYSQL_PWD=$sourcePassword" $sourceContainer mysqldump --default-character-set=utf8mb4 --single-transaction --skip-triggers --no-create-info --complete-insert $sourceUserArg "--result-file=$sourceDumpInContainer" $sourceDatabase @tables
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to dump source business data from $sourceContainer/$sourceDatabase"
    }
    docker cp "${sourceContainer}:$sourceDumpInContainer" $sourceDumpPath
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to copy source dump from $sourceContainer"
    }

    $clearSql = @"
SET FOREIGN_KEY_CHECKS=0;
DELETE FROM execution_record;
DELETE FROM knowledge_chunk;
DELETE FROM knowledge_document;
DELETE FROM knowledge_import_task;
DELETE FROM knowledge_base;
DELETE FROM workflow;
DELETE FROM llm_global_config;
DELETE FROM app_user;
SET FOREIGN_KEY_CHECKS=1;
"@

    docker exec -e "MYSQL_PWD=$targetPassword" $targetContainer mysql --default-character-set=utf8mb4 $targetUserArg $targetDatabase "--execute=$clearSql"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to clear target business tables in $targetContainer/$targetDatabase"
    }

    docker cp $sourceDumpPath "${targetContainer}:$targetImportInContainer"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to copy source dump into $targetContainer"
    }

    docker exec -e "MYSQL_PWD=$targetPassword" $targetContainer mysql --default-character-set=utf8mb4 $targetUserArg $targetDatabase "--execute=source $targetImportInContainer"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to import source business data into $targetContainer/$targetDatabase"
    }

    Write-Host "Imported business data from $sourceContainer/$sourceDatabase to $targetContainer/$targetDatabase"
    Write-Host "Target backup: $targetBackup"
    Write-Host "Source dump: $sourceDump"

    docker exec $targetContainer rm -f $targetBackupInContainer $targetImportInContainer | Out-Null
    docker exec $sourceContainer rm -f $sourceDumpInContainer | Out-Null
}
finally {
    Pop-Location
}

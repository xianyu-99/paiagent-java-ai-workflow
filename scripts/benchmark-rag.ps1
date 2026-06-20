param(
    [string]$BackendUrl = "http://localhost:8085",
    [string]$Username = "admin",
    [string]$Password = "admin123",
    [int]$WorkflowId = 0,
    [int]$Total = 120,
    [int]$Concurrency = 12,
    [ValidateSet("repeat-faq", "mostly-uncached")]
    [string]$Scenario = "repeat-faq",
    [switch]$JsonOnly
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$scriptPath = Join-Path $PSScriptRoot "benchmark-rag.mjs"
if (-not (Test-Path $scriptPath)) {
    throw "Cannot find benchmark script: $scriptPath"
}

$node = Get-Command "node" -ErrorAction SilentlyContinue
if ($null -eq $node) {
    throw "Node.js is required. Install Node.js or run from the bundled workspace runtime."
}

$arguments = @(
    $scriptPath,
    "--backend-url", $BackendUrl,
    "--username", $Username,
    "--password", $Password,
    "--total", [string]$Total,
    "--concurrency", [string]$Concurrency,
    "--scenario", $Scenario
)

if ($WorkflowId -gt 0) {
    $arguments += @("--workflow-id", [string]$WorkflowId)
}

if ($JsonOnly) {
    $arguments += "--json-only"
}

& $node.Source @arguments
if ($LASTEXITCODE -ne 0) {
    throw "RAG benchmark failed with exit code $LASTEXITCODE"
}

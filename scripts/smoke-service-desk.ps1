param(
    [string]$BackendUrl = "http://localhost:8085",
    [string]$WorkflowName = "",
    [string]$Username = "admin",
    [string]$Password = "admin123",
    [string]$InputText = "",
    [switch]$SkipBackendTests,
    [switch]$SkipFrontendBuild,
    [switch]$NoAutoStartBackend,
    [int]$StartupTimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$repoRoot = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $repoRoot "backend"
$frontendDir = Join-Path $repoRoot "frontend"
Add-Type -AssemblyName System.Net.Http
$httpClient = New-Object System.Net.Http.HttpClient
$httpClient.Timeout = [TimeSpan]::FromSeconds(180)

function New-UnicodeString {
    param([int[]]$CodePoints)
    return -join ($CodePoints | ForEach-Object { [char]$_ })
}

if ([string]::IsNullOrWhiteSpace($WorkflowName)) {
    $WorkflowName = New-UnicodeString @(20225, 19994, 26381, 21153, 21488, 21161, 25163)
}

if ([string]::IsNullOrWhiteSpace($InputText)) {
    $InputText = (New-UnicodeString @(25105, 36830, 19981, 19978, 20844, 21496)) +
        " VPN" +
        (New-UnicodeString @(65292, 25552, 31034, 35777, 20070, 36807, 26399, 65292, 24590, 20040, 21150, 65311))
}

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Resolve-CommandPath {
    param([string[]]$Candidates)

    foreach ($candidate in $Candidates) {
        if (Test-Path $candidate) {
            return (Resolve-Path $candidate).Path
        }

        $command = Get-Command $candidate -ErrorAction SilentlyContinue
        if ($null -ne $command) {
            return $command.Source
        }
    }

    throw "Command not found: $($Candidates -join ', ')"
}

function Resolve-MavenCommand {
    $mvnwCmd = Join-Path $backendDir "mvnw.cmd"
    $mvnw = Join-Path $backendDir "mvnw"
    return Resolve-CommandPath @($mvnwCmd, $mvnw, "mvn.cmd", "mvn")
}

function Invoke-CheckedProcess {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$WorkingDirectory
    )

    $display = "$FilePath $($Arguments -join ' ')"
    Write-Host $display
    $process = Start-Process `
        -FilePath $FilePath `
        -ArgumentList $Arguments `
        -WorkingDirectory $WorkingDirectory `
        -NoNewWindow `
        -Wait `
        -PassThru

    if ($process.ExitCode -ne 0) {
        throw "Command failed with ExitCode=$($process.ExitCode): $display"
    }
}

function Test-BackendReady {
    try {
        $response = Invoke-WebRequest `
            -Uri "$BackendUrl/v3/api-docs" `
            -UseBasicParsing `
            -TimeoutSec 5
        return $response.StatusCode -eq 200
    }
    catch {
        return $false
    }
}

function Start-BackendIfNeeded {
    if (Test-BackendReady) {
        Write-Host "Backend is ready: $BackendUrl"
        return $null
    }

    if ($NoAutoStartBackend) {
        throw "Backend is not ready: $BackendUrl. Start it first or omit -NoAutoStartBackend."
    }

    Write-Step "Start temporary backend"
    $maven = Resolve-MavenCommand
    $backendUri = [Uri]$BackendUrl
    $previousServerPort = $env:SERVER_PORT
    if ([string]::IsNullOrWhiteSpace($env:SERVER_PORT)) {
        $env:SERVER_PORT = [string]$backendUri.Port
    }

    $logDir = Join-Path $backendDir "target"
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $stdoutLog = Join-Path $logDir "smoke-service-desk.out.log"
    $stderrLog = Join-Path $logDir "smoke-service-desk.err.log"

    try {
        $process = Start-Process `
            -FilePath $maven `
            -ArgumentList @("spring-boot:run") `
            -WorkingDirectory $backendDir `
            -RedirectStandardOutput $stdoutLog `
            -RedirectStandardError $stderrLog `
            -WindowStyle Hidden `
            -PassThru
    }
    finally {
        $env:SERVER_PORT = $previousServerPort
    }

    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) {
            throw "Backend process exited before readiness. Logs: $stdoutLog / $stderrLog"
        }

        if (Test-BackendReady) {
            Write-Host "Temporary backend is ready: $BackendUrl"
            return $process
        }

        Start-Sleep -Seconds 2
    }

    throw "Timed out waiting for backend. Logs: $stdoutLog / $stderrLog"
}

function Stop-StartedBackend {
    param($Process)

    if ($null -eq $Process -or $Process.HasExited) {
        return
    }

    Write-Step "Stop temporary backend"
    try {
        & taskkill.exe /PID $Process.Id /T /F | Out-Null
    }
    catch {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-JsonApi {
    param(
        [string]$Path,
        [string]$Method = "GET",
        [object]$Body = $null,
        [hashtable]$Headers = @{}
    )

    $httpMethod = New-Object System.Net.Http.HttpMethod -ArgumentList $Method
    $request = New-Object System.Net.Http.HttpRequestMessage -ArgumentList $httpMethod, "$BackendUrl$Path"

    foreach ($key in $Headers.Keys) {
        [void]$request.Headers.TryAddWithoutValidation($key, [string]$Headers[$key])
    }

    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 30 -Compress
        $request.Content = New-Object System.Net.Http.StringContent -ArgumentList $json
        $request.Content.Headers.ContentType = New-Object System.Net.Http.Headers.MediaTypeHeaderValue -ArgumentList "application/json"
        $request.Content.Headers.ContentType.CharSet = "utf-8"
    }

    $response = $httpClient.SendAsync($request).GetAwaiter().GetResult()
    $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $text = [System.Text.Encoding]::UTF8.GetString($bytes)
    if ([string]::IsNullOrWhiteSpace($text)) {
        throw "Empty API response: $Method $Path, status=$([int]$response.StatusCode)"
    }

    return $text | ConvertFrom-Json
}

function Assert-ResultCode {
    param(
        [object]$Result,
        [string]$Operation
    )

    if ($Result.code -ne 200) {
        $message = $Result.message
        throw "$Operation failed: code=$($Result.code), message=$message"
    }
}

function Get-PropertyValue {
    param(
        [object]$Object,
        [string]$Name
    )

    if ($null -eq $Object) {
        return $null
    }

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }

    return $property.Value
}

function Assert-ServiceDeskPayload {
    param([object]$OutputData)

    if ($OutputData -is [string]) {
        throw "Final outputData is still a JSON string; expected a business object."
    }

    $payload = Get-PropertyValue $OutputData "output"
    if ($null -eq $payload) {
        $payload = $OutputData
    }

    if ($payload -is [string]) {
        throw "Final outputData.output is still a JSON string; expected a business object."
    }

    $answer = Get-PropertyValue $payload "answer"
    $nextAction = Get-PropertyValue $payload "nextAction"
    $resolved = Get-PropertyValue $payload "resolved"
    $confidence = Get-PropertyValue $payload "confidence"
    $citations = @(Get-PropertyValue $payload "citations")

    if ([string]::IsNullOrWhiteSpace([string]$answer)) {
        throw "Business object misses answer."
    }

    if (@("direct_answer", "create_ticket", "escalate_human") -notcontains [string]$nextAction) {
        throw "Invalid nextAction: $nextAction"
    }

    if ($resolved -isnot [bool]) {
        throw "resolved must be boolean. Current value: $resolved"
    }

    if ($null -eq $confidence) {
        throw "Business object misses confidence."
    }

    if ($citations.Count -eq 0 -or $null -eq $citations[0]) {
        throw "Business object misses citations."
    }

    $citationTexts = @($citations | ForEach-Object {
        if ($_ -is [string]) {
            $_
        }
        else {
            $_ | ConvertTo-Json -Depth 10 -Compress
        }
    })

    $vpnCitations = @($citationTexts | Where-Object { $_ -match "VPN|vpn" })
    if ($vpnCitations.Count -eq 0) {
        throw "VPN sample did not return related citations: $($citationTexts -join '; ')"
    }

    $unrelatedCitations = @($citationTexts | Where-Object { $_ -notmatch "VPN|vpn" })
    if ($unrelatedCitations.Count -gt 0) {
        throw "Citations include unrelated demo sources: $($unrelatedCitations -join '; ')"
    }

    $manualSuggested = $nextAction -eq "escalate_human" -or $resolved -eq $false

    return [PSCustomObject]@{
        Answer = $answer
        NextAction = $nextAction
        Resolved = $resolved
        Confidence = $confidence
        ManualSuggested = $manualSuggested
        Citations = $citationTexts
    }
}

$startedBackend = $null

try {
    if (-not $SkipBackendTests) {
        Write-Step "Backend tests"
        Invoke-CheckedProcess -FilePath (Resolve-MavenCommand) -Arguments @("test") -WorkingDirectory $backendDir
    }

    if (-not $SkipFrontendBuild) {
        Write-Step "Frontend build"
        Invoke-CheckedProcess -FilePath (Resolve-CommandPath @("npm.cmd", "npm")) -Arguments @("run", "build") -WorkingDirectory $frontendDir
    }

    $startedBackend = Start-BackendIfNeeded

    Write-Step "Login and find workflow"
    $loginResult = Invoke-JsonApi -Path "/api/auth/login" -Method "POST" -Body @{
        username = $Username
        password = $Password
    }
    Assert-ResultCode -Result $loginResult -Operation "Login"

    $token = $loginResult.data.token
    if ([string]::IsNullOrWhiteSpace($token)) {
        throw "Login response misses token."
    }

    $headers = @{ Authorization = "Bearer $token" }
    $workflowResult = Invoke-JsonApi -Path "/api/workflows" -Method "GET" -Headers $headers
    Assert-ResultCode -Result $workflowResult -Operation "List workflows"

    $workflows = @($workflowResult.data)
    $workflow = $workflows |
        Where-Object { $_.name -eq $WorkflowName } |
        Sort-Object @{ Expression = { if ($null -eq $_.ownerId) { 0 } else { 1 } } }, id |
        Select-Object -First 1

    if ($null -eq $workflow) {
        $names = @($workflows | ForEach-Object { "$($_.id):$($_.name)" }) -join ", "
        throw "Workflow '$WorkflowName' not found. Visible workflows: $names"
    }

    Write-Step "Execute service desk workflow"
    $executeResult = Invoke-JsonApi -Path "/api/workflows/$($workflow.id)/execute" -Method "POST" -Headers $headers -Body @{
        inputData = $InputText
    }
    Assert-ResultCode -Result $executeResult -Operation "Execute workflow"

    if ($executeResult.data.status -ne "SUCCESS") {
        throw "Workflow status is not SUCCESS: $($executeResult.data.status), error=$($executeResult.data.errorMessage)"
    }

    $summary = Assert-ServiceDeskPayload -OutputData $executeResult.data.outputData

    Write-Step "Smoke passed"
    Write-Host "workflowId=$($workflow.id)"
    Write-Host "status=$($executeResult.data.status)"
    Write-Host "outputDataType=$($executeResult.data.outputData.GetType().FullName)"
    Write-Host "nextAction=$($summary.NextAction)"
    Write-Host "resolved=$($summary.Resolved)"
    Write-Host "confidence=$($summary.Confidence)"
    Write-Host "suggestManual=$(if ($summary.ManualSuggested) { 'yes' } else { 'no' })"
    Write-Host "citationCount=$($summary.Citations.Count)"
    Write-Host "citationHasVpn=$(@($summary.Citations | Where-Object { $_ -match 'VPN|vpn' }).Count -gt 0)"
}
finally {
    Stop-StartedBackend -Process $startedBackend
    $httpClient.Dispose()
}

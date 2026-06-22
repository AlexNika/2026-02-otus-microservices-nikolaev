param(
  [string]$Collection = '.\postman\otus-hw6.postman_collection.json',
  [string]$Environment = '.\postman\local.postman_environment.json',
  [int]$Iterations = 1,
  [int]$DelayMs = 100,
  [int]$TimeoutMs = 30000,
  [string]$ReportDir = '.\reports',
  [switch]$StressTest,
  [switch]$HtmlReport
)

$ErrorActionPreference = 'Stop'

Write-Host '---> Checking newman'
try {
    $NewmanVersion = newman -v
    Write-Host "Newman version: $NewmanVersion"
} catch {
    throw 'Newman is not installed. Run: npm install -g newman'
}

$NpmPrefix = npm prefix -g
$NewmanJsPath = "$NpmPrefix\node_modules\newman\bin\newman.js"

if (-not (Test-Path $NewmanJsPath)) {
    throw "Newman executable not found at $NewmanJsPath. Check global npm installation."
}

$NewmanArgs = @("run", $Collection)

if (Test-Path $Environment) {
    Write-Host "---> Using environment: $Environment"
    $NewmanArgs += @("-e", $Environment)
}

if ($StressTest) {
    $Iterations = 1000
    $DelayMs = 50
    Write-Host "---> Stress test mode: $Iterations iterations, ${DelayMs}ms delay"
}

if ($Iterations -gt 1) {
    $NewmanArgs += @("--iteration-count", $Iterations)
}

$NewmanArgs += @("--delay-request", $DelayMs)
$NewmanArgs += @("--timeout-request", $TimeoutMs)

if ($HtmlReport) {
    $HtmlExtraInstalled = $false
    try {
        $HtmlExtraPath = "$NpmPrefix\node_modules\newman-reporter-htmlextra"
        if (Test-Path $HtmlExtraPath) {
            $HtmlExtraInstalled = $true
        }
    } catch {}

    if (-not (Test-Path $ReportDir)) {
        New-Item -ItemType Directory -Path $ReportDir -Force | Out-Null
    }
    $Timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $ReportFile = "$ReportDir\newman-report-$Timestamp.html"

    if ($HtmlExtraInstalled) {
        $NewmanArgs += @("--reporters", "cli,htmlextra")
        $NewmanArgs += @("--reporter-htmlextra-export", $ReportFile)
        Write-Host "---> HTML report (htmlextra) will be saved to: $ReportFile"
    } else {
        $NewmanArgs += @("--reporters", "cli,html")
        $NewmanArgs += @("--reporter-html-export", $ReportFile)
        Write-Host "---> HTML report (standard) will be saved to: $ReportFile"
        Write-Host "---> Tip: Install newman-reporter-htmlextra for better reports: npm install -g newman-reporter-htmlextra" -ForegroundColor Cyan
    }
}

Write-Host "---> Running: newman $($NewmanArgs -join ' ')"
Write-Host ""

node --no-deprecation $NewmanJsPath @NewmanArgs
$ExitCode = $LASTEXITCODE

Write-Host ""

if ($ExitCode -ne 0) {
    Write-Host "---> Newman completed with failed assertions (exit code: $ExitCode)" -ForegroundColor Yellow
    if ($HtmlReport) {
        Write-Host "---> HTML report was still generated at: $ReportFile" -ForegroundColor Green
    }
    Write-Host ""
    $Host.UI.RawUI.ForegroundColor = $Host.UI.RawUI.ForegroundColor
} else {
    Write-Host "---> Newman run completed successfully" -ForegroundColor Green
    Write-Host ""
    $Host.UI.RawUI.ForegroundColor = $Host.UI.RawUI.ForegroundColor
}

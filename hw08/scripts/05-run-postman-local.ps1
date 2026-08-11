param(
  [Alias('Collection')]
  [string[]]$Collections = @(
    '.\postman\local\otus-hw8-success.postman_collection.json',
    '.\postman\local\otus-hw8-failed-billing.postman_collection.json',
    '.\postman\local\otus-hw8-failed-warehouse.postman_collection.json',
    '.\postman\local\otus-hw8-failed-delivery.postman_collection.json',
    '.\postman\local\otus-hw8-cancel-conflict.postman_collection.json'
  ),
  [string]$Environment = '.\postman\local\local.postman_environment.json',
  [int]$Iterations = 1,
  [int]$DelayMs = 100,
  [int]$TimeoutMs = 30000,
  [string]$ReportDir = '.\reports\local',
  [switch]$StressTest,
  [switch]$HtmlReport
)

$ErrorActionPreference = 'Stop'

# Decode native command output (node/newman) as UTF-8, otherwise box-drawing
# characters and symbols in newman's CLI report turn into mojibake on RU Windows
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Write-Host '---> Checking newman'
try {
    $NewmanVersion = newman -v
    Write-Host "Newman version: $NewmanVersion"
} catch {
    throw 'Newman is not installed. Run: npm install -g newman'
}

# Resolve global npm prefix directly: default on Windows is %APPDATA%\npm.
# Do NOT use 'npm prefix -g' here: its stdout is UTF-8, PowerShell decodes it
# with the OEM codepage, and a Cyrillic user dir (e.g. C:\Users\Александр)
# gets mangled, so Test-Path fails.
$NpmPrefix = "$env:APPDATA\npm"
$NewmanJsPath = "$NpmPrefix\node_modules\newman\bin\newman.js"

if (-not (Test-Path $NewmanJsPath)) {
    # Fallback for a customized npm prefix: force UTF-8 decoding of npm output
    $prevEncoding = [Console]::OutputEncoding
    try {
        [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
        $NpmPrefix = (npm prefix -g).Trim()
    } finally {
        [Console]::OutputEncoding = $prevEncoding
    }
    $NewmanJsPath = "$NpmPrefix\node_modules\newman\bin\newman.js"
}

if (-not (Test-Path $NewmanJsPath)) {
    throw "Newman executable not found at $NewmanJsPath. Check global npm installation."
}

# Common newman arguments shared by every collection in this run
$CommonArgs = @()

if (Test-Path $Environment) {
    Write-Host "---> Using environment: $Environment"
    $CommonArgs += @("-e", $Environment)
} else {
    Write-Host "---> Environment file not found: $Environment (running without -e)" -ForegroundColor Yellow
}

if ($StressTest) {
    $Iterations = 1000
    $DelayMs = 50
    Write-Host "---> Stress test mode: $Iterations iterations, ${DelayMs}ms delay"
}

if ($Iterations -gt 1) {
    $CommonArgs += @("--iteration-count", $Iterations)
}

$CommonArgs += @("--delay-request", $DelayMs)
$CommonArgs += @("--timeout-request", $TimeoutMs)

$HtmlExtraInstalled = $false
$Timestamp = Get-Date -Format "yyyyMMdd-HHmmss"

if ($HtmlReport) {
    try {
        $HtmlExtraPath = "$NpmPrefix\node_modules\newman-reporter-htmlextra"
        if (Test-Path $HtmlExtraPath) {
            $HtmlExtraInstalled = $true
        }
    } catch {}

    if (-not (Test-Path $ReportDir)) {
        New-Item -ItemType Directory -Path $ReportDir -Force | Out-Null
    }

    if (-not $HtmlExtraInstalled) {
        Write-Host "---> Tip: install newman-reporter-htmlextra for better reports: npm install -g newman-reporter-htmlextra" -ForegroundColor Cyan
    }
}

Write-Host ""
Write-Host "---> Running $($Collections.Count) collection(s) sequentially"

$Results = @()

for ($i = 0; $i -lt $Collections.Count; $i++) {
    $Collection = $Collections[$i]

    Write-Host ""
    Write-Host "=================== [$($i + 1)/$($Collections.Count)] $Collection ===================" -ForegroundColor Cyan

    if (-not (Test-Path $Collection)) {
        Write-Host "---> Collection file not found: $Collection" -ForegroundColor Red
        $Results += [pscustomobject]@{ Collection = $Collection; ExitCode = -1 }
        continue
    }

    $NewmanArgs = @("run", $Collection) + $CommonArgs

    if ($HtmlReport) {
        $CollName = [System.IO.Path]::GetFileNameWithoutExtension($Collection) -replace '\.postman_collection$', ''
        $ReportFile = "$ReportDir\newman-report-$CollName-$Timestamp.html"

        if ($HtmlExtraInstalled) {
            $NewmanArgs += @("--reporters", "cli,htmlextra", "--reporter-htmlextra-export", $ReportFile)
        } else {
            $NewmanArgs += @("--reporters", "cli,html", "--reporter-html-export", $ReportFile)
        }
        Write-Host "---> HTML report will be saved to: $ReportFile"
    }

    Write-Host "---> Running: newman $($NewmanArgs -join ' ')"
    Write-Host ""

    node --no-deprecation $NewmanJsPath @NewmanArgs
    $ExitCode = $LASTEXITCODE

    $Results += [pscustomobject]@{ Collection = $Collection; ExitCode = $ExitCode }
}

Write-Host ""
Write-Host "=================== SUMMARY ===================" -ForegroundColor Cyan

foreach ($r in $Results) {
    if ($r.ExitCode -eq 0) {
        Write-Host ("  PASS  {0}" -f $r.Collection) -ForegroundColor Green
    } else {
        Write-Host ("  FAIL  {0} (exit code: {1})" -f $r.Collection, $r.ExitCode) -ForegroundColor Red
    }
}

Write-Host ""

$FailedCount = ($Results | Where-Object { $_.ExitCode -ne 0 }).Count

if ($FailedCount -gt 0) {
    Write-Host "---> $FailedCount of $($Results.Count) collection(s) completed with failed assertions" -ForegroundColor Yellow
    exit 1
} else {
    Write-Host "---> All $($Results.Count) collection(s) completed successfully" -ForegroundColor Green
    exit 0
}

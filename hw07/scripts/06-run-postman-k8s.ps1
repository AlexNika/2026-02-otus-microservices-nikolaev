param(
  [string]$SuccessCollection = '.\postman\otus-hw7-success-k8s.postman_collection.json',
  [string]$FailedCollection = '.\postman\otus-hw7-failed-k8s.postman_collection.json',
  [string]$Environment = '.\postman\k8s.postman_environment.json',
  [int]$Iterations = 1,
  [int]$DelayMs = 100,
  [int]$TimeoutMs = 30000,
  [string]$ReportDir = '.\reports',
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
$SuccessReport = "$ReportDir\newman-report-k8s-success-$Timestamp.html"
$FailedReport = "$ReportDir\newman-report-k8s-failed-$Timestamp.html"

function Run-Collection {
    param(
        [string]$Collection,
        [string]$ReportFile
    )
    $NewmanArgs = @("run", $Collection)

    if (Test-Path $Environment) {
        $NewmanArgs += @("-e", $Environment)
    }

    if ($Iterations -gt 1) {
        $NewmanArgs += @("--iteration-count", $Iterations)
    }

    $NewmanArgs += @("--delay-request", $DelayMs)
    $NewmanArgs += @("--timeout-request", $TimeoutMs)

    if ($HtmlReport) {
        if ($HtmlExtraInstalled) {
            $NewmanArgs += @("--reporters", "cli,htmlextra")
            $NewmanArgs += @("--reporter-htmlextra-export", $ReportFile)
        } else {
            $NewmanArgs += @("--reporters", "cli,html")
            $NewmanArgs += @("--reporter-html-export", $ReportFile)
        }
    }

    Write-Host ""
    Write-Host "---> Running: newman $($NewmanArgs -join ' ')"
    Write-Host ""

    # Out-Host: newman output streams to the console live and is NOT captured
    # into this function's return value (otherwise $LASTEXITCODE gets mixed
    # with the whole stdout and [math]::Max() downstream fails).
    node --no-deprecation $NewmanJsPath @NewmanArgs | Out-Host
    return $LASTEXITCODE
}

$SuccessExit = Run-Collection -Collection $SuccessCollection -ReportFile $SuccessReport
Write-Host ""
Write-Host "---> Success collection exit code: $SuccessExit" -ForegroundColor $(if ($SuccessExit -eq 0) { 'Green' } else { 'Red' })

$FailedExit = Run-Collection -Collection $FailedCollection -ReportFile $FailedReport
Write-Host ""
Write-Host "---> Failed collection exit code: $FailedExit" -ForegroundColor $(if ($FailedExit -eq 0) { 'Green' } else { 'Red' })

$FinalExit = [math]::Max($SuccessExit, $FailedExit)

Write-Host ""
if ($FinalExit -ne 0) {
    Write-Host "---> One or more collections failed (exit code: $FinalExit)" -ForegroundColor Red
} else {
    Write-Host "---> All collections passed successfully" -ForegroundColor Green
}

exit $FinalExit

param(
  [Alias('Collection')]
  [string[]]$Collections = @(
    '.\postman\k8s\otus-fp-success-k8s.postman_collection.json',
    '.\postman\k8s\otus-fp-failed-billing-k8s.postman_collection.json',
    '.\postman\k8s\otus-fp-failed-warehouse-k8s.postman_collection.json',
    '.\postman\k8s\otus-fp-failed-delivery-k8s.postman_collection.json',
    '.\postman\k8s\otus-fp-cancel-conflict-k8s.postman_collection.json',
    '.\postman\k8s\otus-fp-idempotency-k8s.postman_collection.json',
    '.\postman\k8s\otus-fp-user-sync-k8s.postman_collection.json'
  ),
  [string]$Environment = '.\postman\k8s\k8s.postman_environment.json',
  [string]$InternalApiKey = '',
  [int]$Iterations = 1,
  [int]$DelayMs = 600,
  [int]$TimeoutMs = 30000,
  [string]$ReportDir = '.\reports\k8s',
  [switch]$StressTest,
  [switch]$HtmlReport,
  [switch]$JsonReport
)

$ErrorActionPreference = 'Stop'

# .NET-API ([System.IO.File]::ReadAllText/WriteAllText) резолвит относительные пути
# от CWD процесса, а не от текущей локации PowerShell (Test-Path, newman). Если скрипт
# запущен из другого каталога, относительный $ReportDir укажет в несуществующий путь.
# Приводим $ReportDir к абсолютному сразу.
if (-not [System.IO.Path]::IsPathRooted($ReportDir)) {
    $ReportDir = Join-Path (Get-Location).ProviderPath $ReportDir
}
$ReportDir = [System.IO.Path]::GetFullPath($ReportDir)

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

# INTERNAL_API_KEY: коллекции обращаются к /internal/** warehouse/delivery с заголовком
# X-Internal-API-Key; без ключа эти эндпоинты отдают 401. Значение читается из fp/.env
# (или задаётся параметром -InternalApiKey) и передаётся newman через --env-var —
# в сами коллекции секрет не попадает. В консоль выводится только факт подгрузки, не значение.
function Get-InternalApiKeyFromEnvFile {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return $null }
    foreach ($rawLine in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $line = $rawLine.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { continue }
        if ($line -notmatch '^INTERNAL_API_KEY\s*=') { continue }
        $value = ($line -replace '^INTERNAL_API_KEY\s*=\s*', '').Trim()
        if ($value.Length -ge 2 -and
            (($value[0] -eq '"' -and $value[$value.Length - 1] -eq '"') -or
             ($value[0] -eq "'" -and $value[$value.Length - 1] -eq "'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if ($value -ne '') { return $value }
    }
    return $null
}

if ([string]::IsNullOrWhiteSpace($InternalApiKey)) {
    $EnvFile = '.\.env'
    if (-not (Test-Path $EnvFile)) {
        $EnvFile = Join-Path $PSScriptRoot '..\.env'
    }
    $InternalApiKey = Get-InternalApiKeyFromEnvFile -Path $EnvFile
    if ([string]::IsNullOrWhiteSpace($InternalApiKey)) {
        throw "INTERNAL_API_KEY not found in $EnvFile. Ключ обязателен: без него /internal/** отдают 401. Добавьте строку INTERNAL_API_KEY=<значение> в fp/.env или запустите скрипт с параметром -InternalApiKey <значение>."
    }
    Write-Host "---> INTERNAL_API_KEY loaded from $EnvFile (value is not printed)"
} else {
    Write-Host "---> INTERNAL_API_KEY taken from -InternalApiKey parameter"
}

# Common newman arguments shared by every collection in this run
$CommonArgs = @()

if (Test-Path $Environment) {
    Write-Host "---> Using environment: $Environment"
    $CommonArgs += @("-e", $Environment)
} else {
    Write-Host "---> Environment file not found: $Environment (running without -e)" -ForegroundColor Yellow
}

# Перезаписывает пустое значение internalApiKey из environment-файла
$CommonArgs += @("--env-var", "internalApiKey=$InternalApiKey")

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

    if (-not $HtmlExtraInstalled) {
        Write-Host "---> Tip: install newman-reporter-htmlextra for better reports: npm install -g newman-reporter-htmlextra" -ForegroundColor Cyan
    }
}

if (($HtmlReport -or $JsonReport) -and -not (Test-Path $ReportDir)) {
    New-Item -ItemType Directory -Path $ReportDir -Force | Out-Null
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

    $CollName = [System.IO.Path]::GetFileNameWithoutExtension($Collection) -replace '\.postman_collection$', ''
    $ReportFile = $null
    $JsonFile = $null
    $Reporters = @("cli")

    if ($HtmlReport) {
        $ReportFile = "$ReportDir\newman-report-$CollName-$Timestamp.html"
        if ($HtmlExtraInstalled) {
            $Reporters += "htmlextra"
        } else {
            $Reporters += "html"
        }
        Write-Host "---> HTML report will be saved to: $ReportFile"
    }

    if ($JsonReport) {
        $JsonFile = "$ReportDir\newman-json-$CollName-$Timestamp.json"
        $Reporters += "json"
        Write-Host "---> JSON report will be saved to: $JsonFile"
    }

    if ($Reporters.Count -gt 1) {
        $NewmanArgs += @("--reporters", ($Reporters -join ","))
        if ($HtmlReport) {
            if ($HtmlExtraInstalled) {
                $NewmanArgs += @("--reporter-htmlextra-export", $ReportFile)
            } else {
                $NewmanArgs += @("--reporter-html-export", $ReportFile)
            }
        }
        if ($JsonReport) {
            $NewmanArgs += @("--reporter-json-export", $JsonFile)
        }
    }

    $DisplayArgs = @($NewmanArgs)
    for ($di = 0; $di -lt $DisplayArgs.Count; $di++) {
        if ($DisplayArgs[$di] -like 'internalApiKey=*') { $DisplayArgs[$di] = 'internalApiKey=***' }
    }
    Write-Host "---> Running: newman $($DisplayArgs -join ' ')"
    Write-Host ""

    node --no-deprecation $NewmanJsPath @NewmanArgs
    $ExitCode = $LASTEXITCODE

    # В коллекциях стоит только плейсхолдер {{internalApiKey}}, но репортеры (htmlextra/json)
    # записывают в отчёт заголовки запросов с подставленными значениями. Перед пушем отчётов
    # в git вымарываем значение ключа, чтобы секрет не утекал через reports/.
    foreach ($MaskTarget in @($ReportFile, $JsonFile)) {
        if ($MaskTarget -and (Test-Path $MaskTarget)) {
            $ReportText = [System.IO.File]::ReadAllText($MaskTarget)
            if ($ReportText.Contains($InternalApiKey)) {
                $ReportText = $ReportText.Replace($InternalApiKey, '***')
                [System.IO.File]::WriteAllText($MaskTarget, $ReportText)
                Write-Host "---> INTERNAL_API_KEY value masked in report: $MaskTarget"
            }
        }
    }

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

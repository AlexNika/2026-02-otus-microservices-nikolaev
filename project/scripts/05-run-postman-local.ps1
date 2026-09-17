param(
  [Alias('Collection')]
  [string[]]$Collections = @(
    '.\postman\local\otus-fp-success.postman_collection.json',
    '.\postman\local\otus-fp-failed-billing.postman_collection.json',
    '.\postman\local\otus-fp-failed-warehouse.postman_collection.json',
    '.\postman\local\otus-fp-failed-delivery.postman_collection.json',
    '.\postman\local\otus-fp-cancel-conflict.postman_collection.json',
    '.\postman\local\otus-fp-idempotency.postman_collection.json',
    '.\postman\local\otus-fp-user-sync.postman_collection.json',
    # Circuit Breaker (Resilience4j + Chaos Monkey): требует перезапуска
    # BILLING/WAREHOUSE/DELIVERY с CHAOS_MONKEY_ENABLED=true (см. .env).
    '.\postman\local\otus-fp-circuit-breaker.postman_collection.json'
  ),
  [string]$Environment = '.\postman\local\local.postman_environment.json',
  [string]$InternalApiKey = '',
  [int]$Iterations = 1,
  [int]$DelayMs = 100,
  [int]$TimeoutMs = 30000,
  [string]$ReportDir = '.\reports\local',
  [switch]$StressTest,
  [switch]$HtmlReport
)

$ErrorActionPreference = 'Stop'

# .NET-API ([System.IO.File]::ReadAllText/WriteAllText) резолвит относительные пути
# от CWD процесса, а не от текущей локации PowerShell (Test-Path, newman). Если скрипт
# запущен из другого каталога (например charts\monitoring), относительный $ReportDir
# укажет в несуществующий путь. Приводим $ReportDir к абсолютному сразу.
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

# --- ORDERService restart for the Circuit Breaker collection -------------------
# Коллекция отус-фп-circuit-breaker детерминирована только при свежих окнах
# circuit breaker'ов Resilience4j: коллекции otus-fp-failed-* записывают в те же
# окна транзиентные отказы (3 записи на заказ из-за ретраев), и без рестарта
# окна приходят к коллекции уже заполненными — цепь открывается раньше, чем
# ожидает сценарий. Перед этой коллекцией перезапускаем ORDERService из jar.
function Get-EnvFileVariables {
    param([string]$Path)
    $result = @{}
    if (-not (Test-Path $Path)) { return $result }
    foreach ($rawLine in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $line = $rawLine.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { continue }
        $idx = $line.IndexOf('=')
        if ($idx -le 0) { continue }
        $key = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim()
        if ($value.Length -ge 2 -and
            (($value[0] -eq '"' -and $value[$value.Length - 1] -eq '"') -or
             ($value[0] -eq "'" -and $value[$value.Length - 1] -eq "'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $result[$key] = $value
    }
    return $result
}

# Состояние рестарта/восстановления ORDERService (используется парой
# Restart-OrderServiceFresh / Restore-OrderServiceOriginal).
$script:OriginalOrderExe = $null
$script:OriginalOrderCmdLine = $null
$script:ScriptOrderPid = $null
$script:OrderServiceWasRestarted = $false
$script:CbEnvVars = @{}

# Запускает процесс java полностью отсоединённым от текущей сессии через WMI
# (Win32_Process.Create): родитель — WmiPrvSE, наследования консольных/пайповых
# хендлов сессии нет. Без этого долгоживущий фоновый ORDERService держит хендл
# вывода терминала, и запуск скрипта «висит» после завершения даже при exit 0.
# Переменные окружения передаются внутрь wrapper-скрипта (encoded command),
# вывод целевого процесса перенаправляется в файлы, PID пишется в PidFile.
function Start-OrderServiceDetached {
    param(
        [string]$ExePath,
        [string]$Arguments,
        [hashtable]$EnvVars,
        [string]$OutLog,
        [string]$ErrLog,
        [string]$PidFile
    )

    if (Test-Path -LiteralPath $PidFile) { Remove-Item -LiteralPath $PidFile -Force }

    $sb = [System.Text.StringBuilder]::new()
    foreach ($k in $EnvVars.Keys) {
        $kk = $k -replace "'", "''"
        $vv = "$($EnvVars[$k])" -replace "'", "''"
        [void]$sb.AppendLine("[Environment]::SetEnvironmentVariable('$kk', '$vv', 'Process')")
    }
    $exeEsc = $ExePath -replace "'", "''"
    $argEsc = $Arguments -replace "'", "''"
    $outEsc = $OutLog -replace "'", "''"
    $errEsc = $ErrLog -replace "'", "''"
    $pidEsc = $PidFile -replace "'", "''"
    [void]$sb.AppendLine("`$p = Start-Process -FilePath '$exeEsc' -ArgumentList '$argEsc' -RedirectStandardOutput '$outEsc' -RedirectStandardError '$errEsc' -WindowStyle Hidden -PassThru")
    [void]$sb.AppendLine("`$p.Id | Out-File -FilePath '$pidEsc' -Encoding ascii")

    $encoded = [Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes($sb.ToString()))
    $pwshExe = (Get-Process -Id $PID).Path
    $cimResult = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
        CommandLine = "`"$pwshExe`" -NoProfile -NonInteractive -WindowStyle Hidden -EncodedCommand $encoded"
    }
    if ($cimResult.ReturnValue -ne 0) { throw "Win32_Process.Create failed with code $($cimResult.ReturnValue)" }

    # Ждём, пока wrapper запишет PID целевого процесса (не более 30 с)
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $PidFile) {
            $text = (Get-Content -LiteralPath $PidFile -Raw).Trim()
            if ($text -match '^\d+$') { return [int]$text }
        }
        Start-Sleep -Milliseconds 500
    }
    return $null
}

function Restart-OrderServiceFresh {
    param(
        [string]$ProjectRoot,
        [hashtable]$EnvVars
    )

    $port = 8002
    if ($EnvVars['SERVER_PORT_ORDER_SERVICE']) { $port = [int]$EnvVars['SERVER_PORT_ORDER_SERVICE'] }

    $conn = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($conn) {
        $origPid = $conn.OwningProcess
        # Запоминаем точную командную строку исходного процесса (например, запущенного
        # из IntelliJ IDEA), чтобы после коллекции circuit-breaker восстановить его,
        # а не оставлять в фоне jar-инстанс, запущенный скриптом.
        $origProc = Get-CimInstance Win32_Process -Filter "ProcessId=$origPid" -ErrorAction SilentlyContinue
        if ($origProc -and $origProc.CommandLine) {
            $script:OriginalOrderExe = $origProc.ExecutablePath
            $script:OriginalOrderCmdLine = $origProc.CommandLine
        } else {
            $script:OriginalOrderExe = $null
            $script:OriginalOrderCmdLine = $null
        }
        Write-Host "---> Stopping ORDERService (PID $origPid) on port $port"
        Stop-Process -Id $origPid -Force
        Start-Sleep 2
    }

    $jarCandidates = @(Get-ChildItem (Join-Path $ProjectRoot 'ORDERService\build\libs') -Filter 'ORDERService-*.jar' -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike '*-plain.jar' })
    if ($jarCandidates.Count -eq 0) {
        Write-Host "---> ORDERService jar not found, building :ORDERService:bootJar ..."
        & (Join-Path $ProjectRoot 'gradlew.bat') :ORDERService:bootJar -x checkstyleMain -x checkstyleTest --console=plain -q
        if ($LASTEXITCODE -ne 0) { throw "Failed to build ORDERService bootJar (exit $LASTEXITCODE)" }
        $jarCandidates = @(Get-ChildItem (Join-Path $ProjectRoot 'ORDERService\build\libs') -Filter 'ORDERService-*.jar' | Where-Object { $_.Name -notlike '*-plain.jar' })
    }
    $jar = $jarCandidates[0].FullName

    $javaExe = (Get-Command java -ErrorAction SilentlyContinue).Source
    if (-not $javaExe) { throw "java is not on PATH: cannot restart ORDERService" }

    $logDir = Join-Path $ProjectRoot 'reports\services'
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
    # Кавычки вокруг пути обязательны: в пути проекта есть пробелы, без них
    # Start-Process разбивает аргумент и java получает обрезанный путь.
    $script:ScriptOrderPid = Start-OrderServiceDetached -ExePath $javaExe -Arguments "-jar `"$jar`"" -EnvVars $EnvVars -OutLog (Join-Path $logDir 'order.log') -ErrLog (Join-Path $logDir 'order.err.log') -PidFile (Join-Path $logDir 'order.pid')
    Write-Host "---> ORDERService is restarting from $jar (fresh circuit breakers)"

    Wait-OrderServiceHealthy -Port $port
}

function Wait-OrderServiceHealthy {
    param([int]$Port)
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        try {
            $health = Invoke-WebRequest -Uri "http://localhost:$Port/actuator/health" -UseBasicParsing -TimeoutSec 3
            if ($health.StatusCode -eq 200) {
                Write-Host "---> ORDERService is UP on port $Port"
                return
            }
        } catch { }
        Start-Sleep 3
    }
    throw "ORDERService did not become healthy within 120s"
}

# Возвращает окружение в исходное состояние после коллекции circuit-breaker:
# останавливает jar-инстанс, запущенный скриптом, и поднимает процесс с той же
# командной строкой, которая была у ORDERService до рестарта (например, запуск
# из IntelliJ IDEA). Если исходный процесс не был зафиксирован (порт до рестарта
# был свободен), jar-инстанс остаётся работать.
function Restore-OrderServiceOriginal {
    param(
        [string]$ProjectRoot,
        [hashtable]$EnvVars
    )

    $port = 8002
    if ($EnvVars['SERVER_PORT_ORDER_SERVICE']) { $port = [int]$EnvVars['SERVER_PORT_ORDER_SERVICE'] }

    if (-not $script:OriginalOrderCmdLine) {
        Write-Host "---> Original ORDERService command line was not captured (port $port was free before restart); leaving the script-started instance running" -ForegroundColor Yellow
        return
    }

    # Останавливаем инстанс, запущенный скриптом
    $toStop = @()
    if ($script:ScriptOrderPid -and (Get-Process -Id $script:ScriptOrderPid -ErrorAction SilentlyContinue)) {
        $toStop += $script:ScriptOrderPid
    } else {
        $conn = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($conn) { $toStop += $conn.OwningProcess }
    }
    foreach ($pidToStop in ($toStop | Select-Object -Unique)) {
        Write-Host "---> Stopping script-started ORDERService (PID $pidToStop) on port $port"
        Stop-Process -Id $pidToStop -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep 2
    $script:ScriptOrderPid = $null

    # Разбираем сохранённую командную строку: первый токен в кавычках — executable, остальное — аргументы
    $cmdLine = $script:OriginalOrderCmdLine.Trim()
    if ($cmdLine.StartsWith('"')) {
        $endQuote = $cmdLine.IndexOf('"', 1)
        if ($endQuote -lt 0) { throw "Cannot parse original ORDERService command line: $cmdLine" }
        $exe = $cmdLine.Substring(1, $endQuote - 1)
        $exeArgs = $cmdLine.Substring($endQuote + 1).Trim()
    } else {
        $spaceIdx = $cmdLine.IndexOf(' ')
        if ($spaceIdx -lt 0) { $exe = $cmdLine; $exeArgs = '' }
        else { $exe = $cmdLine.Substring(0, $spaceIdx); $exeArgs = $cmdLine.Substring($spaceIdx + 1).Trim() }
    }
    if (-not $exe) { $exe = $script:OriginalOrderExe }
    if (-not $exe -or -not (Test-Path $exe)) { throw "Original ORDERService executable not found: '$exe'" }

    $logDir = Join-Path $ProjectRoot 'reports\services'
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null

    Write-Host "---> Restoring original ORDERService: $exe"
    $script:ScriptOrderPid = Start-OrderServiceDetached -ExePath $exe -Arguments $exeArgs -EnvVars $EnvVars -OutLog (Join-Path $logDir 'order-original.log') -ErrLog (Join-Path $logDir 'order-original.err.log') -PidFile (Join-Path $logDir 'order-original.pid')

    Wait-OrderServiceHealthy -Port $port
    Write-Host "---> Original ORDERService restored (PID $($script:ScriptOrderPid)); logs: $logDir\order-original.log"
}

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

    if ($Collection -like '*circuit-breaker*') {
        Write-Host "---> Circuit Breaker collection: restarting ORDERService for fresh circuit breaker windows"
        $cbEnvFile = '.\.env'
        if (-not (Test-Path $cbEnvFile)) { $cbEnvFile = Join-Path $PSScriptRoot '..\.env' }
        $script:CbEnvVars = Get-EnvFileVariables -Path $cbEnvFile
        Restart-OrderServiceFresh -ProjectRoot (Split-Path -Parent $PSScriptRoot) -EnvVars $script:CbEnvVars
        $script:OrderServiceWasRestarted = $true
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

    $DisplayArgs = @($NewmanArgs)
    for ($di = 0; $di -lt $DisplayArgs.Count; $di++) {
        if ($DisplayArgs[$di] -like 'internalApiKey=*') { $DisplayArgs[$di] = 'internalApiKey=***' }
    }
    Write-Host "---> Running: newman $($DisplayArgs -join ' ')"
    Write-Host ""

    node --no-deprecation $NewmanJsPath @NewmanArgs
    $ExitCode = $LASTEXITCODE

    # После коллекции circuit-breaker возвращаем окружение в исходное состояние:
    # останавливаем jar-инстанс скрипта и поднимаем ORDERService с исходной
    # командной строкой (той, что была до рестарта — например, из IntelliJ IDEA).
    if ($Collection -like '*circuit-breaker*' -and $script:OrderServiceWasRestarted) {
        $script:OrderServiceWasRestarted = $false
        try {
            Restore-OrderServiceOriginal -ProjectRoot (Split-Path -Parent $PSScriptRoot) -EnvVars $script:CbEnvVars
        } catch {
            Write-Host "---> WARNING: failed to restore original ORDERService: $($_.Exception.Message)" -ForegroundColor Yellow
        }
    }

    # В коллекциях стоит только плейсхолдер {{internalApiKey}}, но htmlextra записывает
    # в отчёт заголовки запросов с подставленными значениями. Перед пушем отчётов в git
    # вымарываем значение ключа, чтобы секрет не утекал через reports/.
    if ($HtmlReport -and (Test-Path $ReportFile)) {
        $ReportText = [System.IO.File]::ReadAllText($ReportFile)
        if ($ReportText.Contains($InternalApiKey)) {
            $ReportText = $ReportText.Replace($InternalApiKey, '***')
            [System.IO.File]::WriteAllText($ReportFile, $ReportText)
            Write-Host "---> INTERNAL_API_KEY value masked in report: $ReportFile"
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

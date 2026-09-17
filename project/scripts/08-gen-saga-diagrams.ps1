# Генерация Mermaid-диаграмм saga-прогонов Postman (success + failed-billing) и рендер PNG.
#
# Диаграммы строятся по последним newman JSON-отчётам (scripts/06-run-postman-k8s.ps1 -JsonReport):
#   reports/k8s/newman-json-otus-fp-success-k8s-*.json
#   reports/k8s/newman-json-otus-fp-failed-billing-k8s-*.json
#
# Результат в ./diagrams: saga-success-sequence.{md,mmd,png,svg}, saga-failed-billing-sequence.{md,mmd,png,svg},
# saga-flow.{md,mmd,png,svg}, README.md. Логика сборки — в gen-saga-diagrams.mjs (node).
# PNG рендерится в максимальном разрешении: useMaxWidth=false (естественный размер диаграммы,
# см. mmdc-config.json) + авто-scale до предела растрирования Chrome; SVG — векторная копия для зума.
param(
  [string]$ReportDir = '.\reports\k8s',
  [string]$OutDir = '.\diagrams',
  [string]$SuccessJson = '',
  [string]$FailedJson = '',
  [int]$PngScale = 8,
  [switch]$SkipPng
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Find-LatestJsonReport {
    param([string]$Pattern)
    $file = Get-ChildItem -Path $ReportDir -Filter $Pattern -File -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $file) {
        throw "Не найден JSON-отчёт '$Pattern' в $ReportDir. Сначала выполните прогон:" +
            " .\scripts\06-run-postman-k8s.ps1 -Collections <success>,<failed-billing> -JsonReport"
    }
    return $file.FullName
}

if (-not $SuccessJson) { $SuccessJson = Find-LatestJsonReport 'newman-json-otus-fp-success-k8s-*.json' }
if (-not $FailedJson) { $FailedJson = Find-LatestJsonReport 'newman-json-otus-fp-failed-billing-k8s-*.json' }

Write-Host "---> success JSON: $SuccessJson"
Write-Host "---> failed JSON:  $FailedJson"
Write-Host "---> out dir:      $OutDir"

$Generator = Join-Path $PSScriptRoot 'gen-saga-diagrams.mjs'
node --no-deprecation $Generator --success $SuccessJson --failed $FailedJson --out $OutDir
if ($LASTEXITCODE -ne 0) { throw "gen-saga-diagrams.mjs завершился с кодом $LASTEXITCODE" }

if ($SkipPng) {
    Write-Host "---> PNG/SVG rendering skipped (-SkipPng)"
    Write-Host "---> Done (md/mmd generated)"
    exit 0
}

# mermaid-cli рендерит через puppeteer: нужен chrome-headless-shell в кэше, ставим одноразово при отсутствии
$PuppeteerCache = Join-Path $env:USERPROFILE '.cache\puppeteer\chrome-headless-shell'
if (-not (Test-Path $PuppeteerCache)) {
    Write-Host "---> installing chrome-headless-shell for mermaid-cli (one-time, ~150MB)"
    npx -y puppeteer browsers install chrome-headless-shell
    if ($LASTEXITCODE -ne 0) { throw "Не удалось установить chrome-headless-shell для рендера PNG" }
}

# puppeteer-конфиг для mermaid-cli (fallback на --no-sandbox, если первый рендер упал)
$PuppeteerCfg = Join-Path $env:TEMP 'mmdc-puppeteer-config.json'
Set-Content -LiteralPath $PuppeteerCfg -Value '{"args":["--no-sandbox","--disable-gpu"]}' -Encoding UTF8

function Get-PngSize {
    param([string]$File)
    $fsStream = [System.IO.File]::OpenRead($File)
    try {
        $buf = New-Object byte[] 24
        $null = $fsStream.Read($buf, 0, 24)
    } finally { $fsStream.Dispose() }
    if ($buf[1] -ne 0x50 -or $buf[2] -ne 0x4E -or $buf[3] -ne 0x47) { throw "$File — не PNG" }
    # IHDR width/height: байты 16..23, big-endian
    $w = ([int]$buf[16] -shl 24) -bor ([int]$buf[17] -shl 16) -bor ([int]$buf[18] -shl 8) -bor [int]$buf[19]
    $h = ([int]$buf[20] -shl 24) -bor ([int]$buf[21] -shl 16) -bor ([int]$buf[22] -shl 8) -bor [int]$buf[23]
    return [pscustomobject]@{ Width = $w; Height = $h }
}

# Конфиг mermaid: useMaxWidth=false — диаграмма рендерится в естественном размере (раньше она
# вжималась в дефолтный viewport ~800px, из-за чего надписи в PNG были нечитаемыми).
# $PngScale — потолок; фактический scale вычисляется автоматически как максимальный, при котором
# результат остаётся ниже лимита растрирования Chrome (~16384px на сторону; запас — $MaxDimension).
$MmdcConfig = Join-Path $PSScriptRoot 'mmdc-config.json'
$MaxDimension = 14000

$Diagrams = @('saga-success-sequence', 'saga-failed-billing-sequence', 'saga-flow')
foreach ($name in $Diagrams) {
    $mmd = Join-Path $OutDir "$name.mmd"
    $png = Join-Path $OutDir "$name.png"
    $svg = Join-Path $OutDir "$name.svg"

    # пробный рендер scale=1, чтобы узнать естественный размер диаграммы
    Write-Host "---> probe render (scale 1): $name"
    npx -y "@mermaid-js/mermaid-cli" -i $mmd -o $png -c $MmdcConfig -b white -s 1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "---> retry with puppeteer config (--no-sandbox)" -ForegroundColor Yellow
        npx -y "@mermaid-js/mermaid-cli" -p $PuppeteerCfg -i $mmd -o $png -c $MmdcConfig -b white -s 1
        if ($LASTEXITCODE -ne 0) { throw "mermaid-cli не смог отрендерить $mmd (см. вывод выше)" }
    }
    $sz = Get-PngSize -File $png
    $scale = [Math]::Min($PngScale, [Math]::Max(1, [Math]::Floor([Math]::Min(
        $MaxDimension / $sz.Width, $MaxDimension / $sz.Height))))
    Write-Host "---> natural size: $($sz.Width)x$($sz.Height) px -> PNG scale x$scale"

    if ($scale -gt 1) {
        npx -y "@mermaid-js/mermaid-cli" -i $mmd -o $png -c $MmdcConfig -b white -s $scale
        if ($LASTEXITCODE -ne 0) {
            npx -y "@mermaid-js/mermaid-cli" -p $PuppeteerCfg -i $mmd -o $png -c $MmdcConfig -b white -s $scale
            if ($LASTEXITCODE -ne 0) { throw "mermaid-cli не смог отрендерить PNG (scale x$scale) для $mmd" }
        }
        $final = Get-PngSize -File $png
        Write-Host "---> PNG: $png ($($final.Width)x$($final.Height) px, $([Math]::Round((Get-Item $png).Length / 1KB))KB)"
    } else {
        Write-Host "---> PNG: $png ($($sz.Width)x$($sz.Height) px, scale 1 — диаграмма уже у предела)"
    }

    Write-Host "---> rendering SVG (векторный, зум без потерь): $svg"
    npx -y "@mermaid-js/mermaid-cli" -i $mmd -o $svg -c $MmdcConfig -b white
    if ($LASTEXITCODE -ne 0) { throw "mermaid-cli не смог отрендерить SVG для $mmd" }
}

Write-Host "---> Done: $($Diagrams.Count) диаграммы (md + mmd + PNG в максимальном разрешении + SVG) в $OutDir"
exit 0

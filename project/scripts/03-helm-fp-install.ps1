param(
  [string]$ChartPath = './charts/finalproject',
  [string]$ValuesFile = './charts/finalproject/values-secret.yaml',
  [string]$MonitoringChartPath = './charts/monitoring',
  [string]$LoggingChartPath = './charts/logging',
  [string]$TracingChartPath = './charts/tracing',
  [string]$IngressChartPath = './charts/ingress-nginx',
  [switch]$SkipPreload
)

$ErrorActionPreference = 'Stop'

# Образы, которые чарты тянут из внешних реестров.
$RegistryImages = @(
  'grafana/loki:3.6.11',
  'grafana/alloy:v1.12.0',
  'postgres:16-alpine',
  'rabbitmq:4.1.3-management',
  'busybox:1.36'
)

# Локально собранные образы микросервисов (scripts/01-build-and-push.ps1).
$FpImages = @(
  'akinxela/otusapp:fp-auth',
  'akinxela/otusapp:fp-user',
  'akinxela/otusapp:fp-billing',
  'akinxela/otusapp:fp-order',
  'akinxela/otusapp:fp-notification',
  'akinxela/otusapp:fp-warehouse',
  'akinxela/otusapp:fp-delivery'
)

$releases = @(
  @{ Name = 'ingress-nginx'; Namespace = 'ingress-nginx';        Chart = $IngressChartPath },
  @{ Name = 'finalproject';  Namespace = 'otus-msa-fp';          Chart = $ChartPath },
  @{ Name = 'monitoring';    Namespace = 'otus-msa-monitoring';  Chart = $MonitoringChartPath },
  @{ Name = 'tracing';       Namespace = 'otus-msa-tracing';     Chart = $TracingChartPath },
  @{ Name = 'logging';       Namespace = 'otus-msa-logging';     Chart = $LoggingChartPath }
)

# Функция для получения списка уже загруженных образов в minikube
function Get-MinikubeImages {
  $list = minikube image ls 2>$null
  if ($LASTEXITCODE -ne 0) { return @() }
  return @(
  $list |
          ForEach-Object { ($_ -split '\s+')[-1] } |
          Where-Object { $_ } |
          ForEach-Object { $_ -replace '^docker\.io/library/', '' -replace '^docker\.io/', '' }
  )
}

function Invoke-ImagePreload {
  $alreadyLoaded = Get-MinikubeImages

  Write-Host '---> Pre-loading registry images into minikube'
  foreach ($image in $RegistryImages) {
    if ($alreadyLoaded -contains $image) {
      Write-Host "     [SKIP] already loaded: $image"
      continue
    }

    Write-Host "     [1/2] docker pull $image"
    docker pull $image
    if ($LASTEXITCODE -ne 0) {
      throw "CRITICAL: docker pull failed for $image. Check your internet connection or image tag."
    }

    Write-Host "     [2/2] minikube image load $image"
    minikube image load $image
    if ($LASTEXITCODE -ne 0) {
      throw "CRITICAL: minikube image load failed for $image."
    }

    Write-Host "     [OK] loaded: $image"
  }

  Write-Host '---> Loading locally built FP microservice images into minikube'
  foreach ($image in $FpImages) {
    if ($alreadyLoaded -contains $image) {
      Write-Host "     [SKIP] already loaded: $image"
      continue
    }

    # Проверяем, существует ли образ локально в Docker-демоне хоста
    $null = docker image inspect $image 2>$null
    if ($LASTEXITCODE -ne 0) {
      throw "CRITICAL: FP image not found in local Docker: $image. Please run 'scripts/01-build-and-push.ps1' first."
    }

    Write-Host "     [1/1] minikube image load $image"
    minikube image load $image
    if ($LASTEXITCODE -ne 0) {
      throw "CRITICAL: minikube image load failed for $image."
    }

    Write-Host "     [OK] loaded: $image"
  }

  Write-Host '---> Image preloading complete'
}

# --- Валидация входных данных ---
if (-not (Test-Path -LiteralPath $ValuesFile)) {
  throw "Values file not found: $ValuesFile"
}

foreach ($release in $releases) {
  if (-not (Test-Path -LiteralPath $release.Chart)) {
    throw "Chart directory not found: $($release.Chart)"
  }
}

# --- Предзагрузка образов ---
if (-not $SkipPreload) {
  Invoke-ImagePreload
} else {
  Write-Host '---> Skipping image preloading (-SkipPreload)'
}

# --- Деплой Helm релизов ---
foreach ($release in $releases) {
  Write-Host "---> Ensuring namespace '$($release.Namespace)'"
  kubectl create namespace $release.Namespace --dry-run=client -o yaml | kubectl apply -f - | Out-Null
  if ($LASTEXITCODE -ne 0) {
    throw "Failed to ensure namespace '$($release.Namespace)'"
  }

  $existingRelease = helm list --namespace $release.Namespace --filter "^$($release.Name)$" --short 2>$null
  if ($LASTEXITCODE -ne 0) {
    throw "Failed to list Helm releases in '$($release.Namespace)'"
  }

  $helmCommand = if ([string]::IsNullOrWhiteSpace($existingRelease)) { 'install' } else { 'upgrade' }

  Write-Host "---> Running helm $helmCommand '$($release.Name)' in '$($release.Namespace)'"
  helm $helmCommand $release.Name $release.Chart -f $ValuesFile --namespace $release.Namespace --create-namespace --wait --timeout 10m

  if ($LASTEXITCODE -ne 0) {
    throw "CRITICAL: helm $helmCommand failed for release '$($release.Name)'. Check Helm output above."
  }
}

Write-Host '---> Current Helm releases'
helm list --all-namespaces
Write-Host '---> Deployment completed successfully!' -ForegroundColor Green
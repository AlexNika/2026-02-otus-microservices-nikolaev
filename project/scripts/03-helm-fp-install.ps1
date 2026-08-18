param(
  [string]$ReleaseName = 'fp',
  [string]$ChartPath = './fpchart',
  [string]$ValuesFile = './fpchart/values-secret.yaml',
  [string]$Namespace = 'default'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $ChartPath)) {
  throw "Chart directory not found: $ChartPath"
}

if (-not (Test-Path $ValuesFile)) {
  throw "Values file not found: $ValuesFile"
}

if ($Namespace -ne 'default') {
  Write-Host "---> Checking namespace '$Namespace'"
  $nsExists = kubectl get namespace "$Namespace" --ignore-not-found=true --output=jsonpath="{.metadata.name}" 2>$null

  if ([string]::IsNullOrWhiteSpace($nsExists)) {
    Write-Host "     Namespace '$Namespace' not found. Creating..."
    kubectl create namespace "$Namespace"
    if ($LASTEXITCODE -ne 0) { throw "Failed to create namespace '$Namespace'" }
    Write-Host "     Namespace created."
  } else {
    Write-Host "     Namespace '$Namespace' already exists."
  }
} else {
  Write-Host "---> Using default namespace (no check/create needed)"
}

Write-Host '---> Checking if Helm release already exists'
$existingRelease = helm list --namespace $Namespace --filter "^$ReleaseName$" --short 2>$null
if ($LASTEXITCODE -ne 0) {
  throw 'Failed to list Helm releases'
}

if ([string]::IsNullOrWhiteSpace($existingRelease)) {
  Write-Host "---> Release '$ReleaseName' not found. Running helm install"
  Write-Host "     Chart:      $ChartPath"
  Write-Host "     Values:     $ValuesFile"
  Write-Host "     Namespace:  $Namespace"

  helm install $ReleaseName $ChartPath -f $ValuesFile --namespace $Namespace
  if ($LASTEXITCODE -ne 0) { throw 'helm install failed' }

  Write-Host "Done. Release '$ReleaseName' installed successfully."
} else {
  Write-Host "---> Release '$ReleaseName' already exists. Running helm upgrade"
  Write-Host "     Chart:      $ChartPath"
  Write-Host "     Values:     $ValuesFile"
  Write-Host "     Namespace:  $Namespace"

  helm upgrade $ReleaseName $ChartPath -f $ValuesFile --namespace $Namespace
  if ($LASTEXITCODE -ne 0) { throw 'helm upgrade failed' }

  Write-Host "Done. Release '$ReleaseName' upgraded successfully."
}

Write-Host '---> Current Helm releases'
helm list --namespace $Namespace

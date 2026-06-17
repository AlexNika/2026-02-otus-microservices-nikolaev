param(
  [string]$ReleaseName = 'hw06',
  [string]$ChartPath = './hw04chart',
  [string]$ValuesFile = './hw04chart/values-secret.yaml',
  [string]$Namespace = 'default'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $ChartPath)) {
  throw "Chart directory not found: $ChartPath"
}

if (-not (Test-Path $ValuesFile)) {
  throw "Values file not found: $ValuesFile"
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

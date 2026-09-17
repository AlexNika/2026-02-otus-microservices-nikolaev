param(
  [string]$ChartPath = './charts/finalproject',
  [string]$MonitoringChartPath = './charts/monitoring',
  [string]$LoggingChartPath = './charts/logging',
  [string]$TracingChartPath = './charts/tracing',
  [string]$IngressChartPath = './charts/ingress-nginx'
)

$ErrorActionPreference = 'Stop'

$releases = @(
  @{ Name = 'finalproject'; Namespace = 'otus-msa-fp' },
  @{ Name = 'monitoring'; Namespace = 'otus-msa-monitoring' },
  @{ Name = 'logging'; Namespace = 'otus-msa-logging' },
  @{ Name = 'tracing'; Namespace = 'otus-msa-tracing' },
  @{ Name = 'ingress-nginx'; Namespace = 'ingress-nginx' }
)

foreach ($release in $releases) {
  Write-Host "---> Checking release '$($release.Name)' in namespace '$($release.Namespace)'"
  $existingRelease = helm list --namespace $release.Namespace --filter "^$($release.Name)$" --short 2>$null
  if ($LASTEXITCODE -ne 0) { throw "Failed to list Helm releases in '$($release.Namespace)'" }

  if ([string]::IsNullOrWhiteSpace($existingRelease)) {
    Write-Host "     Release not found; nothing to uninstall."
    continue
  }

  Write-Host "---> Uninstalling release '$($release.Name)'"
  helm uninstall $release.Name --namespace $release.Namespace --wait
  if ($LASTEXITCODE -ne 0) { throw "helm uninstall failed for '$($release.Name)'" }
  kubectl delete pvc -l app.kubernetes.io/instance=$release.Name --namespace $release.Namespace --ignore-not-found=true
}

Write-Host '---> Current Helm releases'
helm list --all-namespaces

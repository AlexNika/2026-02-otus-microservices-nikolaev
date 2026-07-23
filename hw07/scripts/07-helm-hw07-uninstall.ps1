param(
  [string]$ReleaseName = 'hw07',
  [string]$Namespace = 'default'
)

$ErrorActionPreference = 'Stop'

Write-Host '---> Checking if Helm release exists'
$existingRelease = helm list --namespace $Namespace --filter "^$ReleaseName$" --short 2>$null
if ($LASTEXITCODE -ne 0) {
  throw 'Failed to list Helm releases'
}

if ([string]::IsNullOrWhiteSpace($existingRelease)) {
  Write-Host "---> Release '$ReleaseName' not found in namespace '$Namespace'. Nothing to uninstall."
} else {
  Write-Host "---> Uninstalling release '$ReleaseName' from namespace '$Namespace'"
  helm uninstall $ReleaseName --namespace $Namespace
  if ($LASTEXITCODE -ne 0) { throw 'helm uninstall failed' }
  Write-Host "Done. Release '$ReleaseName' uninstalled successfully."
}

Write-Host '---> Deleting PVCs'
kubectl delete pvc -l app.kubernetes.io/instance=$ReleaseName --namespace $Namespace --ignore-not-found=true

Write-Host '---> Current Helm releases'
helm list --namespace $Namespace

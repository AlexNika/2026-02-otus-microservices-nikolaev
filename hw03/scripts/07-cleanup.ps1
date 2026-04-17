param(
  [string]$ManifestDir = '.\\k8s',
  [switch]$DeleteMinikube
)

$ErrorActionPreference = 'Stop'

Write-Host "---> Deleting manifests from $ManifestDir"
kubectl delete -f $ManifestDir

if ($DeleteMinikube) {
  Write-Host '---> Deleting minikube cluster'
  minikube delete
}

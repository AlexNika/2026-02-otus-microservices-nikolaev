param(
  [string]$ManifestDir = '.\\k8s'
)

$ErrorActionPreference = 'Stop'

Write-Host "---> Applying manifests from $ManifestDir"
kubectl apply -f $ManifestDir
if ($LASTEXITCODE -ne 0) { throw 'kubectl apply failed' }

Write-Host '---> Current resources'
kubectl get deploy,po,svc,ingress

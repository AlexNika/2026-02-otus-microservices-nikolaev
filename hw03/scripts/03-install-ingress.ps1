param(
  [string]$Namespace = 'm',
  [string]$ReleaseName = 'nginx',
  [string]$ValuesFile = '.\scripts\nginx-ingress.yaml'
)

$ErrorActionPreference = 'Stop'

Write-Host "---> Ensuring namespace $Namespace exists"
kubectl create namespace $Namespace --dry-run=client -o yaml | kubectl apply -f -
if ($LASTEXITCODE -ne 0) { throw 'Failed to create/apply namespace' }

Write-Host '---> Adding ingress-nginx Helm repo'
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx/
if ($LASTEXITCODE -ne 0) { throw 'helm repo add failed' }

Write-Host '---> Updating Helm repos'
helm repo update
if ($LASTEXITCODE -ne 0) { throw 'helm repo update failed' }

Write-Host '---> Installing/upgrading ingress-nginx'
if (Test-Path $ValuesFile) {
  helm upgrade --install $ReleaseName ingress-nginx/ingress-nginx --namespace $Namespace -f $ValuesFile
} else {
  Write-Warning "Values file not found: $ValuesFile. Installing without -f"
  helm upgrade --install $ReleaseName ingress-nginx/ingress-nginx --namespace $Namespace
}
if ($LASTEXITCODE -ne 0) { throw 'helm install/upgrade failed' }

Write-Host '---> Waiting for ingress controller pods'
kubectl get pods -n $Namespace
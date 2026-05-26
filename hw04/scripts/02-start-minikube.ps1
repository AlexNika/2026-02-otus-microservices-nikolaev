param(
  [string]$Driver = 'docker',
  [int]$Cpus = 2,
  [int]$Memory = 4096
)

$ErrorActionPreference = 'Stop'

Write-Host '---> Checking Docker Desktop availability'
docker version | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is not running or docker CLI is unavailable' }

Write-Host "---> Setting minikube driver to $Driver"
minikube config set driver $Driver
if ($LASTEXITCODE -ne 0) { throw 'Failed to set minikube driver' }

Write-Host '---> Starting minikube'
minikube start --driver=$Driver --cpus=$Cpus --memory=$Memory
if ($LASTEXITCODE -ne 0) { throw 'minikube start failed' }

Write-Host '---> Cluster status'
minikube status
kubectl get nodes -o wide

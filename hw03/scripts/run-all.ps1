param(
  [Parameter(Mandatory=$true)][string]$DockerHubLogin,
  [string]$ImageName = 'otusapp',
  [string]$ImageTag = 'hw3',
  [string]$StudentName = 'nikolaev',
  [string]$ManifestDir = '.\\k8s',
  [string]$DockerfilePath = '.\\src\\main\\docker\\Dockerfile',
  [string]$BuildContext = '.'
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

& "$scriptDir\01-build-and-push.ps1" -DockerHubLogin $DockerHubLogin -ImageName $ImageName -ImageTag $ImageTag -DockerfilePath $DockerfilePath -BuildContext $BuildContext
& "$scriptDir\02-start-minikube.ps1"
& "$scriptDir\03-install-ingress.ps1"
& "$scriptDir\04-apply-k8s.ps1" -ManifestDir $ManifestDir
& "$scriptDir\05-show-access-info.ps1"

Write-Host ''
Write-Host '---> Suggested next checks'
Write-Host '1. Update hosts file with minikube IP -> arch.homework'
Write-Host '2. If needed, run: minikube tunnel'
Write-Host '3. Test: curl http://arch.homework/health'
Write-Host "4. Test star-task: curl http://arch.homework/otusapp/$StudentName/health"
Write-Host '5. Run Postman/Newman script separately when the app is reachable'

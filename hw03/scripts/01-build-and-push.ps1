param(
  [Parameter(Mandatory=$true)][string]$DockerHubLogin,
  [string]$ImageName = 'otusapp',
  [string]$ImageTag = 'hw3',
  [string]$Platform = 'linux/amd64',
  [string]$DockerfilePath = '.\src\main\docker\Dockerfile',
  [string]$BuildContext = '.'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $DockerfilePath)) {
  throw "Dockerfile not found: $DockerfilePath"
}

$image = "$DockerHubLogin/$ImageName`:$ImageTag"

Write-Host '---> Docker login'
docker login
if ($LASTEXITCODE -ne 0) { throw 'docker login failed' }

Write-Host "---> Building image $image"
Write-Host "     Dockerfile: $DockerfilePath"
Write-Host "     Context:    $BuildContext"
Write-Host "     Platform:   $Platform"

docker build --platform $Platform -f $DockerfilePath -t $image $BuildContext
if ($LASTEXITCODE -ne 0) { throw 'docker build failed' }

Write-Host "---> Pushing image $image"
docker push $image
if ($LASTEXITCODE -ne 0) { throw 'docker push failed' }

Write-Host "Done. Image pushed: $image"

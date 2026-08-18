param(
  [Parameter(Mandatory=$true)][string]$DockerHubLogin,
  [string]$ImageName = 'otusapp',
  [string]$Platform = 'linux/amd64',
  [int]$MaxRetries = 3,
  [switch]$NoCache
)

$ErrorActionPreference = 'Stop'

$services = @(
  @{ Module='USERService'; Tag='hw09-user'; Port=8000 },
  @{ Module='BILLINGService'; Tag='hw09-billing'; Port=8001 },
  @{ Module='ORDERService'; Tag='hw09-order'; Port=8002 },
  @{ Module='NOTIFICATIONService'; Tag='hw09-notification'; Port=8003 },
  @{ Module='WAREHOUSEService'; Tag='hw09-warehouse'; Port=8004 },
  @{ Module='DELIVERYService'; Tag='hw09-delivery'; Port=8005 }
)

Write-Host '---> Docker login'
docker login
if ($LASTEXITCODE -ne 0) { throw 'docker login failed' }

foreach ($s in $services) {
  $image = "$DockerHubLogin/$ImageName`:$($s.Tag)"
  $dockerfile = ".\$($s.Module)\Dockerfile"

  if (-not (Test-Path $dockerfile)) {
    throw "Dockerfile not found: $dockerfile"
  }

  Write-Host ""
  Write-Host "---> Building image $image"
  Write-Host "     Dockerfile: $dockerfile"
  Write-Host "     Context:    ."
  Write-Host "     Platform:   $Platform"

  $buildArgs = @('--platform', $Platform, '-f', $dockerfile, '-t', $image, '.')
  if ($NoCache) { $buildArgs = @('--no-cache') + $buildArgs }

  $built = $false
  for ($attempt = 1; $attempt -le $MaxRetries -and -not $built; $attempt++) {
    if ($attempt -gt 1) { Write-Host "---> Retry $attempt/$MaxRetries for $($s.Module)" }
    docker build @buildArgs
    if ($LASTEXITCODE -eq 0) {
      $built = $true
    } else {
      Write-Host "---> Build attempt $attempt failed for $($s.Module) (exit code: $LASTEXITCODE)" -ForegroundColor Yellow
    }
  }
  if (-not $built) { throw "docker build failed for $($s.Module) after $MaxRetries attempts" }

  Write-Host "---> Pushing image $image"
  docker push $image
  if ($LASTEXITCODE -ne 0) { throw "docker push failed for $($s.Module)" }

  Write-Host "Done. Image pushed: $image"
}

Write-Host ""
Write-Host "All images built and pushed successfully."

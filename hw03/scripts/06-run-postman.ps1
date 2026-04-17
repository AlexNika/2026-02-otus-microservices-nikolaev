param(
  [string]$Collection = '.\\postman\\otus-hw3.postman_collection.json',
  [string]$Environment = '.\\postman\\local.postman_environment.json'
)

$ErrorActionPreference = 'Stop'

Write-Host '---> Checking newman'
try {
    $NewmanVersion = newman -v
    Write-Host "Newman version: $NewmanVersion"
} catch {
    throw 'Newman is not installed. Run: npm install -g newman'
}

$NpmPrefix = npm prefix -g
$NewmanJsPath = "$NpmPrefix\node_modules\newman\bin\newman.js"

if (-not (Test-Path $NewmanJsPath)) {
    throw "Newman executable not found at $NewmanJsPath. Check global npm installation."
}

if (Test-Path $Environment) {
    Write-Host "---> Running collection $Collection with environment $Environment"
    node --no-deprecation $NewmanJsPath run $Collection -e $Environment
} else {
    Write-Host "---> Running collection $Collection without environment"
    node --no-deprecation $NewmanJsPath run $Collection
}

if ($LASTEXITCODE -ne 0) { throw 'newman run failed' }
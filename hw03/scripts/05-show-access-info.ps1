$ErrorActionPreference = 'Stop'

Write-Host '---> Minikube IP'
minikube ip
if ($LASTEXITCODE -ne 0) { throw 'minikube ip failed' }

Write-Host ''
Write-Host 'Add this line to C:\Windows\System32\drivers\etc\hosts (run editor as Administrator):'
Write-Host 'MINIKUBE_IP arch.homework'
Write-Host ''
Write-Host 'If ingress is not reachable, run in a separate elevated PowerShell window:'
Write-Host 'minikube tunnel'
Write-Host ''
Write-Host 'Then test:'
Write-Host 'curl http://arch.homework/health'
Write-Host 'curl http://arch.homework/otusapp/nikolaev/health'

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$srcPath = Join-Path $root 'postman\local\otus-fp-circuit-breaker.postman_collection.json'
$outPath = Join-Path $root 'postman\k8s\otus-fp-circuit-breaker-k8s.postman_collection.json'
$c = Get-Content -LiteralPath $srcPath -Raw | ConvertFrom-Json

# --- URLs: строки -----------------------------------------------------------
function RawReplace([string]$s, [string]$a, [string]$b) { return $s.Replace($a, $b) }

$json = $c | ConvertTo-Json -Depth 60

# health -> /health/<svc> (health-ingress)
$healthMap = @{
    "userServiceUrl" = "user"; "billingServiceUrl" = "billing"; "orderServiceUrl" = "order"
    "notificationServiceUrl" = "notification"; "warehouseServiceUrl" = "warehouse"; "deliveryServiceUrl" = "delivery"
}
foreach ($k in $healthMap.Keys) {
    $json = RawReplace $json "{{$k}}/actuator/health" "{{baseUrl}}/health/$($healthMap[$k])"
}
# chaos endpoints: ingress actuator не публикует — отдельные переменные (порт-форвард)
foreach ($pair in @(@("billingServiceUrl", "chaosBillingUrl"), @("warehouseServiceUrl", "chaosWarehouseUrl"), @("deliveryServiceUrl", "chaosDeliveryUrl"))) {
    $json = RawReplace $json "{{$($pair[0])}}/actuator/chaosmonkey" "{{$($pair[1])}}/actuator/chaosmonkey"
}
# остальные пути идут через ingress
foreach ($k in $healthMap.Keys) {
    $json = RawReplace $json "{{$k}}" "{{baseUrl}}"
}
$json = RawReplace $json "{{authServiceUrl}}" "{{baseUrl}}"

# --- info -------------------------------------------------------------------
$json = RawReplace $json "otus-fp-circuit-breaker" "otus-fp-circuit-breaker-k8s"
$json = RawReplace $json "OTUS - Microservices Final Project - Circuit Breaker (Resilience4j + Chaos Monkey)" "OTUS - Microservices Final Project - Circuit Breaker (Resilience4j + Chaos Monkey) - k8s"
$json = RawReplace $json "BILLING/WAREHOUSE/DELIVERY blocks)." "BILLING/WAREHOUSE/DELIVERY blocks). K8s mirror: public routes go through the ingress (baseUrl); chaos endpoints are NOT published by the ingress, so chaosBillingUrl/chaosWarehouseUrl/chaosDeliveryUrl expect kubectl port-forward targets - the k8s run of this collection is postponed."

# --- variables ---------------------------------------------------------------
$varsBlock = @(
    @{ key = "baseUrl"; value = "http://arch.finalproject" },
    @{ key = "chaosBillingUrl"; value = "http://localhost:8001" },
    @{ key = "chaosWarehouseUrl"; value = "http://localhost:8004" },
    @{ key = "chaosDeliveryUrl"; value = "http://localhost:8005" }
)
$c2 = $json | ConvertFrom-Json
$c2.info.name = "OTUS - Microservices Final Project - Circuit Breaker (Resilience4j + Chaos Monkey) - k8s"
$keepVars = $c2.variable | Where-Object { $_.key -notin @("userServiceUrl", "billingServiceUrl", "orderServiceUrl", "notificationServiceUrl", "warehouseServiceUrl", "deliveryServiceUrl", "authServiceUrl") }
$c2.variable = @($varsBlock) + @($keepVars)

$c2 | ConvertTo-Json -Depth 60 | Set-Content -LiteralPath $outPath -Encoding utf8
Write-Output ("written: " + $outPath)

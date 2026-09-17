$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$src = Get-Content -LiteralPath (Join-Path $root 'postman\local\otus-fp-failed-billing.postman_collection.json') -Raw | ConvertFrom-Json

function Req([string]$name, [string]$method, [hashtable]$url, $headers, $bodyRaw, $auth, $desc, $preExec, $testExec) {
    $item = [ordered]@{ name = $name; event = @() }
    if ($preExec) { $item.event += @{ listen = "prerequest"; script = @{ exec = $preExec; type = "text/javascript" } } }
    if ($testExec) { $item.event += @{ listen = "test"; script = @{ exec = $testExec; type = "text/javascript" } } }
    $request = [ordered]@{ method = $method; header = @() }
    if ($headers) { $request.header = $headers }
    if ($bodyRaw) { $request.body = @{ mode = "raw"; raw = $bodyRaw; options = @{ raw = @{ language = "json" } } } }
    $request.url = $url
    if ($desc) { $request.description = $desc }
    if ($auth) { $request.auth = $auth }
    $item.request = $request
    $item.response = @()
    return [pscustomobject]$item
}
function Url([string]$raw, [string[]]$hosts, [string[]]$path) {
    return @{ raw = $raw; host = $hosts; path = $path }
}
$ct = @(@{ key = "Content-Type"; value = "application/json" })
$ikey = @(@{ key = "X-Internal-API-Key"; value = "{{internalApiKey}}" })
$authAdmin = @{ type = "bearer"; bearer = @(@{ key = "token"; value = "{{adminToken}}"; type = "string" }) }
$authUser = @{ type = "bearer"; bearer = @(@{ key = "token"; value = "{{userToken}}"; type = "string" }) }

$testOk200 = @('pm.test("Status code is 200", function () {', '    pm.response.to.have.status(200);', '});')

# --- bodies (одинарные строки, компактный JSON) ------------------------------
$productBody = '{"manufacturerArticle": "{{manufacturerArticle}}", "sku": "{{sku}}", "name": "CB scenario product", "description": "Circuit Breaker postman scenario product", "price": 10.00, "productStock": {"availableQuantity": 100, "reservedQuantity": 0}}'
$depositBody = '{"amount": 10000}'
$assaultsOnBody = '{"exceptionsActive": true, "latencyActive": false, "level": 1}'
$assaultsOffBody = '{"exceptionsActive": false, "latencyActive": false, "level": 1}'
$orderBodyTpl = '{"userId": {{userId}}, "price": 10.0000, "description": "__M__ scenario order", "productId": {{productId}}, "quantity": 1, "deliveryDate": "{{deliveryDate}}", "slotStart": "{{slotStart}}", "slotEnd": "{{slotEnd}}"}'
function OrderBody([string]$marker) { return $orderBodyTpl.Replace('__M__', $marker) }

# --- setup items: копируем из образца (01..13) -----------------------------
$items = New-Object System.Collections.ArrayList
foreach ($i in 0..12) { [void]$items.Add($src.item[$i]) }

# товар: сток 100 (заменяем оригинальный 08 из образца)
$prod = $src.item[9]
$prod.name = "08 - Create product (availableQuantity=100)"
$prod.request.body.raw = $productBody

# 07a - проба ёмкости курьеров: запас 10 на контрольные заказы (3) и коллизии дат,
# вместо +1 из образца (при ёмкости 1 второй же заказ получает DELIVERY_NO_FREE_COURIER).
$probeTestScript = @(
    'pm.test("Probe response is 200 or 404", function () {',
    '    pm.expect(pm.response.code).to.be.oneOf([200, 404]);',
    '});',
    '',
    'if (pm.response.code === 404) {',
    '    pm.collectionVariables.set("courierCount", "10");',
    '    console.log("Capacity probe: 404, courierCount=10");',
    '} else {',
    '    var jsonData = pm.response.json();',
    '    var slots = jsonData.slots || [];',
    '    pm.test("Capacity response has slots", function () {',
    '        pm.expect(slots).to.be.an("array").that.is.not.empty;',
    '    });',
    '    var reservedMax = slots.reduce(function (m, s) {',
    '        return Math.max(m, s.reservedCount || 0);',
    '    }, 0);',
    '    pm.collectionVariables.set("courierCount", String(reservedMax + 10));',
    '    console.log("Capacity probe: 200, reservedMax=" + reservedMax + ", courierCount=" + (reservedMax + 10));',
    '}'
)
$probe = $src.item[7]
$probeEvent = $probe.event | Where-Object { $_.listen -eq "test" }
$probeEvent.script.exec = $probeTestScript

# 10b - депозит 10000
$depositTest = @(
    '// Account is created async after registration: poll until deposit succeeds.',
    'var maxAttempts = 15;',
    'var attempt = parseInt(pm.collectionVariables.get("depositPollAttempt") || "1", 10);',
    '',
    'if (pm.response.code === 200) {',
    '    pm.test("Status code is 200", function () {',
    '        pm.response.to.have.status(200);',
    '    });',
    '    pm.test("Balance is 10000 after deposit", function () {',
    '        pm.expect(Number(pm.response.json().newBalance)).to.eql(10000);',
    '    });',
    '    pm.collectionVariables.set("depositPollAttempt", "1");',
    '} else if (pm.response.code === 404 && attempt < maxAttempts) {',
    '    console.log("Attempt " + attempt + "/" + maxAttempts + ": billing account not ready, retrying deposit...");',
    '    pm.collectionVariables.set("depositPollAttempt", String(attempt + 1));',
    '    postman.setNextRequest(pm.info.requestName);',
    '} else {',
    '    pm.collectionVariables.set("depositPollAttempt", "1");',
    '    pm.test("Deposit succeeded (billing account ready)", function () {',
    '        pm.expect(pm.response.code, "Deposit failed within " + maxAttempts + " attempts").to.eql(200);',
    '    });',
    '}'
)
[void]$items.Add((Req "10b - Deposit 10000 RUB" "POST" (Url "{{billingServiceUrl}}/api/v1/account/{{userId}}/deposit" @("{{billingServiceUrl}}") @("api","v1","account","{{userId}}","deposit")) $ct $depositBody $authUser "Top-up before chaos scenarios." $null $depositTest))

function BalanceRequest([string]$name, [string]$desc, [string[]]$testExec) {
    return (Req $name "GET" (Url "{{billingServiceUrl}}/api/v1/account/user/{{userId}}" @("{{billingServiceUrl}}") @("api","v1","account","user","{{userId}}")) @() $null $authUser $desc $null $testExec)
}
$orderUrl = Url "{{orderServiceUrl}}/api/v1/order" @("{{orderServiceUrl}}") @("api","v1","order")
$ordersUrl = Url "{{orderServiceUrl}}/api/v1/order/user/{{userId}}" @("{{orderServiceUrl}}") @("api","v1","order","user","{{userId}}")
$waitCb = @(
    '// CB: waitDurationInOpenState=10s, automaticTransitionFromOpenToHalfOpenEnabled=true.',
    '// OPEN -> HALF_OPEN transition, then the control call closes the circuit.',
    '// Newman sandbox forbids root await in pre-request: synchronous blocking wait.',
    'var waitUntil = Date.now() + 11000;',
    'while (Date.now() < waitUntil) { }'
)
$slow502Test = @(
    'pm.test("Slow failure: 502 after retries", function () {',
    '    pm.response.to.have.status(502);',
    '});',
    'pm.test("Slow: retries + chaos latency took >= 300 ms", function () {',
    '    pm.expect(pm.response.responseTime).to.be.at.least(300);',
    '});'
)
$slow409Test = @(
    'pm.test("Slow failure: 409 after retries", function () {',
    '    pm.response.to.have.status(409);',
    '});',
    'pm.test("Slow: retries + chaos latency took >= 300 ms", function () {',
    '    pm.expect(pm.response.responseTime).to.be.at.least(300);',
    '});'
)
$fastCbTest = @(
    'pm.test("Fast failure: 503 CIRCUIT_BREAKER_OPEN", function () {',
    '    pm.response.to.have.status(503);',
    '});',
    'pm.test("ErrorDto.code is CIRCUIT_BREAKER_OPEN", function () {',
    '    pm.expect(pm.response.json().code).to.eql("CIRCUIT_BREAKER_OPEN");',
    '});',
    'pm.test("Fast reject under 1000 ms", function () {',
    '    pm.expect(pm.response.responseTime).to.be.below(1000);',
    '});',
    'pm.test("Retry-After: 10", function () {',
    '    pm.expect(pm.response.headers.get("Retry-After")).to.eql("10");',
    '});'
)
$plc201Test = @(
    'pm.test("Status code is 201", function () {',
    '    pm.response.to.have.status(201);',
    '});',
    'pm.test("Order is PLACED (CB half-open -> closed)", function () {',
    '    pm.expect(pm.response.json().orderStatus).to.eql("PLACED");',
    '});'
)
# 409 без ассерта на время: если окно уже содержит записи (например, успех
# контрольного заказа предыдущего блока), CB открывается на ПЕРВОМ же аттемпте
# заказа — ретраи не успевают добавиться, ответ быстрый, но это ещё не
# CIRCUIT_BREAKER_OPEN (аттемпт успел выполниться).
$fail409NoTimingTest = @(
    'pm.test("Failure: 409 (CB opens after the 1st attempt, further retries dropped)", function () {',
    '    pm.response.to.have.status(409);',
    '});'
)
$notFoundTest = @(
    'pm.test("Status code is 404", function () {',
    '    pm.response.to.have.status(404);',
    '});'
)
# Компенсация не удаляет запись резерва, а переводит её в RELEASED: для заказов, у
# которых складской шаг прошёл до отказа (блок C — отказ на доставке), резерв
# существует со статусом RELEASED.
$releasedReservationTest = @(
    'pm.test("Status code is 200", function () {',
    '    pm.response.to.have.status(200);',
    '});',
    'pm.test("Warehouse reservation is RELEASED by compensation", function () {',
    '    var reservations = pm.response.json().reservations || [];',
    '    pm.expect(reservations).to.be.an("array").that.is.not.empty;',
    '    reservations.forEach(function (r) {',
    '        pm.expect(r.reservationStatus).to.eql("RELEASED");',
    '    });',
    '});'
)
function AssaultsReq([string]$svcVar, [string]$num, [bool]$on, [string]$blockLetter) {
    $body = $assaultsOnBody
    $verb = "enable"
    if (-not $on) { $body = $assaultsOffBody; $verb = "disable" }
    $test = $testOk200 + @(
        'pm.test("Assault config updated", function () {',
        '    pm.expect(pm.response.text()).to.contain("Assault config has changed");',
        '});'
    )
    return (Req "$num - $blockLetter" "POST" (Url "{{$svcVar}}/actuator/chaosmonkey/assaults" @("{{$svcVar}}") @("actuator","chaosmonkey","assaults")) $ct $body $authAdmin "Chaos assaults management (ADMIN JWT only): exceptionsActive=$($on.ToString().ToLower())." $null $test)
}
function ChaosSecChecks([string]$svcVar, [string]$blockLetter) {
    $t401 = @(
        'pm.test("401 without token", function () { pm.response.to.have.status(401); });',
        'pm.test("ErrorDto.status=401", function () { pm.expect(pm.response.json().status).to.eql(401); });'
    )
    $t403 = @(
        'pm.test("403 for USER role", function () { pm.response.to.have.status(403); });',
        'pm.test("ErrorDto.status=403", function () { pm.expect(pm.response.json().status).to.eql(403); });'
    )
    $r1 = (Req "$blockLetter - chaos endpoint without token -> 401" "GET" (Url "{{$svcVar}}/actuator/chaosmonkey" @("{{$svcVar}}") @("actuator","chaosmonkey")) @() $null $null "Security check: no JWT -> 401." $null $t401)
    $hdr = @(@{ key = "Authorization"; value = "Bearer {{userToken}}" })
    $r2 = (Req "$blockLetter - chaos endpoint with USER token -> 403" "GET" (Url "{{$svcVar}}/actuator/chaosmonkey" @("{{$svcVar}}") @("actuator","chaosmonkey")) $hdr $null $null "Security check: USER role -> 403." $null $t403)
    return @($r1, $r2)
}
function FindFailed([string]$name, [string]$marker, [int]$minCount) {
    # Конкатенация делается ОДНОЙ строкой до массива: `+` внутри многострочного @( )
    # в PowerShell раскладывается на отдельные элементы exec (разрыв строкового литерала в JSON).
    $predicateLine = '    return o.orderStatus === "FAILED" && (o.description || "").indexOf("' + $marker + '") === 0;'
    $atLeastTestLine = 'pm.test("At least ' + $minCount + ' FAILED orders of the block", function () {'
    $atLeastExprLine = '    pm.expect(failed.length).to.be.at.least(' + $minCount + ');'
    $test = @(
        'pm.test("Status code is 200", function () {',
        '    pm.response.to.have.status(200);',
        '});',
        'var orders = pm.response.json();',
        'var failed = orders.filter(function (o) {',
        $predicateLine,
        '});',
        $atLeastTestLine,
        $atLeastExprLine,
        '});',
        'if (failed.length > 0) {',
        '    pm.collectionVariables.set("orderId", failed[failed.length - 1].id);',
        '    console.log("FAILED order picked: " + failed[failed.length - 1].id);',
        '}'
    )
    return (Req $name "GET" $ordersUrl @() $null $authUser ("Find FAILED orders of block " + $marker + " and pick the last orderId.") $null $test)
}

# --- Блок A (BILLING) -------------------------------------------------------
# Reset-guard: после прерванного прогона ассолты могли остаться включёнными —
# это испортило бы и эту коллекцию, и следующие прогоны остальных коллекций.
[void]$items.Add((AssaultsReq "billingServiceUrl" "A0a" $false "BILLING: ensure assaults disabled (reset guard)"))
[void]$items.Add((AssaultsReq "warehouseServiceUrl" "A0b" $false "WAREHOUSE: ensure assaults disabled (reset guard)"))
[void]$items.Add((AssaultsReq "deliveryServiceUrl" "A0c" $false "DELIVERY: ensure assaults disabled (reset guard)"))
[void]$items.Add((ChaosSecChecks "billingServiceUrl" "A1")[0])
[void]$items.Add((ChaosSecChecks "billingServiceUrl" "A2")[1])
[void]$items.Add((AssaultsReq "billingServiceUrl" "A3" $true "BILLING: enable exception assaults"))
[void]$items.Add((Req "A4 - Order #1 -> slow failure 502 (retries over chaos, opens the CB)" "POST" $orderUrl $ct (OrderBody "CB-A") $authUser "Billing step fails: 3 withdraw retries + 3 compensation-refund retries are recorded by the CB (window min 5 calls, 50% threshold) -> CB opens during compensation -> 502." $null $slow502Test))
[void]$items.Add((Req "A5 - Order #2 -> fast 503 CIRCUIT_BREAKER_OPEN" "POST" $orderUrl $ct (OrderBody "CB-A") $authUser "CB already open after A4 (withdraw + compensation retries filled the window): fast reject without calling billing." $null $fastCbTest))
[void]$items.Add((FindFailed "A6 - User orders: >= 2 FAILED of block A" "CB-A" 2))
[void]$items.Add((Req "A7 - Order #3 -> fast 503 CIRCUIT_BREAKER_OPEN" "POST" $orderUrl $ct (OrderBody "CB-A") $authUser "CB is open: fast reject without calling billing." $null $fastCbTest))
[void]$items.Add((Req "A8 - Order #4 -> fast 503 CIRCUIT_BREAKER_OPEN" "POST" $orderUrl $ct (OrderBody "CB-A") $authUser "Fast reject again + Retry-After header." $null $fastCbTest))
[void]$items.Add((AssaultsReq "billingServiceUrl" "A9" $false "BILLING: disable exception assaults"))
[void]$items.Add((Req "A10 - Wait CB recovery, control order -> 201 PLACED" "POST" $orderUrl $ct (OrderBody "CB-A") $authUser "11s pause > waitDurationInOpenState; control order closes the circuit." $waitCb $plc201Test))
$balATest = @(
    'pm.test("Status code is 200", function () {',
    '    pm.response.to.have.status(200);',
    '});',
    'var balance = Number(pm.response.json().balance);',
    'pm.test("Balance is 9990 (10000 - 10 control order)", function () {',
    '    pm.expect(balance).to.eql(9990);',
    '});',
    'pm.collectionVariables.set("balanceBefore", String(balance));'
)
[void]$items.Add((BalanceRequest "A11 - Billing balance after block A" "Only the control order withdrew 10." $balATest))

# --- Блок B (WAREHOUSE) -----------------------------------------------------
[void]$items.Add((AssaultsReq "warehouseServiceUrl" "B1" $true "WAREHOUSE: enable exception assaults"))
[void]$items.Add((Req "B2 - Order #1 -> warehouse step failure 409" "POST" $orderUrl $ct (OrderBody "CB-B") $authUser "Billing ok, warehouse fails after retries -> refund compensation -> 409." $null $slow409Test))
[void]$items.Add((Req "B3 - Order #2 -> fast 503 CIRCUIT_BREAKER_OPEN" "POST" $orderUrl $ct (OrderBody "CB-B") $authUser "CB already open after B2 (3 reserve retries + 3 compensation-cancel retries filled the window): fast reject without calling warehouse." $null $fastCbTest))
[void]$items.Add((Req "B4 - Order #3 -> fast 503 CIRCUIT_BREAKER_OPEN" "POST" $orderUrl $ct (OrderBody "CB-B") $authUser "Fast reject on warehouse." $null $fastCbTest))
[void]$items.Add((Req "B5 - Order #4 -> fast 503 CIRCUIT_BREAKER_OPEN" "POST" $orderUrl $ct (OrderBody "CB-B") $authUser "Fast reject again." $null $fastCbTest))
[void]$items.Add((AssaultsReq "warehouseServiceUrl" "B6" $false "WAREHOUSE: disable exception assaults"))
[void]$items.Add((Req "B7 - Wait CB recovery, control order -> 201 PLACED" "POST" $orderUrl $ct (OrderBody "CB-B") $authUser "11s pause, control order." $waitCb $plc201Test))
$balBTest = @(
    'pm.test("Status code is 200", function () {',
    '    pm.response.to.have.status(200);',
    '});',
    'var balance = Number(pm.response.json().balance);',
    'var before = Number(pm.collectionVariables.get("balanceBefore"));',
    'pm.test("Refunds returned deposits: balance = before - 10", function () {',
    '    pm.expect(balance).to.eql(before - 10);',
    '});',
    'pm.collectionVariables.set("balanceBefore", String(balance));'
)
[void]$items.Add((BalanceRequest "B8 - Billing balance after block B (refunds returned)" "Compensations returned withdrawals; control order took 10." $balBTest))
[void]$items.Add((FindFailed "B9 - User orders: >= 2 FAILED of block B" "CB-B" 2))
[void]$items.Add((Req "B10 - Warehouse reservation of FAILED order -> 404" "GET" (Url "{{warehouseServiceUrl}}/internal/products/reservations/{{orderId}}" @("{{warehouseServiceUrl}}") @("internal","products","reservations","{{orderId}}")) $ikey $null $null "Warehouse reservation absent (reservation step itself failed)." $null $notFoundTest))

# --- Блок C (DELIVERY) ------------------------------------------------------
[void]$items.Add((AssaultsReq "deliveryServiceUrl" "C1" $true "DELIVERY: enable exception assaults"))
[void]$items.Add((Req "C2 - Order #1 -> delivery step failure 409 (opens the CB)" "POST" $orderUrl $ct (OrderBody "CB-C") $authUser "Billing+warehouse ok, delivery fails after retries -> compensations -> 409; the window (2 control successes + 3 failures) crosses the 50% threshold on the last attempt -> CB opens." $null $slow409Test))
[void]$items.Add((Req "C3 - Order #2 -> fast 503 CIRCUIT_BREAKER_OPEN" "POST" $orderUrl $ct (OrderBody "CB-C") $authUser "CB already open after C2 retries: fast reject on delivery." $null $fastCbTest))
[void]$items.Add((Req "C4 - Order #3 -> fast 503 CIRCUIT_BREAKER_OPEN" "POST" $orderUrl $ct (OrderBody "CB-C") $authUser "Fast reject on delivery." $null $fastCbTest))
[void]$items.Add((Req "C5 - Order #4 -> fast 503 CIRCUIT_BREAKER_OPEN" "POST" $orderUrl $ct (OrderBody "CB-C") $authUser "Fast reject again." $null $fastCbTest))
[void]$items.Add((AssaultsReq "deliveryServiceUrl" "C6" $false "DELIVERY: disable exception assaults"))
[void]$items.Add((Req "C7 - Wait CB recovery, control order -> 201 PLACED" "POST" $orderUrl $ct (OrderBody "CB-C") $authUser "11s pause, control order." $waitCb $plc201Test))
$finalBalTest = @(
    'pm.test("Status code is 200", function () {',
    '    pm.response.to.have.status(200);',
    '});',
    'pm.test("Final balance is 9970 (10000 - 3 control orders)", function () {',
    '    pm.expect(Number(pm.response.json().balance)).to.eql(9970);',
    '});'
)
[void]$items.Add((BalanceRequest "C8 - Final billing balance" "Total: three control orders withdrew 10 each." $finalBalTest))
[void]$items.Add((FindFailed "C9 - User orders: >= 2 FAILED of block C" "CB-C" 2))
[void]$items.Add((Req "C10 - Warehouse reservation of FAILED order -> RELEASED" "GET" (Url "{{warehouseServiceUrl}}/internal/products/reservations/{{orderId}}" @("{{warehouseServiceUrl}}") @("internal","products","reservations","{{orderId}}")) $ikey $null $null "In block C the warehouse step succeeded before delivery failed, so the reservation record exists and compensation marked it RELEASED." $null $releasedReservationTest))
[void]$items.Add((Req "C11 - Delivery reservation of FAILED order -> 404" "GET" (Url "{{deliveryServiceUrl}}/internal/delivery/reservations/{{orderId}}" @("{{deliveryServiceUrl}}") @("internal","delivery","reservations","{{orderId}}")) $ikey $null $null "Delivery reservation was never created." $null $notFoundTest))

# --- переменные коллекции ----------------------------------------------------
$vars = @(
    @("userServiceUrl", "http://localhost:8000"),
    @("billingServiceUrl", "http://localhost:8001"),
    @("orderServiceUrl", "http://localhost:8002"),
    @("notificationServiceUrl", "http://localhost:8003"),
    @("warehouseServiceUrl", "http://localhost:8004"),
    @("deliveryServiceUrl", "http://localhost:8005"),
    @("authServiceUrl", "http://localhost:8006"),
    @("runBase", ""), @("rand", ""), @("deliveryDate", ""), @("slotStart", ""), @("slotEnd", ""),
    @("sku", ""), @("manufacturerArticle", ""), @("productId", ""),
    @("userName", ""), @("userEmail", ""), @("userId", ""), @("orderId", ""),
    @("balanceBefore", ""), @("pollAttempt", "1"), @("accountPollAttempt", "1"), @("depositPollAttempt", "1"),
    @("adminToken", ""), @("userToken", "")
) | ForEach-Object { @{ key = $_[0]; value = $_[1] } }

$collection = [ordered]@{
    info = [ordered]@{
        _postman_id = "otus-fp-circuit-breaker"
        name = "OTUS - Microservices Final Project - Circuit Breaker (Resilience4j + Chaos Monkey)"
        description = "Circuit Breaker scenarios: fault injection via /actuator/chaosmonkey (ADMIN only), slow failures with retries, fast 503 CIRCUIT_BREAKER_OPEN when the circuit is open, recovery and control orders (BILLING/WAREHOUSE/DELIVERY blocks)."
        schema = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
    }
    variable = $vars
    item = @($items)
}

$out = Join-Path $root 'postman\local\otus-fp-circuit-breaker.postman_collection.json'
$collection | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $out -Encoding utf8
Write-Output ("written: " + $out + " items=" + $items.Count)

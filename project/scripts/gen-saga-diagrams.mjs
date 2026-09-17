#!/usr/bin/env node
/**
 * Генератор Mermaid-диаграмм saga-прогонов Postman (success + failed-billing).
 *
 * Вход: newman JSON-отчёты (--success, --failed), каталог вывода (--out).
 * Выход: saga-success-sequence.md/.mmd, saga-failed-billing-sequence.md/.mmd,
 *        saga-flow.md/.mmd, README.md.
 *
 * Внешние шаги (Newman -> сервисы), коды ответов, тайминги и ✅/❌ берутся
 * ИЗ ФАКТИЧЕСКОГО ПРОГОНА (newman JSON). Внутренние saga-вызовы оркестратора
 * (ORDER -> BILLING/WAREHOUSE/DELIVERY) в отчёте не видны — они производные
 * из кода ORDERService:
 *   - OrderServiceImpl.runSaga (ORDERService/src/main/java/ru/otus/hw/service/OrderServiceImpl.java:255):
 *     BILLING_WITHDRAW -> WAREHOUSE_RESERVE -> DELIVERY_RESERVE -> WAREHOUSE_CONFIRM -> DELIVERY_CONFIRM,
 *     синхронный RestClient на /internal/** (BillingServiceClient, WarehouseServiceClient, DeliveryServiceClient);
 *   - OrderServiceImpl.compensate (строка 466): обратный порядок DELIVERY_CANCEL -> WAREHOUSE_CANCEL -> BILLING_REFUND,
 *     только для реально выполненных шагов;
 *   - уведомление — асинхронно: transactional outbox -> scheduled OutboxPublisher -> RabbitMQ
 *     (notification.queue -> NotificationConsumer), в финальной транзакции заказа
 *     (finalizeOrderWithNotification, строка 584).
 *
 * Стиль стрелок: сплошные ->>/-->> = синхронный REST, пунктирные --)/--) = асинхронный RabbitMQ.
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = path.resolve(SCRIPT_DIR, '..');

// ---------- CLI ----------

function parseArgs(argv) {
  const opts = { success: null, failed: null, out: path.join(PROJECT_ROOT, 'diagrams') };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--success') opts.success = argv[++i];
    else if (a === '--failed') opts.failed = argv[++i];
    else if (a === '--out') opts.out = argv[++i];
    else throw new Error(`Unknown argument: ${a}`);
  }
  if (!opts.success) opts.success = latestReport('newman-json-otus-fp-success-k8s-*.json');
  if (!opts.failed) opts.failed = latestReport('newman-json-otus-fp-failed-billing-k8s-*.json');
  if (!path.isAbsolute(opts.out)) opts.out = path.resolve(PROJECT_ROOT, opts.out);
  return opts;
}

function latestReport(namePattern) {
  const dir = path.join(PROJECT_ROOT, 'reports', 'k8s');
  const re = new RegExp('^' + namePattern.replace(/[.*]/g, (m) => (m === '*' ? '.*' : '\\.')) + '$');
  const files = fs.readdirSync(dir).filter((f) => re.test(f));
  if (files.length === 0) {
    throw new Error(`Не найден JSON-отчёт ${namePattern} в ${dir}. Сначала выполните: ` +
      `scripts/06-run-postman-k8s.ps1 ... -JsonReport`);
  }
  files.sort();
  return path.join(dir, files[files.length - 1]);
}

// ---------- newman JSON -> нормализованные шаги ----------

function loadRun(jsonPath) {
  const j = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
  const execs = j.run.executions.map(normalizeExec);
  const timings = j.run.timings;
  const durationSec = timings?.started && timings?.completed
    ? ((timings.completed - timings.started) / 1000).toFixed(1)
    : '?';
  return {
    file: jsonPath,
    name: j.run.collection?.name ?? path.basename(jsonPath),
    startedAt: timings?.started ? new Date(timings.started).toISOString() : '?',
    durationSec,
    stats: {
      requests: j.run.stats?.requests?.total ?? execs.length,
      assertions: j.run.stats?.assertions?.total ?? 0,
      assertionsFailed: j.run.stats?.assertions?.failed ?? 0,
    },
    execs,
  };
}

function normalizeExec(e) {
  const url = e.request.url;
  const p = Array.isArray(url?.path) ? '/' + url.path.join('/') : String(url ?? '');
  const query = (url?.query ?? []).filter((x) => x.key).map((x) => `${x.key}=${x.value}`).join('&');
  let bodyText = '';
  try { bodyText = Buffer.from(e.response.stream.data).toString('utf8'); } catch { /* no body */ }
  let body = null;
  try { body = JSON.parse(bodyText); } catch { /* not JSON */ }
  const assertions = e.assertions ?? [];
  const stepMatch = e.item.name.match(/^(\d+[a-z]?)\s*-\s*/);
  return {
    name: e.item.name,
    step: stepMatch ? stepMatch[1] : '?',
    method: e.request.method,
    path: p,
    query,
    code: e.response.code,
    ms: e.response.responseTime,
    aTotal: assertions.length,
    aFailed: assertions.filter((a) => a.error).length,
    body,
  };
}

const ALIAS = { USER: 'U', BILLING: 'B', WAREHOUSE: 'W', DELIVERY: 'D', NOTIFICATION: 'N', ORDER: 'O' };

function serviceOf(p) {
  if (p.startsWith('/health/')) return p.split('/')[2].toUpperCase();
  if (p.startsWith('/api/v1/auth')) return 'USER';
  if (p.startsWith('/api/v1/order')) return 'ORDER';
  if (p.startsWith('/api/v1/account')) return 'BILLING';
  if (p.startsWith('/api/v1/products') || p.startsWith('/internal/products')) return 'WAREHOUSE';
  if (p.startsWith('/api/v1/delivery') || p.startsWith('/internal/delivery')) return 'DELIVERY';
  if (p.startsWith('/api/v1/notification')) return 'NOTIFICATION';
  return 'UNKNOWN';
}

const money = (v) => (v == null ? '?' : Number(v).toFixed(2));
const slot = (t) => (t == null ? '?' : String(t).slice(0, 5));
const plural = (n, forms) => forms[(n % 10 === 1 && n % 100 !== 11) ? 0 : (n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14)) ? 1 : 2];
const mark = (e) => (e.aFailed === 0 ? '✅' : `❌ (${e.aFailed}/${e.aTotal} assert failed)`);

function splitSaga(execs) {
  const idx = execs.findIndex((e) => e.method === 'POST' && e.path === '/api/v1/order');
  if (idx < 0) throw new Error('Не найден шаг POST /api/v1/order — saga-часть определить нельзя');
  return { setup: execs.slice(0, idx), saga: execs.slice(idx) };
}

function findExec(execs, method, pathPrefix) {
  return execs.find((e) => e.method === method && e.path.startsWith(pathPrefix));
}

// ---------- sequence: success ----------

function buildSuccessSequence(run) {
  const { setup, saga } = splitSaga(run.execs);
  const allOk = run.execs.every((e) => e.aFailed === 0);

  const create = saga[0];
  const o = create.body ?? {};
  const deposit = findExec(setup, 'POST', '/api/v1/account/');
  const product = findExec(setup, 'POST', '/api/v1/products');
  const user = findExec(setup, 'POST', '/api/v1/auth/register');
  const balance = findExec(saga, 'GET', '/api/v1/account/');
  const whRes = findExec(saga, 'GET', '/internal/products/reservations');
  const delRes = findExec(saga, 'GET', '/internal/delivery/reservations');
  const stocks = findExec(saga, 'GET', `/api/v1/products/${o.productId ?? ''}/stocks`);
  const notif = findExec(saga, 'GET', '/api/v1/notification');
  const notifItem = Array.isArray(notif?.body)
    ? notif.body.find((n) => n.orderId === o.id && n.notificationStatus === 'SUCCESS') ?? notif.body[0]
    : null;
  const whFirst = Array.isArray(whRes?.body?.reservations) ? whRes.body.reservations[0] : null;

  const L = [];
  L.push('sequenceDiagram');
  L.push('    participant C as Newman (Client)');
  L.push('    participant O as ORDER (orchestrator)');
  L.push('    participant U as USER/AUTH');
  L.push('    participant B as BILLING');
  L.push('    participant W as WAREHOUSE');
  L.push('    participant D as DELIVERY');
  L.push('    participant N as NOTIFICATION');
  L.push('');
  L.push(`    note over C,N: Setup ${setup[0].step}–${setup[setup.length - 1].step} (свёрнуто): ${setup.length} ${plural(setup.length, ['запрос', 'запроса', 'запросов'])} · ${allOk ? 'все ✅' : 'есть ❌'}<br/>health ×6 · login admin/user · courier capacity ${product ? o.deliveryDate : ''}<br/>product #${product?.body?.id ?? '?'} (${money(product?.body?.price)} ₽, qty 20) · user #${user?.body?.id ?? '?'} · account 0.00 ₽ · deposit → ${money(deposit?.body?.newBalance)} ₽`);
  L.push('');
  L.push('    rect rgb(232, 245, 233)');
  L.push('    note over C,N: Saga внутри POST /api/v1/order (синхронный REST /internal/**, порядок из OrderServiceImpl.runSaga)');
  L.push(`    C->>O: ${create.step} · POST /api/v1/order (product #${o.productId}, qty=${o.quantity}, ${money(o.price)} ₽)`);
  L.push('    activate O');
  L.push(`    O->>B: 1. BILLING_WITHDRAW · POST /internal/order/withdraw (${money(o.price)} ₽)`);
  L.push(`    B-->>O: 200 OK · списано: ${money(deposit?.body?.newBalance)} → ${money(balance?.body?.balance)} ₽`);
  L.push(`    O->>W: 2. WAREHOUSE_RESERVE · POST /internal/products/reservations (product #${o.productId}, qty=${o.quantity}, idem-key order-${o.id}-p${o.productId})`);
  L.push('    W-->>O: 200 OK · RESERVED');
  L.push(`    O->>D: 3. DELIVERY_RESERVE · POST /internal/delivery/reservations (${o.deliveryDate} ${slot(o.slotStart)}–${slot(o.slotEnd)})`);
  L.push(`    D-->>O: 200 OK · RESERVED · courier #${delRes?.body?.assignedCourierNumber ?? '?'}`);
  L.push(`    O->>W: 4. WAREHOUSE_CONFIRM · POST /internal/products/reservations/${o.id}/confirm`);
  L.push('    W-->>O: 200 OK · CONFIRMED');
  L.push(`    O->>D: 5. DELIVERY_CONFIRM · POST /internal/delivery/reservations/${o.id}/confirm`);
  L.push('    D-->>O: 200 OK · CONFIRMED');
  L.push(`    O->>O: saga CONFIRMED · order → PLACED + событие в transactional outbox (одной транзакцией)`);
  L.push(`    O--)N: async · OutboxPublisher → RabbitMQ → notification.queue: "${notifItem?.message ?? 'Order placed successfully.'}"`);
  L.push(`    O-->>C: ${create.code} Created · order #${o.id} · ${o.orderStatus} · ${mark(create)} (${create.ms}ms)`);
  L.push('    deactivate O');
  L.push('    end');
  L.push('');
  L.push('    note over C,N: Проверки результата (шаги коллекции, фактические ответы прогона)');
  const checks = [
    [saga[1], 'O', `${saga[1].code} · order #${saga[1].body?.id ?? o.id} · ${saga[1].body?.orderStatus ?? o.orderStatus}`],
    [saga[2], 'W', `${whRes?.code} · ${whRes?.body?.reservations?.length ?? 1} бронь · все ${whFirst?.reservationStatus ?? 'CONFIRMED'}`],
    [saga[3], 'D', `${delRes?.code} · ${delRes?.body?.status ?? 'CONFIRMED'} · courier #${delRes?.body?.assignedCourierNumber ?? '?'}`],
    [saga[4], 'B', `${balance?.code} · balance ${money(balance?.body?.balance)} ₽ (${money(deposit?.body?.newBalance)} − ${money(o.price)})`],
    [saga[5], 'W', `${stocks?.code} · available=${stocks?.body?.availableQuantity} · reserved=${stocks?.body?.reservedQuantity}`],
    [saga[6], 'N', `${notif?.code} · ${notifItem?.notificationStatus ?? 'SUCCESS'} уведомление #${notifItem?.id ?? '?'}`],
  ];
  for (const [e, alias, fact] of checks) {
    L.push(`    C->>${alias}: ${e.step} · ${e.method} ${e.path}${e.query ? '?' + e.query : ''}`);
    L.push(`    ${alias}-->>C: ${fact} · ${mark(e)} (${e.ms}ms)`);
  }
  return L;
}

// ---------- sequence: failed (billing) ----------

function buildFailedSequence(run) {
  const { setup, saga } = splitSaga(run.execs);
  const allOk = run.execs.every((e) => e.aFailed === 0);

  const create = saga[0]; // POST /order -> 502
  const user = findExec(setup, 'POST', '/api/v1/auth/register');
  const product = findExec(setup, 'POST', '/api/v1/products');
  const account = findExec(setup, 'GET', '/api/v1/account/');
  const ordersList = saga[1]; // GET /api/v1/order/user/{id}
  const failedOrder = Array.isArray(ordersList?.body)
    ? ordersList.body.find((x) => x.orderStatus === 'FAILED') ?? ordersList.body[0]
    : null;
  const orderId = failedOrder?.id ?? '?';
  const price = money(failedOrder?.price ?? product?.body?.price);
  const whRes = saga[2]; // 404
  const delRes = saga[3]; // 404
  const balance = saga[4];
  const notif = saga[5];
  const notifItem = Array.isArray(notif?.body)
    ? notif.body.find((n) => n.orderId === orderId && n.notificationStatus === 'FAILED') ?? notif.body[0]
    : null;
  const billingErr = /status=(\d+)/.exec(notifItem?.message ?? create.body?.message ?? '')?.[1] ?? '409';
  const errCode = /BILLING_[A-Z_]+/.exec(notifItem?.message ?? create.body?.message ?? '')?.[0] ?? 'BILLING_INSUFFICIENT_FUNDS';

  const L = [];
  L.push('sequenceDiagram');
  L.push('    participant C as Newman (Client)');
  L.push('    participant O as ORDER (orchestrator)');
  L.push('    participant B as BILLING');
  L.push('    participant W as WAREHOUSE');
  L.push('    participant D as DELIVERY');
  L.push('    participant N as NOTIFICATION');
  L.push('');
  L.push(`    note over C,N: Setup ${setup[0].step}–${setup[setup.length - 1].step} (свёрнуто): ${setup.length} ${plural(setup.length, ['запрос', 'запроса', 'запросов'])} · ${allOk ? 'все ✅' : 'есть ❌'}<br/>health ×6 · login admin/user · courier capacity · product #${product?.body?.id ?? '?'} (${price} ₽)<br/>user #${user?.body?.id ?? '?'} · account ${money(account?.body?.balance)} ₽ · БЕЗ депозита — сценарий отказа платежа`);
  L.push('');
  L.push('    rect rgb(255, 235, 238)');
  L.push('    note over C,N: Отказ saga внутри POST /api/v1/order: сбой на первом шаге BILLING_WITHDRAW');
  L.push(`    C->>O: ${create.step} · POST /api/v1/order (product #${product?.body?.id ?? '?'}, qty=1, ${price} ₽)`);
  L.push('    activate O');
  L.push(`    O->>B: 1. BILLING_WITHDRAW · POST /internal/order/withdraw (${price} ₽)`);
  L.push(`    B--xO: ${billingErr} · ${errCode} (balance ${money(account?.body?.balance)} ₽, требуется ${price} ₽)`);
  L.push('    note over O: runSaga catch → compensate() (OrderServiceImpl:466)<br/>сбой на ПЕРВОМ шаге: billingReserved=false · warehouseReserved=false · deliveryReserved=false');
  L.push('    opt компенсация — обратный порядок, только реально выполненные шаги');
  L.push(`        O--)D: compensation: POST /internal/delivery/reservations/${orderId}/cancel — ПРОПУЩЕН (брони нет)`);
  L.push(`        O--)W: compensation: POST /internal/products/reservations/${orderId}/cancel — ПРОПУЩЕН (брони нет)`);
  L.push(`        O--)B: compensation: POST /internal/order/refund — ПРОПУЩЕН (деньги не списаны)`);
  L.push('    end');
  L.push(`    O->>O: saga COMPENSATED · order #${orderId} → FAILED + событие в transactional outbox`);
  L.push('    O--)N: async · OutboxPublisher → RabbitMQ → notification.queue: "Order processing failed: ..."');
  L.push(`    O--xC: ${create.code} Bad Gateway · BillingServiceException → 502 · assert: ожидался 502 ${mark(create)} (${create.ms}ms)`);
  L.push('    deactivate O');
  L.push('    end');
  L.push('');
  L.push('    note over C,N: Проверки компенсаций — коллекция фиксирует отсутствие побочных эффектов');
  const checks = [
    [ordersList, 'O', `${ordersList.code} · order #${orderId} · ${failedOrder?.orderStatus ?? 'FAILED'} (единственный заказ пользователя)`],
    [whRes, 'W', `${whRes.code} · брони склада НЕТ — до WAREHOUSE_RESERVE дело не дошло`],
    [delRes, 'D', `${delRes.code} · брони доставки НЕТ — до DELIVERY_RESERVE дело не дошло`],
    [balance, 'B', `${balance.code} · balance ${money(balance?.body?.balance)} ₽ — списания не было, refund не требуется`],
    [notif, 'N', `${notif.code} · FAILED уведомление #${notifItem?.id ?? '?'} (${errCode})`],
  ];
  for (const [e, alias, fact] of checks) {
    L.push(`    C->>${alias}: ${e.step} · ${e.method} ${e.path}${e.query ? '?' + e.query : ''}`);
    L.push(`    ${alias}-->>C: ${fact} · ${mark(e)} (${e.ms}ms)`);
  }
  return L;
}

// ---------- flowchart ----------

function buildFlow(successRun, failedRun) {
  const s = splitSaga(successRun.execs);
  const f = splitSaga(failedRun.execs);
  const sCreate = s.saga[0], fCreate = f.saga[0];
  const so = sCreate.body ?? {};
  const sDeposit = findExec(s.setup, 'POST', '/api/v1/account/');
  const sBalance = findExec(s.saga, 'GET', '/api/v1/account/');
  const sNotif = findExec(s.saga, 'GET', '/api/v1/notification');
  const sNotifItem = Array.isArray(sNotif?.body) ? sNotif.body.find((n) => n.orderId === so.id) : null;
  const fOrders = f.saga[1];
  const fOrder = Array.isArray(fOrders?.body) ? fOrders.body.find((x) => x.orderStatus === 'FAILED') : null;
  const fBalance = f.saga[4];
  const fNotif = f.saga[5];
  const fNotifItem = Array.isArray(fNotif?.body) ? fNotif.body.find((n) => n.notificationStatus === 'FAILED') : null;
  const errCode = /BILLING_[A-Z_]+/.exec(fNotifItem?.message ?? '')?.[0] ?? 'BILLING_INSUFFICIENT_FUNDS';

  return [
    'flowchart TD',
    '    classDef ok fill:#C8E6C9,stroke:#2E7D32,color:#1B5E20',
    '    classDef bad fill:#FFCDD2,stroke:#C62828,color:#B71C1C',
    '    classDef neutral fill:#E3F2FD,stroke:#1565C0,color:#0D47A1',
    '    classDef comp fill:#FFF3CD,stroke:#B8860B,color:#5D4037',
    '',
    '    subgraph CLIENT["Newman (Client) — прогон коллекций"]',
    `        A["${sCreate.step} · POST /api/v1/order<br/>product #${so.productId} · qty=${so.quantity} · ${money(so.price)} ₽"]:::neutral`,
    `        V1["Проверки success ${s.saga[1].step}–${s.saga[6].step}: PLACED · CONFIRMED ×2 ·<br/>balance ${money(sBalance?.body?.balance)} ₽ · stocks ${findStocks(s.saga, so)} · ${sNotifItem?.notificationStatus ?? 'SUCCESS'} уведомление #${sNotifItem?.id ?? '?'}"]:::ok`,
    `        V2["Проверки failed ${f.saga[1].step}–${f.saga[5].step}: order FAILED · брони 404 ×2 ·<br/>balance ${money(fBalance?.body?.balance)} ₽ · FAILED уведомление #${fNotifItem?.id ?? '?'}"]:::bad`,
    '    end',
    '',
    '    subgraph ORDER["ORDERService — оркестратор saga (OrderServiceImpl.runSaga)"]',
    '        S0["order PENDING → PROCESSING<br/>saga_state STARTED"]:::neutral',
    '        ST1{"Шаг 1 · BILLING_WITHDRAW<br/>POST /internal/order/withdraw — результат?"}',
    `        SUC["order #${so.id} → PLACED · saga CONFIRMED<br/>${sCreate.code} Created (${sCreate.ms}ms)"]:::ok`,
    '        CAT["catch → compensate() · saga COMPENSATING"]:::comp',
    `        FAIL["order #${fOrder?.id ?? '?'} → FAILED · saga COMPENSATED<br/>BillingServiceException → ${fCreate.code} Bad Gateway (${fCreate.ms}ms)"]:::bad`,
    '        RCV["SagaRecoveryService @Scheduled 60s —<br/>докатка незавершённых saga по saga_state"]:::neutral',
    '    end',
    '',
    '    subgraph BILLING["BILLINGService"]',
    `        BOK["200 · списано ${money(so.price)} ₽:<br/>${money(sDeposit?.body?.newBalance)} → ${money(sBalance?.body?.balance)} ₽ (success-прогон)"]:::ok`,
    `        B409["409 · ${errCode}<br/>balance ${money(fBalance?.body?.balance)} ₽, требуется ${money(fOrder?.price ?? so.price)} ₽ (failed-прогон)"]:::bad`,
    '        BRF["POST /internal/order/refund<br/>(компенсация BILLING_REFUND)"]:::comp',
    '    end',
    '',
    '    subgraph WAREHOUSE["WAREHOUSEService"]',
    `        W1["POST /internal/products/reservations → RESERVED<br/>confirm → CONFIRMED · stocks: available=${findStocksRaw(s.saga, so)?.availableQuantity} reserved=${findStocksRaw(s.saga, so)?.reservedQuantity}"]:::ok`,
    '        W404["броней нет → GET reservations = 404<br/>(шаг 2 не выполнялся)"]:::bad',
    '        WC["POST /internal/products/reservations/id/cancel<br/>(компенсация WAREHOUSE_CANCEL)"]:::comp',
    '    end',
    '',
    '    subgraph DELIVERY["DELIVERYService"]',
    `        D1["POST /internal/delivery/reservations → RESERVED<br/>confirm → CONFIRMED · ${so.deliveryDate} ${slot(so.slotStart)}–${slot(so.slotEnd)}"]:::ok`,
    '        D404["брони нет → GET reservation = 404<br/>(шаг 3 не выполнялся)"]:::bad',
    '        DC["POST /internal/delivery/reservations/id/cancel<br/>(компенсация DELIVERY_CANCEL)"]:::comp',
    '    end',
    '',
    '    subgraph NOTIF["NOTIFICATIONService — асинхронно через RabbitMQ"]',
    `        N1["outbox → OutboxPublisher → RabbitMQ →<br/>notification.queue: SUCCESS #${sNotifItem?.id ?? '?'}"]:::ok`,
    `        N2["outbox → OutboxPublisher → RabbitMQ →<br/>notification.queue: FAILED #${fNotifItem?.id ?? '?'}"]:::bad`,
    '    end',
    '',
    '    A --> S0',
    '    S0 --> ST1',
    '    ST1 -->|"success-прогон: 200 OK · деньги списаны"| BOK',
    '    BOK --> W1',
    '    W1 --> D1',
    '    D1 -->|"оба confirm OK"| SUC',
    '    SUC -->|"outbox: SUCCESS-событие"| N1',
    '    N1 --> V1',
    `    ST1 -->|"failed-прогон: 409 ${errCode}"| B409`,
    '    B409 --> CAT',
    '',
    '    subgraph COMP["compensate() — откат в обратном порядке (только выполненные шаги)"]',
    '        direction TB',
    `        C1["1. DELIVERY_CANCEL — ПРОПУЩЕН<br/>deliveryReserved=false → ${f.saga[3]?.code} при проверке"]:::comp`,
    `        C2["2. WAREHOUSE_CANCEL — ПРОПУЩЕН<br/>warehouseReserved=false → ${f.saga[2]?.code} при проверке"]:::comp`,
    `        C3["3. BILLING_REFUND — ПРОПУЩЕН<br/>billingReserved=false → balance ${money(fBalance?.body?.balance)} ₽ без изменений"]:::comp`,
    '        C1 --> C2 --> C3',
    '    end',
    '',
    '    CAT --> COMP',
    '    COMP --> FAIL',
    '    FAIL -->|"outbox: FAILED-событие"| N2',
    '    N2 --> V2',
    '    B409 -.->|"до шагов 2–3 дело не дошло"| W404',
    '    B409 -.->|"до шагов 2–3 дело не дошло"| D404',
    '    W404 -.-> V2',
    '    D404 -.-> V2',
    '    SUC -.-> RCV',
    '    FAIL -.-> RCV',
    '',
    '    %% При сбое на ДРУГОМ шаге (см. коллекции failed-warehouse/failed-delivery) compensate()',
    '    %% реально вызывает DC/WC/BRF в показанном порядке — узлы компенсаций уже на схеме.',
  ];
}

function findStocksRaw(sagaExecs, order) {
  const e = sagaExecs.find((x) => x.method === 'GET' && x.path.startsWith('/api/v1/products/'));
  return e?.body ?? null;
}
function findStocks(sagaExecs, order) {
  const b = findStocksRaw(sagaExecs, order);
  return b ? `available=${b.availableQuantity}/reserved=${b.reservedQuantity}` : 'stocks ?/?';
}

// ---------- запись файлов ----------

function writeFile(dir, name, content) {
  const full = path.join(dir, name);
  fs.writeFileSync(full, content, 'utf8');
  console.log(`written: ${full}`);
}

function mdDoc(title, sources, mermaidLines, notes) {
  return [
    `# ${title}`,
    '',
    ...sources,
    '',
    '```mermaid',
    ...mermaidLines,
    '```',
    '',
    ...notes,
    '',
  ].join('\n');
}

function srcLine(run) {
  return `- Источник: \`${path.relative(PROJECT_ROOT, run.file).replace(/\\/g, '/')}\` — ${run.stats.requests} ${plural(run.stats.requests, ['запрос', 'запроса', 'запросов'])}, ` +
    `${run.stats.assertions} assertions (${run.stats.assertionsFailed} failed), длительность ${run.durationSec}s, старт ${run.startedAt}`;
}

const LEGEND = [
  '## Легенда',
  '',
  '- `->> / -->>` — синхронный REST-вызов (сплошная/пунктирная стрелка с наконечником).',
  '- `--) / --x` — асинхронная доставка RabbitMQ (`--)`), сбой/ошибка (`--x`, `-x`).',
  '- Внутренние вызовы ORDER → BILLING/WAREHOUSE/DELIVERY (`/internal/**`) производные из кода',
  '  `OrderServiceImpl.runSaga/compensate` — newman их не видит; коллекции проверяют результат.',
  '- Уведомления: transactional outbox → scheduled `OutboxPublisher` → RabbitMQ → `NOTIFICATIONService.NotificationConsumer`.',
  '- ✅ / ❌ — статус assertions newman по факту прогона, `(Nms)` — фактическое время ответа.',
].join('\n');

// ---------- main ----------

const opts = parseArgs(process.argv);
if (!fs.existsSync(opts.out)) fs.mkdirSync(opts.out, { recursive: true });

const successRun = loadRun(opts.success);
const failedRun = loadRun(opts.failed);
console.log(`success: ${successRun.file}`);
console.log(`failed:  ${failedRun.file}`);

const genNote = `> Сгенерировано ${new Date().toISOString().slice(0, 16).replace('T', ' ')} UTC скриптом \`scripts/08-gen-saga-diagrams.ps1\` (генератор \`scripts/gen-saga-diagrams.mjs\`). Не редактировать вручную — перегенерировать.`;

const successSeq = buildSuccessSequence(successRun);
const failedSeq = buildFailedSequence(failedRun);
const flow = buildFlow(successRun, failedRun);

writeFile(opts.out, 'saga-success-sequence.mmd', successSeq.join('\n') + '\n');
writeFile(opts.out, 'saga-success-sequence.md', mdDoc(
  'Saga SUCCESS — sequence-диаграмма прогона otus-fp-success-k8s',
  [genNote, srcLine(successRun)],
  successSeq,
  [LEGEND, '', `Прогон: **${successRun.name}**. Saga-часть — шаги ${splitSaga(successRun.execs).saga.map((e) => e.step).join(', ')}; setup (${splitSaga(successRun.execs).setup.length} запросов) свёрнут в note.`, ''].join('\n').split('\n'),
));

writeFile(opts.out, 'saga-failed-billing-sequence.mmd', failedSeq.join('\n') + '\n');
writeFile(opts.out, 'saga-failed-billing-sequence.md', mdDoc(
  'Saga FAILED (billing) — sequence-диаграмма прогона otus-fp-failed-billing-k8s',
  [genNote, srcLine(failedRun)],
  failedSeq,
  [LEGEND, '', `Прогон: **${failedRun.name}**. Отказ на первом шаге saga (BILLING_WITHDRAW → ${errOf(failedRun)}), компенсации пропущены — откатывать нечего; отсутствие побочных эффектов подтверждают проверки (404/404, balance 0, FAILED-уведомление).`, ''].join('\n').split('\n'),
));

writeFile(opts.out, 'saga-flow.mmd', flow.join('\n') + '\n');
writeFile(opts.out, 'saga-flow.md', mdDoc(
  'Saga ORDER → BILLING → WAREHOUSE → DELIVERY — блок-схема с ветвлением и компенсациями',
  [genNote, srcLine(successRun), srcLine(failedRun)],
  flow,
  [
    LEGEND,
    '',
    'Зелёный путь — success-прогон (`otus-fp-success-k8s`), красный — failed-billing (`otus-fp-failed-billing-k8s`).',
    'Жёлтые узлы — логика компенсаций `compensate()` (обратный порядок DELIVERY → WAREHOUSE → BILLING,',
    'выполняются только реально пройденные шаги). В billing-сценарии все три пропускаются: сбой на первом шаге.',
    '',
  ],
));

function errOf(run) {
  const n = findExec(run.execs, 'GET', '/api/v1/notification');
  const item = Array.isArray(n?.body) ? n.body.find((x) => x.notificationStatus === 'FAILED') : null;
  return /BILLING_[A-Z_]+/.exec(item?.message ?? '')?.[0] ?? ' billing error';
}

const readme = [
  '# Диаграммы saga-прогонов Postman (k8s)',
  '',
  genNote.replace('Не редактировать вручную — перегенерировать.', 'Каталог поддерживается генератором.'),
  '',
  '| Файл | Что показывает |',
  '| --- | --- |',
  '| `saga-success-sequence.md` / `.png` / `.svg` | Success-прогон: saga внутри `POST /api/v1/order` (withdraw → reserve ×2 → confirm ×2 → PLACED) + проверки 13–18 |',
  '| `saga-failed-billing-sequence.md` / `.png` / `.svg` | Отказ BILLING (409 INSUFFICIENT_FUNDS) на первом шаге → compensate() (пропущен — нечего откатывать) → FAILED + 502, проверки компенсаций 12–16 |',
  '| `saga-flow.md` / `.png` / `.svg` | Сводная блок-схема: зелёный success-путь, красная ветка billing-failure, compensations subgraph, RabbitMQ-уведомления, SagaRecoveryService |',
  '',
  'PNG рендерятся в максимальном разрешении (`useMaxWidth=false` + авто-scale до предела Chrome ~14000px/сторона);',
  '`.svg` — векторная копия: надписи читаются при любом зуме без потери качества (для презентации предпочтительно).',
  '',
  'Источники данных (newman JSON-прогоны):',
  srcLine(successRun),
  srcLine(failedRun),
  '',
  '## Как перегенерировать',
  '',
  '1. Прогнать коллекции с JSON-отчётом (из `project/`):',
  '',
  '   ```powershell',
  '   .\\scripts\\06-run-postman-k8s.ps1 -Collections \'.\\postman\\k8s\\otus-fp-success-k8s.postman_collection.json\',\'.\\postman\\k8s\\otus-fp-failed-billing-k8s.postman_collection.json\' -HtmlReport -JsonReport',
  '   ```',
  '',
  '2. Сгенерировать диаграммы, PNG и SVG (берёт самые свежие `reports/k8s/newman-json-otus-fp-{success,failed-billing}-k8s-*.json`):',
  '',
  '   ```powershell',
  '   .\\scripts\\08-gen-saga-diagrams.ps1              # md + mmd + PNG (авто-scale до макс. разрешения) + SVG',
  '   .\\scripts\\08-gen-saga-diagrams.ps1 -PngScale 4  # ограничить потолок scale для PNG',
  '   .\\scripts\\08-gen-saga-diagrams.ps1 -SkipPng     # только md/mmd, без рендера PNG/SVG',
  '   ```',
  '',
  'Внутренние вызовы ORDER→BILLING/WAREHOUSE/DELIVERY на диаграммах — из кода `ORDERService`',
  '(`OrderServiceImpl.runSaga/compensate`, клиенты `BillingServiceClient`/`WarehouseServiceClient`/`DeliveryServiceClient`),',
  'newman их не наблюдает; факты прогона — коды ответов, ✅/❌ assertions, тайминги, суммы и ID — из JSON-отчётов выше.',
  '',
].join('\n');
writeFile(opts.out, 'README.md', readme);

console.log('OK');

# HW05 - Prometheus + Grafana Monitoring

Домашнее задание №5 по курсу "Microservice Architecture" OTUS.

## Цель

Инструментировать User CRUD сервис метриками Prometheus, развернуть стек мониторинга (Prometheus + Grafana) в minikube через отдельный Helm chart, создать дашборды с метриками приложения, nginx-ingress-controller, подов и PostgreSQL, настроить алертинг.

## Решение задачи производилось под Windows11, Docker Desktop и MINIKUBE

## Директории проекта

- `src/main/java` - simple Java Spring Boot application
- `src/main/docker` - файл Dockerfile
- `hw04chart/` - helm chart с необходимыми шаблонами для создания манифестов kubernetes
- `prometheus-grafana-chart/` - helm charts с необходимыми шаблонами для установки monitoring stack
- `scripts/` - PowerShell-скрипты по этапам и один общий запускной скрипт
- `postman/` - коллекция Postman и environment для Newman
- `report/` - отчет выполнения стресс-тестирования newman с использованием postman коллекции
- `docs/` - документация, включая скриншоты
---

## Архитектура

```
┌─────────────────────────────────────────────────────────────────┐
│                        Minikube Cluster                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────────┐         ┌────────────────────────────┐    │
│  │  User CRUD App   │────────>│  PostgreSQL StatefulSet    │    │
│  │    (hw05:hw05)   │         │       (1 replica)          │    │
│  └────────┬─────────┘         └────────────────────────────┘    │
│           │                                                     │
│           │ /actuator/prometheus                                │
│           ▼                                                     │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              Monitoring Stack                            │   │
│  │  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐   │   │
│  │  │ Prometheus  │    │   Grafana   │    │ PostgreSQL  │   │   │
│  │  │  (1 replica)│<───│  (1 replica)│    │  Exporter   │   │   │
│  │  └─────────────┘    └─────────────┘    └─────────────┘   │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              Ingress Layer                               │   │
│  │  ┌─────────────────────────────────────────────────────┐ │   │
│  │  │         nginx-ingress-controller                    │ │   │
│  │  │  - grafana.arch.homework                            │ │   │
│  │  │  - prometheus.arch.homework                         │ │   │
│  │  │  - arch.homework (app)                              │ │   │
│  │  └─────────────────────────────────────────────────────┘ │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

## Перед запуском

1. Запустить Docker Desktop
2. Убедиться, что установлены `minikube`, `kubectl`, `helm`, `nodejs`, `newman`, `newman-reporter-htmlextra`
---

## Подготовка окружения

### 1. Очистка старого окружения

```powershell
# Удалить старый релиз hw04 если он есть
helm uninstall hw04

# Удалить PersistentVolume для старого PostgreSQL если они остались
kubectl delete pvc data-hw04-postgresql-0
```

### 2. Установить nginx-ingress-controller через Helm

Установка через Helm (вместо minikube addon) необходима для включения метрик Prometheus:

```powershell
# Добавить Helm репозиторий ingress-nginx
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm repo update

# Установить ingress-nginx с включёнными метриками
helm install ingress-nginx ingress-nginx/ingress-nginx \
  --namespace ingress-nginx \
  --create-namespace \
  --set controller.metrics.enabled=true \
  --set controller.metrics.serviceMonitor.enabled=true \
  --set controller.metrics.serviceMonitor.additionalLabels.release=monitoring

# Проверить, что ingress-controller запущен
kubectl get pods -n ingress-nginx

# Проверить, что порт metrics (10254) доступен
kubectl get svc -n ingress-nginx ingress-nginx-controller -o jsonpath='{.spec.ports[*].name}'
```

**Важно:** параметр `controller.metrics.enabled=true` включает экспорт метрик `nginx_ingress_controller_*` на порту 10254. Без этого метрики ingress-controller не будут доступны в Prometheus и дашборд Grafana не сможет отображать данные по Nginx Ingress.

### 3. Добавить DNS записи

Добавьте в файл `C:\Windows\System32\drivers\etc\hosts`:

```
<minikube-ip> grafana.arch.homework
<minikube-ip> prometheus.arch.homework
<minikube-ip> arch.homework
```

Получить IP minikube:

```powershell
minikube ip
```

## Сборка и публикация Docker образа

```powershell
# Собрать образ
docker build -t akinxela/otusapp:hw05 -f src/main/docker/Dockerfile .

# Опубликовать в Docker Hub
docker push akinxela/otusapp:hw05
```

## Установка приложения

```powershell
# Установить приложение через Helm с секретными значениями
# values-secret.yaml содержит пароль от PostgreSQL
helm install hw05 ./hw04chart -f ./hw04chart/values-secret.yaml

# Проверить статус подов
kubectl get pods

# Проверить логи
kubectl logs -l app.kubernetes.io/name=hw04chart -f
```

**Важно:** Файл `hw04chart/values-secret.yaml` содержит пароль от PostgreSQL и должен передаваться при установке через флаг `-f`. Этот же секрет будет использоваться PostgreSQL Exporter для мониторинга базы данных.

### Структура values-secret.yaml

```yaml
postgresql:
  auth:
    password: "your_super_secret_password"
```

Этот файл переопределяет пустой пароль из основного `values.yaml` и используется для создания Kubernetes Secret.

## Установка мониторинга

### 1. Добавить Helm репозитории

```powershell
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
```

### 2. Установить зависимости

```powershell
cd prometheus-grafana-chart
helm dependency update
cd ..
```

### 3. Установить мониторинг

```powershell
helm install monitoring ./prometheus-grafana-chart
```

### 4. Проверить статус

```powershell
# Проверить все поды
kubectl get pods

# Проверить ServiceMonitor'ы
kubectl get servicemonitor

# Проверить PrometheusRule
kubectl get prometheusrule
```

## Доступ к мониторингу

### Grafana

- URL: http://grafana.arch.homework
- Логин: admin (анонимный доступ включен с ролью Admin)

### Prometheus

- URL: http://prometheus.arch.homework

## Дашборды

### Application Metrics

Дашборд `HW05 - Application Monitoring Dashboard` включает:

1. **Application Latency by API Method**
   - p50, p95, p99, max для каждого API метода
   - Метрика: `http_server_requests_seconds_bucket`

2. **Application RPS by API Method**
   - Requests per second для каждого API метода
   - Метрика: `http_server_requests_seconds_count`

3. **Application Error Rate (5xx) by API Method**
   - Процент 5xx ошибок для каждого API метода
   - Метрика: `http_server_requests_seconds_count` с фильтром `status=~"5.."`

4. **Nginx Ingress Latency**
   - p50, p95, p99, max
   - Метрика: `nginx_ingress_controller_request_duration_seconds_bucket`

5. **Nginx Ingress RPS**
   - Requests per second
   - Метрика: `nginx_ingress_controller_requests`

6. **Nginx Ingress Error Rate (5xx)**
   - Процент 5xx ошибок
   - Метрика: `nginx_ingress_controller_requests` с фильтром `status=~"5.."`

7. **Pod CPU Usage**
   - Использование CPU подами приложения
   - Метрика: `container_cpu_usage_seconds_total`

8. **Pod Memory Usage**
   - Использование памяти подами приложения
   - Метрика: `container_memory_working_set_bytes`

9. **PostgreSQL Active Connections**
   - Количество активных соединений
   - Метрика: `pg_stat_database_numbackends`

10. **PostgreSQL Cache Hit Ratio**
    - Коэффициент попаданий в кэш
    - Метрика: `pg_stat_database_blks_hit / (blks_hit + blks_read)`

## Алертинг

Настроены следующие алерты в PrometheusRule:

### HighErrorRate

- **Условие**: Error Rate > 10% в течение 5 минут
- **Severity**: critical
- **Описание**: Срабатывает, когда процент 5xx ошибок превышает 10%

### HighLatency

- **Условие**: p95 latency > 2 секунды в течение 5 минут
- **Severity**: warning
- **Описание**: Срабатывает, когда 95-й перцентиль задержки превышает 2 секунды

### ApplicationDown

- **Условие**: Приложение недоступно в течение 1 минуты
- **Severity**: critical
- **Описание**: Срабатывает, когда все инстансы приложения недоступны

## Стресс-тестирование

Для создания нагрузки на приложение и проверки работы мониторинга необходимо провести стресс-тестирование в течение 5-10 минут.

### Как устроена коллекция

Коллекция [`postman/otus-hw5.postman_collection.json`](postman/otus-hw5.postman_collection.json) решает три ключевые проблемы, которые возникают при многократном запуске (Newman итерации или Collection Runner):

Каждая итерация создаёт пользователя с уникальными `userName` и `email`, поэтому 409 Conflict не возникает.

Все последующие запросы (`GET /user/{{userId}}`, `PUT /user/{{userId}}`, `DELETE /user/{{userId}}`) используют ID только что созданного пользователя.

### Порядок запросов в коллекции

| #  | Запрос                 | Метод  | Описание                                                             |
|----|------------------------|--------|----------------------------------------------------------------------|
| 01 | `/actuator/health`     | GET    | Проверка доступности сервиса                                         |
| 02 | `/user`                | POST   | Создание пользователя с уникальными данными, сохранение `{{userId}}` |
| 03 | `/user/{{userId}}`     | GET    | Получение созданного пользователя                                    |
| 04 | `/user`                | GET    | Получение списка всех пользователей                                  |
| 05 | `/user?ids={{userId}}` | GET    | Получение пользователя по ID из списка                               |
| 06 | `/user/{{userId}}`     | PUT    | Обновление созданного пользователя                                   |
| 07 | `/user/{{userId}}`     | DELETE | Удаление созданного пользователя (cleanup)                           |
| 08 | `/user/{{userId}}`     | GET    | Проверка удаления (ожидается 404)                                    |

Каждая итерация коллекции — это полный CRUD цикл: создание → чтение → обновление → удаление.

### Вариант 1: Использование PowerShell скрипта (РЕКОМЕНДУЕТСЯ)

```powershell
# Единичный прогон коллекции (1 итерация)
.\scripts\05-run-postman.ps1

# Стресс-тест: 1000 итераций с задержкой 50ms
.\scripts\05-run-postman.ps1 -StressTest

# Стресс-тест с HTML отчётом
.\scripts\05-run-postman.ps1 -StressTest -HtmlReport

# Кастомные параметры: 50 итераций, задержка 200ms
.\scripts\05-run-postman.ps1 -Iterations 50 -DelayMs 200

# Кастомные параметры с HTML отчётом
.\scripts\05-run-postman.ps1 -Iterations 200 -DelayMs 100 -HtmlReport
```

Отчёты сохраняются в директорию `reports/` с timestamp в имени файла.

### Вариант 2: Использование Postman Collection Runner

1. Откройте Postman
2. Импортируйте коллекцию из `postman/otus-hw5.postman_collection.json`
3. Импортируйте окружение из `postman/local.postman_environment.json`
4. Выберите коллекцию и нажмите **"Run"**
5. Настройте параметры запуска:
   - **Iterations**: 1000 (каждая итерация создаёт нового пользователя)
   - **Delay**: 50ms
   - **Timeout**: 300000ms (5 минут)
6. Нажмите **"Run Collection"**

> **Важно:** благодаря динамическим данным в POST запросе, каждая итерация создаёт нового уникального пользователя. Ошибки 409 Conflict не будет.

### Вариант 3: Использование Newman напрямую

#### Установка Newman

```powershell
npm install -g newman
npm install -g newman-reporter-htmlextra
```

#### Запуск

**Единичный прогон (проверка работоспособности):**

```powershell
newman run postman/otus-hw5.postman_collection.json \
  --environment postman/local.postman_environment.json
```

**Стресс-тест (1000 итераций, задержка 50ms):**

```powershell
newman run postman/otus-hw5.postman_collection.json \
  --environment postman/local.postman_environment.json \
  --iteration-count 1000 \
  --delay-request 50 \
  --timeout-request 30000
```

**Стресс-тест с HTML отчётом:**

```powershell
newman run postman/otus-hw5.postman_collection.json \
  --environment postman/local.postman_environment.json \
  --iteration-count 1000 \
  --delay-request 50 \
  --timeout-request 30000 \
  --reporters cli,htmlextra \
  --reporter-htmlextra-export reports/stress-test-report.html
```

**Непрерывная нагрузка в течение 10 минут (PowerShell):**

```powershell
$endTime = (Get-Date).AddMinutes(10)
$iteration = 1

while ((Get-Date) -lt $endTime) {
    Write-Host "=== Итерация $iteration ===" -ForegroundColor Green
    newman run postman/otus-hw5.postman_collection.json `
      --environment postman/local.postman_environment.json `
      --iteration-count 50 `
      --delay-request 50 `
      --timeout-request 30000 `
      --reporters cli
    
    $iteration++
    Start-Sleep -Seconds 1
}

Write-Host "Стресс-тест завершён. Всего итераций: $iteration" -ForegroundColor Cyan
```

#### Параметры Newman

| Параметр                      | Описание                      | Пример                                            |
|-------------------------------|-------------------------------|---------------------------------------------------|
| `--iteration-count`           | Количество итераций коллекции | `--iteration-count 100`                           |
| `--delay-request`             | Задержка между запросами (мс) | `--delay-request 50`                              |
| `--timeout-request`           | Таймаут запроса (мс)          | `--timeout-request 30000`                         |
| `--reporters`                 | Типы отчётов                  | `--reporters cli,htmlextra`                       |
| `--reporter-htmlextra-export` | Путь к HTML отчёту            | `--reporter-htmlextra-export reports/report.html` |
| `--bail`                      | Остановка при первой ошибке   | `--bail`                                          |
| `--suppress-exit-code`        | Не возвращать код ошибки      | `--suppress-exit-code`                            |

#### Расчёт нагрузки

Каждая итерация коллекции выполняет 8 запросов (полный CRUD цикл). При настройках:
- **100 итераций** × 8 запросов = **800 HTTP запросов**
- **100 итераций** с **задержкой 50ms** ≈ ~40 секунд
- Для 5-10 минут нагрузки используйте `--iteration-count 500-1000` или циклический запуск

### Ожидаемые результаты

При 10% вероятности 500 ошибок:
- Примерно 10% запросов будут возвращать HTTP 500
- Error Rate на графиках должен показывать ~10%
- Latency должна оставаться в пределах нормы (p95 < 2s)
- RPS (requests per second) должен быть стабильным

### Мониторинг во время теста

Во время стресс-теста откройте Grafana и наблюдайте за метриками:

1. **Application Metrics** - метрики приложения (latency, RPS, error rate)
   - URL: http://grafana.arch.homework
   - Dashboard: "HW05 - Application Monitoring Dashboard"

2. **Nginx Ingress Metrics** - метрики ingress controller
   - Latency p50, p95, p99
   - RPS и error rate

3. **Pod Metrics** - использование CPU и памяти подами
   - CPU usage (должен быть < 80%)
   - Memory usage (должен быть < 512Mi)

4. **PostgreSQL Metrics** - метрики базы данных
   - Active connections
   - Cache hit ratio (должен быть > 95%)

### Проверка алертов

Во время стресс-теста проверьте срабатывание алертов:

1. Откройте Prometheus: http://prometheus.arch.homework
2. Перейдите в раздел "Alerts"
3. Проверьте статус алертов:
   - **HighErrorRate** - должен сработать при error rate > 10%
   - **HighLatency** - должен сработать при p95 > 2s
   - **ApplicationDown** - не должен сработать (приложение работает)

4. В Grafana проверьте раздел "Alerting" -> "Alert rules"

## PromQL запросы

### Application Latency by API Method

**p50 (50-й перцентиль задержки):**
```promql
histogram_quantile(0.5, sum(rate(http_server_requests_seconds_bucket{application="hw05"}[5m])) by (le, uri, method))
```

**p95 (95-й перцентиль задержки):**
```promql
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application="hw05"}[5m])) by (le, uri, method))
```

**p99 (99-й перцентиль задержки):**
```promql
histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{application="hw05"}[5m])) by (le, uri, method))
```

**max (максимальная задержка):**
```promql
max(http_server_requests_seconds_max{application="hw05"}) by (uri, method)
```

### Application RPS by API Method

```promql
sum(rate(http_server_requests_seconds_count{application="hw05"}[1m])) by (uri, method)
```

### Application Error Rate (5xx) by API Method

```promql
(sum(rate(http_server_requests_seconds_count{application="hw05", status=~"5.."}[5m])) by (uri, method) / sum(rate(http_server_requests_seconds_count{application="hw05"}[5m])) by (uri, method)) * 100
```

### Nginx Ingress Latency

**p50 (50-й перцентиль задержки):**
```promql
histogram_quantile(0.5, sum(rate(nginx_ingress_controller_request_duration_seconds_bucket{ingress=~".*hw05.*|.*hw04chart.*"}[5m])) by (le, ingress))
```

**p95 (95-й перцентиль задержки):**
```promql
histogram_quantile(0.95, sum(rate(nginx_ingress_controller_request_duration_seconds_bucket{ingress=~".*hw05.*|.*hw04chart.*"}[5m])) by (le, ingress))
```

**p99 (99-й перцентиль задержки):**
```promql
histogram_quantile(0.99, sum(rate(nginx_ingress_controller_request_duration_seconds_bucket{ingress=~".*hw05.*|.*hw04chart.*"}[5m])) by (le, ingress))
```

**max (максимальная задержка):**
```promql
histogram_quantile(1.0, sum(rate(nginx_ingress_controller_request_duration_seconds_bucket{ingress=~".*hw05.*|.*hw04chart.*"}[5m])) by (le, ingress))
```

### Nginx Ingress RPS

```promql
sum(rate(nginx_ingress_controller_requests{ingress=~".*hw05.*|.*hw04chart.*"}[1m])) by (ingress)
```

### Nginx Ingress Error Rate (5xx)

```promql
(sum(rate(nginx_ingress_controller_requests{ingress=~".*hw05.*|.*hw04chart.*", status=~"5.."}[5m])) by (ingress) / sum(rate(nginx_ingress_controller_requests{ingress=~".*hw05.*|.*hw04chart.*"}[5m])) by (ingress)) * 100
```

### Pod CPU Usage (cores)

```promql
sum by (pod) (rate(container_cpu_usage_seconds_total{pod=~"hw05-hw04chart.*"}[5m]))
```

### Pod Memory Usage

```promql
container_memory_working_set_bytes{pod=~"hw05-hw04chart.*"}
```

### PostgreSQL Active Connections

```promql
pg_stat_database_numbackends{datname="hw04db"}
```

### PostgreSQL Cache Hit Ratio

```promql
rate(pg_stat_database_blks_hit{datname="hw04db"}[5m]) / (rate(pg_stat_database_blks_hit{datname="hw04db"}[5m]) + rate(pg_stat_database_blks_read{datname="hw04db"}[5m])) * 100
```

### PostgreSQL Database Size

```promql
pg_database_size_bytes{datname="hw04db"}
```

### PostgreSQL Row Activity (per second)

```promql
rate(pg_stat_database_tup_fetched{datname="hw04db"}[5m])
```

```promql
rate(pg_stat_database_tup_inserted{datname="hw04db"}[5m])
```

```promql
rate(pg_stat_database_tup_updated{datname="hw04db"}[5m])
```

```promql
rate(pg_stat_database_tup_deleted{datname="hw04db"}[5m])
```

### PostgreSQL Transactions (per second)

```promql
rate(pg_stat_database_xact_commit{datname="hw04db"}[5m])
```

```promql
rate(pg_stat_database_xact_rollback{datname="hw04db"}[5m])
```

## Удаление

```powershell
# Удалить мониторинг
helm uninstall monitoring

# Удалить приложение
helm uninstall hw05

# Удалить PVC
kubectl delete pvc data-hw05-postgresql-0
kubectl delete pvc prometheus-monitoring-kube-prometheus-prometheus-db-prometheus-monitoring-kube-prometheus-prometheus-0
```

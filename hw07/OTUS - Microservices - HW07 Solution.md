# HW07 - RESTful / Stream processing

Домашнее задание №7 по курсу "Microservice Architecture" OTUS.

## Цель

Выполнить домашнее задание №7 (см. файл [README.md](./README.md)). Спроектировать взаимодействие сервисов при создании заказов. 
Запустить 4 микросервиса (User, Billing, Order, Notification) в Kubernetes (minikube + Helm)
с доступом через `http://{{baseUrl}}/api/v1/...` (nginx ingress) и прогнать postman-сценарии через newman.

## Решение задачи производилось под Windows 11, Docker Desktop и MINIKUBE

## Директории проекта

- `USERService/`, `BILLINGService/`, `ORDERService/`, `NOTIFICATIONService/` - Spring Boot микросервисы
- `COMMONDomain/` - общая библиотека домена
- `DEV/` - файлы docker для локальной разработки
- `hw07chart/` - Helm chart с манифестами Kubernetes
- `scripts/` - PowerShell-скрипты по этапам
- `postman/` - коллекции Postman и environment для Newman
- `reports/` - отчёты выполнения стресс-тестирования

---

## Архитектура Minikube Cluster

```
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│                          Minikube Cluster (namespace: default)                                  │
│                                                                                                 │
│  ingress-nginx (subchart)                                                                       │
│   arch.homework/health/*        → rewrite /actuator/health (4 paths)                            │
│   arch.homework/api/v1/auth|user|profile|roles → hw07-user-service:8000                         │
│   arch.homework/api/v1/account               → hw07-billing-service:8001                        │
│   arch.homework/api/v1/order                 → hw07-order-service:8002                          │
│   arch.homework/api/v1/notification          → hw07-notification-svc:8003                       │
│                                                                                                 │
│  Deployments (replicas=1):                                                                      │
│   hw07-user-service ─ HTTP /internal/account ─► hw07-billing-service                            │
│   hw07-order-service ─ HTTP /internal/order/withdraw|refund ─► hw07-billing                     │
│   hw07-order-service ─ AMQP publish ─► hw07-rabbitmq ◄─ listen ─ hw07-notification-service      │
│                                                                                                 │
│  StatefulSets postgres:16-alpine (PVC 1Gi каждый):                                              │
│   hw07-postgres-user | hw07-postgres-billing | hw07-postgres-order | hw07-postgres-notification │
│  Deployment rabbitmq:4.1.3-management: hw07-rabbitmq (5672, 15672)                              │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Перед запуском

1. Запустить Docker Desktop
2. Убедиться, что установлены `minikube`, `helm`, `node`, `newman`, `newman-reporter-htmlextra`

---

## Подготовка окружения

### 1. Очистка старого окружения

```bash
# Удалить старый релиз hw06 если он есть
helm uninstall hw06
```

```bash
# Удалить PersistentVolume для старого PostgreSQL если они остались
kubectl delete pvc data-hw06-postgresql-0
```

### 2. Запуск minikube

```bash
.\scripts\02-start-minikube.ps1
```

**Важно:** `minikube start --memory=10240` (10 ГБ) — 4 сервиса + 4 PostgreSQL + RabbitMQ + ingress.

### 3. Сборка и публикация Docker образов

```bash
.\scripts\01-build-and-push.ps1 -DockerHubLogin akinxela
```

Образы: `akinxela/otusapp:hw07-user`, `:hw07-billing`, `:hw07-order`, `:hw07-notification`.

### 4. Добавить DNS записи

```bash
# Узнать IP minikube кластера
minikube ip
```

Добавить в `C:\Windows\System32\drivers\etc\hosts`:
```
<minikube-ip> arch.homework
```

Если ingress недоступен - запустить 
```bash
# Запустить minikube tunnel 
minikube tunnel
```

---

## Установка приложения

### 4. Установка через Helm

```bash
.\scripts\03-helm-hw07-install.ps1
```

**Важно:** namespace - `default`.

Проверка статуса:
```bash
kubectl get pods
```

---

## Важные моменты реализации

### 5. Мультимодульный проект
В рамках домашнего задания, для упрощения выбран мультимодульный проект Gradle. 
В реальном проекте, разработка каждого микросервиса должна осуществляться в отдельном git репозитории

### 6. Внутреннее взаимодействие микросервисов
Микросервисы UserService, OrderService и BillingService взаимодействуют между собой используя внутренний api 
(Internal API через endpoint `http://{{baseUrl}}/internal/...`, авторизуясь через header ключ `X-Internal-API-Key`)
С помощью внутреннего api происходит запрос из UserService в BillingService на создание аккаунта при создании пользователя.
С помощью внутреннего api происходит запрос из OrderService в BillingService о достаточности денег на счету.

### 7. Общая библиотека
В домашнем задании использован принцип DRY и общие для всех микросервисов классы вынесены в отдельную библиотеку COMMONDomain.
В реальном проекте, COMMONDomain может быть собран отдельно и подключаться к каждому микросервису как зависимость.

### 8. Использование брокера сообщений.
В реализации домашнего задания использован брокер сообщений RabbitMQ для передачи уведомлений от OrderService к NotificationService.
Таким образом используется архитектурный паттерн - "событийное взаимодействие с использование брокера сообщений для нотификаций (уведомлений)".

---

## Postman-тесты

### 9. Запуск тестов (Kubernetes)

```bash
.\scripts\06-run-postman-k8s.ps1 -HtmlReport
```

Отчёты сохраняются в `reports/` с timestamp в имени файла.
Т.к. отчеты собирались в файлы html (с помощью newman-reporter-htmlextra), то для их просмотра необходимо открыть файлы отчетов в любом браузере.
Чтобы посмотреть данных запроса и данных ответа, необходимо развернуть "схлопнутые" части отчета.

### Структура коллекций

Коллекции `otus-hw7-success-k8s` и `otus-hw7-failed-k8s` используют `{{baseUrl}}` = `http://arch.homework`.
Коллекция `otus-hw7-success-k8s` отрабатывает сценарий, когда на балансе пользователя достаточно денег и размещение заказа происходит успешно.
Коллекция `otus-hw7-failed-k8s` отрабатывает сценарий, когда на балансе пользователя не достаточно денег и размещение заказа не происходит.

| #  | Запрос | Метод | Описание |
|----|--------|-------|----------|
| 01 | `/health/user` | GET | Проверка доступности USERService |
| 02 | `/health/billing` | GET | Проверка доступности BILLINGService |
| 03 | `/health/order` | GET | Проверка доступности ORDERService |
| 04 | `/health/notification` | GET | Проверка доступности NOTIFICATIONService |
| 05 | `/api/v1/auth/register` | POST | Регистрация пользователя (создаётся аккаунт в биллинге) |
| 06 | `/api/v1/account/user/{userId}` | GET | Проверка баланса (0 или текущий) |
| 07 | `/api/v1/account/{userId}/deposit` | POST | Пополнение счёта (1000 руб.) |
| 08 | `/api/v1/order` | POST | Создание заказа (success: 250 руб.; failed: 1000 руб.) |
| 09 | `/api/v1/account/user/{userId}` | GET | Проверка баланса после заказа |
| 10 | `/api/v1/notification?userId={userId}` | GET | Проверка отправленных уведомлений |

**Важно:** Тесты используют `{{baseUrl}}` с initial значением `http://arch.homework`.

---

### 10. Удаление приложения

```bash
.\scripts\07-helm-hw07-uninstall.ps1
```

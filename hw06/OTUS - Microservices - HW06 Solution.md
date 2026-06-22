# HW06 - Backend for frontends. APIGateway

Домашнее задание №6 по курсу "Microservice Architecture" OTUS.

## Цель

### Научиться добавлять в приложение аутентификацию и регистрацию пользователей.

## Решение задачи производилось под Windows11, Docker Desktop и MINIKUBE

## Директории проекта

- `src/main/java` - simple Java Spring Boot application
- `src/main/docker` - файл Dockerfile
- `hw04chart/` - helm chart с необходимыми шаблонами для создания манифестов kubernetes
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
│  │ User CRUD+SEC App│────────>│  PostgreSQL StatefulSet    │    │
│  │    (hw06:hw06)   │         │       (1 replica)          │    │
│  └──────────────────┘         └────────────────────────────┘    │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              Ingress Layer                               │   │
│  │  ┌─────────────────────────────────────────────────────┐ │   │
│  │  │         nginx-ingress-controller                    │ │   │
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
# Удалить старый релиз hw05 если он есть
helm uninstall hw05

# Удалить PersistentVolume для старого PostgreSQL если они остались
kubectl delete pvc data-hw05-postgresql-0
```

### 2. Установить nginx-ingress-controller через Helm

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
```

**ВАЖНО!** В качестве APIGateWay использован NGINX-ingess


### 3. Добавить DNS записи

Добавьте в файл `C:\Windows\System32\drivers\etc\hosts`:

```
<minikube-ip> arch.homework
```

Получить IP minikube:

```powershell
minikube ip
```

## Сборка и публикация Docker образа

```powershell
# Собрать образ
docker build -t akinxela/otusapp:hw06 -f src/main/docker/Dockerfile .

# Опубликовать в Docker Hub
docker push akinxela/otusapp:hw06
```

## Установка приложения

```powershell
# Установить приложение через Helm с секретными значениями
# values-secret.yaml содержит пароль от PostgreSQL и JWT SecretKey
helm install hw06 ./hw04chart -f ./hw04chart/values-secret.yaml
```
**ВАЖНО:** namespace - default

```
# Проверить статус подов
kubectl get pods

# Проверить логи
kubectl logs -l app.kubernetes.io/name=hw04chart -f
```

**Важно:** Файл `hw04chart/values-secret.yaml` содержит пароль от PostgreSQL и JWT SecretKey и должен передаваться при установке через флаг `-f`.

### Структура values-secret.yaml

```yaml
postgresql:
  auth:
    password: "your_super_secret_password"
jwt:
  secretkey: "your_super_secret_jwt_key"
```

Этот файл переопределяет пустой пароль и JWT key из основного `values.yaml` и используется для создания Kubernetes Secret.

### Как устроена коллекция

Коллекция [`postman/otus-hw6.postman_collection.json`](postman/otus-hw6.postman_collection.json) решает ключевую задачу - проверить безопасность и изоляцию профилей пользователей в приложении с аутентификацией и регистрацией.
Она реализует полный тестовый сценарий, описанный в метаданных коллекции:

1. Регистрация пользователя 1
2. Проверка, что /api/v1/profile недоступен без аутентификации (GET и PUT)
3. Вход пользователя 1
4. Обновление профиля пользователя 1
5. Проверка обновлённого профиля
6. Выход пользователя 1
7. Регистрация пользователя 2
8. Вход пользователя 2
9. Проверка, что пользователь 2 видит свой собственный профиль, а не профиль пользователя 1

Для предотвращения конфликтов при многократных запусках (Newman или Collection Runner) каждая регистрация использует уникальные userName и email, 
генерируемые с помощью временной метки и случайного суффикса. Все последующие запросы используют переменные (user1Id, user1Token, user2Token и т.д.), 
установленные в результате предыдущих шагов.

Коллекция использует:
 - Переменные окружения (baseUrl);
 - Переменные коллекции (user1Email, user1Token, updatedUserName, и др.);
 - Сценарии Pre-request и Tests для автоматической генерации данных и валидации ответов;
 - Bearer-токен аутентификации через заголовок Authorization.

### Порядок запросов в коллекции

| #  | Запрос                  | Метод | Описание                                                                                                                       |
|----|-------------------------|-------|--------------------------------------------------------------------------------------------------------------------------------|
| 01 | `/actuator/health`      | GET   | Проверка доступности сервиса                                                                                                   |
| 02 | `/api/v1/auth/register` | POST  | Регистрация пользователя 1 с уникальными данными. Сохраняются: `user1Id`, `user1UserName`, `user1Email`, `user1Token` (пустой) |
| 03 | `/api/v1/profile`       | GET   | Без аутентификации → проверка ответа `401`                                                                                     |
| 04 | `/api/v1/profile`       | PUT   | Без аутентификации → проверка ответа `401`                                                                                     |
| 05 | `/api/v1/auth/login`    | POST  | Вход пользователя 1. Извлекается JWT и сохраняется в `user1Token`                                                              |
| 06 | `/api/v1/profile`       | PUT   | Обновление профиля пользователя 1 с новыми данными. Проверяется, что изменения применены                                       |
| 07 | `/api/v1/profile`       | GET   | Повторное чтение профиля пользователя 1 → верификация обновлённых данных                                                       |
| 08 | `/api/v1/auth/logout`   | POST  | Выход пользователя 1. Токен очищается (user1Token = "")                                                                        |
| 09 | `/api/v1/auth/register` | POST  | Регистрация пользователя 2 с уникальными данными. Сохраняются: `user2Id`, `user2UserName`, `user2Email`, `user2Token` (пустой) |
| 10 | `/api/v1/auth/login`    | POST  | Вход пользователя 2. Извлекается JWT и сохраняется в `user2Token`                                                              |
| 11 | `/api/v1/profile`       | GET   | Пользователь 2 запрашивает свой профиль → проверка, что возвращены данные пользователя 2                                       |
| 12 | `/api/v1/profile`       | GET   | Изоляция профилей: пользователь 2 физически не может получить профиль пользователя 1 — всегда получает свой собственный        |

Каждая итерация коллекции представляет собой полный цикл аутентификации и авторизации:
   * регистрация → проверка блокировки без токена → вход → редактирование/чтение профиля → выход → регистрация второго пользователя → проверка изоляции.

Благодаря динамическим переменным и тест-скриптам, коллекция гарантирует:
   * отсутствие дубликатов email / userName
   * корректную цепочку зависимостей между запросами
   * аудит безопасности - проверка 401 при отсутствии токена и изоляция между пользователями

### Вариант 1: Использование PowerShell скрипта (РЕКОМЕНДУЕТСЯ)

```powershell
# Единичный прогон коллекции (1 итерация)
.\scripts\05-run-postman.ps1
```

Отчёты сохраняются в директорию `reports/` с timestamp в имени файла.

### Вариант 2: Использование Postman Collection Runner

1. Откройте Postman
2. Импортируйте коллекцию из `postman/otus-hw6.postman_collection.json`
3. Импортируйте окружение из `postman/local.postman_environment.json`
4. Выберите коллекцию и нажмите **"Run"**
5. Нажмите **"Run Collection"**

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
newman run postman/otus-hw6.postman_collection.json \
  --environment postman/local.postman_environment.json
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


## Удаление

```powershell

# Удалить приложение
helm uninstall hw06

# Удалить PVC
kubectl delete pvc data-hw06-postgresql-0
```

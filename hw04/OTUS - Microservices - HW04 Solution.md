# OTUS - Microservices - HW04 Solution

## Решение задачи производилось под Windows11, Docker Desktop и MINIKUBE

## Директории проекта

- `src/main/java` - simple Java Spring Boot application
- `src/main/docker` - файл Dockerfile
- `hw04chart/` - helm chart с необходимыми шаблонами для создания манифестов kubernetes
- `k8s/` - готовые Kubernetes манифесты для ручного применения через kubectl
- `scripts/` - PowerShell-скрипты по этапам и один общий запускной скрипт
- `postman/` - коллекция Postman и environment для Newman
---

## Перед запуском

1. Запустить Docker Desktop
2. Убедиться, что установлены `minikube`, `kubectl`, `helm`, `nodejs`, `newman`
---

## Запуск через kubectl (ручное применение манифестов)

**Важно!** Все команды выполняются из корневой папки проекта.

### Шаг 1: Установка базы данных PostgreSQL

Применить манифесты для создания Secret, ConfigMap, StatefulSet и Service для PostgreSQL:

```powershell
kubectl apply -f k8s/01-postgresql-secret.yaml
kubectl apply -f k8s/02-postgresql-configmap.yaml
kubectl apply -f k8s/03-postgres-statefulset.yaml
kubectl apply -f k8s/04-postgresql-service.yaml
```

Дождаться готовности PostgreSQL:

```powershell
kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=postgres --timeout=120s
```

### Шаг 2: Применение миграций базы данных

Запустить Job для применения Flyway миграций:

```powershell
kubectl apply -f k8s/09-flyway-migration-job.yaml
```

Дождаться завершения миграций:

```powershell
kubectl wait --for=condition=complete job/hw04-flyway-migrate --timeout=120s
```

### Шаг 3: Запуск приложения

Применить манифесты для ConfigMap приложения, Deployment, Service и Ingress:

```powershell
kubectl apply -f k8s/05-app-configmap.yaml
kubectl apply -f k8s/06-app-deployment.yaml
kubectl apply -f k8s/07-app-service.yaml
kubectl apply -f k8s/08-ingress.yaml
```

Дождаться готовности приложения:

```powershell
kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=hw04 --timeout=120s
```

---

## Запуск через Helm (одной командой)

**Важно!** Все команды выполняются из корневой папки проекта.

### Вариант 1: Установка с использованием values-secret.yaml

```powershell
helm install hw04 ./hw04chart -f ./hw04chart/values-secret.yaml
```

### Вариант 2: Установка с передачей пароля через --set

```powershell
helm install hw04 ./hw04chart --set postgresql.auth.password=hw04Password
```

### Вариант 3: Установка с использованием скрипта (при наличии Release, происходи update вместо install)

```powershell
.\scripts\03-helm-install.ps1 -ValuesFile ./hw04chart/values-secret.yaml
```

---

## Запуск по этапам

**Важно!** Запуск скриптов производится из корневой папки проекта с помощью PowerShell

```powershell
.\scripts\01-build-and-push.ps1 -DockerHubLogin YOUR_DOCKERHUB_LOGIN
.\scripts\02-start-minikube.ps1
.\scripts\03-helm-install.ps1 -ValuesFile ./hw04chart/secret-values.yaml
.\scripts\04-show-access-info.ps1
.\scripts\05-run-postman.ps1 -Collection .\postman\otus-hw4.postman_collection.json -Environment .\postman\local.postman_environment.json
.\scripts\06-helm-uninstall.ps1 -ReleaseName hw04 -Namespace default
```
---

## Проверка

```powershell
curl http://arch.homework/health
```
Запуск коллекции postman через newman:

**Важно!** Запуск коллекции производится из корневой папки проекта с помощью PowerShell

```powershell
newman run .\postman\otus-hw4.postman_collection.json -e .\postman\local.postman_environment.json
```

Через скрипт:

```powershell
.\scripts\05-run-postman.ps1
```
---

## Удаление

**Важно!** Запуск скрипта удаления производится из корневой папки проекта с помощью PowerShell

```powershell
.\scripts\06-helm-uninstall.ps1 -ReleaseName hw04 -Namespace default
```
---


# OTUS - Microservices - HW03 Solution

## Решение задачи производилось под Windows11 и Docker Desktop

## Директории проекта

- `src/main/java` - simple Java Spring Boot application
- `src/main/docker` - файл Dockerfile
- `k8s/` - манифесты Kubernetes
- `scripts/` - PowerShell-скрипты по этапам и один общий запускной скрипт
- `postman/` - коллекция Postman и environment для Newman
---

## Перед запуском

1. Запустить Docker Desktop
2. Убедиться, что установлены `minikube`, `kubectl`, `helm`, `nodejs`, `newman`
3. Заменить значение `image` в `k8s/deployment.yaml` на свое название имиджа в формате `<YOUR DOCKER HUB LOGIN>/otusapp:hw03`. 
4. Заменить значение в `path` в `k8s/ingress-rewrite.yaml` и в `postman/local.postman_environment.json` на свое имя студента
5. Заменить значение в `$StudentName` в `k8s/run-all.ps1` на свое имя студента
---

## Запуск по этапам

**Важно!** Запуск скриптов производится из корневой папки проекта с помощью PowerShell

```powershell
.\scripts\01-build-and-push.ps1 -DockerHubLogin YOUR_DOCKERHUB_LOGIN
.\scripts\02-start-minikube.ps1
.\scripts\03-install-ingress.ps1
.\scripts\04-apply-k8s.ps1 -ManifestDir .\k8s
.\scripts\05-show-access-info.ps1
.\scripts\06-run-postman.ps1 -Collection .\postman\otus-hw3.postman_collection.json -Environment .\postman\local.postman_environment.json
```
---

## Единый запускной скрипт

**Важно!** Запуск единого скрипта производится из корневой папки проекта с помощью PowerShell

```powershell
.\scripts\run-all.ps1 -DockerHubLogin YOUR_DOCKERHUB_LOGIN -StudentName yourname -ManifestDir .\k8s
```
---

## Проверка

```powershell
curl http://arch.homework/health
curl http://arch.homework/otusapp/yourname/health
```
Запуск коллекции postman через newman:

**Важно!** Запуск коллекции производится из корневой папки проекта с помощью PowerShell

```powershell
newman run .\postman\otus-hw3.postman_collection.json -e .\postman\local.postman_environment.json
```

Через скрипт:

```powershell
.\scripts\06-run-postman.ps1
```
---

## Удаление

**Важно!** Запуск скрипта удаления производится из корневой папки проекта с помощью PowerShell

```powershell
.\scripts\07-cleanup.ps1 -ManifestDir .\k8s
```

Полное удаление minikube:

```powershell
.\scripts\07-cleanup.ps1 -ManifestDir .\k8s -DeleteMinikube
```
---


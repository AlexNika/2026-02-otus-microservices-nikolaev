# Урок №12 - Базовые сущности Kubernetes: Service, Ingress

# Домашнее задание

## Основы работы с Kubernetes

### Цель: В этом ДЗ вы научитесь создавать минимальный сервис.

## Описание/Пошаговая инструкция выполнения домашнего задания

---

<u>**Вариант 1 (С КОДОМ)**</u>

Шаг 1. Создать минимальный сервис, который:

- Отвечает на порту 8000;
- Имеет http-метод:
  - GET /health/
  - RESPONSE: {"status": "OK"}

Шаг 2. 
- Собрать локально образ приложения в докер-контейнер под архитектуру AMD64;
- Запушить образ в DockerHub.

Шаг 3.
- Написать манифесты для деплоя в k8s для этого сервиса;
- Манифесты должны описывать сущности: Deployment, Service, Ingress;
- В Deployment могут быть указаны Liveness, Readiness пробы;
- Количество реплик должно быть не меньше 2;
- Image контейнера должен быть указан с DockerHub;
- Хост в ингрессе должен быть arch.homework.

В итоге после применения манифестов GET запрос на http://arch.homework/health должен отдавать {“status”: “OK”}.

Шаг 4. На выходе необходимо предоставить:

- Ссылку на GitHub c манифестами (в виде pull request);
- Манифесты должны лежать в одной директории, так чтобы можно было их все применить одной командой kubectl apply -f;
- Url, по которому можно будет получить ответ от сервиса (либо тест в postman).

**Задание со звездой:**
В Ingress-е должно быть правило, которое форвардит все запросы с /otusapp/{student name}/* на сервис с rewrite-ом пути. 
Где {student name} - это имя студента.

Например: curl arch.homework/otusapp/aeugene/health → рерайт пути на arch.homework/health

**Рекомендации по форме сдачи дз:**
- использовать nginx ingress контроллер, установленный через helm, а не встроенный в minikube:
  `kubectl create namespace m`
  `helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx/`
  `helm repo update`
  `helm install nginx ingress-nginx/ingress-nginx --namespace m -f nginx-ingress.yaml` (файл по ссылке)
- https://kubernetes.github.io/ingress-nginx/user-guide/basic-usage/ необходимо в новых версиях nginx добавлять класс ингресса `ingressClassName: nginx`;
- прикладывать к 2 дз урл для проверки: curl http://arch.homework/health или как указано в дз со *;
- К 3 ДЗ и далее прикладывать коллекцию postman и проверять ее работу через newman run имя_коллекции (прикладывать кроме команд разворачивания приложения, команду удаления);
- прописать у себя в /etc/hosts хост arch.homework с адресом своего миникубика (minikube ip), чтобы обращение было по имени хоста в запросах, а не IP.

Обратите внимание, что при сборке на m1 при запуске вашего контейнера на стандартных платформах будет ошибка такого вида:
standard_init_linux.go:228: exec user process caused: exec format error

Для сборки рекомендую указать тип платформы linux/amd64:
docker build --platform linux/amd64 -t tag

---

<u>**Вариант 2 (БЕЗ КОДА)**</u>

Предложить тест на основные сущности кубера из 10 вопросов.

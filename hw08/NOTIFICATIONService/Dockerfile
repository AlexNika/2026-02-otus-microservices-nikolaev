# syntax=docker/dockerfile:1
# Step1 - Сборка модуля (build context = корень hw08, нужен COMMONDomain)
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /app
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
COPY COMMONDomain ./COMMONDomain
COPY NOTIFICATIONService ./NOTIFICATIONService
# Gradle 9 требует существования каталогов всех проектов из settings.gradle (собирать их не нужно)
RUN mkdir -p USERService BILLINGService ORDERService WAREHOUSEService DELIVERYService
# Cache-mount /root/.gradle: дистрибутив gradle и зависимости кэшируются между сборками сервисов
RUN --mount=type=cache,target=/root/.gradle sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew :NOTIFICATIONService:bootJar -x test --no-daemon

# Step2 - Подготовка образа для запуска
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=builder /app/NOTIFICATIONService/build/libs/*.jar app.jar
EXPOSE 8003
ENTRYPOINT ["java", "-jar", "app.jar"]

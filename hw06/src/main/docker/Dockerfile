# Step1 - Сборка проекта
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY mvnw .
COPY .mvn ./.mvn
COPY src ./src
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw clean package -DskipTests

# Step2 - Подготовка образа для запуска
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "app.jar"]
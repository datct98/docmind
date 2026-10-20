# syntax=docker/dockerfile:1
# === Stage 1: Build (với BuildKit Maven Cache giúp build lại chỉ mất vài giây) ===
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy pom + tải dependency trước (cache layer + BuildKit /root/.m2 mount)
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B

# Copy source + build (skip test trong image build, tái sử dụng /root/.m2 cache)
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -B

# === Stage 2: Runtime ===
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8090

ENTRYPOINT ["java", "-jar", "app.jar"]

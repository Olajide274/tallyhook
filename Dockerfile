# This file builds the API with JDK 21 and runs it as a non-root user on JRE 21.
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace
COPY . .
RUN ./gradlew :api:bootJar --no-daemon

FROM eclipse-temurin:21-jre-jammy AS runtime

RUN useradd --system --uid 10001 --create-home tallyhook
WORKDIR /app
COPY --from=build --chown=tallyhook:tallyhook /workspace/api/build/libs/*-0.1.0.jar /app/app.jar
USER tallyhook
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

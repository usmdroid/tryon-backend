# --- Build bosqichi ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline -DskipTests || true
COPY src ./src
RUN mvn -q -DskipTests package

# --- Run bosqichi ---
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
# Konteyner RAM'ining ~70% ni heap'ga (qolgani ONNX native + metaspace uchun)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "/app/app.jar"]

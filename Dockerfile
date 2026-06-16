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
# Heap'ga ~55% (qolgani ONNX native off-heap + metaspace uchun), kam xotirali GC
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=55.0", "-XX:+UseSerialGC", "-XX:MaxMetaspaceSize=192m", "-jar", "/app/app.jar"]

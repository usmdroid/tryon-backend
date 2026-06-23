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
# Xotira optimallashuvi (kichik Railway instansi uchun):
#  - MaxRAMPercentage 45% — operatsion tizim/native (ImageIO/Tomcat)/metaspace uchun joy
#  - SerialGC — kam xotirali, single-thread (kichik instans uchun ideal)
#  - MaxMetaspaceSize 160m — class metadata cheklash
#  - ReservedCodeCacheSize 48m — JIT cache cheklash
#  - ExitOnOutOfMemoryError — OOM bo'lsa darhol chiqsin (Railway qayta tushiradi, hang'da turmaydi)
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=45.0", \
  "-XX:+UseSerialGC", \
  "-XX:MaxMetaspaceSize=160m", \
  "-XX:ReservedCodeCacheSize=48m", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "/app/app.jar"]

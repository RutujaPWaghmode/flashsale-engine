# Two stages: build the jar with the full JDK, then run it on the smaller JRE image.
# Build and run locally:  docker build -t flashsale . && docker run -p 8080:8080 --env-file .env flashsale

# --- Stage 1: build ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# Dependencies first, so code-only changes reuse the cached download layer
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
# Tests need MongoDB, Redis and Kafka, which the build machine does not have; run them locally before pushing
RUN ./mvnw -B -q -DskipTests package

# --- Stage 2: run ---
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 app
COPY --from=build /app/target/*.jar app.jar
USER app
EXPOSE 8080
# Sized for a 512 MB container (Render free): heap is a share of the container limit, one GC thread,
# and restart cleanly on out-of-memory instead of limping on
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "app.jar"]

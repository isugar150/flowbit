# Flowbit backend image (docs/02-architecture.md "Docker 이미지").
#   docker build -t flowbit-backend ./backend
# Run it with the variables in docs/02-architecture.md "환경과 설정"; uploads are written to
# /var/lib/flowbit/uploads, which must be a volume (it is what gets backed up).

# --- build: JDK 25 (the Gradle toolchain finds it here, nothing is downloaded) ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Gradle and the dependencies first, so editing the code does not download them again.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null

COPY src src
# Tests need Docker (Testcontainers) and run in development / CI, not in the image build.
RUN ./gradlew --no-daemon bootJar \
    && find build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} app.jar \;

# --- run: JRE only. Debian/Ubuntu base (glibc), which the bundled WebP encoder needs. ---
FROM eclipse-temurin:25-jre
RUN groupadd --system flowbit && useradd --system --gid flowbit --home /app flowbit \
    && mkdir -p /app /var/lib/flowbit/uploads \
    && chown -R flowbit:flowbit /app /var/lib/flowbit
WORKDIR /app
COPY --from=build --chown=flowbit:flowbit /workspace/app.jar app.jar

ENV SERVER_PORT=8080 \
    FLOWBIT_STORAGE_PATH=/var/lib/flowbit/uploads
VOLUME /var/lib/flowbit/uploads
EXPOSE 8080
USER flowbit
# MaxRAMPercentage: use the container's memory limit rather than the JVM's small default.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

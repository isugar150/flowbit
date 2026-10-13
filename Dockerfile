# Flowbit as one image: the backend (Spring Boot) and the frontend (Next.js) together
# (docs/02-architecture.md "Docker 이미지").
#   docker build -t flowbit .
# The container listens on 3000 (the frontend); the backend is only reached from inside, through
# the frontend's /api. The database is not included: give FLOWBIT_DB_* when running it.
# Uploads are written to /var/lib/flowbit/uploads, which must be a volume (it gets backed up).

# --- backend build: Amazon Corretto 25 (the Gradle toolchain asks for this vendor and finds it
# here, so nothing is downloaded). findutils: the Gradle wrapper needs xargs. ---
FROM amazoncorretto:25 AS backend-build
RUN dnf install -y findutils && dnf clean all
WORKDIR /workspace

# Gradle and the dependencies first, so editing the code does not download them again.
COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts ./
COPY backend/gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null

COPY backend/src src
# Tests need Docker (Testcontainers) and run in development / CI, not in the image build.
RUN ./gradlew --no-daemon bootJar \
    && find build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} app.jar \;

# --- frontend build: Next.js standalone output (next.config.ts `output: "standalone"`) ---
FROM node:22-bookworm-slim AS frontend-build
WORKDIR /app
# Written into the build (the /api rewrite). Inside the one container the backend is on localhost.
ENV BACKEND_URL=http://127.0.0.1:8080 \
    NEXT_TELEMETRY_DISABLED=1
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ .
RUN npm run build

# --- run: a small Debian with only the two runtimes added: Amazon Corretto 25 and Node.js 22,
# each taken from its official image. Debian (glibc) because the bundled WebP encoder needs it. ---
FROM debian:bookworm-slim
ENV JAVA_HOME=/opt/corretto-25
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=amazoncorretto:25 /usr/lib/jvm/java-25-amazon-corretto ${JAVA_HOME}
# The JDK's trust store (lib/security/cacerts) is a link to the system one in the Corretto image,
# so the folder alone arrives with a dangling link and every outgoing HTTPS call (SSO, webhooks,
# FCM) fails with "trustAnchors parameter must be non-empty". Put the file where the link points.
COPY --from=amazoncorretto:25 /etc/pki/ca-trust/extracted/java/cacerts /etc/pki/java/cacerts
COPY --from=node:22-bookworm-slim /usr/local/bin/node /usr/local/bin/node
# Corretto's JPEG reader (libjavajpeg.so) links the system libjpeg instead of bundling one. Without
# it the first JPEG upload dies with UnsatisfiedLinkError and the request never gets an answer.
RUN apt-get update && apt-get install -y --no-install-recommends libjpeg62-turbo \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system flowbit && useradd --system --gid flowbit --home /app flowbit \
    && mkdir -p /app/backend /app/frontend /var/lib/flowbit/uploads \
    && chown -R flowbit:flowbit /app /var/lib/flowbit

COPY --from=backend-build --chown=flowbit:flowbit /workspace/app.jar /app/backend/app.jar
# The standalone server does not carry the static files and public/: they go next to it.
COPY --from=frontend-build --chown=flowbit:flowbit /app/.next/standalone /app/frontend
COPY --from=frontend-build --chown=flowbit:flowbit /app/.next/static /app/frontend/.next/static
COPY --from=frontend-build --chown=flowbit:flowbit /app/public /app/frontend/public
COPY --chown=flowbit:flowbit docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

ENV FLOWBIT_STORAGE_PATH=/var/lib/flowbit/uploads \
    NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1
VOLUME /var/lib/flowbit/uploads
EXPOSE 3000
USER flowbit
WORKDIR /app
ENTRYPOINT ["/app/entrypoint.sh"]

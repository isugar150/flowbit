# syntax=docker/dockerfile:1
# Flowbit as one image: the backend (Spring Boot) and the frontend (Next.js) together
# (docs/02-architecture.md "Docker 이미지").
#   docker build -t flowbit .
# The container listens on 3000 (the frontend); the backend is only reached from inside, through
# the frontend's /api. The database is not included: give FLOWBIT_DB_* when running it.
# Uploads are written to /var/lib/flowbit/uploads, which must be a volume (it gets backed up).
#
# Build speed: what the builds download or work out (Gradle's dependencies and its compiled
# classes, npm's packages, Next.js's build cache) is kept between builds in BuildKit cache mounts,
# so a code change or a new base image does not start them from nothing. What goes into the image
# is split into layers that rarely change (libraries) and ones that change on every build (the
# application), so a push uploads only the latter.

# --- backend build: Amazon Corretto 25 (the Gradle toolchain asks for this vendor and finds it
# here, so nothing is downloaded). Gradle is started through the wrapper's jar rather than the
# gradlew script: the script needs xargs, which this image lacks, and installing it (findutils, with
# find) with dnf took about a minute every time the base image changed. So nothing below uses
# find or xargs either. ---
FROM amazoncorretto:25 AS backend-build
WORKDIR /workspace
COPY backend/settings.gradle.kts backend/build.gradle.kts ./
COPY backend/gradle gradle
COPY backend/src src
# Tests need Docker (Testcontainers) and run in development / CI, not in the image build.
# build/ and .gradle/ are kept too, so Kotlin compiles only what changed. The jar is then split
# into its layers (Spring Boot jarmode tools): libraries, loader, application.
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/workspace/.gradle \
    --mount=type=cache,target=/workspace/build \
    java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain --no-daemon bootJar \
    && for jar in build/libs/*.jar; do case "$jar" in *-plain.jar) ;; *) cp "$jar" app.jar ;; esac; done \
    && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# --- frontend build: Next.js standalone output (next.config.ts `output: "standalone"`) ---
FROM node:22-bookworm-slim AS frontend-build
WORKDIR /app
# Written into the build (the /api rewrite). Inside the one container the backend is on localhost.
ENV BACKEND_URL=http://127.0.0.1:8080 \
    NEXT_TELEMETRY_DISABLED=1
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/ .
# .next/cache holds Turbopack's build cache (on by default in Next.js 16.4). The libraries the
# standalone server needs are moved aside, to go into a layer of their own.
RUN --mount=type=cache,target=/app/.next/cache npm run build \
    && mv .next/standalone/node_modules /app/standalone-modules

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

# The backend in its layers, the ones that change least first: together they make
# /app/backend/app.jar with its libraries in /app/backend/lib, run as before (`java -jar`).
COPY --from=backend-build --chown=flowbit:flowbit /workspace/extracted/dependencies/ /app/backend/
COPY --from=backend-build --chown=flowbit:flowbit /workspace/extracted/spring-boot-loader/ /app/backend/
COPY --from=backend-build --chown=flowbit:flowbit /workspace/extracted/snapshot-dependencies/ /app/backend/
COPY --from=backend-build --chown=flowbit:flowbit /workspace/extracted/application/ /app/backend/
# The frontend: its libraries first, then the server and the pages. The standalone server does not
# carry the static files and public/: they go next to it.
COPY --from=frontend-build --chown=flowbit:flowbit /app/standalone-modules /app/frontend/node_modules
COPY --from=frontend-build --chown=flowbit:flowbit /app/.next/standalone /app/frontend
COPY --from=frontend-build --chown=flowbit:flowbit /app/.next/static /app/frontend/.next/static
COPY --from=frontend-build --chown=flowbit:flowbit /app/public /app/frontend/public
COPY --chown=flowbit:flowbit --chmod=755 docker/entrypoint.sh /app/entrypoint.sh

ENV FLOWBIT_STORAGE_PATH=/var/lib/flowbit/uploads \
    NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1
VOLUME /var/lib/flowbit/uploads
EXPOSE 3000
# Through the frontend to the backend to the database: one request checks all three (the first start waits for the
# database migration, so it gets a long start period). `docker ps` then says healthy or unhealthy.
HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=3 \
  CMD ["node", "-e", "fetch('http://127.0.0.1:3000/api/v1/health').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))"]
USER flowbit
WORKDIR /app
ENTRYPOINT ["/app/entrypoint.sh"]

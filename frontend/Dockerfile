# Flowbit frontend image (docs/02-architecture.md "Docker 이미지").
#   docker build --build-arg BACKEND_URL=http://backend:8080 -t flowbit-frontend ./frontend
# BACKEND_URL is fixed at build time: the /api rewrite to the backend is written into the build.
# Give the container the same value at run time (server components call the backend with it).

# --- dependencies ---
FROM node:22-bookworm-slim AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

# --- build: Next.js standalone output (next.config.ts `output: "standalone"`) ---
FROM node:22-bookworm-slim AS build
WORKDIR /app
ARG BACKEND_URL=http://backend:8080
ENV BACKEND_URL=${BACKEND_URL} \
    NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules node_modules
COPY . .
RUN npm run build

# --- run: only the standalone server, its static files and public/ ---
FROM node:22-bookworm-slim
WORKDIR /app
ARG BACKEND_URL=http://backend:8080
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    BACKEND_URL=${BACKEND_URL} \
    PORT=3000 \
    HOSTNAME=0.0.0.0
COPY --from=build --chown=node:node /app/public public
COPY --from=build --chown=node:node /app/.next/standalone ./
COPY --from=build --chown=node:node /app/.next/static .next/static
EXPOSE 3000
USER node
CMD ["node", "server.js"]

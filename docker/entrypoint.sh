#!/bin/bash
# Runs the backend and the frontend in the one Flowbit container (see ../Dockerfile).
# When either of them stops, the other is stopped too and the container exits, so Docker's
# restart policy brings both back together.
set -u

# The backend: only reached from inside the container, by the frontend. The heap may use 60% of the memory the
# container has (its limit, else the machine's): the rest is for the JVM's own memory (class metadata, threads, direct
# buffers) and the Node.js frontend that shares the container (about 150MB). When the heap does run out the JVM
# exits instead of limping on, which ends the container and lets the restart policy bring it back clean.
SERVER_PORT=8080 java -XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError -jar /app/backend/app.jar &
BACKEND_PID=$!

# The frontend: the port the container exposes. HOSTNAME is the address Next.js listens on
# (Docker sets it to the container id otherwise).
cd /app/frontend || exit 1
PORT=3000 HOSTNAME=0.0.0.0 BACKEND_URL=http://127.0.0.1:8080 node server.js &
FRONTEND_PID=$!

stop_both() {
  kill -TERM "$BACKEND_PID" "$FRONTEND_PID" 2>/dev/null
}

# `docker stop` sends TERM to this script; hand it on.
trap stop_both TERM INT

wait -n "$BACKEND_PID" "$FRONTEND_PID"
EXIT_CODE=$?

if kill -0 "$BACKEND_PID" 2>/dev/null; then
  echo "entrypoint: the frontend stopped (exit $EXIT_CODE); stopping the backend" >&2
else
  echo "entrypoint: the backend stopped (exit $EXIT_CODE); stopping the frontend" >&2
fi

stop_both
wait
exit "$EXIT_CODE"

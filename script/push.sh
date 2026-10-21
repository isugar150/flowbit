#!/usr/bin/env bash
# Run on a development PC (Docker running, `docker login` done): builds the Flowbit image (the
# backend and the frontend together, ../Dockerfile) from this checkout and pushes it to Docker
# Hub. The server then only runs deploy.sh.
#
#   ./script/push.sh             version = the current commit (short hash)
#   ./script/push.sh 1.0.0       a version of your own
#
# Pushed tags:  isugar150/flowbit:<version>   and   isugar150/flowbit:latest
# Another repository: FLOWBIT_IMAGE=someone/flowbit ./script/push.sh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
IMAGE="${FLOWBIT_IMAGE:-isugar150/flowbit}"

cd "$REPO_DIR"

command -v docker >/dev/null || { echo "docker not found."; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker is not running. Start Docker Desktop first."; exit 1; }

VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  VERSION="$(git rev-parse --short HEAD)"
  # The image would not be what that commit says.
  if [[ -n "$(git status --porcelain -- backend frontend Dockerfile docker/entrypoint.sh)" ]]; then
    VERSION="${VERSION}-dirty"
    echo "Uncommitted changes in what goes into the image: version is $VERSION"
  fi
fi

echo "=== Build $IMAGE:$VERSION ==="
# Always for x86-64 servers, also when this PC is an ARM one (Apple Silicon).
docker build --platform linux/amd64 -t "$IMAGE:$VERSION" -t "$IMAGE:latest" .

echo "=== Push ==="
docker push "$IMAGE:$VERSION"
docker push "$IMAGE:latest"

echo "=== Pushed ==="
echo "Version: $VERSION"
echo "On the server:  ./deploy.sh            (newest)"
echo "                ./deploy.sh $VERSION   (exactly this one)"

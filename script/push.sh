#!/usr/bin/env bash
# Run on a development PC (Docker running, `docker login` done): builds the backend and frontend
# images from this checkout and pushes them to Docker Hub. The server then only runs deploy.sh.
#
#   ./script/push.sh             version = the current commit (short hash)
#   ./script/push.sh 1.0.0       a version of your own
#
# Both images go to the one repository, told apart by the tag:
#   isugar150/flowbit:backend-<version>     isugar150/flowbit:backend-latest
#   isugar150/flowbit:frontend-<version>    isugar150/flowbit:frontend-latest
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
  if [[ -n "$(git status --porcelain -- backend frontend)" ]]; then
    VERSION="${VERSION}-dirty"
    echo "Uncommitted changes in backend/ or frontend/: version is $VERSION"
  fi
fi

echo "=== Build backend: $IMAGE:backend-$VERSION ==="
docker build -t "$IMAGE:backend-$VERSION" -t "$IMAGE:backend-latest" backend

# The address is written into the build (the /api rewrite): the backend's name in
# script/docker-compose.yml.
echo "=== Build frontend: $IMAGE:frontend-$VERSION ==="
docker build --build-arg BACKEND_URL=http://backend:8080 \
  -t "$IMAGE:frontend-$VERSION" -t "$IMAGE:frontend-latest" frontend

echo "=== Push ==="
for tag in "backend-$VERSION" "backend-latest" "frontend-$VERSION" "frontend-latest"; do
  docker push "$IMAGE:$tag"
done

echo "=== Pushed ==="
echo "Version: $VERSION"
echo "On the server:  ./deploy.sh            (newest)"
echo "                ./deploy.sh $VERSION   (exactly this one)"

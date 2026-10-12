#!/usr/bin/env bash
# git pull -> build -> stop -> replace -> start. The service is down for the moment between
# stop and start (there is no second instance to switch to).
#   ./deploy.sh        asks what to deploy
#   ./deploy.sh 1      backend + frontend
#   ./deploy.sh 2      backend
#   ./deploy.sh 3      frontend
# Everything is built before anything is stopped, so a build that fails leaves the running
# servers as they are.
set -euo pipefail

source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/common.sh"

validate_operator

mkdir -p "$(dirname "$DEPLOY_LOCK_FILE")"

exec 9>"$DEPLOY_LOCK_FILE"

if ! flock -n 9; then
  echo "Another deploy is already running."
  echo "Check: lsof $DEPLOY_LOCK_FILE"
  exit 1
fi

cleanup_lock() {
  flock -u 9 || true
}

trap cleanup_lock EXIT

print_deploy_menu() {
  echo "Select deploy target:"
  echo "  1) backend + frontend"
  echo "  2) backend"
  echo "  3) frontend"
}

select_deploy_target() {
  local selected="${1:-}"

  if [[ -z "$selected" ]]; then
    print_deploy_menu
    read -r -p "Select [1-3, default: 1]: " selected || selected=""
  fi

  selected="${selected//[[:space:]]/}"
  selected="${selected:-1}"

  case "$selected" in
    1)
      DEPLOY_BACKEND=true
      DEPLOY_FRONTEND=true
      DEPLOY_TARGET_LABEL="backend + frontend"
      ;;
    2)
      DEPLOY_BACKEND=true
      DEPLOY_FRONTEND=false
      DEPLOY_TARGET_LABEL="backend"
      ;;
    3)
      DEPLOY_BACKEND=false
      DEPLOY_FRONTEND=true
      DEPLOY_TARGET_LABEL="frontend"
      ;;
    *)
      echo "Invalid deploy target: $selected"
      echo "Use 1, 2, or 3."
      exit 1
      ;;
  esac
}

show_remote_updates() {
  echo "=== Check remote updates ==="
  cd "$SOURCE_DIR"

  local upstream=""
  upstream="$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null || true)"

  if [[ -z "$upstream" ]]; then
    echo "No upstream branch configured. Skip remote file preview."
    echo "Current branch: $(git rev-parse --abbrev-ref HEAD)"
    return 0
  fi

  echo "Fetch remote: $upstream"
  git fetch --prune

  local remote_commit_count="0"
  remote_commit_count="$(git rev-list --count HEAD.."$upstream" 2>/dev/null || echo "0")"

  if [[ "$remote_commit_count" == "0" ]]; then
    echo "No remote updates. Local HEAD is up to date with $upstream."
    return 0
  fi

  echo "Remote updates found: ${remote_commit_count} commit(s)"
  echo "Updated files from remote:"
  git diff --name-status HEAD.."$upstream"

  echo "Recent remote commits:"
  git log --oneline --decorate --max-count=10 HEAD.."$upstream"
}

pull_latest_source() {
  echo "=== Pull latest source ==="
  cd "$SOURCE_DIR"
  git pull
}

build_backend() {
  echo "=== Build backend ==="
  cd "$SOURCE_DIR/backend"
  # `sh`: works even when the checkout lost gradlew's executable bit.
  sh ./gradlew --no-daemon clean bootJar

  BUILT_JAR="$(find "$SOURCE_DIR/backend/build/libs" -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -n 1)"

  [[ -n "$BUILT_JAR" && -f "$BUILT_JAR" ]] || {
    echo "JAR not found in $SOURCE_DIR/backend/build/libs"
    exit 1
  }
  echo "Built: $BUILT_JAR"
}

build_frontend() {
  echo "=== Build frontend ==="
  cd "$SOURCE_DIR/frontend"

  # BACKEND_URL (exported by common.sh) is written into the build: the /api rewrite.
  echo "BACKEND_URL: $BACKEND_URL"
  npm ci --no-audit --no-fund
  NEXT_TELEMETRY_DISABLED=1 npm run build

  # The self-contained server (next.config.ts `output: "standalone"`).
  BUILT_SERVER_JS="$(find "$SOURCE_DIR/frontend/.next/standalone" -maxdepth 3 -name server.js -not -path '*/node_modules/*' | head -n 1)"

  [[ -n "$BUILT_SERVER_JS" && -f "$BUILT_SERVER_JS" ]] || {
    echo "Frontend build not found: $SOURCE_DIR/frontend/.next/standalone/server.js"
    exit 1
  }
}

deploy_backend() {
  echo "=== Deploy backend ==="
  mkdir -p "$BACKEND_RUN_DIR"

  # Copied next to the old one first, so the server is only down for stop + start.
  cp -f "$BUILT_JAR" "$BACKEND_JAR.new"

  stop_service backend

  if [[ -f "$BACKEND_JAR" ]]; then
    mv -f "$BACKEND_JAR" "$BACKEND_JAR.prev"
  fi
  mv -f "$BACKEND_JAR.new" "$BACKEND_JAR"

  start_service backend || {
    echo "Backend did not start. The previous build is kept as $BACKEND_JAR.prev"
    exit 1
  }
}

deploy_frontend() {
  echo "=== Deploy frontend ==="
  local standalone_dir staging="$FRONT_RUN_DIR.new"

  standalone_dir="$(dirname "$BUILT_SERVER_JS")"

  # The standalone server does not carry the static files and public/: they go next to it.
  rm -rf "$staging"
  mkdir -p "$staging"
  cp -a "$SOURCE_DIR/frontend/.next/standalone/." "$staging/"

  local staged_server_dir="$staging${standalone_dir#"$SOURCE_DIR/frontend/.next/standalone"}"
  mkdir -p "$staged_server_dir/.next"
  cp -a "$SOURCE_DIR/frontend/.next/static" "$staged_server_dir/.next/static"
  cp -a "$SOURCE_DIR/frontend/public" "$staged_server_dir/public"

  # startup.sh expects server.js right in $FRONT_RUN_DIR.
  if [[ "$staged_server_dir" != "$staging" ]]; then
    echo "Frontend server is nested in the build ($staged_server_dir). Expected it at the top."
    echo "Check that frontend/ has its own package-lock.json and no lockfile sits above it."
    rm -rf "$staging"
    exit 1
  fi

  stop_service frontend

  rm -rf "$FRONT_RUN_DIR.prev"
  if [[ -d "$FRONT_RUN_DIR" ]]; then
    mv "$FRONT_RUN_DIR" "$FRONT_RUN_DIR.prev"
  fi
  mv "$staging" "$FRONT_RUN_DIR"

  start_service frontend || {
    echo "Frontend did not start. The previous build is kept as $FRONT_RUN_DIR.prev"
    exit 1
  }
}

print_deploy_summary() {
  echo "=== Deploy completed ==="
  echo "Deploy target: $DEPLOY_TARGET_LABEL"
  echo "Commit: $(cd "$SOURCE_DIR" && git log --oneline --max-count=1)"

  if [[ "$DEPLOY_BACKEND" == "true" ]]; then
    echo "Backend:  http://127.0.0.1:${BACKEND_PORT}  ($BACKEND_JAR)"
  fi

  if [[ "$DEPLOY_FRONTEND" == "true" ]]; then
    echo "Frontend: http://127.0.0.1:${FRONT_PORT}  ($FRONT_RUN_DIR)"
  fi
}

show_remote_updates

select_deploy_target "${1:-}"

echo "Deploy target: $DEPLOY_TARGET_LABEL"

pull_latest_source

if [[ "$DEPLOY_BACKEND" == "true" ]]; then
  build_backend
fi

if [[ "$DEPLOY_FRONTEND" == "true" ]]; then
  build_frontend
fi

if [[ "$DEPLOY_BACKEND" == "true" ]]; then
  deploy_backend
fi

if [[ "$DEPLOY_FRONTEND" == "true" ]]; then
  deploy_frontend
fi

print_deploy_summary

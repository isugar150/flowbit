#!/usr/bin/env bash
# Shared by startup.sh, shutdown.sh and deploy.sh. Not run on its own: the others `source` it.
#
# Reads the script settings (app.env, then app.local.env when there is one) and the application's
# .env file, and defines how the two processes are started and stopped:
#   backend   java -jar   $RUN_DIR/backend/app.jar     port SERVER_PORT       (default 8080)
#   frontend  node        $RUN_DIR/frontend/server.js  port FLOWBIT_WEB_PORT  (default 3000)

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

# shellcheck source=app.env
source "$SCRIPT_DIR/app.env"
if [[ -f "$SCRIPT_DIR/app.local.env" ]]; then
  # shellcheck disable=SC1091
  source "$SCRIPT_DIR/app.local.env"
fi

is_true() {
  [[ "${1,,}" == "true" ]]
}

is_alive() {
  local pid="${1:-}"
  [[ -n "$pid" ]] && ps -p "$pid" >/dev/null 2>&1
}

# Exports every KEY=VALUE line of the application's .env file, so the backend and the frontend
# (and the frontend build) see them as environment variables. The file is read as data, not run
# as a shell script: a password with $ or spaces in it arrives as written. Quotes around a value
# are removed. A missing file is fine; the application then starts with its defaults.
load_app_env() {
  local file="$1" line key value

  if [[ ! -f "$file" ]]; then
    echo "App env file not found: $file (application defaults are used)"
    return 0
  fi

  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    line="${line#"${line%%[![:space:]]*}"}"
    [[ -z "$line" || "$line" == \#* ]] && continue
    line="${line#export }"
    [[ "$line" == *=* ]] || continue

    key="${line%%=*}"
    key="${key%"${key##*[![:space:]]}"}"
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue

    value="${line#*=}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    if [[ ${#value} -ge 2 ]]; then
      if [[ "$value" == \"*\" || "$value" == \'*\' ]]; then
        value="${value:1:${#value}-2}"
      fi
    fi

    export "$key=$value"
  done < "$file"
}

load_app_env "$ENV_FILE"

SOURCE_DIR="$(cd -- "$SOURCE_DIR" && pwd)"
BACKEND_PORT="${SERVER_PORT:-8080}"
FRONT_PORT="${FLOWBIT_WEB_PORT:-3000}"
# The frontend reaches the backend at this address, at build time (the /api rewrite is written
# into the build) and at run time. On one machine that is the backend's own port.
export BACKEND_URL="${BACKEND_URL:-http://127.0.0.1:${BACKEND_PORT}}"

BACKEND_RUN_DIR="$RUN_DIR/backend"
FRONT_RUN_DIR="$RUN_DIR/frontend"
BACKEND_JAR="$BACKEND_RUN_DIR/app.jar"
FRONT_SERVER_JS="$FRONT_RUN_DIR/server.js"

validate_operator() {
  local current_uid current_user
  current_uid="$(id -u)"
  current_user="$(id -un)"

  if [[ -n "$CONTROL_USER" && "$current_user" != "$CONTROL_USER" ]]; then
    echo "Only user '$CONTROL_USER' can control this app. Current user: '$current_user'"
    exit 1
  fi

  if [[ "$current_uid" -eq 0 ]] && ! is_true "$ALLOW_ROOT_EXECUTION"; then
    echo "Root execution is disabled. Set ALLOW_ROOT_EXECUTION=true to allow it."
    exit 1
  fi
}

# backend | frontend | all  ->  the services to act on, in start order.
services_of() {
  case "${1:-all}" in
    all) echo "backend frontend" ;;
    backend | frontend) echo "$1" ;;
    *)
      echo "Unknown target: $1 (use backend, frontend or all)" >&2
      return 1
      ;;
  esac
}

pid_file_of() {
  echo "$SCRIPT_DIR/$1.pid"
}

log_file_of() {
  echo "$LOG_DIR/$1.log"
}

port_of() {
  if [[ "$1" == "backend" ]]; then echo "$BACKEND_PORT"; else echo "$FRONT_PORT"; fi
}

# What the command line of a process must match before it is killed by port. The Next.js server
# renames itself to "next-server (v…)".
runtime_of() {
  if [[ "$1" == "backend" ]]; then echo "java"; else echo "node|next-server"; fi
}

get_cmdline() {
  ps -p "$1" -o args= 2>/dev/null || true
}

get_listener_pids_by_port() {
  local port="$1"
  local pids=""

  if command -v lsof >/dev/null 2>&1; then
    pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | sort -u || true)"
  fi

  if [[ -z "$pids" ]] && command -v ss >/dev/null 2>&1; then
    pids="$(
      ss -ltnp 2>/dev/null \
        | awk -v port=":$port" '$4 ~ port"$" {print $0}' \
        | sed -n 's/.*pid=\([0-9]\+\).*/\1/p' \
        | sort -u \
        || true
    )"
  fi

  if [[ -z "$pids" ]] && command -v fuser >/dev/null 2>&1; then
    pids="$(fuser "${port}/tcp" 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -u || true)"
  fi

  echo "$pids"
}

# The backend counts as up when its health endpoint answers 2xx (it also checks the database).
# The frontend counts as up when it answers at all: its first page may be a redirect to /login.
is_service_up() {
  local service="$1"

  if [[ "$service" == "backend" ]]; then
    curl -fsS "http://127.0.0.1:${BACKEND_PORT}${HEALTH_CHECK_PATH}" >/dev/null 2>&1
  else
    curl -sS -o /dev/null "http://127.0.0.1:${FRONT_PORT}/" 2>/dev/null
  fi
}

wait_health() {
  local service="$1" pid="$2"

  if ! is_true "$HEALTH_CHECK_ENABLED"; then
    return 0
  fi

  for ((i = 1; i <= HEALTH_CHECK_TIMEOUT; i++)); do
    if ! is_alive "$pid"; then
      echo "Process exited early. $service PID=$pid"
      return 1
    fi

    if is_service_up "$service"; then
      echo "Health check OK ($service)"
      return 0
    fi

    sleep "$HEALTH_CHECK_INTERVAL"
  done

  echo "Health check failed ($service). tries=${HEALTH_CHECK_TIMEOUT}"
  return 1
}

# Starts one service in the background and waits until it answers. Returns 1 when it does not
# come up (the process is then killed and the last log lines are printed).
start_service() {
  local service="$1"
  local pid_file log_file old_pid tail_pid="" child_pid

  pid_file="$(pid_file_of "$service")"
  log_file="$(log_file_of "$service")"

  if [[ "$service" == "backend" ]]; then
    command -v java >/dev/null || { echo "java not found"; return 1; }
    [[ -f "$BACKEND_JAR" ]] || { echo "JAR not found: $BACKEND_JAR (run deploy.sh first)"; return 1; }
  else
    command -v node >/dev/null || { echo "node not found"; return 1; }
    [[ -f "$FRONT_SERVER_JS" ]] || { echo "Frontend not found: $FRONT_SERVER_JS (run deploy.sh first)"; return 1; }
  fi

  if [[ -f "$pid_file" ]]; then
    old_pid="$(cat "$pid_file" 2>/dev/null || true)"
    old_pid="${old_pid//[[:space:]]/}"

    if is_alive "$old_pid"; then
      echo "Already running. $service PID=$old_pid"
      wait_health "$service" "$old_pid"
      return $?
    fi

    echo "Remove stale pid file: $pid_file"
    rm -f "$pid_file"
  fi

  mkdir -p "$LOG_DIR"
  # Keep the log of the previous run; each start begins a new file.
  if [[ -s "$log_file" ]]; then
    mv -f "$log_file" "$log_file.prev"
  fi
  : > "$log_file"

  echo "Starting $APP_NAME $service"
  echo "PORT: $(port_of "$service")"
  echo "PID_FILE: $pid_file"
  echo "LOG_FILE: $log_file"

  if is_true "$ECHO_START_LOG"; then
    tail -n 0 -F "$log_file" 9>&- &
    tail_pid="$!"
  fi

  # 9>&- : do not hand deploy.sh's lock to the server, or the lock would outlive the deploy.
  if [[ "$service" == "backend" ]]; then
    # Started from the script folder, so a relative FLOWBIT_STORAGE_PATH lands under it.
    cd "$SCRIPT_DIR"
    # shellcheck disable=SC2086
    nohup java $JAVA_OPTS \
      -jar "$BACKEND_JAR" \
      --server.port="$BACKEND_PORT" \
      $APP_ARGS \
      >> "$log_file" 2>&1 9>&- &
    child_pid="$!"
  else
    cd "$FRONT_RUN_DIR"
    NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT="$FRONT_PORT" HOSTNAME="$FRONT_HOST" \
      nohup node server.js >> "$log_file" 2>&1 9>&- &
    child_pid="$!"
  fi
  cd "$SCRIPT_DIR"

  echo "$child_pid" > "$pid_file"

  if wait_health "$service" "$child_pid"; then
    [[ -n "$tail_pid" ]] && kill "$tail_pid" 2>/dev/null || true
    echo "Started successfully. $service PID=$child_pid"
    return 0
  fi

  [[ -n "$tail_pid" ]] && kill "$tail_pid" 2>/dev/null || true
  echo "Startup failed ($service). Last logs:"
  tail -n 200 "$log_file" || true

  kill -TERM "$child_pid" 2>/dev/null || true
  sleep 3
  kill -KILL "$child_pid" 2>/dev/null || true
  rm -f "$pid_file"
  return 1
}

# The process in the pid file, and whatever java (backend) or node (frontend) process listens on
# the service's port: that catches a server whose pid file was lost.
collect_target_pids() {
  local service="$1"
  local pid_file pid port runtime listener_pid result=""

  pid_file="$(pid_file_of "$service")"
  port="$(port_of "$service")"
  runtime="$(runtime_of "$service")"

  if [[ -f "$pid_file" ]]; then
    pid="$(cat "$pid_file" 2>/dev/null || true)"
    pid="${pid//[[:space:]]/}"

    if [[ "$pid" =~ ^[0-9]+$ ]] && is_alive "$pid"; then
      result="$pid"
    fi
  fi

  while IFS= read -r listener_pid; do
    [[ -z "$listener_pid" ]] && continue

    if is_alive "$listener_pid" && [[ "$(get_cmdline "$listener_pid")" =~ $runtime ]]; then
      result="$(printf "%s\n%s\n" "$result" "$listener_pid")"
    else
      echo "Skip listener that is not ours ($runtime). port=$port pid=$listener_pid cmd=$(get_cmdline "$listener_pid")" >&2
    fi
  done <<< "$(get_listener_pids_by_port "$port")"

  echo "$result" | grep -E '^[0-9]+$' | sort -u || true
}

any_alive() {
  local pid
  while IFS= read -r pid; do
    [[ -z "$pid" ]] && continue
    if is_alive "$pid"; then return 0; fi
  done <<< "$1"
  return 1
}

signal_pids() {
  local signal="$1" pids="$2" pid
  while IFS= read -r pid; do
    [[ -z "$pid" ]] && continue
    if is_alive "$pid"; then
      echo "Send $signal to $APP_NAME. PID=$pid"
      kill "-$signal" "$pid" 2>/dev/null || true
    fi
  done <<< "$pids"
}

# Stops one service: TERM, wait, then KILL when allowed. Returns 1 when something is still alive.
stop_service() {
  local service="$1"
  local pid_file pids

  pid_file="$(pid_file_of "$service")"
  pids="$(collect_target_pids "$service")"

  if [[ -z "$pids" ]]; then
    echo "Not running: $service"
    rm -f "$pid_file"
    return 0
  fi

  echo "Stopping $service"
  signal_pids TERM "$pids"

  for ((i = 0; i < SHUTDOWN_WAIT_SECONDS; i++)); do
    if ! any_alive "$pids"; then
      rm -f "$pid_file"
      echo "Stopped: $service"
      return 0
    fi
    sleep 1
  done

  if is_true "$FORCE_KILL_ENABLED"; then
    echo "Graceful shutdown timeout. Force kill."
    signal_pids KILL "$pids"
    sleep 1

    if ! any_alive "$pids"; then
      rm -f "$pid_file"
      echo "Force killed: $service"
      return 0
    fi
  fi

  echo "Could not stop $service. Remaining processes:"
  while IFS= read -r pid; do
    [[ -z "$pid" ]] && continue
    if is_alive "$pid"; then echo "PID=$pid CMD=$(get_cmdline "$pid")"; fi
  done <<< "$pids"
  return 1
}

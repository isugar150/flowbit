#!/usr/bin/env bash
# Run on the server: pulls the Flowbit image from Docker Hub and (re)starts the container.
# The server needs Docker only. This file and docker-compose.yml must be in the same folder.
#
#   ./deploy.sh             the newest image (latest)
#   ./deploy.sh 1a2b3c4     a version pushed earlier (the tag push.sh printed); also the way back
#
# Settings are in .env next to this file. The first run creates it and stops: enter the address
# and account of the PostgreSQL database there (Flowbit does not run a database of its own) and
# run this again. Replacing a container takes the service down for a few seconds.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"
HEALTH_TRIES=120

cd "$SCRIPT_DIR"

command -v docker >/dev/null || { echo "docker not found. Install Docker first."; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "The docker compose plugin is missing."; exit 1; }

# One value from .env, without running the file as a shell script.
env_value() {
  local key="$1" default="$2" line
  line="$(grep -E "^[[:space:]]*${key}[[:space:]]*=" "$ENV_FILE" 2>/dev/null | tail -n 1 || true)"
  if [[ -z "$line" ]]; then
    echo "$default"
    return 0
  fi
  line="${line#*=}"
  line="${line%$'\r'}"
  line="${line#"${line%%[![:space:]]*}"}"
  line="${line%"${line##*[![:space:]]}"}"
  line="${line%\"}"
  line="${line#\"}"
  echo "${line:-$default}"
}

random_key() {
  if command -v openssl >/dev/null; then
    openssl rand -base64 32
  else
    head -c 32 /dev/urandom | base64
  fi
}

# An .env made before secrets were encrypted has no key: nothing is lost, the secrets just stay plain text.
warn_secret_key() {
  if [[ -z "$(env_value FLOWBIT_SECRET_KEY "")" ]]; then
    echo "NOTE: FLOWBIT_SECRET_KEY is not set in $ENV_FILE, so the secrets kept in the database (LDAP, SSO, mail, Firebase,"
    echo "webhook and Git secrets) are stored as plain text. To encrypt them, add a line like"
    echo "  FLOWBIT_SECRET_KEY=$(random_key)"
    echo "and run this script again: the secrets already stored are encrypted when the server starts. Keep that key safe;"
    echo "without it the encrypted secrets cannot be read."
  fi
}

create_env_file() {
  echo "=== Create .env ==="

  cat > "$ENV_FILE" <<'EOF'
# Flowbit 서버 설정. 고친 뒤 ./deploy.sh 를 다시 실행하면 적용된다.

# 데이터베이스 (PostgreSQL 18). Flowbit 은 DB 를 띄우지 않고 이미 있는 DB 에 붙는다.
# 빈 DB 와 계정만 만들어 두면 테이블은 백엔드가 처음 뜰 때 만든다.
# DB 가 이 서버에 있으면 localhost 가 아니라 host.docker.internal 을 적는다
# (컨테이너 안에서 localhost 는 컨테이너 자신이다).
FLOWBIT_DB_HOST=
FLOWBIT_DB_PORT=5432
FLOWBIT_DB_NAME=flowbit
FLOWBIT_DB_USER=
# 비밀번호에 $ 가 있으면 작은따옴표로 감싼다: FLOWBIT_DB_PASSWORD='pa$$word'
FLOWBIT_DB_PASSWORD=

# 브라우저로 여는 포트. 80 으로 바꾸면 http://서버주소 로 바로 열린다.
FLOWBIT_WEB_PORT=3000
# 그 포트를 여는 주소. 앞에 nginx 를 두고 이 서버 안에서만 열려면 127.0.0.1
# FLOWBIT_WEB_BIND=127.0.0.1

# 사람들이 Flowbit 을 여는 주소. 메일과 API 알림에 넣는 티켓 링크에 쓴다.
# FLOWBIT_PUBLIC_URL=http://flowbit.example.com

# 올릴 수 있는 이미지 한 장의 최대 크기
# FLOWBIT_MAX_UPLOAD_SIZE=5MB

# 올린 파일(이미지, 첨부 파일, 댓글 파일)을 어디에 둘지: local (컨테이너의 uploads 볼륨) 또는 amazon-s3 (아래 FLOWBIT_S3_*).
# 적은 대로 따른다. 바꾼 뒤 ./deploy.sh 를 다시 실행해야 적용된다.
# 같은 DB 를 쓰는 서버(개발 PC 에서 직접 띄운 것 등)는 모두 같은 값을 쓴다. 다르면 한쪽이 올린 사진이 다른 쪽에서 열리지 않는다.
# 비워 두면 FLOWBIT_S3_BUCKET 이 있을 때 amazon-s3, 없으면 local 이다. 바꾸기 전에 올라간 파일은 옮겨지지 않는다.
FLOWBIT_STORAGE_TYPE=local

# amazon-s3 일 때만 쓴다. 위를 amazon-s3 로 바꾸고 버킷 이름을 적는다. (local 인데 버킷이 적혀 있으면 시작할 때 경고가 남는다.)
# FLOWBIT_S3_BUCKET=
# FLOWBIT_S3_REGION=ap-northeast-2
# 키를 적지 않으면 서버에 붙은 IAM 역할이나 AWS_ACCESS_KEY_ID 같은 환경 변수를 쓴다.
# FLOWBIT_S3_ACCESS_KEY=
# FLOWBIT_S3_SECRET_KEY=
# 버킷 안의 폴더(선택). 비우면 버킷 맨 위
# FLOWBIT_S3_PREFIX=flowbit
# MinIO 같은 S3 호환 저장소를 쓸 때만 그 주소를 적는다(AWS 는 적지 않는다).
# FLOWBIT_S3_ENDPOINT=
EOF
  {
    echo
    echo "# DB 에 저장되는 비밀 값(LDAP·SSO·메일·Firebase·웹훅·Git 의 비밀번호와 키)을 암호화하는 암호(16자 이상). 처음 만들 때 정해진다."
    echo "# 잃어버리거나 바꾸면 이미 암호화된 비밀 값을 읽을 수 없다. 이 파일과 함께 안전한 곳에 보관한다."
    echo "FLOWBIT_SECRET_KEY=$(random_key)"
  } >> "$ENV_FILE"
  chmod 600 "$ENV_FILE" 2>/dev/null || true

  echo "Created $ENV_FILE"
  echo "Fill in the database settings (FLOWBIT_DB_HOST, FLOWBIT_DB_USER, FLOWBIT_DB_PASSWORD)"
  echo "and run this script again."
}

# Stops before anything is pulled or replaced when the database settings are not usable.
check_database_settings() {
  local missing=() key host
  for key in FLOWBIT_DB_HOST FLOWBIT_DB_USER FLOWBIT_DB_PASSWORD; do
    if [[ -z "$(env_value "$key" "")" ]]; then missing+=("$key"); fi
  done

  if [[ ${#missing[@]} -gt 0 ]]; then
    echo "Missing in $ENV_FILE: ${missing[*]}"
    echo "Flowbit uses an existing PostgreSQL database; enter its address and account."
    exit 1
  fi

  host="$(env_value FLOWBIT_DB_HOST "")"
  if [[ "$host" == "localhost" || "$host" == "127.0.0.1" || "$host" == "::1" ]]; then
    echo "FLOWBIT_DB_HOST=$host points at the container itself, not at this server."
    echo "For a database on this server use FLOWBIT_DB_HOST=host.docker.internal"
    exit 1
  fi
}

wait_until_up() {
  local port="$1"
  # Through the frontend to the backend to the database: one request checks all three.
  local url="http://127.0.0.1:${port}/api/v1/health"

  if ! command -v curl >/dev/null; then
    echo "curl not found. Skip the health check; see: docker compose ps"
    return 0
  fi

  echo "=== Wait until Flowbit answers: $url ==="
  for ((i = 1; i <= HEALTH_TRIES; i++)); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      echo "Health check OK"
      return 0
    fi
    sleep 1
  done

  echo "Flowbit did not answer within ${HEALTH_TRIES}s. If it cannot reach the database,"
  echo "check FLOWBIT_DB_* in .env and that the database accepts connections from this server."
  echo "Last logs:"
  docker compose logs --tail 80 || true
  docker compose ps || true
  return 1
}

if [[ ! -f "$ENV_FILE" ]]; then
  create_env_file
  exit 1
fi

check_database_settings
warn_secret_key

# Wins over a FLOWBIT_VERSION line in .env, so the next plain ./deploy.sh is the newest again.
if [[ -n "${1:-}" ]]; then
  export FLOWBIT_VERSION="$1"
fi
echo "Version: ${FLOWBIT_VERSION:-$(env_value FLOWBIT_VERSION latest)}"

WEB_PORT="$(env_value FLOWBIT_WEB_PORT 3000)"

# Everything is downloaded before anything is replaced: a failed pull leaves what is running.
echo "=== Pull image ==="
docker compose pull

echo "=== Start container ==="
docker compose up -d

wait_until_up "$WEB_PORT"

# The images that were just replaced.
docker image prune -f >/dev/null 2>&1 || true

echo "=== Deploy completed ==="
docker compose ps
echo "Open: http://<this server>:${WEB_PORT}"

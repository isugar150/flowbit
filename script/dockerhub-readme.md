# Flowbit

Linear처럼 빠르고 단순한 이슈 트래커입니다. 한 조직이 자기 서버에 설치해서 씁니다.

이 이미지 하나에 백엔드(Spring Boot)와 프런트엔드(Next.js)가 함께 들어 있습니다. **데이터베이스는 들어 있지 않습니다.** 이미 있는 PostgreSQL 18에 연결해서 씁니다.

## 태그

| 태그 | 내용 |
|---|---|
| `latest` | 가장 최근에 올린 버전 |
| `<커밋 해시>` (예: `1a2b3c4`) | 특정 버전. 되돌릴 때 씁니다 |

플랫폼은 `linux/amd64` 입니다.

## 빠른 시작

빈 PostgreSQL 18 데이터베이스와 계정을 먼저 만들어 둡니다. 테이블은 Flowbit이 처음 뜰 때 알아서 만듭니다.

```bash
docker run -d --name flowbit --restart unless-stopped \
  -p 3000:3000 \
  -e FLOWBIT_DB_HOST=db.example.com \
  -e FLOWBIT_DB_NAME=flowbit \
  -e FLOWBIT_DB_USER=flowbit \
  -e FLOWBIT_DB_PASSWORD=비밀번호 \
  -v flowbit-uploads:/var/lib/flowbit/uploads \
  isugar150/flowbit:latest
```

`http://서버주소:3000` 을 열면 초기 설정(관리자 계정 만들기) 화면이 나옵니다.

## Docker Compose

```yaml
services:
  flowbit:
    image: isugar150/flowbit:latest
    container_name: flowbit
    restart: unless-stopped
    environment:
      FLOWBIT_DB_HOST: db.example.com
      FLOWBIT_DB_PORT: 5432
      FLOWBIT_DB_NAME: flowbit
      FLOWBIT_DB_USER: flowbit
      FLOWBIT_DB_PASSWORD: 비밀번호
      FLOWBIT_PUBLIC_URL: http://flowbit.example.com
    extra_hosts:
      - "host.docker.internal:host-gateway"
    volumes:
      - uploads:/var/lib/flowbit/uploads
    ports:
      - "3000:3000"

volumes:
  uploads:
```

## 환경 변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `FLOWBIT_DB_HOST` | `localhost` | PostgreSQL 주소. **반드시 지정합니다** (아래 "같은 서버의 데이터베이스") |
| `FLOWBIT_DB_PORT` | `5432` | PostgreSQL 포트 |
| `FLOWBIT_DB_NAME` | `flowbit` | 데이터베이스 이름 |
| `FLOWBIT_DB_USER` | `flowbit` | 계정 |
| `FLOWBIT_DB_PASSWORD` | `flowbit` | 비밀번호. **반드시 지정합니다** |
| `FLOWBIT_PUBLIC_URL` | (없음) | 사람들이 Flowbit을 여는 주소. 알림 메일과 API 알림의 이슈 링크에 쓰입니다 |
| `FLOWBIT_MAX_UPLOAD_SIZE` | `5MB` | 올릴 수 있는 이미지 한 장의 최대 크기 |

LDAP, SSO, 메일(SMTP), 웹 푸시, API 알림은 환경 변수가 아니라 로그인한 뒤 관리자 설정 화면에서 설정합니다.

## 포트와 볼륨

| 항목 | 값 | 설명 |
|---|---|---|
| 포트 | `3000` | 웹 화면. 열어야 하는 포트는 이것 하나입니다 (API와 실시간 연결도 이 포트로 갑니다) |
| 볼륨 | `/var/lib/flowbit/uploads` | 올린 파일. **데이터베이스와 함께 백업해야 합니다** |

80 포트로 열려면 `-p 80:3000` 으로 바꿉니다.

## 같은 서버의 데이터베이스

컨테이너 안에서 `localhost` 는 컨테이너 자신입니다. PostgreSQL이 Docker를 돌리는 그 서버에 있으면 `localhost` 대신 `host.docker.internal` 을 씁니다.

```bash
docker run -d --name flowbit --restart unless-stopped \
  --add-host host.docker.internal:host-gateway \
  -p 3000:3000 \
  -e FLOWBIT_DB_HOST=host.docker.internal \
  -e FLOWBIT_DB_USER=flowbit \
  -e FLOWBIT_DB_PASSWORD=비밀번호 \
  -v flowbit-uploads:/var/lib/flowbit/uploads \
  isugar150/flowbit:latest
```

이때 PostgreSQL이 Docker 쪽 주소에서 오는 접속을 받도록 `listen_addresses` 와 `pg_hba.conf` 를 열어 두어야 합니다.

## 앞에 리버스 프록시를 둘 때

nginx 같은 프록시를 앞에 두면 다음을 지켜야 실시간 반영이 동작합니다.

- `Host` 헤더를 브라우저가 보낸 그대로 넘깁니다.
- WebSocket 업그레이드 헤더(`Upgrade`, `Connection`)를 넘깁니다.

웹 푸시 알림은 HTTPS에서만 동작합니다. HTTP로만 열면 메일과 API 알림만 쓸 수 있습니다.

## 업데이트

```bash
docker pull isugar150/flowbit:latest
docker rm -f flowbit
# 위의 docker run 명령을 다시 실행합니다
```

Compose를 쓰면 `docker compose pull && docker compose up -d` 입니다. 데이터베이스 스키마는 새 버전이 뜰 때 자동으로 올라갑니다. 올라간 뒤에 예전 버전으로 되돌리면 스키마가 맞지 않을 수 있으니, 업데이트 전에 데이터베이스를 백업해 두세요.

## 상태 확인

```bash
curl http://localhost:3000/api/v1/health
```

정상이면 `{"status":"UP"}` 이 나옵니다. 데이터베이스 연결까지 확인합니다. 뜨지 않으면 `docker logs flowbit` 을 봅니다.

## 구성

- Amazon Corretto 25, Node.js 22, Debian 12 (slim)
- 비루트 사용자(`flowbit`)로 실행됩니다
- 컨테이너 안에서 백엔드(8080, 밖으로 열지 않음)와 프런트엔드(3000)가 함께 돕니다. 둘 중 하나가 멈추면 컨테이너가 끝나고, 재시작 정책이 다시 올립니다

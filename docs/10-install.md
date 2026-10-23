# 10. 설치 가이드

Flowbit 을 서버에 설치해서 쓰기까지의 순서다. **서버에는 Docker 와 PostgreSQL 18 만 있으면 된다.** 개발 환경을 꾸리는 방법은 [README.md](../README.md), 구조와 환경 변수의 근거는 [02-architecture.md](02-architecture.md) 에 있다.

> **이전 개발 DB 가 있다면 지우고 시작한다 (D-168).** 개발 중에 쌓인 마이그레이션 26개(`V1`~`V26`)를 이번 배포 때 `V1__schema.sql` 하나로 합쳤다. 예전 파일로 만든 DB 는 Flyway 가 "이미 적용한 V1 이 달라졌다"며 시작을 거부한다. 쓰던 데이터를 옮기는 방법은 없다(사용자 결정: 일회성으로 모두 지움). 아래 2단계의 `DROP DATABASE` 를 한 번 실행한다. **그 DB 의 티켓·프로젝트·계정이 모두 사라진다.**
>
> **예외 (D-191, 2026-10-23)**: 그 뒤 `V2`~`V4` 가 더해졌다가 다시 `V1` 하나로 합쳤다. 이때는 DB 를 지우지 않는다. `V1`~`V4` 가 **모두 적용된** DB 에 새 빌드를 띄우기 **전에** 그 DB 에서 아래 SQL 을 한 번 실행하면 된다(`flyway_schema_history` 만 고친다. 데이터와 스키마는 그대로).
>
> ```sql
> BEGIN;
> DELETE FROM flyway_schema_history WHERE version IN ('2', '3', '4');
> UPDATE flyway_schema_history SET checksum = 440577120 WHERE version = '1';
> COMMIT;
> ```
>
> 새로 설치하는 DB 는 아무것도 하지 않는다. `V4` 까지 적용되지 않은 DB 는 먼저 이전 빌드로 `V4` 까지 올린 뒤 실행한다(안 그러면 목표 표가 남은 채로 기준이 잡힌다).

## 1. 구성

```
브라우저 ──▶ [Flowbit 컨테이너: 프런트(Next.js, 3000) → 백엔드(Spring Boot, 8080)] ──▶ PostgreSQL 18
                                   │
                                   └─▶ 올린 파일: 볼륨(기본) 또는 Amazon S3
```

- 이미지 하나에 백엔드와 프런트가 함께 들어 있다. 밖으로 여는 포트는 **3000 하나**이고(API 와 실시간 연결도 이 포트로 간다), 백엔드는 컨테이너 안에서만 닿는다.
- **PostgreSQL 은 이미지에 없다.** 이미 있는 PostgreSQL 18 에 연결한다. 테이블은 Flowbit 이 처음 뜰 때 알아서 만든다.
- 올린 파일(프로필 사진, 로고, 편집기 이미지, 티켓 첨부, 댓글 파일)은 기본으로 컨테이너의 `uploads` 볼륨에 둔다. 환경 변수만 넣으면 Amazon S3 에 둘 수 있다([5. 파일 저장소](#5-파일-저장소-로컬-또는-s3)).

필요한 것:

| 항목 | 내용 |
|---|---|
| 서버 | Linux(amd64) + Docker 와 `docker compose` 플러그인. 메모리는 2GB 이상을 권한다(백엔드 JVM 과 프런트 Node 가 한 컨테이너에서 돈다) |
| 데이터베이스 | PostgreSQL 18. 빈 DB 하나와 그 DB 를 쓸 수 있는 계정 |
| 개발 PC | 이미지를 만들어 올릴 때만: Docker, Docker Hub 계정(`docker login`), 이 저장소 |
| 선택 | 도메인과 HTTPS(웹 푸시·앱 설치에 필요), SMTP(메일 알림), Amazon S3 버킷 |

## 2. 데이터베이스 준비

PostgreSQL 서버에서(관리자 계정으로) 한 번만 한다.

```sql
-- 계정과 빈 DB. 비밀번호는 바꾼다.
CREATE ROLE flowbit WITH LOGIN PASSWORD '바꿀-비밀번호';
CREATE DATABASE flowbit OWNER flowbit;
```

- 이전 개발 DB 를 지우고 다시 만들 때는 `DROP DATABASE flowbit;` 를 먼저 한다(접속 중인 컨테이너가 있으면 멈춘 뒤에).
- 확장(extension)은 필요 없다. 마이그레이션이 만드는 것은 테이블, 인덱스, 함수 하나(`hangul_initials`, 초성 검색)뿐이다. DB 의 소유자 계정이면 충분하다.
- Flowbit 이 있는 서버와 DB 서버가 다르면 DB 서버가 Flowbit 서버의 접속을 받도록 `pg_hba.conf` 와 `listen_addresses` 를 연다. **같은 서버**에 있으면 [같은 서버의 데이터베이스](#같은-서버의-데이터베이스)를 본다.
- 시간대는 UTC 로 저장하고 화면에서 바꿔 보여 준다. 따로 맞출 것이 없다.

## 3. 이미지 올리기 (개발 PC)

```bash
./script/push.sh            # 버전 = 지금 커밋의 짧은 해시
./script/push.sh 1.0.0      # 버전을 직접 정할 때
```

`isugar150/flowbit:<버전>` 과 `:latest` 가 Docker Hub 에 올라간다(다른 저장소는 `FLOWBIT_IMAGE=someone/flowbit ./script/push.sh`). 커밋하지 않은 변경이 있으면 버전에 `-dirty` 가 붙는다. 이미지는 항상 `linux/amd64`(x86-64 서버용)로 만든다. 개발 PC 가 ARM(Apple Silicon)이어도 된다(`push.sh` 가 `--platform linux/amd64` 를 준다. 느릴 수 있다).

이미지를 받아 가는 곳이 Docker Hub 의 비공개 저장소라면 서버에서도 `docker login` 을 해 둔다.

## 4. 서버에 설치

서버에 폴더를 하나 만들고 저장소의 **`script/deploy.sh`, `script/docker-compose.yml`** 두 파일을 같은 폴더에 둔다(저장소 전체를 올릴 필요가 없다).

```bash
chmod +x deploy.sh
./deploy.sh
```

처음 실행하면 `.env` 양식이 만들어지고 **멈춘다.** 열어서 DB 접속 정보를 적고 다시 실행한다.

```ini
FLOWBIT_DB_HOST=db.example.com        # DB 서버 주소. 같은 서버면 host.docker.internal
FLOWBIT_DB_PORT=5432
FLOWBIT_DB_NAME=flowbit
FLOWBIT_DB_USER=flowbit
FLOWBIT_DB_PASSWORD='바꿀-비밀번호'    # $ 가 있으면 작은따옴표로 감싼다
FLOWBIT_PUBLIC_URL=https://flowbit.example.com   # 사람들이 여는 주소 (알림 링크와 SSO 에 쓴다)
```

```bash
./deploy.sh
```

스크립트가 하는 일: DB 설정을 확인하고 → 이미지를 받고 → 컨테이너를 (다시) 띄우고 → `/api/v1/health` 가 답할 때까지 기다린다(첫 실행은 마이그레이션 때문에 조금 더 걸린다). 끝나면 `Open: http://<서버>:3000` 을 알려 준다.

**설치한 뒤 바로 초기 설정을 끝낸다.** 브라우저로 서버 주소를 열면 초기 설정 화면이 나오고, 여기서 정한 아이디·비밀번호가 첫 관리자 계정이 된다. 초기 설정 전에는 그 주소에 접근할 수 있는 누구나 관리자 계정을 만들 수 있다.

Docker 만으로 띄우려면(스크립트 없이):

```bash
docker run -d --name flowbit --restart unless-stopped \
  -p 3000:3000 \
  -e FLOWBIT_DB_HOST=db.example.com -e FLOWBIT_DB_NAME=flowbit \
  -e FLOWBIT_DB_USER=flowbit -e FLOWBIT_DB_PASSWORD=비밀번호 \
  -e FLOWBIT_PUBLIC_URL=https://flowbit.example.com \
  -v flowbit-uploads:/var/lib/flowbit/uploads \
  isugar150/flowbit:latest
```

### 같은 서버의 데이터베이스

컨테이너 안에서 `localhost` 는 컨테이너 자신이다. DB 가 Docker 를 돌리는 그 서버에 있으면 `FLOWBIT_DB_HOST=host.docker.internal` 을 쓴다(`deploy.sh` 의 compose 가 `host-gateway` 로 이어 준다. `docker run` 은 `--add-host host.docker.internal:host-gateway` 를 더한다). `localhost`/`127.0.0.1` 을 적으면 `deploy.sh` 가 알려 주고 멈춘다. 이때 PostgreSQL 이 Docker 쪽 주소에서 오는 접속을 받도록 `listen_addresses` 와 `pg_hba.conf` 를 열어 둔다.

### 환경 변수

`.env` 에 적는다(`deploy.sh` 의 compose 가 컨테이너로 넘긴다). 바꾼 뒤에는 `./deploy.sh` 를 다시 실행한다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `FLOWBIT_DB_HOST` | (필수) | PostgreSQL 주소 |
| `FLOWBIT_DB_PORT` | `5432` | PostgreSQL 포트 |
| `FLOWBIT_DB_NAME` | `flowbit` | DB 이름 |
| `FLOWBIT_DB_USER`, `FLOWBIT_DB_PASSWORD` | (필수) | DB 계정 |
| `FLOWBIT_PUBLIC_URL` | (없음) | 사람들이 Flowbit 을 여는 주소. 메일·API 알림의 링크와 SSO 리다이렉트에 쓴다. 없으면 알림에 `/issue/FB-12` 같은 경로만 들어간다 |
| `FLOWBIT_WEB_PORT` | `3000` | 서버가 여는 포트. `80` 으로 바꾸면 `http://서버주소` 로 바로 열린다 |
| `FLOWBIT_WEB_BIND` | `0.0.0.0` | 그 포트를 여는 주소. 앞에 nginx 를 두고 이 서버 안에서만 열려면 `127.0.0.1` |
| `FLOWBIT_MAX_UPLOAD_SIZE` | `5MB` | 올릴 수 있는 파일 하나의 최대 크기(백엔드가 한 파트를 20MB 에서 끊으므로 그 이상으로는 올릴 수 없다). 프록시를 두면 그 쪽 본문 제한(`client_max_body_size`)도 맞춘다. 화면에 보이는 "N MB까지"와 올리기 전의 크기 검사도 이 값을 따르고, 관리자 설정 > 파일 관리에서 현재 값을 볼 수 있다 |
| `FLOWBIT_VERSION`, `FLOWBIT_IMAGE` | `latest`, `isugar150/flowbit` | 받을 이미지의 버전과 저장소 |
| `FLOWBIT_STORAGE_TYPE` | `local` | **올린 파일을 어디에 둘지: `local` 또는 `amazon-s3`**([5](#5-파일-저장소-로컬-또는-s3)). 처음 만들어지는 `.env` 에 `local` 로 적혀 있다 |
| `FLOWBIT_S3_*` | (없음) | `amazon-s3` 일 때 버킷·리전·키 |

LDAP, SSO, 메일(SMTP), 웹 푸시, API 알림, Git 연동은 환경 변수가 아니라 **로그인한 뒤 관리자 설정 화면**에서 정한다.

## 5. 파일 저장소: 로컬 또는 S3

`.env` 의 **`FLOWBIT_STORAGE_TYPE`** 이 어디에 둘지 정한다: `local` 또는 `amazon-s3`.

**`local`(기본)** 은 컨테이너의 `/var/lib/flowbit/uploads` 볼륨(compose 의 `flowbit_uploads`)이다. 이 볼륨은 **DB 와 함께 백업 대상**이다.

**Amazon S3 에 두려면** `.env` 에서 `FLOWBIT_STORAGE_TYPE=amazon-s3` 로 바꾸고 버킷 정보를 적은 뒤 `./deploy.sh` 를 다시 실행한다. 모든 파일이 S3 에 올라간다. (이 줄을 비워 두면 `FLOWBIT_S3_BUCKET` 이 있을 때 `amazon-s3`, 없으면 `local` 로 본다. 예전에 버킷만 적어 쓰던 서버가 그대로 동작하게 한 것이고, 새로 만드는 `.env` 에는 항상 적혀 있다. `local` 인데 버킷이 적혀 있으면 시작할 때 경고가 남는다.)

```ini
FLOWBIT_STORAGE_TYPE=amazon-s3
FLOWBIT_S3_BUCKET=my-flowbit-files
FLOWBIT_S3_REGION=ap-northeast-2
FLOWBIT_S3_ACCESS_KEY=AKIA...        # 서버에 IAM 역할을 붙였다면 둘 다 비운다
FLOWBIT_S3_SECRET_KEY=...
# FLOWBIT_S3_PREFIX=flowbit          # 버킷 안의 폴더(선택)
# FLOWBIT_S3_ENDPOINT=http://minio:9000   # MinIO 같은 S3 호환 저장소일 때만 (AWS 는 적지 않는다)
```

- 버킷은 **비공개**로 둔다. 파일은 Flowbit 이 권한을 확인한 뒤 대신 읽어 내려 주므로 공개 주소가 필요 없다.
- 접근 키(또는 IAM 역할)에 필요한 권한은 이 버킷(과 접두어)의 `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject`, 그리고 시작할 때 버킷에 닿는지 보는 `s3:ListBucket` 이다.

  ```json
  {
    "Version": "2012-10-17",
    "Statement": [
      { "Effect": "Allow", "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"], "Resource": "arn:aws:s3:::my-flowbit-files/*" },
      { "Effect": "Allow", "Action": "s3:ListBucket", "Resource": "arn:aws:s3:::my-flowbit-files" }
    ]
  }
  ```
- **처음 설치할 때 정한다.** 이미 로컬에 올라간 파일은 S3 로 옮겨지지 않고, S3 로 바꾸면 S3 에서만 읽으므로 그 파일은 열리지 않는다. 쓰던 서버를 바꿔야 하면 볼륨의 `files/`·`images/`·`originals/` 를 버킷의 같은 이름 아래로 올린다(`aws s3 sync`).
- **저장소 설정은 서버를 시작할 때 한 번만 읽는다.** `.env` 에 `amazon-s3` 를 적어도 이미 떠 있는 서버는 다시 시작하기 전까지 로컬 폴더에 저장한다. 같은 DB 를 쓰는 서버가 둘(예: 개발 PC 에서 직접 띄운 것과 서버의 컨테이너)이면 **둘의 저장소 설정이 같아야 한다.** 다르면 한쪽이 올린 사진의 이름이 DB 에는 있고 파일은 다른 쪽 저장소에만 있어, 그 사진이 404 로 열리지 않는다. 어느 쪽에 저장하는지는 시작 로그의 `File storage: …`(그 아래 `Uploaded files are stored in …` 에 폴더나 버킷)로 안다. 이미 어긋났다면 관리자 > 파일 관리의 "없는 파일"에 나온다.
- 버킷에 닿지 않으면 시작할 때 로그에 `Cannot reach the S3 bucket …` 가 남지만 앱은 뜬다. 그동안 파일 올리기·내려받기가 이유와 함께 실패한다.
- 백업은 버킷의 버전 관리·복제로 한다. 이 경우 `uploads` 볼륨은 쓰이지 않는다.

## 6. 앞에 리버스 프록시와 HTTPS 두기

포트를 `80` 으로 열어(`FLOWBIT_WEB_PORT=80`) 그대로 써도 된다. **다음 경우에는 프록시(nginx 등)와 HTTPS 를 둔다**: 한 서버가 여러 도메인을 받을 때, 웹 푸시 알림이나 앱 설치(PWA)를 쓸 때(브라우저가 HTTPS 에서만 허용한다), 로그인 쿠키를 `Secure` 로 보내고 싶을 때.

1. `.env`: `FLOWBIT_WEB_BIND=127.0.0.1`, `FLOWBIT_PUBLIC_URL=https://도메인` → `./deploy.sh`.
2. 프록시 설정은 `script/nginx/flowbit.conf`(HTTP) 가 기준이다. 지켜야 하는 것은 셋이다.
   - **`Host` 를 브라우저가 보낸 그대로** 넘긴다(실시간 연결이 페이지의 Origin 과 Host 를 비교한다).
   - **WebSocket 업그레이드 헤더**(`Upgrade`, `Connection`)를 넘긴다. 읽기 제한은 120초 이상(서버가 20초마다 핑을 보낸다).
   - **`X-Forwarded-Proto: https`** 를 넘긴다(쿠키의 `Secure` 판단과 리다이렉트 주소에 쓴다).
3. HTTPS 는 `certbot --nginx -d 도메인` 으로 인증서를 받는 것이 가장 쉽다. 아래는 HTTPS 로 바꾼 서버 블록의 모양이다(예시).
4. 로그인한 뒤 브라우저 개발자 도구의 쿠키(`FLOWBIT_SESSION`)에 `Secure` 가 있는지 확인한다. 없으면 프록시가 `X-Forwarded-Proto` 를 넘기지 않는 것이다.

```nginx
server {
    listen 443 ssl;
    server_name flowbit.example.com;
    ssl_certificate     /etc/letsencrypt/live/flowbit.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/flowbit.example.com/privkey.pem;
    add_header Strict-Transport-Security "max-age=31536000" always;
    client_max_body_size 20m;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade    $http_upgrade;
        proxy_set_header Connection $flowbit_connection_upgrade;   # 이 변수를 만드는 map 은 flowbit.conf 맨 위에 있다
        proxy_read_timeout 120s;
        proxy_send_timeout 120s;
    }
}
```

Flowbit 이 내보내는 페이지에는 `X-Content-Type-Options`, `Content-Security-Policy: frame-ancestors 'self'`(다른 사이트가 Flowbit 을 프레임에 넣을 수 없다), `Referrer-Policy`, `Permissions-Policy` 가 이미 붙는다. HSTS 는 HTTPS 를 끝내는 프록시가 붙인다(위의 `add_header`).

## 7. 설치한 뒤 할 일

1. 초기 설정: 관리자 계정과 워크스페이스 이름·티켓 키(`FB` 등). 티켓 키는 관리자 설정에서 바꿀 수 있지만 브랜치 이름과 커밋 메시지에 들어가므로(`FB-12`) 처음에 정해 두는 편이 좋다.
2. **관리자 설정**(사용자 메뉴): 구성원 추가, 알림(메일 SMTP·웹 푸시·API 알림), 로그인 연동(LDAP·SSO), Git 연동, 데이터 보관 기간, 파일 관리(저장소 사용량, 쓰지 않는 파일 정리), 백업과 복원.
3. 프로젝트를 만들고 구성원을 넣는다. 도움말(`/help`)에 쓰는 법이 있다.

## 8. 운영

| 일 | 방법 |
|---|---|
| 상태 확인 | `curl http://localhost:3000/api/v1/health` → `{"status":"UP"}` (DB 연결까지 본다). `docker ps` 의 `STATUS` 에 `healthy` 가 나온다 |
| 로그 | `docker compose logs -f` (또는 `docker logs -f flowbit`) |
| 메모리 | 쉬는 동안 컨테이너 하나가 600MB 안팎을 쓴다(2026-10-21 측정: JVM 과 Node). 백엔드의 힙은 컨테이너(또는 서버)가 가진 메모리의 60% 까지이고, 힙이 모자라면 백엔드가 끝나 컨테이너가 다시 뜬다. `docker-compose.yml` 에 `mem_limit` 을 주면 그 값이 기준이 된다 |
| 멈추기·켜기 | `docker compose stop`, `docker compose start`. 서버를 재부팅해도 `restart: unless-stopped` 로 다시 뜬다(Docker 가 부팅 때 시작되면) |
| **업데이트** | 개발 PC 에서 `./script/push.sh` → 서버에서 `./deploy.sh`. DB 스키마는 새 버전이 뜰 때 자동으로 올라간다. 교체하는 동안 몇 초 끊긴다. **업데이트 전에 DB 를 백업한다** |
| 되돌리기 | `./deploy.sh <버전>` (push.sh 가 알려 준 태그). 새 버전이 스키마를 이미 올렸다면 예전 버전과 맞지 않을 수 있다 — 그때는 백업한 DB 를 되살린다 |
| **백업** | ① DB: `pg_dump -h <호스트> -U flowbit -Fc flowbit > flowbit.dump` ② 파일(로컬일 때): `docker run --rm -v flowbit_uploads:/data -v "$PWD":/backup alpine tar czf /backup/uploads.tgz -C /data .` (S3 는 버킷 쪽). **둘을 같은 시점에 받는다.** 관리자 설정의 "백업 및 복원"은 설정과 구성원만 담는다(티켓·프로젝트·파일은 안 들어간다) |
| 복원 | 빈 DB 에 `pg_restore -d flowbit flowbit.dump`, 볼륨에 `tar xzf` |
| 지우기 | `docker compose down` (볼륨은 남는다). 파일까지 지우려면 `docker compose down -v` — **되돌릴 수 없다** |

## 9. 문제가 생기면

| 증상 | 확인 |
|---|---|
| `deploy.sh` 가 `Missing in .env` 로 멈춘다 | `FLOWBIT_DB_HOST`·`FLOWBIT_DB_USER`·`FLOWBIT_DB_PASSWORD` 를 적는다 |
| `Flowbit did not answer within 120s` | 마지막 로그를 본다. DB 에 닿지 않는 것이 가장 흔하다: 주소·포트·계정, DB 서버의 `pg_hba.conf`, 방화벽. 같은 서버의 DB 인데 `localhost` 를 적지 않았는지 |
| 로그에 `Detected applied migration not resolved locally` 나 `Migration checksum mismatch` | 예전 개발 DB 에 접속하고 있다. 위의 안내대로 DB 를 지우고 다시 만든다(D-168) |
| 화면은 뜨는데 다른 사람의 변경이 바로 안 보인다 | 프록시가 WebSocket 업그레이드 헤더나 `Host` 를 안 넘긴다(6) |
| 웹 푸시가 안 된다 | HTTPS 에서만 동작한다. 관리자 설정 > 알림에서 Firebase 값을 넣었는지 |
| 메일이 안 온다 | 관리자 설정 > 알림 > 메일의 "테스트 메일", 받는 사람이 내 설정에서 이메일 주소를 적고 수신을 켰는지 |
| 파일이 안 올라간다/안 열린다 | S3 면 로그의 `Cannot reach the S3 bucket`, 키·리전·권한·버킷 이름. 파일 크기는 `FLOWBIT_MAX_UPLOAD_SIZE`(프록시는 `client_max_body_size`) 이하여야 한다 |
| 로그인이 계속 풀린다 | HTTPS 인데 프록시가 `X-Forwarded-Proto` 를 안 넘기면 쿠키가 맞지 않을 수 있다 |

## 10. 개발 환경과 검사

개발 PC 에서 직접 띄우고 시험하는 방법은 [README.md](../README.md)(로컬 실행, 검사)와 [04-frontend-guide.md](04-frontend-guide.md) "테스트와 검사"에 있다. 이미지가 서버에서 돌기 전에 개발 PC 에서 한 번 확인하려면:

```bash
docker build -t flowbit-check .
docker run --rm -p 3100:3000 -e FLOWBIT_DB_HOST=host.docker.internal -e FLOWBIT_DB_USER=… -e FLOWBIT_DB_PASSWORD=… flowbit-check
```

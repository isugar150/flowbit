# Flowbit

Linear처럼 빠르고 단순한 티켓 트래커. 한 조직이 자기 서버에 설치해서 쓴다.

지금 되는 것: 초기 설정(관리자 계정 만들기), 로그인, 로그아웃, 설정(프로필 사진·이름, 워크스페이스 로고·이름). 설계와 계획은 [docs/](docs/README.md) 에 있다.

## 필요한 것

- Docker (PostgreSQL 18 컨테이너, 백엔드 테스트의 Testcontainers)
- JDK 17 이상 (Gradle 실행용. 빌드에 쓰는 JDK 는 Amazon Corretto 25 로 고정되어 있고, 없으면 Gradle 이 내려받는다)
- Node.js 22 이상

## 로컬 실행

터미널 세 개에서 차례로 실행한다.

```bash
docker compose up -d
```

```bash
cd backend
./gradlew bootRun
```

```bash
cd frontend
npm install
npm run dev
```

브라우저에서 http://localhost:3000 을 연다. 처음에는 초기 설정 화면이 나오고, 여기서 정한 아이디와 비밀번호가 첫 관리자 계정이 된다.

**설치한 뒤 바로 초기 설정을 끝낸다.** 초기 설정 전에는 이 주소에 접근할 수 있는 누구나 관리자 계정을 만들 수 있다.

Windows 에서는 `./gradlew` 대신 `.\gradlew.bat` 을 쓴다. `JAVA_HOME` 이 JDK 17 미만이면 Gradle 이 실행되지 않는다.

화면이 느리게 느껴지면 개발 모드 때문일 수 있다. 개발 모드는 처음 여는 화면마다 컴파일하느라 1~2초가 걸린다. 실제 속도는 아래처럼 빌드해서 본다.

```bash
cd frontend
npm run build
npm run start
```

## Docker 로 띄우기

DB 와 앱(백엔드와 프런트가 한 이미지, 루트의 `Dockerfile`)을 컨테이너로 띄운다. 처음에는 이미지를 빌드하느라 몇 분 걸린다.

```bash
docker compose --profile app up -d --build
```

http://localhost:3000 에서 초기 설정부터 한다. 포트는 `.env` 의 `FLOWBIT_WEB_PORT` 로 바꾼다. 올린 이미지는 `uploads` 볼륨에, DB 는 `postgres-data` 볼륨에 있다. 자세한 내용은 [docs/02-architecture.md](docs/02-architecture.md) "Docker 이미지".

## 서버에 배포하기

개발 PC 에서 이미지를 만들어 Docker Hub(`isugar150/flowbit`)에 올리고, 서버는 받아서 띄운다. 서버에는 Docker 만 있으면 된다.

```bash
# 개발 PC (Docker 실행 중, docker login 완료)
./script/push.sh

# 서버: script/deploy.sh 와 script/docker-compose.yml 을 한 폴더에 두고
./deploy.sh
```

DB 는 포함하지 않는다. 처음 실행하면 `.env` 양식이 만들어지고 멈추므로, 거기에 PostgreSQL 18 의 접속 정보(`FLOWBIT_DB_*`)를 적고 다시 실행한다. 포트는 그 파일의 `FLOWBIT_WEB_PORT` 로 바꾼다. 자세한 내용은 [docs/02-architecture.md](docs/02-architecture.md) "서버 배포".

## 설정

기본값으로 바로 실행된다. 바꾸려면 `.env.example` 을 저장소 루트에 `.env` 로 복사해 고친다. `docker compose` 와 백엔드가 이 파일을 읽는다. 프론트엔드의 `BACKEND_URL` 은 환경 변수나 `frontend/.env.local` 로 준다. 변수 목록은 [docs/02-architecture.md](docs/02-architecture.md).

올린 파일(이미지, 첨부 파일, 댓글 파일)은 기본으로 `backend/data/uploads` 에 저장된다(Amazon S3 에 두려면 `.env` 에 `FLOWBIT_S3_BUCKET` 등을 적는다. `.env.example` 참고). 다른 곳에 두려면 `.env` 에 `FLOWBIT_STORAGE_PATH` 를 지정하고 백엔드를 다시 시작한다. 이 폴더는 DB 와 함께 백업해야 한다.

## 검사

```bash
cd backend
./gradlew check
```

ktlint 와 테스트를 돌린다. 테스트는 PostgreSQL 18 컨테이너를 띄우므로 Docker 가 켜져 있어야 한다.

```bash
cd frontend
npm run lint
npm run typecheck
```

## 개발 데이터 초기화

초기 설정부터 다시 해 보려면 DB 볼륨을 지우고 다시 띄운다. **이 데이터베이스의 모든 데이터가 사라진다.**

```bash
docker compose down -v
```

그 뒤 프론트엔드 서버도 다시 시작한다(초기 설정이 끝났다는 것을 기억하고 있다).

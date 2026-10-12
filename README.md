# Flowbit

Linear처럼 빠르고 단순한 이슈 트래커. 한 조직이 자기 서버에 설치해서 쓴다.

지금 되는 것: 초기 설정(관리자 계정 만들기), 로그인, 로그아웃, 설정(프로필 사진·이름, 워크스페이스 로고·이름). 설계와 계획은 [docs/](docs/README.md) 에 있다.

## 필요한 것

- Docker (PostgreSQL 18 컨테이너, 백엔드 테스트의 Testcontainers)
- JDK 17 이상 (Gradle 실행용. 빌드에 쓰는 JDK 25 는 Gradle 이 자동으로 내려받는다)
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

DB, 백엔드, 프런트를 모두 컨테이너로 띄운다. 처음에는 이미지를 빌드하느라 몇 분 걸린다.

```bash
docker compose --profile app up -d --build
```

http://localhost:3000 에서 초기 설정부터 한다. 포트는 `.env` 의 `FLOWBIT_WEB_PORT` 로 바꾼다. 올린 이미지는 `uploads` 볼륨에, DB 는 `postgres-data` 볼륨에 있다. 자세한 내용은 [docs/02-architecture.md](docs/02-architecture.md) "Docker 이미지".

## 설정

기본값으로 바로 실행된다. 바꾸려면 `.env.example` 을 저장소 루트에 `.env` 로 복사해 고친다. `docker compose` 와 백엔드가 이 파일을 읽는다. 프론트엔드의 `BACKEND_URL` 은 환경 변수나 `frontend/.env.local` 로 준다. 변수 목록은 [docs/02-architecture.md](docs/02-architecture.md).

올린 이미지는 기본으로 `backend/data/uploads` 에 저장된다. 다른 곳에 두려면 `.env` 에 `FLOWBIT_STORAGE_PATH` 를 지정하고 백엔드를 다시 시작한다. 이 폴더는 DB 와 함께 백업해야 한다.

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

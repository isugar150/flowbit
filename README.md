# Flowbit

Linear처럼 빠르고 단순한 티켓 트래커. 한 조직이 자기 서버에 설치해서 쓴다.

- **티켓**: 문서처럼 쓰는 본문(블록 편집기, `/` 메뉴, 표·이미지·임베드, `@` 태그), 하위 티켓(상위 티켓은 검색해서 고르고 경로가 보인다), 라벨, 여러 담당자, 사용자 속성, 목록·타임라인·캘린더 보기
- **프로젝트**: 개요(편집기), 문서(페이지), 구성원과 역할, 프로젝트 업데이트, 접근 요청
- **대화**: 블록마다 댓글, 댓글과 답글, 이모지, 파일, 변경 기록(활동), 구독과 알림(메일·웹 푸시·API 알림)
- **그 밖에**: 검색(초성 검색 포함), Git 연동(Gitea·GitLab·GitHub), LDAP·SSO 로그인, 실시간 반영, 앱으로 설치(PWA), 데이터 보관 기간, 설정 백업

설계와 계획은 [docs/](docs/README.md), 쓰는 법은 앱 안의 도움말(`/help`)에 있다.

## 설치

서버에는 **Docker 와 PostgreSQL 18** 만 있으면 된다. 개발 PC 에서 이미지를 만들어 Docker Hub 에 올리고, 서버는 받아서 띄운다.

```bash
# 개발 PC (Docker 실행 중, docker login 완료)
./script/push.sh

# 서버: script/deploy.sh 와 script/docker-compose.yml 을 한 폴더에 두고
./deploy.sh      # 처음에는 .env 양식을 만들고 멈춘다. DB 접속 정보를 적고 다시 실행한다
```

브라우저로 서버 주소를 열면 초기 설정(첫 관리자 계정 만들기)이 나온다. **설치한 뒤 바로 초기 설정을 끝낸다.**

데이터베이스 준비, 환경 변수, 파일을 Amazon S3 에 두는 법, HTTPS, 업데이트와 백업, 문제 해결까지 한 곳에 정리한 **[설치 가이드](docs/10-install.md)** 를 본다.

## 개발 환경

필요한 것:

- Docker (PostgreSQL 18 컨테이너, 백엔드 테스트의 Testcontainers)
- JDK 17 이상 (Gradle 실행용. 빌드에 쓰는 JDK 는 Amazon Corretto 25 로 고정되어 있고, 없으면 Gradle 이 내려받는다)
- Node.js 22 이상

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

브라우저에서 http://localhost:3000 을 연다. 처음에는 초기 설정 화면이 나오고, 여기서 정한 아이디와 비밀번호가 첫 관리자 계정이 된다. Windows 에서는 `./gradlew` 대신 `.\gradlew.bat` 을 쓴다. `JAVA_HOME` 이 JDK 17 미만이면 Gradle 이 실행되지 않는다.

화면이 느리게 느껴지면 개발 모드 때문일 수 있다. 개발 모드는 처음 여는 화면마다 컴파일하느라 1~2초가 걸린다. 실제 속도는 `npm run build && npm run start` 로 본다.

### 컨테이너로 띄우기

DB 와 앱(백엔드와 프런트가 한 이미지, 루트의 `Dockerfile`)을 컨테이너로 띄운다. 처음에는 이미지를 빌드하느라 몇 분 걸린다.

```bash
docker compose --profile app up -d --build
```

http://localhost:3000 에서 초기 설정부터 한다. 올린 파일은 `uploads` 볼륨에, DB 는 `postgres-data` 볼륨에 있다.

### 설정

기본값으로 바로 실행된다. 바꾸려면 `.env.example` 을 저장소 루트에 `.env` 로 복사해 고친다. `docker compose` 와 백엔드가 이 파일을 읽는다. 프런트엔드의 `BACKEND_URL` 은 환경 변수나 `frontend/.env.local` 로 준다. 변수 목록은 [docs/02-architecture.md](docs/02-architecture.md).

올린 파일(이미지, 첨부 파일, 댓글 파일)은 기본으로 `backend/data/uploads` 에 저장된다. 다른 곳에 두려면 `FLOWBIT_STORAGE_PATH`, Amazon S3 에 두려면 `FLOWBIT_S3_BUCKET` 등을 적는다(`.env.example` 참고). 이 폴더는 DB 와 함께 백업해야 한다.

### 검사

```bash
cd backend
./gradlew check
```

ktlint 와 테스트를 돌린다. 테스트는 PostgreSQL 18 컨테이너(그리고 S3 시험용 S3Mock 컨테이너)를 띄우므로 Docker 가 켜져 있어야 한다.

```bash
cd frontend
npm run lint
npm run typecheck
npm test
```

### 개발 데이터 초기화

초기 설정부터 다시 해 보려면 DB 볼륨을 지우고 다시 띄운다. **이 데이터베이스의 모든 데이터가 사라진다.**

```bash
docker compose down -v
```

그 뒤 프런트엔드 서버도 다시 시작한다(초기 설정이 끝났다는 것을 기억하고 있다).

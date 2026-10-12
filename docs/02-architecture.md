# 02. 아키텍처

## 기술 스택과 버전

"최신 안정 버전"이 원칙이다. 아래는 **2026-10-12 에 실제로 설치·사용한 버전**이다. 스캐폴딩 도구가 고정한 버전이 레지스트리의 최신 버전보다 우선한다.

### 백엔드

| 항목 | 버전 | 비고 |
|---|---|---|
| Spring Boot | 4.1.1 | start.spring.io 기준. 4.2.0 은 아직 마일스톤이라 쓰지 않는다 |
| 웹 | **Spring WebFlux** (Netty) | 사용자 지정 (D-019). Spring MVC 를 쓰지 않는다 |
| Kotlin | 2.3.21 | start.spring.io 가 Boot 4.1.1 에 고정한 버전. 코루틴(`suspend`) 사용 |
| JDK | 25 (LTS) | Gradle toolchain 이 자동으로 내려받는다(foojay). Gradle 자체는 JDK 17 이상으로 실행 |
| 빌드 | Gradle 9.7.1 (Kotlin DSL, Wrapper) | |
| DB | PostgreSQL 18 | 사용자 지정. `docker-compose.yml` 의 `postgres:18` |
| DB 접근 | **Spring Data R2DBC** (`r2dbc-postgresql`) | WebFlux 에 맞춘 논블로킹 접근. JPA 는 쓰지 않는다 (D-019) |
| 마이그레이션 | Flyway | 사용자 지정. Flyway 만 JDBC 로 접속해 시작 시 스키마를 올린다 |
| 인증 | Spring Security (WebFlux) + 자체 세션 테이블 | 세션 쿠키 방식. Spring Session 은 쓰지 않는다 ([06-api-conventions.md](06-api-conventions.md)) |
| 비밀번호 해시 | Argon2id (BouncyCastle 1.86) | |
| 이미지 처리 | scrimage 4.6.8 (`scrimage-core`, `scrimage-webp`) | 업로드를 WebP 로 변환·리사이즈. WebP 인코더(`cwebp`) 실행 파일이 라이브러리에 들어 있다 |
| 코드 스타일 | ktlint (Gradle 플러그인 14.2.0) | `./gradlew check` 에 포함 |
| 테스트 | JUnit 5, kotlin-test, Testcontainers(PostgreSQL 18) | |

### 프론트엔드

| 항목 | 버전 | 비고 |
|---|---|---|
| Node.js | 22 LTS 이상 | 개발 PC: v22.23.2 |
| 패키지 매니저 | npm | 개발 PC에 pnpm 이 없어 Node 기본 도구를 쓴다 (D-021) |
| Next.js | 16.4.0 | App Router, Turbopack, `cacheComponents` 켜짐(create-next-app 기본값) |
| React | 19.3.0 | |
| TypeScript | 5.x | create-next-app 이 고정 |
| Tailwind CSS | 4.x | CSS 기반 설정(`@theme`) |
| shadcn/ui | CLI 4.21.4, 스타일 `base-nova` | 기반 프리미티브는 **Base UI** (`@base-ui/react`). 트리거 커스터마이징은 `asChild` 가 아니라 `render` prop |
| 아이콘 | lucide-react | |
| 서체 | Pretendard 1.3.9 (Variable, dynamic subset) | |
| 서버 상태 | TanStack Query 5.104.2 | |
| 폼/검증 | react-hook-form 7.90.0, zod 4.6.6 | |
| 테마 | next-themes 0.4.6 | 기본 라이트 |
| 테스트 | Vitest, Playwright (설치만 됨) | 프론트엔드 자동 테스트는 아직 없다 ([PROGRESS.md](PROGRESS.md)) |

## 저장소 구조

```
flowbit/
├─ AGENTS.md              에이전트 진입점
├─ CLAUDE.md              AGENTS.md 를 불러온다
├─ README.md              로컬 실행 방법
├─ docs/                  설계 문서 (이 폴더)
├─ agent-skills/          참고용 스킬 저장소 (git 추적 안 함)
├─ backend/               Spring Boot(WebFlux) 애플리케이션
│   └─ data/uploads/       올린 이미지의 기본 저장 위치 (git 추적 안 함)
├─ frontend/              Next.js 애플리케이션
├─ docker/postgres/init/  DB 볼륨이 처음 만들어질 때 실행되는 SQL
├─ docker-compose.yml     로컬 개발용 PostgreSQL 18
└─ .env.example           환경 변수 예시
```

## 시스템 구성

```
브라우저
   │  (같은 출처, 쿠키)
   ▼
Next.js (frontend, :3000) ── 화면 렌더링, /api/* 는 백엔드로 전달(rewrite)
   │
   ▼
Spring Boot WebFlux (backend, :8080) ── REST API, 인증, 비즈니스 규칙
   │  R2DBC (런타임) / JDBC (Flyway, 시작 시)
   ▼
PostgreSQL 18 ── 데이터, 세션
```

- 브라우저는 Next.js 하나만 본다. `/api/**` 요청은 Next.js 가 백엔드로 넘긴다(`next.config.ts` 의 `rewrites`). 같은 출처가 되므로 CORS 설정이 필요 없고 세션 쿠키를 그대로 쓸 수 있다.
- `frontend/src/proxy.ts` 와 서버 컴포넌트는 백엔드를 직접 부르며(`BACKEND_URL`), 들어온 요청의 세션 쿠키를 그대로 전달한다.
- **인증과 권한의 판단은 항상 백엔드가 한다.** Next.js 쪽의 리다이렉트는 사용 편의를 위한 것이고 보안 경계가 아니다.
- 세션은 PostgreSQL 의 `user_sessions` 테이블에 저장한다. 설치형 제품이라 Redis 같은 추가 구성요소를 요구하지 않는다.
- 백엔드는 모듈형 모놀리스다. 마이크로서비스로 나누지 않는다.
- **백엔드를 외부에 직접 노출하지 않는다.** 백엔드는 `X-Forwarded-For` 를 믿고 클라이언트 IP 를 판단한다(`server.forward-headers-strategy: native`). 직접 노출하면 IP 기준 요청 제한을 속일 수 있다.

## 실시간 동기화 (M3 부터, 사용자 지정 D-024)

여러 사람이 같은 화면을 보고 있을 때 한 사람의 변경이 다른 사람 화면에 새로 고침 없이 반영되어야 한다. WebSocket 으로 구현한다.

```
A 의 브라우저 ── PATCH /api/v1/issues/.. ──▶ 백엔드 ── 커밋 ──▶ 이벤트 발행
                                                                │
B 의 브라우저 ◀── WebSocket: 변경 이벤트 ◀──────────────────────┘
(캐시에 반영, 화면 즉시 갱신)
```

- **변경은 REST, 전파는 WebSocket.** 쓰기는 지금처럼 REST API 로 하고(검증, 권한, 오류 형식이 한 곳에 있다), 서버가 커밋 후에 변경 이벤트를 WebSocket 으로 뿌린다. WebSocket 으로 쓰기 명령을 받지 않는다.
- 엔드포인트: `GET /api/v1/realtime` (WebSocket 업그레이드). 연결할 때 세션 쿠키로 인증하고 `Origin` 헤더를 확인한다. 세션이 끝나면 서버가 연결을 닫는다.
- 이벤트: `{ type: "issue.updated", id, version, changes: {...}, actorId, at }` 형태. 받는 쪽은 TanStack Query 캐시의 해당 항목에 `changes` 를 합친다. 자기가 낸 변경은 이미 낙관적으로 반영했으므로 `version` 으로 중복을 걸러낸다.
- 구독 범위: 연결 후 클라이언트가 보고 있는 범위(팀, 이슈)를 알리고, 서버는 그 범위이면서 볼 권한이 있는 이벤트만 보낸다.
- 끊김: 자동 재연결(지수 백오프). 다시 붙으면 마지막으로 받은 이벤트 이후의 변경을 받거나, 간격이 크면 보고 있는 목록을 다시 조회한다. 연결이 끊긴 동안에도 앱은 동작해야 한다(REST 만으로).
- 충돌: 필드 단위로 나중에 쓴 값이 이긴다. 같은 이슈의 다른 필드를 동시에 고치면 둘 다 남는다. 긴 글(이슈 설명)을 두 사람이 동시에 고친 경우는 덮어쓰지 않고 나중에 저장하는 쪽에 알린다. 한 글자씩 합치는 공동 편집(CRDT)은 범위 밖이다.
- 서버 구현: WebFlux `WebSocketHandler` + 프로세스 안의 이벤트 버스(Reactor `Sinks`). 인스턴스가 하나이므로 이것으로 충분하다. 여러 인스턴스로 늘릴 때는 PostgreSQL `LISTEN/NOTIFY` 로 인스턴스 사이에 이벤트를 전달한다.
- 표시: 누가 같은 이슈를 보고 있는지(프레즌스)는 이슈 상세에 아바타로 보여준다. 기반이 생긴 뒤에 추가한다.
- **확인 필요**: Next.js 의 `/api` rewrite 가 WebSocket 업그레이드를 운영 환경에서 안정적으로 전달하는지. 안 되면 리버스 프록시에서 `/api/v1/realtime` 만 백엔드로 직접 보낸다. M3 에서 가장 먼저 확인한다.

## 환경과 설정

| 변수 | 사용처 | 기본값 | 설명 |
|---|---|---|---|
| `FLOWBIT_DB_HOST` | backend | `localhost` | DB 호스트 |
| `FLOWBIT_DB_PORT` | docker-compose, backend | `5432` | DB 포트 (compose 에서는 호스트에 노출할 포트) |
| `FLOWBIT_DB_NAME` | docker-compose, backend | `flowbit` | DB 이름 |
| `FLOWBIT_DB_USER`, `FLOWBIT_DB_PASSWORD` | docker-compose, backend | `flowbit` | DB 계정. 기본값은 로컬 개발용이다 |
| `FLOWBIT_STORAGE_PATH` | backend | `./data/uploads` | 올린 이미지를 저장할 폴더. 상대 경로는 백엔드를 실행한 폴더 기준. **백업 대상** |
| `FLOWBIT_MAX_UPLOAD_SIZE` | backend | `5MB` | 올릴 수 있는 파일 하나의 최대 크기 |
| `SERVER_PORT` | backend | `8080` | 백엔드 포트 |
| `BACKEND_URL` | frontend | `http://localhost:8080` | Next.js 가 `/api` 를 넘기고 서버에서 직접 부를 백엔드 주소 |

- 비밀 값은 저장소에 넣지 않는다. `.env.example` 에는 이름과 예시 값만 둔다.
- **백엔드는 `.env` 파일을 직접 읽는다** (`spring.config.import`): 저장소 루트의 `.env`(`backend/` 에서 실행할 때의 `../.env`)와 실행 폴더의 `.env`. 실제 환경 변수가 있으면 그쪽이 우선한다. `docker compose` 도 루트의 `.env` 를 읽으므로 DB 값은 한 곳에서 맞춰진다. 프론트엔드(`BACKEND_URL`)는 루트 `.env` 를 읽지 않는다. 바꾸려면 환경 변수나 `frontend/.env.local` 로 준다.
- 인증 관련 수치(잠금 기준, 세션 수명, 요청 제한)는 `backend/src/main/resources/application.yml` 의 `flowbit.auth.*` 에 있다.
- Spring 프로필 구분(`local`/`prod`)은 아직 없다. 운영 배포를 다룰 때 추가한다.

## 로컬 실행

루트 [README.md](../README.md) 참고. 요약:

1. `docker compose up -d` — PostgreSQL 18 (`127.0.0.1:5432`). 볼륨이 처음 만들어질 때 E2E 용 `flowbit_e2e` DB 도 함께 만든다.
2. `backend/` 에서 `./gradlew bootRun` — 8080. 시작할 때 Flyway 가 스키마를 올린다.
3. `frontend/` 에서 `npm run dev` — 3000.

PostgreSQL 18 이미지는 볼륨을 `/var/lib/postgresql` 에 마운트한다 (17 이하의 `/var/lib/postgresql/data` 와 다르다).

운영 배포 방식(Docker 이미지, 단일 compose 파일 등)은 미결정이다. [09-decisions.md](09-decisions.md) 참고.

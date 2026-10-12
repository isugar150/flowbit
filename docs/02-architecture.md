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

## 실시간 동기화 (M3 부터, 사용자 지정 D-024, 구현 D-044)

여러 사람이 같은 화면을 보고 있을 때 한 사람의 변경이 다른 사람 화면에 새로 고침 없이 반영되어야 한다. WebSocket 으로 구현한다.

```
A 의 브라우저 ── PATCH /api/v1/projects/FLOW ──▶ 백엔드 ── 커밋 ──▶ RealtimeHub.publish("project:FLOW")
                                                                          │
B 의 브라우저 ◀── WebSocket {"type":"changed","topic":"project:FLOW"} ◀───┘
(router.refresh() 로 서버 컴포넌트를 다시 그림)
```

- **변경은 REST, 전파는 WebSocket.** 쓰기는 REST API 로 하고(검증, 권한, 오류 형식이 한 곳에 있다), 컨트롤러가 서비스의 트랜잭션이 커밋된 뒤 `RealtimeHub.publish(topic, actorId)` 를 부른다. WebSocket 으로 쓰기 명령을 받지 않는다.
- 엔드포인트: `GET /api/v1/realtime` (WebSocket 업그레이드, `realtime/RealtimeWebSocketHandler.kt`). 다른 요청과 똑같이 세션 쿠키로 인증하고(로그인 안 됐으면 401), `Origin` 이 Flowbit 자신(`X-Forwarded-Host`, `Host`, 요청 URI 중 하나)인지 확인한다. 60초마다 세션이 살아 있는지 확인하고(세션을 연장하지 않는다), 끝났으면 코드 4401 로 닫는다.
- **주제(topic)**: `project:{id}`(프로젝트 정보·속성·개요·구성원, 그 프로젝트의 이슈 목록), `issue:{번호}`(이슈 한 건, 그 이슈를 볼 수 있는 사람만), `issues`(어떤 이슈든 바뀜. 내용 없이 알리기만 하며 로그인한 누구나. 전체 이슈·내 이슈 화면이 듣는다). 이슈를 고치면 `issues`, 연결된(그리고 떠난) 프로젝트의 `project:`, `issue:` 를 알리고, 하위 이슈면 상위 이슈의 `issue:` 도 알린다 (D-062).
- **메시지**
  - 브라우저 → 서버: `{"type":"subscribe","topics":[...],"presence":"issue:12"}`. 보낼 때마다 이전 구독을 바꾼다. `{"type":"focus","target":"description"}`(없으면 `null`)로 그 화면의 어느 부분을 가리키고 있는지 알린다.
  - 서버 → 브라우저: `{"type":"changed","topic","actorId"}`, `{"type":"presence","key","viewers":[{id,loginId,displayName,avatarUrl,focus}]}`, `{"type":"ping"}`(20초마다).
- **받는 쪽**: 이벤트에 바뀐 값을 싣지 않는다. 받으면 그 화면을 `router.refresh()` 로 다시 그린다(연달아 오면 250ms 로 묶는다). 화면은 서버 컴포넌트가 데이터를 읽는 구조라, 자기 변경 뒤에 하는 것과 같은 방법이 가장 단순하고 어긋나지 않는다. 이 탭이 3초 안에 쓴 변경의 메아리는 건너뛴다(같은 사람의 다른 탭은 반영된다).
- **편집 중인 글**: 문서 편집기와 제목·요약 입력은 서버에서 새 값이 오면 바꿔 보여 준다. 단, **그 칸에 아직 저장되지 않은 입력이 있으면 덮지 않는다**(`useAutosave().isDirty`). 동시에 고치면 나중에 저장한 쪽이 이긴다. 충돌 알림과 한 글자씩 합치는 공동 편집(CRDT)은 아직 없다.
- **프레즌스**: 각 화면이 `presence` 키를 알리고(프로젝트 화면은 `project:{id}`, 이슈 상세는 `issue:KEY-N`), 서버는 같은 키를 보는 사람이 바뀔 때마다 그 목록을 보낸다. 화면 오른쪽 위(경로 표시 줄)에 **나를 뺀** 사람들의 아바타가 겹쳐 보이고, 마우스를 올리거나 키보드로 초점을 주면 이름이 보인다. 한 사람이 탭을 여러 개 열어도 한 번만 보인다.
- **가리키는 위치** (D-053): 화면의 고칠 수 있는 부분에 `data-presence-id` 를 단다(속성 줄은 `property:<이름>`, 개요의 `summary`·`description`·`update`, 마일스톤 `milestone:<id>`, 이슈의 `title`·`body`). `features/realtime/presence-focus.tsx` 가 키보드 초점이 있는 부분(없으면 마우스가 올라간 부분)을 120ms 뒤에 `focus` 로 보내고, 다른 사람이 가리키는 부분에는 `data-presence-by`(이름)를 붙인다. 모양(사람별 색 테두리 + 이름표)은 `globals.css`. 서버는 값을 100자까지만 받고 뜻을 해석하지 않는다. 화면을 옮기면 지워진다. 글 안의 커서 위치(몇 번째 글자)까지는 보여 주지 않는다. 새 화면에 고칠 수 있는 부분을 만들면 감싸는 요소(입력 칸 자체가 아니라 `div`)에 `data-presence-id` 를 단다.
- 끊김: 지수 백오프(1초 → 최대 30초)로 다시 붙고, 다시 붙으면 놓친 변경이 있을 수 있으니 한 번 다시 그린다. 연결이 없어도 앱은 REST 만으로 동작한다.
- 서버 구현: WebFlux `WebSocketHandler` + 프로세스 안의 이벤트 버스(Reactor `Sinks`, `realtime/RealtimeHub.kt`). **Sink 에는 한 스레드(`realtime-hub`)가 차례로만 신호를 넣는다.** 여러 요청 스레드나 다른 신호를 처리하는 도중(연결이 닫히며 `leave` 가 불릴 때)에 직접 넣으면 Reactor 가 "serially" 오류를 내고 그 연결이 끊긴다(실제로 겪었다. `RealtimeIntegrationTest` 가 지킨다). 인스턴스가 하나이므로 이것으로 충분하다. 여러 인스턴스로 늘릴 때는 PostgreSQL `LISTEN/NOTIFY` 로 인스턴스 사이에 이벤트를 전달한다.
- 프런트엔드: `features/realtime/realtime-provider.tsx`(연결 하나, `RealtimeScope`, `useViewers`), `features/realtime/components/presence-avatars.tsx`. `RealtimeProvider` 는 앱 틀(`app-shell.tsx`)에 있다. 새 화면이 실시간으로 갱신되어야 하면 그 화면에 `<RealtimeScope topics=[...] presence="..." />` 를 둔다.
- **Next.js rewrite 와 WebSocket** (확인함): `next dev`/`next start` 모두 `/api` rewrite 로 WebSocket 업그레이드를 백엔드에 넘기고 `x-forwarded-host` 를 붙인다(`next/dist/server/lib/router-server.js` 의 `upgradeHandler`). 단, 운영 모드의 프록시는 30초 동안 오가는 것이 없으면 끊으므로 서버가 20초마다 `ping` 을 보낸다. 앞에 리버스 프록시(nginx 등)를 두면 `/api/v1/realtime` 에 `Upgrade`/`Connection` 헤더를 넘기도록 설정해야 한다.
- 알려진 한계: 구독할 때만 권한을 확인한다. 연결 중에 프로젝트에서 내보내진 사람은 다시 연결하거나 화면을 옮길 때까지 "바뀌었다"는 알림(내용 없음)과 보는 사람 목록을 계속 받는다.

## 알림 (메일, 웹 푸시를 붙일 자리)

이슈가 바뀌면 그 이슈를 **구독하는 사람**에게 알린다. 지금은 알릴 통로가 하나도 없고(아무것도 보내지 않는다), 통로를 꽂을 자리만 있다 (D-061).

```
IssueController ─ 커밋 뒤 ─▶ NotificationDispatcher.dispatch(IssueNotification)
                                   │  등록된 NotificationChannel 빈을 차례로 부른다
                                   ├─▶ (앞으로) SmtpNotificationChannel      : 메일
                                   └─▶ (앞으로) FcmNotificationChannel       : Firebase 웹 푸시
```

- `notification/Notifications.kt`: `IssueNotification`(이슈 키·제목, 프로젝트 키, 바꾼 사람, 바뀐 일 목록, 받는 사람 id 목록), `NotificationChannel`(구현할 인터페이스), `NotificationDispatcher`.
- **받는 사람** = 그 이슈의 구독자에서 바꾼 사람 본인을 뺀 것. 서비스가 트랜잭션 안에서 계산해 `IssueChange.recipientIds` 로 돌려준다.
- **바뀐 일** = 활동 기록과 같은 종류와 값(`IssueActivityType`, `{from,to}`). 메일 본문이나 푸시 문구는 통로가 이것으로 만든다.
- 통로는 요청 처리 중에(커밋 뒤) 불린다. 통로 하나가 실패해도 요청과 다른 통로는 실패하지 않고 로그만 남는다.
- **SMTP 를 붙일 때**: `NotificationChannel` 을 구현한 빈을 만들고, 받는 사람의 주소는 `users.email`(지금은 비어 있다. 입력 화면이 필요하다)에서 읽는다. 메일 서버 설정은 환경 변수로 받는다.
- **Firebase 웹 푸시를 붙일 때**: 브라우저가 받은 FCM 토큰을 저장할 테이블(사용자 id, 토큰, 만든 때)과 등록 API, 프런트의 서비스 워커가 필요하다. 통로는 받는 사람의 토큰으로 보낸다.
- 보내는 일이 느려지거나(메일 서버 지연) 재시도가 필요해지면, 통로를 직접 부르는 대신 알림을 테이블에 쌓고 따로 내보내는 방식(아웃박스)으로 바꾼다. 그때 같은 테이블이 앱 안의 수신함도 된다.
- 사용자별 알림 끄기·종류 고르기는 아직 없다. 구독이 유일한 스위치다.

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
| `FLOWBIT_DEV_ORIGINS` | frontend (개발 서버만) | 없음 | `npm run dev` 를 localhost 가 아닌 주소(예: 사내망 `192.168.0.75`)로 열 때 허용할 호스트, 쉼표로 구분, `192.168.0.*` 같은 와일드카드 가능. 없으면 Next 16 이 그 주소에 `/_next` 스크립트를 403 으로 막아 화면이 동작하지 않는다(`next.config.ts` 의 `allowedDevOrigins`) |

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

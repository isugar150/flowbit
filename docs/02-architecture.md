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
├─ script/                서버에서 git pull → 빌드 → 재시작하는 배포 스크립트 ("스크립트 배포")
├─ docker/postgres/init/  DB 볼륨이 처음 만들어질 때 실행되는 SQL
├─ docker-compose.yml     로컬 개발용 PostgreSQL 18, `app` 프로필로 백엔드·프런트 컨테이너
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
- **주제(topic)**: `project:{id}`(프로젝트 정보·속성·개요·구성원, 그 프로젝트의 이슈 목록), `issue:{번호}`(이슈 한 건, 그 이슈를 볼 수 있는 사람만), `projects`(프로젝트 목록이 누군가에게 달라짐: 프로젝트 생성·이름/아이콘 변경, 구성원 추가·역할 변경·내보내기. 내용 없이 알리기만 하며 로그인한 누구나. 앱 전체 레이아웃이 들어서 사이드바와 프로젝트 목록을 다시 그린다), `issues`(어떤 이슈든 바뀜. 내용 없이 알리기만 하며 로그인한 누구나. 전체 이슈·내 이슈 화면이 듣는다). 이슈를 고치면 `issues`, 연결된(그리고 떠난) 프로젝트의 `project:`, `issue:` 를 알리고, 하위 이슈면 상위 이슈의 `issue:` 도 알린다 (D-062).
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
- 서버 종료: `server.shutdown: immediate` (D-103). 열린 WebSocket 연결은 끝나지 않는 요청이라, 기본값(graceful)으로는 종료 신호를 받고도 30초를 기다렸다. 서버가 내려가면 브라우저는 위의 "끊김" 규칙대로 다시 붙는다.
- 알려진 한계: 구독할 때만 권한을 확인한다. 연결 중에 프로젝트에서 내보내진 사람은 다시 연결하거나 화면을 옮길 때까지 "바뀌었다"는 알림(내용 없음)과 보는 사람 목록을 계속 받는다.

## 외부 로그인 (LDAP, SSO)

명세는 [features/M2b-ldap-and-sso.md](features/M2b-ldap-and-sso.md). 설정은 환경 변수가 아니라 DB 에 있고 관리자 화면에서 바꾼다(재시작 없이 적용).

- **LDAP**: UnboundID LDAP SDK. 블로킹 라이브러리라 `LdapDirectory` 가 모든 호출을 `Dispatchers.IO` 에서 한다. 연결은 로그인마다 하나를 맺어(풀 없음) 검색 계정 바인드 → 검색 → 사용자 바인드를 그 연결에서 차례로 한다 (D-094).
- **SSO**: OpenID Connect 를 직접 구현했다(`OidcClient`, `SsoService`, `SsoController`). Spring Security 의 `oauth2Login` 은 인가 요청을 `WebSession` 에 두는데 이 프로젝트는 `WebSession` 을 만들지 않고, 설정도 DB 에서 실행 중에 바뀌기 때문이다. ID 토큰 검증만 `spring-security-oauth2-jose`(Nimbus)를 쓴다. SAML 은 Spring Security 가 WebFlux 를 지원하지 않아 하지 않는다 (D-088).
- **SSO "직접 설정"**(D-094): OIDC 가 아닌 서비스(일반 OAuth 2.0, 사내 SSO)는 `OAuth2Client` 가 설정에 적힌 대로 코드를 교환한다. 서명 검증이 없으므로 설정한 주소에 서버가 직접 받아 온 응답이라는 것이 믿는 근거다.
- 두 방식 모두 마지막에는 `ExternalAccountService.signIn` 이 계정을 찾거나 만들고 보통 로그인과 같은 세션을 연다.

## 알림 (메일, 웹 푸시, API 알림)

명세는 [features/M7a-notification-channels.md](features/M7a-notification-channels.md) (D-061, D-091~D-093). 설정은 DB 에 있고 관리자 화면에서 바꾼다.

```
IssueAnnouncer.changed ─ 커밋 뒤 ─▶ NotificationDispatcher.dispatch(IssueNotification)   기다리지 않는다
                                        │  NotificationChannel 빈마다 백그라운드 코루틴
                                        ├─▶ MailNotificationChannel      SMTP (Jakarta Mail, Dispatchers.IO)
                                        ├─▶ PushNotificationChannel      Firebase Cloud Messaging HTTP v1 (WebClient)
                                        └─▶ WebhookNotificationChannel   관리자가 정한 주소 (WebClient)
```

- `notification/` 패키지: `Notifications.kt`(`IssueNotification`, `NotificationChannel`, `NotificationDispatcher`), `NotificationText.kt`(세 통로가 함께 쓰는 문구), `MailChannel.kt`, `PushChannel.kt`, `WebhookChannel.kt`, `NotificationSettingsService.kt`, `NotificationControllers.kt`, `NotificationData.kt`(엔티티).
- **받는 사람** = 그 이슈의 구독자와 **그 이슈가 연결된 프로젝트의 구독자**(지금도 그 프로젝트에 들어갈 수 있는 사람만)를 합친 것에서 바꾼 사람 본인을 뺀 것(D-102, `IssueActivityStore.recipientIds`). 서비스가 트랜잭션 안에서 계산해 `IssueChange.recipientIds` 로 돌려준다. 받는 사람이 없으면 어느 통로로도 보내지 않는다. 세 통로 모두 받는 사람에게만 가고, API 알림도 받는 사람마다 한 번씩 부른다(구독과 무관하게 전체로 나가는 알림은 없다). **`@` 로 태그된 사람**(댓글, 이슈 본문)은 그 자리에서 구독자가 되므로 같은 계산에 들어간다(D-101). 댓글은 `IssueAnnouncer.commented` 가 알린다(목록은 다시 그리지 않고 그 이슈 화면만).
- **바뀐 일** = 활동 기록과 같은 종류와 값(`IssueActivityType`, `{from,to}`).
- 통로를 더하려면 `NotificationChannel` 을 구현한 빈을 만든다. 통로는 스스로 켜져 있는지 확인하고, 필요한 것(주소, 토큰)을 직접 찾는다.
- 디스크에 쌓지 않는다. 보내는 중에 서버가 꺼지면 그 알림은 사라진다. 재시도는 API 알림만 한다(5xx·무응답, 두 번 더).
- 프런트: `public/push-sw.js`(서비스 워커), `features/notifications/push.ts`(브라우저 등록, `firebase` 는 동적 import).
- 링크에 쓰는 접속 주소는 `FLOWBIT_PUBLIC_URL`(`common/web/PublicUrl.kt`). 없으면 메일·API 알림의 링크가 경로뿐이다.

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
| `FLOWBIT_PUBLIC_URL` | backend | 없음 | 사람들이 Flowbit 을 여는 주소(`https://flowbit.example.com`). **메일과 API 알림의 이슈 링크**에 쓴다(없으면 `/issue/FB-12` 같은 경로만 들어간다). SSO 리다이렉트 URI 에도 쓰는데, 그쪽은 비워 두면 요청의 `X-Forwarded-*` 로 알아낸다 |
| `BACKEND_URL` | frontend | `http://localhost:8080` | Next.js 가 `/api` 를 넘기고 서버에서 직접 부를 백엔드 주소. Docker 이미지에서는 빌드 인자로도 준다 ("Docker 이미지") |
| `FLOWBIT_WEB_PORT` | docker-compose (`app` 프로필) | `3000` | 프런트 컨테이너를 호스트에 여는 포트 |
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

## Docker 이미지 (D-100)

백엔드와 프런트엔드는 각각 Dockerfile 이 있다. `docker-compose.yml` 의 `app` 프로필로 DB 와 함께 띄운다.

```bash
docker compose --profile app up -d --build   # http://localhost:3000 (FLOWBIT_WEB_PORT)
docker compose up -d                         # 개발: DB 만 (지금까지와 같다)
```

| 이미지 | 파일 | 내용 |
|---|---|---|
| `flowbit-backend` | `backend/Dockerfile` | JDK 25 로 `bootJar` 를 만들고(테스트는 빼고) JRE 25 위에서 실행. Ubuntu 기반(WebP 변환기가 glibc 를 쓴다). 비루트 사용자, 8080. 올린 파일은 `/var/lib/flowbit/uploads`(볼륨, **백업 대상**) |
| `flowbit-frontend` | `frontend/Dockerfile` | `npm ci` → `next build`(standalone 출력) → `node server.js`. 비루트 사용자, 3000 |

- **`BACKEND_URL` 은 프런트 이미지를 빌드할 때 고정된다.** `/api` rewrite 주소가 빌드 결과에 들어가기 때문이다. 빌드 인자 `BACKEND_URL` 과 실행할 때의 환경 변수 `BACKEND_URL`(서버 컴포넌트가 백엔드를 부르는 주소)을 같게 준다. compose 는 둘 다 `http://backend:8080`.
- 백엔드는 포트를 열지 않는다. 브라우저는 프런트의 `/api`(WebSocket `/api/v1/realtime` 포함)를 거쳐서만 백엔드에 닿는다.
- `next.config.ts` 에 `output: "standalone"` 을 두었다. `next dev`, `next start` 에는 영향이 없다.
- 운영에서는 `.env` 에 `FLOWBIT_DB_PASSWORD` 를 바꾸고, `FLOWBIT_PUBLIC_URL` 을 실제 주소로 준다. 앞에 HTTPS 리버스 프록시(nginx 등)를 둘 때는 `Upgrade`/`Connection` 헤더를 넘긴다("실시간 동기화").
- 아직 없는 것: 이미지 레지스트리에 올리기, CI 에서 이미지 빌드, Spring 프로필(`prod`).

## 스크립트 배포 (D-105)

Docker 없이 서버 한 대에서 프로세스로 띄우는 방법이다. 저장소를 서버에 받아 두고 `script/deploy.sh` 를 돌리면 **git pull → 빌드 → 내리기 → 바꾸기 → 올리기**를 한다. 무중단이 아니다: 내리고 올리는 사이(수 초~수십 초)에는 접속이 끊긴다. 두 번째 인스턴스, nginx 전환, 릴리스 폴더는 없다.

```bash
cd /app/flowbit/script        # 저장소를 /app/flowbit 에 받았다면
cp ../.env.example .env       # 앱 설정. 값을 고친다 (DB 비밀번호, FLOWBIT_PUBLIC_URL, FLOWBIT_STORAGE_PATH …)
./deploy.sh                   # 무엇을 배포할지 묻는다. ./deploy.sh 1 | 2 | 3 으로 바로 고를 수 있다
./shutdown.sh                 # 내리기 (인자: backend | frontend, 없으면 둘 다)
./startup.sh                  # 마지막으로 빌드한 것을 올리기
```

| 파일 | 내용 |
|---|---|
| `deploy.sh` | 1 백엔드+프런트, 2 백엔드, 3 프런트. 원격에 새 커밋이 있으면 바뀐 파일을 먼저 보여 준다. **고른 것을 전부 빌드한 뒤에 내리므로 빌드가 실패하면 돌던 서버는 그대로다.** 동시에 두 번 돌지 않게 잠근다(`/tmp/flowbit.deploy.lock`) |
| `startup.sh`, `shutdown.sh` | 백엔드(`java -jar`), 프런트(`node server.js`)를 pid 파일로 올리고 내린다. pid 파일을 잃어도 그 포트를 듣는 java·node 프로세스를 찾아 내린다 |
| `common.sh` | 위 셋이 함께 쓰는 함수. 직접 실행하지 않는다 |
| `app.env` | **스크립트의 설정**(경로, JVM 옵션, 헬스체크, 종료 대기). git 이 추적하므로 서버에서는 고치지 않고, 바꿀 줄만 `script/app.local.env`(git 추적 안 함)에 적는다 |
| `.env` | **앱의 설정**. `.env.example` 과 같은 키. `script/` 에 두면 스크립트가 읽어 백엔드·프런트 프로세스와 프런트 빌드에 환경 변수로 넘긴다. git 추적 안 함 |

- 서버에 필요한 것: git, JDK 25, Node.js 22 와 npm, curl, bash, `flock`(util-linux), 그리고 PostgreSQL 18(이 스크립트는 DB 를 띄우지 않는다).
- JDK 를 골라 쓰려면 `app.env` 의 `JDK_HOME` 에 설치 폴더(그 아래 `bin/java`)를 적는다. 빌드(Gradle 의 `JAVA_HOME`)와 백엔드 실행이 둘 다 그 JDK 로 된다. 비우면 `PATH` 의 `java`. 폴더가 틀리면 스크립트가 바로 멈춘다.
- 포트는 `.env` 에서 정한다: `SERVER_PORT`(백엔드, 기본 8080), `FLOWBIT_WEB_PORT`(프런트, 기본 3000). `BACKEND_URL` 을 적지 않으면 `http://127.0.0.1:<SERVER_PORT>` 로 정해져 프런트 빌드와 실행에 함께 쓰인다.
- `.env` 는 셸 스크립트로 실행하지 않고 `KEY=VALUE` 줄로만 읽는다. 비밀번호에 `$` 나 공백이 있어도 적힌 그대로 들어간다. 값 양끝의 따옴표는 뗀다.
- 실행 파일은 `script/app/backend/app.jar`, `script/app/frontend/` 에 복사해 두고 거기서 돈다. 바로 앞 빌드는 `app.jar.prev`, `frontend.prev` 로 하나만 남는다(새 것이 뜨지 않을 때 손으로 되돌리는 용도. 자동 롤백은 없다).
- 로그는 `script/logs/backend.log`, `frontend.log`. 시작할 때마다 새 파일이고 바로 앞 것은 `.prev`.
- 백엔드는 `script/` 에서 실행된다. `FLOWBIT_STORAGE_PATH` 가 상대 경로면 `script/data/uploads` 가 되므로 **운영에서는 백업되는 절대 경로를 준다.**
- 떴는지 확인: 백엔드는 `GET /api/v1/health` 가 2xx(DB 까지 확인), 프런트는 포트가 응답하면 된 것으로 본다.
- 프런트는 기본으로 `0.0.0.0:3000` 을 듣는다(`FRONT_HOST`). 앞에 nginx 를 둘 때는 `127.0.0.1` 로 바꾸고 `/` 전체를 프런트로 넘긴다(`Upgrade`/`Connection` 헤더 포함, "실시간 동기화"). 백엔드 포트는 밖에 열 필요가 없다.
- 서버를 재부팅하면 자동으로 뜨지 않는다. 필요하면 `startup.sh` 를 부르는 systemd 유닛이나 cron `@reboot` 을 서버에 둔다(저장소에는 없다).

# 진행 상황

작업을 시작할 때 읽고, 끝낼 때 갱신한다. 다음 에이전트는 이전 대화를 볼 수 없으므로, 이어서 작업하는 데 필요한 것은 모두 여기에 적는다.

## 현재 상태

- **M1(로그인 및 초기 설정)이 구현되어 동작한다.** 사용자가 브라우저에서 초기 설정 → 로그아웃 → 로그인을 직접 확인했다 (2026-10-12).
- 제품 방향은 **Linear식**이다 (D-020). 기준 문서는 [01-product.md](01-product.md). M2 이후의 화면은 아직 하나도 없다.
- 백엔드는 **WebFlux + 코루틴 + R2DBC** 다 (D-019). `./gradlew check` 통과: ktlint, 테스트 48개(통합 41, 단위 7), PostgreSQL 18 Testcontainers.
- 프론트엔드는 `npm run lint`, `npm run typecheck` 통과. **자동 테스트는 없다** (Vitest, Playwright 는 설치만 됨).
- **M2a(설정 화면과 이미지 업로드)가 구현됐다** ([features/M2a-settings-and-images.md](features/M2a-settings-and-images.md)). 백엔드는 테스트로 확인했고, **화면은 아무도 아직 확인하지 않았다** (에이전트는 사용자 계정의 비밀번호를 몰라 로그인 후 화면을 볼 수 없었다).
- 실시간 동기화(WebSocket)는 설계만 있다 (D-024, [02-architecture.md](02-architecture.md) "실시간 동기화"). M3 에서 구현한다.
- 개발 DB(`flowbit`)에는 사용자가 만든 관리자 계정이 있다. 지우지 않는다.

## 다음 작업

사용자가 다음 지시를 주기 전에 임의로 M2 를 시작하지 않는다. 지시를 받으면:

1. 해당 마일스톤의 범위를 사용자에게 확인한다 ([08-roadmap.md](08-roadmap.md) 의 M2 이후는 초안이다).
2. `features/M<번호>-<이름>.md` 명세를 먼저 쓴다. 화면은 [01-product.md](01-product.md) 의 해당 절을 따른다.
3. 구현한다.

남아 있는 M0·M1 의 빈 곳 (사용자가 "테스트는 내가 해 보고 문제 있으면 말하겠다"고 해서 멈춘 것들):

- [ ] 프론트엔드 단위 테스트 (Vitest): `safeNextPath`, `features/auth/schema.ts`, `lib/api/errors.ts`
- [ ] E2E (Playwright): 초기 설정 → 로그아웃 → 로그인, 리다이렉트 규칙, `next` 파라미터. `flowbit_e2e` DB 를 쓰는 별도 백엔드(예: 8081)와 Next 서버(예: 3100)로 돌린다. DB 는 `docker/postgres/init` 스크립트가 볼륨 생성 시 만들어 둔다
- [ ] CI
- [ ] 디자인 리뷰 스킬로 점검, 다크 모드와 360px 폭 확인, 키보드만으로 전체 흐름 확인 (에이전트는 라이트 모드 데스크톱 폭의 초기 설정 화면과 검증 오류 표시까지만 직접 봤다)
- [ ] 429·네트워크 오류·서버 오류 문구가 화면에 제대로 나오는지 (코드는 있고, 화면에서 확인하지 않았다)

## 마일스톤 체크리스트

### M0. 프로젝트 뼈대

- [x] `docker-compose.yml` (PostgreSQL 18), `.env.example`, E2E 용 DB 생성 스크립트
- [x] `backend/` WebFlux + R2DBC + Flyway, 공통 구성, ktlint, health 엔드포인트, 통합 테스트 기반
- [x] `frontend/` Next.js 16 + shadcn(base-nova), `/api` rewrite, API 클라이언트, 디자인 토큰, Pretendard
- [x] 루트 `README.md`
- [ ] 프론트엔드 테스트 설정과 테스트
- [ ] CI

### M1. 로그인 및 초기 설정

- [x] 백엔드: 마이그레이션 `V1` (instance_settings, users, user_sessions, audit_events)
- [x] 백엔드: 보안 설정 (세션 쿠키, CSRF, 공개 경로, 401/403 문제 문서)
- [x] 백엔드: `setup`, `auth`, `user`, `instance` 모듈, 요청 제한, 잠금, 감사 로그
- [x] 백엔드: 인수 기준 통합 테스트
- [x] 프론트엔드: `/setup`, `/login`, 홈(빈 상태), 사이드바와 사용자 메뉴(테마, 로그아웃), `/unavailable`
- [x] 프론트엔드: `proxy.ts` 리다이렉트 규칙
- [ ] 프론트엔드 자동 테스트, 디자인·접근성 점검 (위 "남아 있는 빈 곳")
- [ ] 세부 수치 사용자 확인 (제안값으로 구현됨: 아이디 4~32자, 비밀번호 10자 이상, 5회 실패 시 15분 잠금, 세션 7일/최대 30일, IP당 분당 10회)

### M2a. 설정 화면과 이미지 업로드

- [x] 백엔드: `V2` 마이그레이션, `media` 모듈(WebP 변환, 파일 저장, 파일 제공), 프로필·워크스페이스 API, `/api/v1/admin/**` 관리자 규칙, `.env` 읽기
- [x] 백엔드: 통합 테스트 15개
- [x] 프론트엔드: 사용자 메뉴의 설정/관리자 설정, `/settings/profile`, `/settings/workspace`, 사이드바의 로고·이름, 아바타
- [ ] 화면 확인 (명세의 "화면에서 확인이 필요한 것")
- [ ] 명세의 "확인이 필요한 것" 3가지 사용자 확인

## 알아둘 것 (코드를 고치기 전에)

- **블로킹 코드 금지.** 백엔드는 WebFlux 다. JDBC/JPA/`.block()` 을 쓰지 않는다. 규칙은 [03-backend-guide.md](03-backend-guide.md) 맨 위.
- R2DBC 엔티티는 변경 감지가 없다. 값을 바꾸면 `save` 를 직접 부르고, 새 행은 `R2dbcEntityTemplate.insert` 로 넣는다.
- `@AuthenticationPrincipal` 로 받는 `AuthenticatedUser` 에 `java.security.Principal` 을 구현하면 타입 오류가 난다 (실제로 겪었다).
- shadcn 은 Base UI 기반이다. `asChild` 가 아니라 `render` prop 을 쓴다.
- Next.js 16: `middleware.ts` 가 아니라 `src/proxy.ts`. `cacheComponents` 가 켜져 있어 요청별 데이터를 읽는 서버 컴포넌트는 `<Suspense>` 안에 둔다.
- Next 개발 서버를 켠 채로 `providers.tsx` 같은 최상위 클라이언트 모듈을 새로 만들면 "No QueryClient set" 오류가 날 수 있다. 개발 서버를 다시 시작하면 사라진다.
- 프론트엔드에 `npm install` 로 패키지를 추가할 때 peer 충돌이 나면 `@types/node` 버전부터 확인한다 (Vitest 가 22 이상을 요구해서 22 로 맞췄다).
- Gradle 은 JDK 17 이상으로 실행해야 한다. 이 PC 의 기본 `JAVA_HOME` 은 JDK 11 이라, 에이전트는 `JAVA_HOME` 을 `~/.jdks/corretto-21.0.12.1` 로 지정해서 실행했다.
- 같은 저장소를 다른 세션이나 에디터가 동시에 고치고 있을 수 있다. 문서를 통째로 다시 쓰기 전에 현재 내용을 다시 읽는다.

## 열린 질문

[09-decisions.md](09-decisions.md) 의 "미결정" 표 참고. 작업 중 새로 생긴 질문은 아래에 적는다.

- (없음)

## 작업 기록

최신 항목을 위에 추가한다. 한 번의 작업 세션마다 한 항목.

### 2026-10-12 — 설정 화면과 이미지 업로드 (M2a)

- 사용자 지시: 왼쪽 아래 사용자 메뉴에 설정 버튼과 관리자 설정 버튼, 이미지 업로드, 저장 경로는 `.env` 로 지정, 올리면 WebP 변환과 해상도 최적화.
- 한 일: 위 체크리스트의 M2a. 올릴 이미지는 프로필 사진과 워크스페이스 로고로 정했다(사용자가 종류를 지정하지 않았다).
- 확인한 범위: 백엔드 `./gradlew check` 통과(48개). 프론트엔드는 lint, typecheck 만. 개발 DB 에 `V2` 가 적용됐고 백엔드 개발 서버를 새 코드로 다시 띄웠다.
- 겪은 것: WebFlux 의 HTTP 방화벽은 경로에 인코딩된 `/`(`%2F`)가 있으면 컨트롤러 전에 400 으로 막는다.

### 2026-10-12 — M0·M1 구현, WebFlux 전환, Linear식 재설계

- 사용자 지시 (시간순): ① 코드로 구현, 디자인 스킬을 잘 보고 → ② 문서와 코드를 Jira식에서 Linear식으로 마이그레이션 (재설계 원칙과 출력 구조를 함께 줌) → ③ 백엔드를 Spring MVC 가 아니라 WebFlux 로 → ④ 초기 설정과 로그인은 직접 확인했으니 더 테스트하지 말 것 → ⑤ 여러 사용자의 실시간 동시 작업을 WebSocket 으로.
- 한 일:
  - 백엔드: 처음에 MVC + JPA + Spring Session 으로 만들었다가 ③ 에 따라 WebFlux + 코루틴 + R2DBC + 자체 세션 테이블로 다시 썼다. 테스트 33개 통과.
  - 프론트엔드: Next.js 16 + shadcn(base-nova). 초기 설정, 로그인, 홈, 사용자 메뉴. 디자인 토큰은 ② 의 원칙(Pretendard, 흰 바탕 + 파란색, 라이트 기본)에 맞췄다.
  - 문서: [01-product.md](01-product.md) 를 Linear식 재설계 문서로 다시 썼다(사용자가 지정한 8개 절 구조). 02, 03, 04, 05, 06, 08, 09 를 그에 맞게 고쳤다. 실시간 동기화 설계를 02 에 추가했다.
- 참고한 스킬: `shadcn-ui-ui/skills/shadcn`(폼, 컴포넌트 규칙), `anthropics-skills/skills/frontend-design`, `mengto-skills/agent-skills/ui/no-ai-design-slop`, `jakubkrehel-skills/skills/better-ui`, `pbakaus-impeccable` 의 `reference/mode-operate.md`.
- 확인한 범위: 백엔드는 자동 테스트. 프론트엔드는 에이전트가 초기 설정 화면과 검증 오류 표시를 브라우저로 봤고, 계정 생성·로그아웃·로그인은 사용자가 직접 확인했다. 그 밖의 화면 상태는 확인하지 않았다 (위 "남아 있는 빈 곳").
- 에이전트가 포트 3000 에서 돌던 Next 개발 서버를 한 번 종료하고 다시 띄웠다 (오래된 모듈 그래프 때문에 오류가 났다).

### 2026-10-12 — 초기 설정 방식 확정, PostgreSQL 18 compose

- 사용자 확정: 초기 설정은 관리자 아이디와 비밀번호만 받고 그 계정으로 로그인한다 (D-015, D-016). DB 는 PostgreSQL 18 (D-017). 스키마 관리는 Flyway (D-008).
- 한 일: M1 명세를 다시 작성 (설정 토큰·조직 정보 단계 제거, 이메일 → 아이디). `docker-compose.yml`, `.env.example` 작성.

### 2026-10-12 — 설계 문서 초안

- 한 일: `docs/` 문서 세트 작성, `AGENTS.md`/`CLAUDE.md` 작성, 스킬 저장소 9개 clone, `.gitignore` 작성.
- 사용자 확정 사항: Spring Boot + Kotlin, Next.js + shadcn/ui (모두 최신 안정 버전), 단일 조직 설치형, 1차 범위는 로그인 및 초기 설정.

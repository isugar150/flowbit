# 진행 상황

작업을 시작할 때 읽고, 끝낼 때 갱신한다. 다음 에이전트는 이전 대화를 볼 수 없으므로, 이어서 작업하는 데 필요한 것은 모두 여기에 적는다.

## 현재 상태

- **M1(로그인 및 초기 설정)이 구현되어 동작한다.** 사용자가 브라우저에서 초기 설정 → 로그아웃 → 로그인을 직접 확인했다 (2026-10-12).
- 제품 방향은 **Linear식**이다 (D-020). 기준 문서는 [01-product.md](01-product.md). M2 이후의 화면은 아직 하나도 없다.
- 백엔드는 **WebFlux + 코루틴 + R2DBC** 다 (D-019). `./gradlew check` 통과: ktlint, 테스트 96개(통합 86, 단위 10), PostgreSQL 18 Testcontainers. **실시간·마일스톤·건강도·프로젝트 아이콘(`V7`~`V9`)까지 포함한 현재 코드로 2026-10-12 에 다시 통과를 확인했다.** 다만 그 기능들의 전용 통합 테스트는 아직 없다(아래 체크리스트).
- 프론트엔드는 `npm run lint`, `npm run typecheck`, `npx next build` 통과. **자동 테스트는 없다** (Vitest, Playwright 는 설치만 됨).
- **M2a(설정 화면과 이미지 업로드)가 구현됐다** ([features/M2a-settings-and-images.md](features/M2a-settings-and-images.md)). 백엔드는 테스트로 확인했고, **화면은 아무도 아직 확인하지 않았다** (에이전트는 사용자 계정의 비밀번호를 몰라 로그인 후 화면을 볼 수 없었다).
- **M3 1단계(구성원 관리, 프로젝트, 프로젝트 권한)가 구현됐다** ([features/M3-projects-and-issues.md](features/M3-projects-and-issues.md)). 팀 개념은 없어지고 프로젝트로 합쳐졌다 (D-032). 2~4단계는 처음에 명세만 있었고, 지금 된 것은 아래 줄들과 명세의 "지금 구현된 범위"가 기준이다.
- **이슈 댓글과 `@` 태그가 구현됐다** (D-101, 2026-10-13). 댓글·이슈 본문에서 태그된 사람은 그 이슈의 구독자가 되어 등록한 알림 수단 전부로 알림을 받는다. 마이그레이션 `V5`. **화면은 아무도 확인하지 않았다.**
- **이슈를 만들고, 목록에서 보고, 문서 편집기로 본문을 쓸 수 있다** (M3 2·3단계의 핵심). 화면 구조는 Linear 의 현재 UI를 따라 다시 짰다 (D-039). 무엇이 되고 안 되는지는 [features/M3-projects-and-issues.md](features/M3-projects-and-issues.md) 의 "지금 구현된 범위".
- **실시간 동기화(WebSocket)와 "보는 사람" 아바타가 구현됐다** (D-044, [02-architecture.md](02-architecture.md) "실시간 동기화"). 2026-10-13 에 에이전트가 두 계정으로 일부를 확인했다(아래 작업 기록). 편집 중인 글·커서 표시는 아직 아무도 확인하지 않았다.
- **LDAP 연동, SSO(OpenID Connect) 연동, 이메일 아이디가 구현됐다** ([features/M2b-ldap-and-sso.md](features/M2b-ldap-and-sso.md), D-087~D-090). 관리자 설정의 "로그인 연동" 탭. 계정이 없으면 처음 로그인할 때 가입된다. 백엔드는 인메모리 LDAP·가짜 OIDC 서버로 테스트했고, **화면과 실제 LDAP·SSO 서버로는 아무도 확인하지 않았다.**
- **알림 통로(메일 SMTP, Firebase 웹 푸시, API 알림)가 구현됐다** ([features/M7a-notification-channels.md](features/M7a-notification-channels.md), D-091~D-093). 관리자 설정의 "알림" 탭, 내 설정의 "알림". 백엔드는 가짜 SMTP·FCM·웹훅 서버로 테스트했고, **화면과 실제 서비스로는 확인하지 않았다.**
- **알림은 구독한 사람에게만 간다** (D-102, 2026-10-13). 받는 사람 = 이슈 구독자 + 그 이슈의 프로젝트 구독자. 프로젝트 개요에 "구독" 버튼이 생겼고(`project_subscriptions`), API 알림은 항상 받는 사람마다 한 번씩 부른다(전체 변경을 한 주소로 보내는 방식은 없앴다). **화면은 아무도 확인하지 않았다.**
- 백엔드 테스트는 2026-10-13 기준 170개가 통과한다(`FLOWBIT_BUILD_DIR=<세션마다 다른 폴더> ./gradlew check`). 위의 "96개"는 그 전 숫자다.
- **마이그레이션은 `V1__schema.sql` 하나다** (D-104, 2026-10-13). 운영 배포를 앞두고 `V1`~`V6` 을 한 번 합쳤다. **이 파일은 이제 고치지 않는다. 스키마 변경은 `V2__….sql` 부터 새 파일로 추가한다.** 이 문서의 예전 작업 기록에 나오는 `V2`~`V9` 는 합치기 전 파일 번호다.
- 개발 DB(`flowbit`)는 합치기 전 마이그레이션이 적용돼 있어 **지우고 다시 만들어야 백엔드가 뜬다**(Flyway 체크섬 불일치). 사용자가 직접 지우기로 했다. 에이전트는 지우지 않는다.

## 다음 작업

**다음 차례는 M3 의 남은 것이다**: 완료 조건 진행률, 본문 변경의 이전 판 보기, 낙관적 업데이트 (명세의 "아직 없는 것"). 댓글과 `@` 태그는 2026-10-13 에 만들었고 화면 확인이 남았다. 사용자가 순서를 정해 주기 전에는 하나를 임의로 고르지 않는다.

(이전 메모) M3 2단계(프리셋과 문서형 이슈 쓰기)는 구현됐다. 시작하기 전에 명세의 "확인이 필요한 것"(특히 편집기 Tiptap, 프리셋 목차, 상태 이름, 동시 편집 방식)을 사용자에게 확인한다. 그 전에 채울 만한 빈 곳: 구성원이 자기 비밀번호를 바꾸는 기능(임시 비밀번호를 받은 사람이 스스로 바꿀 방법이 없다).

그 밖의 일은 사용자가 지시하기 전에 임의로 시작하지 않는다. 지시를 받으면:

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

### M2b. LDAP·SSO 연동, 이메일 아이디

- [x] 백엔드: `V2`, LDAP 인증, OIDC SSO, 자동 가입, 이메일 아이디, 설정 API, 통합 테스트 22개
- [x] 프론트엔드: `/admin/auth`, 로그인 화면의 SSO 버튼, 구성원 목록 표시
- [ ] 화면 확인, 실제 LDAP(특히 Active Directory)·SSO 서비스로 확인

### M7a. 알림 통로

- [x] 백엔드: `V3`, 메일·웹 푸시·API 알림 통로, 설정·내 알림 API, 통합 테스트 14개
- [x] 프론트엔드: `/admin/notifications`, 내 설정의 알림, 서비스 워커
- [x] 프로젝트 구독, API 알림을 받는 사람마다로만 (D-102)
- [ ] 화면 확인(프로젝트 개요의 구독 버튼 포함), 실제 SMTP·Firebase·메신저 주소로 확인

### M3. 프로젝트와 이슈

- [x] 1단계 백엔드: `V3` 마이그레이션, 구성원 관리 API(`/api/v1/admin/members`), 사람 목록(`/api/v1/users`), 프로젝트와 프로젝트 구성원 API, 접근 판단(`ProjectService.access`), 통합 테스트 21개
- [x] 1단계 프론트엔드: `/settings/members`, 홈의 프로젝트 목록, 사이드바 프로젝트 목록, 새 프로젝트 대화상자, `/project/{key}`, `/project/{key}/settings`
- [ ] 1단계 화면 확인 (에이전트는 로그인할 수 없어 보지 못했다)
- [ ] 2단계: 프리셋, 문서형 이슈 쓰기
- [ ] 3단계: 이슈 목록, 상태 변경, 실시간
- [x] 4단계: 댓글과 `@` 태그 (D-101, 2026-10-13). 변경 이력은 활동 기록(D-061)까지
- [ ] 4단계 화면 확인, 본문 변경의 이전 판 보기

### M3 2·3단계 (이슈, 편집기, Linear 식 화면)

- [x] 백엔드: `V4`(issues), `V5`(content), 이슈 API, 문서 검증(`RichText`), 프로젝트 개요 문서. 테스트 18개 추가
- [x] 프론트엔드: 앱 틀 재구성(사이드바 + 둥근 패널, 경로 표시, 알약 탭, 속성 카드), 프로젝트 개요, 새 이슈 창, 이슈 목록·상세, 문서 편집기(Tiptap, `/` 메뉴), 자동 저장
- [x] 화면 확인: 에이전트가 **테스트 전용 DB(`flowbit_e2e`)에 별도 서버(8081/3100)를 띄워** 직접 확인했다. 초기 설정, 홈, 프로젝트 개요, 편집기의 `/` 메뉴와 제목·체크리스트, 자동 저장 후 새로 고침 유지, 이슈 목록, `C` 로 새 이슈 창, 프리셋 목차, `Ctrl+Enter` 로 생성, 이슈 상세, 내 설정, 관리자 구성원 화면
- [ ] 확인하지 못한 것: 다크 모드, 모바일 폭, 이미지 올리기, 구성원 추가·프로젝트 구성원 관리 대화상자의 실제 조작, 줄에서 상태 바꾸기, 보기 역할로 본 화면

### 2026-10-12 뒤에 고친 것

- [x] 내 설정(`/settings`)과 관리자 설정(`/admin/workspace`, `/admin/members`)을 별도 화면으로 분리. 테마 선택을 사용자 메뉴에서 내 설정으로 옮김 (D-037)
- [x] 페이지 이동 속도: `proxy.ts` 의 백엔드 호출 제거, `/session-expired`, `(app)/loading.tsx`, `(app)/error.tsx`, 중복 조회 제거 (D-038, [04-frontend-guide.md](04-frontend-guide.md) "페이지 이동이 느려지지 않게 하는 규칙")
- [ ] 위 변경의 화면 확인 (에이전트는 로그인할 수 없어 보지 못했다. lint, typecheck, `next build` 통과)
- [x] 프로젝트 속성: 상태, 우선순위, 리드, 기간(시작일 → 종료일), 라벨. `V6` 마이그레이션, `PATCH /api/v1/projects/{key}` 확장, 개요의 속성 카드 (D-042, [features/M3-projects-and-issues.md](features/M3-projects-and-issues.md) "프로젝트 속성")
- [x] `/project/[key]` 의 "uncached data outside of `<Suspense>`" 오류: 프로젝트 레이아웃의 조회를 `<Suspense>` 안으로 옮기고 `project/[key]/loading.tsx` 추가 ([04-frontend-guide.md](04-frontend-guide.md) 규칙 추가)
- [x] 날짜 선택을 달력 팝오버로 교체 (D-043): 이슈 기한은 `DateChip`, 프로젝트 기간은 `DateRangeChip`(첫 클릭 시작일, 두 번째 클릭 종료일). `react-day-picker` 추가
- [x] 실시간 반영과 프레즌스 (D-044): 백엔드 `realtime` 모듈(`RealtimeHub`, `RealtimeWebSocketHandler`), `SessionService.isActive`, 프로젝트·이슈 컨트롤러의 알림, 프런트 `RealtimeProvider`·`RealtimeScope`·`PresenceAvatars`, 편집기·입력 칸의 원격 값 반영, `components/ui/tooltip.tsx`
- [x] 마일스톤 (D-045): `V7`, `milestone` 모듈(API 4개), 이슈의 `milestoneId`, 개요의 마일스톤 목록과 진행률, 이슈 상세·새 이슈 창의 마일스톤 칩, 이슈 목록의 마일스톤 거르기(`?milestone=`)
- [x] 전체 프로젝트(`/`)를 Linear 식 표로 (D-046): 프로젝트 목록 API 에 `issueCount`, `doneCount`, `currentMilestone` 추가
- [x] 모든 프로젝트를 목록에 표시, 들어갈 수 없는 프로젝트는 `/no-access/{key}` 에서 관리자 안내 (D-047). `GET /api/v1/projects/{key}/access`, 프런트 `loadProject`(lib/current-user.ts). `ProjectsIntegrationTest` 의 해당 테스트를 고쳤다(통과 확인)
- [x] 프로젝트 업데이트와 건강도 (D-048): `V8`, `ProjectUpdateService`/`ProjectUpdateController`, 개요의 "프로젝트 업데이트", 속성 카드와 목록의 건강도, `--success` 색
- [x] 마일스톤 필터 고침: 개요의 `이슈 n/m` 링크로 오면 그 마일스톤만 보이게(필터를 주소에 둠), 거른 상태의 새 이슈에 마일스톤이 미리 골라지게(버튼·`C` 로 열 때도), "필터 초기화" 버튼
- [x] 로그인 시 아이디·비밀번호가 주소(GET)에 들어가던 문제: 로그인·초기 설정 폼에 `method="post"`, `proxy.ts` 가 주소의 `password` 를 지움, 개발 서버를 사내망 주소로 열 수 있게 `FLOWBIT_DEV_ORIGINS`(`allowedDevOrigins`)
- [x] 프로젝트 아이콘 (D-049): `V9`(`projects.icon_key`), `PUT/DELETE /api/v1/projects/{key}/icon`, 설정 화면의 올리기, `components/project-icon.tsx` 를 사이드바·목록·경로·개요에 사용
- [ ] 프로젝트 아이콘: 통합 테스트(관리 역할만, 이전 파일 삭제)
- [ ] 프로젝트 업데이트: `ProjectUpdatesIntegrationTest`(권한, 최근 건강도가 목록에 반영) 작성
- [ ] 마일스톤: `MilestonesIntegrationTest`(권한, 다른 프로젝트 마일스톤 거부, 진행률 계산, 삭제 시 이슈 유지)와 화면 확인
- [ ] 실시간: 두 계정으로 남은 것 확인(이슈 생성·상태 변경이 상대 화면에 그려지는지, 이슈 상세의 제목·본문 원격 반영, 커서·블록 표시, 끊겼다 다시 붙기. 프로젝트 목록 반영과 아바타는 2026-10-13 에 확인함), `RealtimeIntegrationTest`(WebSocket 연결·권한·Origin·알림) 작성. 백엔드 컴파일과 기존 테스트는 통과한다
- [ ] 프로젝트 속성의 백엔드 통합 테스트와 화면 확인 (달력 팝오버 포함) (사용자가 "작업 뒤 테스트하지 말 것"을 지정해 하지 않았다)

## 화면을 직접 확인하는 방법 (사용자 계정 없이)

개발 DB 의 계정은 사용자의 것이라 에이전트는 로그인할 수 없다. 대신 테스트 전용 DB 로 서버를 따로 띄운다.

1. `flowbit_e2e` DB 를 비운다 (`docker compose exec -T postgres psql -U flowbit -d postgres` 에서 drop/create).
2. 백엔드: `./gradlew bootJar` 뒤 환경 변수 `FLOWBIT_DB_NAME=flowbit_e2e`, `SERVER_PORT=8081`, `FLOWBIT_STORAGE_PATH=build/e2e-uploads` 로 jar 실행.
3. 프론트엔드: `BACKEND_URL=http://localhost:8081` 로 `next build` 후, 같은 환경 변수로 `next start -p 3100` (`proxy.ts` 가 실행 시점의 `BACKEND_URL` 로 초기 설정 여부를 묻는다). `.claude/launch.json` 의 `frontend-e2e` 가 이 실행 설정이다. (개발 서버는 같은 폴더에서 두 개를 띄울 수 없다.)
4. `http://localhost:3100` 에서 초기 설정부터 한다. 데이터는 API 로 넣는 편이 빠르다.
5. 끝나면 두 서버를 끄고, **`BACKEND_URL` 없이 `next build` 를 다시 한다** (rewrite 주소가 빌드에 들어가기 때문).

브라우저 창이 가려져 있으면 화면 캡처가 자주 실패한다. 그때는 페이지 글자를 읽어 확인한다.

## 알아둘 것 (코드를 고치기 전에)

- **블로킹 코드 금지.** 백엔드는 WebFlux 다. JDBC/JPA/`.block()` 을 쓰지 않는다. 규칙은 [03-backend-guide.md](03-backend-guide.md) 맨 위.
- R2DBC 엔티티는 변경 감지가 없다. 값을 바꾸면 `save` 를 직접 부르고, 새 행은 `R2dbcEntityTemplate.insert` 로 넣는다.
- `@AuthenticationPrincipal` 로 받는 `AuthenticatedUser` 에 `java.security.Principal` 을 구현하면 타입 오류가 난다 (실제로 겪었다).
- shadcn 은 Base UI 기반이다. `asChild` 가 아니라 `render` prop 을 쓴다.
- Next.js 16: `middleware.ts` 가 아니라 `src/proxy.ts`. `cacheComponents` 가 켜져 있어 요청별 데이터를 읽는 서버 컴포넌트는 `<Suspense>` 안에 둔다.
- 개발 DB 를 지우고 다시 시작해도 Next 서버는 그대로 둬도 된다. `proxy.ts` 는 "초기 설정 완료"를 기억하지만 **로그인하지 않은 요청에서는 매번 백엔드에 다시 묻는다**. 예전 세션 쿠키가 남아 있으면 한 번 `/session-expired` 를 거쳐 `/login` → `/setup` 으로 간다.
- Next 개발 서버를 켠 채로 `providers.tsx` 같은 최상위 클라이언트 모듈을 새로 만들면 "No QueryClient set" 오류가 날 수 있다. 개발 서버를 다시 시작하면 사라진다.
- 프론트엔드에 `npm install` 로 패키지를 추가할 때 peer 충돌이 나면 `@types/node` 버전부터 확인한다 (Vitest 가 22 이상을 요구해서 22 로 맞췄다).
- 이 PC(macOS)의 JDK 25 는 `~/Library/Java/JavaVirtualMachines/corretto-25.0.4.1/Contents/Home` 에 있다. Gradle 을 돌릴 때 `JAVA_HOME` 을 이 경로로 지정한다.
- **사용자가 IDE 로 백엔드를 띄워 둔 채 `./gradlew` 를 돌리면 그 서버가 깨진다.** IDE 는 `backend/build/classes` 의 클래스로 서버를 띄우는데, 빌드가 그 파일들을 바꿔 놓으면 서버가 나중에 읽는 클래스가 새 버전이 되어 요청이 멈추거나 500 이 난다(2026-10-12 에 초기 설정의 로고 올리기에서 겪었다). 에이전트의 검사는 `FLOWBIT_BUILD_DIR=build-check ./gradlew check` 처럼 다른 폴더에 빌드한다(`backend/build.gradle.kts` 끝).
- 같은 저장소를 다른 세션이나 에디터가 동시에 고치고 있을 수 있다. 문서를 통째로 다시 쓰기 전에 현재 내용을 다시 읽는다.

## 열린 질문

[09-decisions.md](09-decisions.md) 의 "미결정" 표 참고. 작업 중 새로 생긴 질문은 아래에 적는다.

- LDAP·SSO: (아이디가 같은 계정은 전환하기로 정해졌다, D-097) 이메일만 같은 계정도 이을지, LDAP 그룹으로 역할을 정할지, 디렉터리에서 사라진 사람을 자동으로 비활성화할지, SAML 이 필요한지. ([features/M2b-ldap-and-sso.md](features/M2b-ldap-and-sso.md) "확인이 필요한 것")
- hrm `/api/works/send` 에 쓸 만료되지 않는 토큰을 받을 수 있는지(문서에 없다). 없으면 API 알림의 고정 헤더로는 못 쓴다.
- 알림: 첨부 파일·문서 변경도 알릴지(지금은 안 간다), 프로젝트에서 빠진 사람의 이슈 구독을 정리할지, 이메일 주소 확인 절차와 수신함이 필요한지, 프로젝트 구독을 자동으로 걸지(만든 사람·리드·새 구성원)와 구독자 목록을 보일지, 사람마다 받을 변경 종류를 고르게 할지. ([features/M7a-notification-channels.md](features/M7a-notification-channels.md) "확인이 필요한 것")
- DB 에 평문으로 둔 비밀 값(LDAP 검색 계정 비밀번호, SSO 시크릿, SMTP 비밀번호, Firebase 서비스 계정 키, 웹훅 서명 값·헤더)을 암호화할지. 하려면 암호화 키를 환경 변수로 받아야 한다.

## 작업 기록

### 2026-10-12 — Dockerfile

- 사용자 지시: Dockerfile 을 만들 것.
- 한 일: `backend/Dockerfile`, `frontend/Dockerfile`, 각 `.dockerignore`, `next.config.ts` 의 `output: "standalone"`, `docker-compose.yml` 의 `app` 프로필(backend, frontend, `uploads` 볼륨), 문서(02 "Docker 이미지", D-100, README, `.env.example`).
- 확인한 범위: `docker compose config` 검사만. **이미지는 빌드해 보지 않았다**(사용자 지시로 작업 뒤 테스트를 하지 않았고, 이 환경은 Maven Central 이 429 를 낸다). 처음 빌드할 때 확인이 필요하다.

### 2026-10-12 — 프론트엔드 최적화 (모듈화, 공통화, 속도)

화면의 모양과 동작은 바꾸지 않았다. `npm run lint`, `npm run typecheck`, `npx next build` 통과. **브라우저로는 확인하지 않았다** (아래 "확인할 것").

- **속도 (D-099)**
  - `serverGet` 이 같은 주소를 한 요청에서 한 번만 부른다. 프로젝트 화면(레이아웃의 `generateMetadata` + `loadProject`), 이슈 주소 직접 열기(제목용 조회 + 상세, 목록과 상세의 라벨·사용자), 문서 화면(제목 + 본문)에서 겹치던 백엔드 호출이 하나로 줄었다.
  - 늦게 불러오는 것: 새 이슈 창(Tiptap 포함), 달력(react-day-picker), 프로젝트 만들기 폼(react-hook-form·zod. 사이드바에 있어 모든 화면에 실려 있었다), 이슈 목록의 목표·캘린더 보기.
  - 이슈 목록: 걸러 내기·묶기를 `useMemo` 로, 줄을 `memo` 한 `IssueListRow` 로, 검색어를 `useDeferredValue` 로. 프로젝트 권한 확인은 줄마다 `find` 하던 것을 `Set` 으로.
- **공통화**: `fileForm`(4곳), `personName`·`PersonAvatar`·`PersonMark`(`components/person.tsx`, 20여 곳), `usersAsMembers`(4곳), 이슈 줄의 조각(`issue-row.tsx`: 목록·읽기 전용 줄·하위 이슈), `replaceListQuery`, 사이드바·좁은 화면 메뉴의 목적지(`nav-destinations.ts`).
- **모듈화**: `create-project-dialog.tsx`(버튼과 틀) / `create-project-form.tsx`(폼)으로 나눔. `PersonAvatar` 는 `features/issues/options.tsx` 에서 `components/person.tsx` 로 옮김.
- **확인할 것 (화면)**: ① 새 이슈 창을 처음 열 때(버튼, `C`, 캘린더의 날짜) 프로젝트·목표·담당자·날짜 기본값이 들어가고 제목에 커서가 가는지 ② 프로젝트 만들기 창을 처음 열 때 이름 칸에 커서가 가는지 ③ 날짜 칩을 처음 열 때 달력이 뜨기 전 빈 상자가 어색하지 않은지 ④ 목표·캘린더 보기로 처음 바꿀 때.
- **하지 않은 것 (다음에 볼 만한 것)**: 관리자 설정 폼 네 개(LDAP, SSO, SMTP·푸시, 웹훅)가 "폼 상태 + 저장·테스트 mutation + 필드 오류" 구조를 각자 되풀이한다. 훅 하나로 묶을 수 있으나 화면 확인 없이 고치기엔 위험해 두었다. `text-[0.8125rem] leading-5` 가 90여 곳에 있다(Tailwind 테마 토큰 후보). 이슈 수가 수천이 되면 목록 가상화가 필요하다.

최신 항목을 위에 추가한다. 한 번의 작업 세션마다 한 항목.

### 2026-10-13 — 서버 배포를 Docker 이미지로 (아래 "배포 스크립트" 항목을 대체)

- 사용자 지시: 프로세스로 띄우는 스크립트는 손이 너무 많이 가니 Docker 로 빌드해서 올리게 할 것. 레지스트리는 Docker Hub `isugar150/flowbit` (D-106, [02-architecture.md](02-architecture.md) "서버 배포").
- 한 일: `script/push.sh`(개발 PC: 빌드 + push), `script/docker-compose.yml`(서버용, 이미지만 받음), `script/deploy.sh`(서버: pull → up → 헬스체크, `.env` 자동 생성). **지운 것**: `script/app.env`, `common.sh`, `startup.sh`, `shutdown.sh`(git 기록에는 남아 있다), `.gitignore` 의 그 스크립트용 줄. `script/nginx/flowbit.conf` 는 선택 사항으로 남기고 설명을 고쳤다. 개발용 `docker-compose.yml` 에 `FLOWBIT_WEB_BIND` 추가.
- 태그 규칙: `isugar150/flowbit:backend-<버전>`, `frontend-<버전>`, `backend-latest`, `frontend-latest`. 버전은 커밋 짧은 해시.
- 확인한 범위: `bash -n`, 그리고 가짜 `docker`·`curl` 로 `deploy.sh` 의 흐름(첫 실행의 `.env` 생성, 버전 지정, 포트 읽기, pull 실패 시 교체 안 함). **실제 이미지는 한 번도 빌드해 보지 못했다**(이 PC 의 Docker Desktop 이 떠 있지 않았다. Dockerfile 을 만든 2026-10-12 작업에서도 빌드하지 않았다). `push.sh` 도 실행해 보지 않았다. `script/docker-compose.yml` 은 `docker compose config` 로 검사하지 못했다. `nginx -t` 도 못 했다.
- **다음에 먼저 할 것**: 개발 PC 에서 Docker 를 켜고 `./script/push.sh` 를 돌려 두 이미지가 빌드·push 되는지 본다. 막히기 쉬운 곳: 백엔드 이미지의 Gradle 의존성 받기, 프런트의 `next build`, `docker login`.
- 같은 날 바꿈(사용자 지정, D-107): **서버 배포에 DB 를 넣지 않는다.** `script/docker-compose.yml` 에서 PostgreSQL 을 뺐고, `.env` 에 `FLOWBIT_DB_HOST/PORT/NAME/USER/PASSWORD` 를 적는다. `deploy.sh` 는 처음에 양식만 만들고 멈추며, 값이 비었거나 호스트가 `localhost` 면 멈춘다(같은 서버의 DB 는 `host.docker.internal`). 가짜 `docker` 로 네 경우(첫 실행, 미입력, localhost, 입력됨)를 확인했다. 실제 외부 DB 로는 띄워 보지 않았다. 위 항목의 "무작위 DB 비밀번호" 는 없어졌다.
- 커밋하지 않았다(스테이징만: `script/` 의 추가·삭제와 실행 권한).

### 2026-10-13 — 배포 스크립트 (`script/`)

- 사용자 지시: 쓰던 "무중단 + git" 스크립트에서 무중단만 빼고 이 프로젝트의 `script/` 폴더에 넣을 것. 설정한 경로에서 git pull → 빌드 → 바로 배포. 앱의 env 파일을 스크립트 경로에 넣으면 알아서 읽게 (D-105, [02-architecture.md](02-architecture.md) "스크립트 배포").
- 한 일: `script/deploy.sh`, `startup.sh`, `shutdown.sh`, `common.sh`, `app.env`. 루트 `.gitattributes`(`/script/*` 는 LF), `.gitignore`(`script/app/`, `logs/`, `data/`, `*.pid`, `app.local.env`), README, `.env.example` 안내. 스크립트는 실행 권한(100755)으로 git 에 올려 두었다(스테이징만, 커밋은 하지 않았다).
- 원본과 다른 점: nginx 전환·두 포트·릴리스 폴더가 없다. 프런트는 정적 파일 복사가 아니라 Next.js standalone 서버(`node server.js`)를 띄운다. `git pull` 은 원본 그대로다.
- 확인한 범위: `bash -n`, 그리고 **리눅스 컨테이너에서 가짜 `java`·`node`·`git`·`npm`·`gradlew` 로** 다음을 돌려 봤다: `.env` 읽기(공백·`$`·따옴표·CRLF), 시작·이미 떠 있음·부분 종료·전체 종료, 배포(둘 다, 프런트만), 중복 실행 잠금, 빌드 실패 시 서버 유지. **실제 서버에서 진짜 빌드(Gradle, `next build`)와 헬스체크로는 돌려 보지 않았다.**
- 첫 배포에서 먼저 볼 것: ① 서버의 JDK 가 25 인지(없으면 Gradle 이 내려받으려 한다) ② `next build` 뒤 `.next/standalone/server.js` 가 맨 위에 생기는지(저장소 루트에 lockfile 이 있으면 `frontend/` 아래로 들어가고, 스크립트가 그 경우 멈추고 알려 준다) ③ 백엔드 헬스체크가 90회 안에 통과하는지(첫 기동은 Flyway 때문에 더 걸린다) ④ `FLOWBIT_STORAGE_PATH` 를 절대 경로로 줬는지.
- 같은 날 추가(사용자 요청): `app.env` 의 `JDK_HOME` 으로 JDK 경로를 지정한다(`common.sh` 가 `JAVA_HOME` 과 `PATH` 에 넣는다). Git Bash 에서 가짜 JDK 폴더로 확인(지정, 틀린 경로, 비움). 실제 Gradle 빌드로는 확인하지 않았다.
- 같은 날 추가(사용자 요청): nginx 설정 견본 `script/nginx/flowbit.conf`(80 포트만, 특정 도메인을 프런트로 프록시). `server_name` 은 자리표시자 `flowbit.example.com` 이다. **`nginx -t` 로 검사하지 못했다**(이 PC 에 nginx 가 없고 Docker 가 꺼져 있었다). `.gitattributes` 를 `/script/**` 로 넓혔다.
- 없는 것: 자동 롤백(바로 앞 빌드가 `.prev` 로 남을 뿐), 재부팅 뒤 자동 시작, DB 백업, HTTPS.

### 2026-10-13 — Flyway 마이그레이션을 하나로 (운영 배포 준비)

- 사용자 지시: 운영 배포를 위해 Flyway DDL 을 전부 합칠 것. 일회성이다. 개발 DB 는 사용자가 지운다 (D-104).
- 한 일: `V2`~`V6` 의 내용을 `V1__schema.sql` 에 넣고 다섯 파일을 지웠다. `ALTER` 는 `CREATE TABLE` 안으로 옮겼다(`users` 의 `auth_source`·`external_id`·`notify_email`·아이디 규칙·`password_hash` null 허용, `sso_settings` 의 직접 설정 컬럼, `webhooks.method` 의 `GET`·`DELETE`). 옛 데이터를 고치던 SQL(`per_recipient` 관련)은 뺐다. 코드 주석의 옛 파일 이름 세 곳을 테이블 이름으로 바꿨다.
- 확인한 범위: `./gradlew check` 통과(170개, 실패 0). 테스트는 빈 PostgreSQL 18 컨테이너에 합친 `V1` 을 적용해 돈다. 합치기 전후의 스키마를 덤프로 비교하지는 않았다.
- **다음 사람이 알아둘 것**: ① `V1__schema.sql` 은 다시 고치지 않는다. 변경은 `V2__….sql` 부터. ② 합치기 전 파일이 적용된 DB(사용자의 개발 DB, 그리고 `flowbit_e2e`)는 지우고 다시 만들어야 한다.

### 2026-10-13 — 알림을 이슈별·프로젝트별 구독자에게만

- 사용자 지시: 알림을 모든 사람에게 보내지 말고 이슈별·프로젝트별로 따로 보낼 것. 확인한 답: "프로젝트별"은 **사용자가 프로젝트를 구독**하는 것, 구독과 무관하게 전체 변경을 한 주소로 보내는 API 알림은 **없앤다** (D-102, [features/M7a-notification-channels.md](features/M7a-notification-channels.md) "범위").
- 한 일:
  - 백엔드: `V6__project_subscriptions.sql`(`project_subscriptions` 추가, `webhooks.per_recipient` 삭제). 받는 사람 계산을 `IssueActivityStore.recipientIds`(이슈 구독자 + 프로젝트 구독자 중 지금 들어갈 수 있는 사람)로. `PUT/DELETE /api/v1/projects/{id}/subscription`, 프로젝트 응답의 `subscribed`. `WebhookNotificationChannel` 은 항상 받는 사람마다 부른다. 표준 JSON 에서 `recipientIds` 를 빼고 `recipient` 를 항상 넣는다. `IssueAnnouncer` 는 받는 사람이 없으면 알림을 넘기지 않는다.
  - 프런트: 프로젝트 개요 속성 카드의 "구독" 버튼(`project-subscribe-button.tsx`), API 알림 폼에서 "받는 사람마다" 체크와 Slack·Discord·Teams 예시 삭제, 안내 문구.
- (같은 날 뒤에 `V6` 은 `V1` 로 합쳐졌다, D-104. 아래 항목 참고.) API 알림에 채널 웹훅 주소를 넣으면 같은 글이 받는 사람 수만큼 올라가므로, 한 사람에게 보내는 형태(`{{recipient.email}}` 등)로 쓴다.
- 확인한 범위: `./gradlew check` 통과(170개, 실패 0. 프로젝트 구독 테스트 1개 추가, 웹훅 테스트 4개를 받는 사람이 있는 상황으로 고침), 프런트 typecheck·eslint. `next build` 는 하지 않았다. **화면은 보지 않았다**(다른 세션이 같은 저장소와 개발 서버를 쓰고 있었고, 에이전트는 개발 DB 계정으로 로그인할 수 없다).
- 알아둘 것: 같은 시간에 다른 세션이 `build-check` 폴더로 빌드하고 있어 첫 실행이 `NoClassDefFoundError` 등으로 66개 실패했다(코드 문제가 아니다). 세션마다 `FLOWBIT_BUILD_DIR` 을 다른 폴더로 준다.
- 화면에서 먼저 볼 것: ① 속성 카드 제목 옆 구독 버튼의 자리와 좁은 화면 ② 누른 뒤 "구독 취소"로 바뀌는지 ③ 관리자 알림 화면의 API 알림 폼.
- 정하지 않은 것: 프로젝트 구독을 자동으로 걸지, 구독자 목록 표시, 사람마다 받을 변경 종류 고르기, 채널로 전체 흐름 보내기(필요하면 "프로젝트를 지정한 API 알림"으로).

### 2026-10-13 — 이슈 댓글과 `@` 태그

- 사용자 지시: ① 이슈에 대한 코멘트 기능. ② 태그 기능도 필요하고, 태그하면 그 사용자에게 등록된 알림 수단 전부로 보낼 것. ③ 에디터에도 멘션을 넣을 것. (D-101, [features/M3-projects-and-issues.md](features/M3-projects-and-issues.md) "지금 구현된 범위"의 댓글·`@` 태그)
- 한 일:
  - 백엔드: `V5__issue_comments.sql`, `IssueCommentStore`(`issue/application/IssueComments.kt`), `IssueService.addComment/updateComment/removeComment/tag`, `POST /api/v1/issues/{번호}/comments`, `PATCH/DELETE /api/v1/comments/{id}`, 활동 응답의 `comments`. `RichText` 가 `mention` 블록을 받고 태그된 사람 id 를 돌려준다(`RichTextDocument.mentions`, `mentionsIn`). 알림 종류 `COMMENT_ADDED`·`MENTIONED`(활동 기록에는 쌓지 않는다), `IssueAnnouncer.commented`.
  - 프런트: `components/editor/mention.tsx`, `RichTextEditor` 의 `mentionables`(`@` 메뉴), `features/issues/components/issue-comments.tsx`(입력 칸, 댓글 한 건), `issue-activity.tsx` 가 활동과 댓글을 시간순으로 섞음. 이슈 본문과 새 이슈 창에도 `@`. API 알림의 "보낼 변경"에 댓글·본문 태그.
- 태그 알림의 방식: 태그된 사람을 그 이슈의 **구독자로 만든다.** 따로 보내는 경로는 없고, 구독자에게 가는 알림이 그 사람의 메일·푸시·"받는 사람마다" API 알림으로 간다. 그래서 태그된 뒤의 다른 변경도 계속 받는다(구독 취소로 끈다).
- 확인한 범위: `FLOWBIT_BUILD_DIR=build-check ./gradlew check`(169개 통과: 댓글 권한·고치기·지우기, 태그하면 구독, 삭제된 이슈, 태그된 사람에게 메일 4개 추가), 프런트 typecheck·eslint. `next build` 는 하지 않았다(개발 서버가 떠 있었다). **화면은 보지 않았다**(같은 시간에 다른 세션이 개발 서버로 실시간을 시험하고 있었고, 에이전트는 개발 DB 계정으로 로그인할 수 없다).
- **백엔드를 다시 시작하면 `V5` 가 적용된다.** 이미 적용됐을 수 있으니 `V5` 파일은 고치지 않는다(바꿀 것은 `V6` 으로).
- 화면에서 먼저 볼 것: ① `@` 메뉴의 위치와 한글 이름 찾기(조합 중 입력), 대화상자 안에서의 위치 ② 댓글 입력 칸의 `Ctrl/⌘+Enter`(편집기의 줄바꿈보다 먼저 잡히는지) ③ 댓글이 여러 개일 때 읽기 전용 편집기가 댓글마다 하나씩 뜨는 것의 속도 ④ 댓글 고치기·취소·두 번 눌러 삭제 ⑤ 상대 화면에 댓글이 실시간으로 뜨는지 ⑥ 다크 모드의 태그 색.
- 같은 날 고친 버그(사용자 지적): 이슈 옆 패널에서 라벨을 체크하면 라벨 목록이 창 왼쪽 위로 튀었다가 돌아왔다. 저장하는 동안 "+" 버튼이 `disabled` 가 되고 `disabled:hidden` 으로 숨겨져 목록의 기준점이 사라진 것이다. 목록이 열려 있는 동안에는 숨기지 않게 했다(`label-chip.tsx`). eslint 만 확인, 화면은 보지 않았다.
- 같은 날 고친 버그(사용자 지적): 화면 아래쪽(댓글 입력 칸)에서 `@` 메뉴가 커서에서 한참 위에 떴다. 위로 뒤집을 때 메뉴의 최대 높이(320px)만큼 올려 윗변을 놓았기 때문에, 두 줄짜리 목록은 커서와 250px 떨어졌다. 뒤집으면 아랫변을 줄 바로 위에 붙이게 했다(`readSlash` 의 `above`, `translateY(-100%)`). `/` 메뉴도 항목이 적게 걸러졌을 때 같은 문제가 있었다. typecheck·eslint 만 확인.
- 같은 날 바꾼 것(사용자 지시): 태그가 글자(`@이름`)로만 보이던 것을 **칩 모양**으로, 마우스를 올리면 **프로필 사진·이름·아이디 카드**가 뜨게. `mention.ts` → `mention.tsx`(React 노드 뷰 + `Tooltip` + `Person`), `MentionPerson.avatarUrl`. 처음에 `globals.css` 에 둔 `.mention` 스타일은 떠 있는 개발 서버에 실리지 않아 글자로만 보였던 것으로 보인다(전에도 같은 일이 있었다). 지웠다. typecheck·eslint 만 확인.
- 정하지 않은 것: 프로젝트 개요·문서 안의 태그(알릴 종류가 없어 `@` 메뉴를 켜지 않았다), 태그만 받고 구독은 안 하는 선택, 댓글 스레드·이모지.

### 2026-10-13 — 서버가 바로 종료되게

- 사용자 지시: 종료 신호를 보내면 "Commencing graceful shutdown. Waiting for active requests to complete" 에서 멈춘다. 바로 종료되게 할 것.
- 원인: 열린 실시간 WebSocket 연결이 끝나지 않는 요청이라 graceful shutdown 이 제한 시간(30초)까지 기다렸다.
- 한 일: `application.yml` 에 `server.shutdown: immediate` (D-103, [02-architecture.md](02-architecture.md) "실시간 동기화").
- 확인한 범위: **실행해 보지 않았다.** 백엔드는 사용자가 IDE 로 띄우고 있어 에이전트가 다시 시작하지 않았다. 다시 시작한 뒤 종료해 보면 된다.

### 2026-10-13 — 실시간을 두 계정으로 확인 (코드 변경 없음)

- 사용자 지시: 관리자 계정으로 들어가 새 사용자를 만들고 브라우저로 실시간을 시험할 것.
- 방법: 개발 서버(3000/8080)에서 탭 두 개. 관리자는 `http://localhost:3000`, 두 번째 계정은 `http://b.localhost:3000`(호스트가 달라 쿠키가 따로 간다. `127.0.0.1` 은 `allowedDevOrigins` 에 없어 스크립트가 막힌다. `*.localhost` 는 Next 가 기본으로 허용한다). 개발 DB 에 테스트 계정 `rttester`("실시간 테스터", 구성원, 멘토리 프로젝트의 편집 역할)를 만들었다. 비밀번호는 관리자 설정의 "비밀번호 재설정"으로 다시 정하면 된다.
- 확인한 것:
  - 프로젝트 구성원 추가 → 상대의 사이드바와 전체 프로젝트 표에 새로 고침 없이 반영(누른 뒤 약 0.7초). `projects` 주제.
  - 프로젝트 화면의 "지금 보고 있는 사람" 아바타가 양쪽에 서로 뜬다.
  - 이슈 수정(`PATCH /api/v1/issues/1`) → 상대의 WebSocket 에 `issues`, `project:{id}`, `issue:1` 세 알림이 0.2초 안에 도착. 구독을 연달아 바꿔 보내도 서버의 프레즌스가 마지막 상태로 맞는다.
- 확인하지 못한 것: 이슈 생성·상태 변경이 상대 **화면**에 그려지는 것(알림 도착까지만 봤다), 이슈 상세의 제목·본문 원격 반영, 커서·블록 표시, 끊김 뒤 재연결.
- 못 한 이유(다음 사람이 알아둘 것):
  - **앱의 Browser 패널이 숨겨져 있으면 페이지의 `requestAnimationFrame` 이 돌지 않아, 전체 로드한 화면의 스트리밍된 `<Suspense>` 영역이 끝까지 그려지지 않는다.** 그 안의 `RealtimeScope` 가 등록되지 않아 프레즌스가 "안 되는 것처럼" 보인다(앱 버그가 아니다. 앱 안의 링크를 눌러 이동하면 된다). 화면 시험은 패널을 띄워 놓고 한다.
  - 시험 도중 다른 세션이 편집기(멘션)·이슈 댓글을 고치고 있었고 백엔드가 다시 시작됐다. 새 이슈 창이 한 번 `withMentions is not defined` 로 깨졌다(수정 도중의 일시 상태로 보인다).
- 시험 중 콘솔에서 본 것(고치지 않았다): `/admin/members`, `/project/[id]/settings` 에서 "uncached data outside of `<Suspense>`"(`AppLayout` 의 `fetchMe`, 설정 페이지의 조회), `/project/[id]` 의 `generateMetadata()` uncached data 경고.

### 2026-10-12 — LDAP 사용 시 구성원 관리 제한, 계정 전환, 구성원별 프로젝트 권한

- 사용자 지시: ① LDAP 을 쓰면 구성원 메뉴의 구성원 추가와, 관리자를 뺀 비밀번호 재설정을 제한. ② 기존 계정과 아이디가 겹치는 사람이 LDAP·SSO 로 로그인하면 그 계정으로 전환하고 비밀번호도 LDAP 에서 검증. ③ 구성원 메뉴에서 그 사용자의 권한을 바꿀 수 있게.
- 한 일 (D-097, D-098, [features/M2b-ldap-and-sso.md](features/M2b-ldap-and-sso.md) "계정 전환"·"LDAP 을 쓰는 동안의 구성원 관리"):
  - `AuthService.login`: LDAP 이 켜져 있으면 디렉터리에 먼저 묻고, 받아들이지 않으면 저장된 비밀번호로 확인. `ExternalAccountService.convert`(`User.convertTo`, 감사 로그 `MEMBER_CONVERTED`). 비밀번호 재설정은 계정을 다시 `LOCAL` 로 만든다(`UserRepository.updatePassword`).
  - `MemberAdminService`: LDAP 켜짐이면 추가 409 `MEMBERS_FROM_DIRECTORY`, 관리자가 아닌 계정의 재설정 409 `PASSWORD_IN_DIRECTORY`.
  - `GET /api/v1/admin/members/{id}/projects`(`ProjectService.rolesOf`), 프런트 `member-projects-dialog.tsx`("프로젝트 권한" 버튼), 구성원 화면의 LDAP 안내문.
- ③ 은 "프로젝트 권한"으로 풀었다. 워크스페이스 역할(관리자/구성원) 변경은 전부터 같은 줄의 선택 상자에 있다. 사용자가 다른 것을 뜻했다면 고친다.
- 확인한 범위: `FLOWBIT_BUILD_DIR=build-check ./gradlew check`(165개 통과), typecheck, eslint, `next build`. 화면은 보지 않았다.
- 주의: 전환된 관리자는 LDAP 이 죽으면 들어올 수 없다. LDAP 에 없는 아이디의 로컬 관리자를 하나 남겨 둬야 한다.

### 2026-10-12 — hrm 의 LDAP·SSO·메시지 API 에 맞추기, 설정 화면 다시 짜기

- 사용자 지시: 사내 hrm 의 LDAP(`ldap-guide`), SSO(`sso-guide`), `/api/works/send` 를 셋 다 쓸 수 있어야 한다. 표준도 자체 방식도 되게 호환성을 최대한. 설정 화면은 서로 다른 설정이 길게 늘어져 보기 힘드니 고칠 것.
- 한 일 (D-094~D-096):
  - LDAP: 검색 계정 바인드 → 검색 → 사용자 바인드를 **한 연결에서**(hrm LDAP 은 새 연결의 사용자 바인드를 거부할 수 있다). 응답 시간 제한 5초.
  - SSO: 방식 `OIDC` / `OAUTH2`("직접 설정"). `V4__sso_protocols_and_webhook_targets.sql`, `OAuth2Client`, `SsoService` 분기, 버튼 모양(NAVER WORKS, `public/naver-works-app-icon.svg`), `sso_denied`.
  - API 알림: `GET`·`DELETE`, 주소 자리표시자(URL 인코딩), `per_recipient`(구독자마다 호출, `{{recipient.email}}` 등).
  - 화면: `components/settings-card.tsx`(접는 카드), `setting-fields.tsx` 의 `FieldSection`·`SegmentedSetting`·`SelectSetting`·`AdvancedSettings`. `/admin/auth` 와 `/admin/notifications` 를 카드 목록으로. hrm 값을 채우는 "예시 넣기" 버튼(SSO, API 알림).
- 확인한 범위: `FLOWBIT_BUILD_DIR=build-check ./gradlew check`(161개 통과: 직접 설정 SSO 2개, 받는 사람별 GET 호출 1개 추가), typecheck, eslint, `next build`. **화면은 보지 않았다. 실제 hrm 으로도 확인하지 않았다**(가이드 문서를 읽고 맞췄다).
- **백엔드를 다시 시작하면 `V4` 가 적용된다.**
- hrm 에 연결하려면: [features/M2b-ldap-and-sso.md](features/M2b-ldap-and-sso.md) "설정 항목"·"hrm", [features/M7a-notification-channels.md](features/M7a-notification-channels.md) "hrm 의 NAVER WORKS 메시지". hrm 쪽 준비물: LDAP 조회 계정과 CIDR 허용·각자의 앱 비밀번호, SSO 용 `api_name`·API 키(`sso_enabled`), 메시지 API 용 봇 ID 와 **만료되지 않는 토큰**(받을 수 있는지 미확인).
- **사고 기록**: 앞 작업에서 결정 번호를 옮기던 스크립트가 `M2b-ldap-and-sso.md` 를 빈 파일로 만들었다(읽기 전에 쓰기 모드로 열었다). 이번에 발견해 전체를 다시 썼다. 파일을 고치는 스크립트는 먼저 읽고 나서 쓴다.

### 2026-10-12 — LDAP·SSO 연동과 이메일 아이디, 알림 통로(메일·웹 푸시·API)

- 사용자 지시 ①: 관리자 설정에 LDAP 연동과 SSO 연동을 넣고, 가입이 안 된 사람은 바로 가입까지 되게. 로그인 아이디는 이메일도 일반 아이디도 가능하게. ②(작업 중 추가): 알림 기능(Firebase 웹 푸시, SMTP, API)을 넣고 API 는 범용으로 설정할 수 있게, 스킬을 참고해서.
- 한 일 ① (D-087~D-090, [features/M2b-ldap-and-sso.md](features/M2b-ldap-and-sso.md)):
  - 백엔드: `V2__external_auth.sql`(`users.auth_source`·`external_id`, `password_hash` null 허용, `login_id` 검사 완화, `ldap_settings`, `sso_settings`). `auth/` 에 `LdapDirectory`(UnboundID, `Dispatchers.IO`), `OidcClient`·`SsoService`·`SsoController`(인가 코드 + PKCE + state + nonce, ID 토큰 검증), `ExternalAccountService`(계정 찾기·자동 가입), `ExternalAuthSettingsService`, `AdminAuthController`. `AuthService.login` 을 "로컬 계정 → 없으면 LDAP" 순서로 다시 썼고 LDAP 호출은 트랜잭션 밖에서 한다. `CredentialPolicy` 가 이메일 아이디를 받는다. LDAP·SSO 계정은 비밀번호 재설정 불가.
  - 프런트: `/admin/auth`("로그인 연동" 탭, `features/external-auth/`), 로그인 화면의 "아이디 또는 이메일"·SSO 버튼·`?error=sso_…` 문구, 구성원 목록의 LDAP·SSO 표시, 아이디 입력 규칙.
- 한 일 ② (D-091~D-093, [features/M7a-notification-channels.md](features/M7a-notification-channels.md)):
  - 백엔드: `V3__notifications.sql`(`users.notify_email`, `notification_settings`, `push_tokens`, `webhooks`). `notification/` 에 통로 세 개(`MailChannel.kt`, `PushChannel.kt`, `WebhookChannel.kt`), `NotificationText`, 설정 서비스와 API. `NotificationDispatcher` 는 이제 백그라운드에서 보낸다(요청을 늦추지 않음). `IssueAnnouncer` 는 구독자가 없어도 알림을 넘긴다(API 알림 때문에). 접속 주소 설정을 `flowbit.public-url`(`FLOWBIT_PUBLIC_URL`, `common/web/PublicUrl.kt`)로 모았다.
  - 프런트: `/admin/notifications`("알림" 탭), 내 설정의 "알림"(이메일, 메일 수신, 이 브라우저의 푸시), `public/push-sw.js`, `features/notifications/`, `components/setting-fields.tsx`. `firebase` 패키지 추가.
  - 참고한 스킬: `backend-security-coder`(외부 요청·SSRF), `error-handling-patterns`(재시도), `api-design-principles`. [07-skills-guide.md](07-skills-guide.md) 에 적었다.
- 확인한 범위: `FLOWBIT_BUILD_DIR=build-check ./gradlew check`(158개 통과. `ExternalAuthIntegrationTest` 22개, `NotificationsIntegrationTest` 14개 추가), 프런트 typecheck·eslint·`next build`. **화면은 보지 않았다.** 실제 LDAP·SSO·SMTP·Firebase·Slack 으로도 확인하지 않았다.
- **백엔드를 다시 시작하면 `V2`, `V3` 가 적용된다.** DB 를 지울 필요는 없다(기존 계정은 `LOCAL` 로 남는다).
- 써 보려면: SSO 는 SSO 서비스에 관리자 화면이 보여 주는 리다이렉트 URI 를 등록한다. 메일·API 알림에 열리는 링크를 넣으려면 `FLOWBIT_PUBLIC_URL` 을 정한다. 푸시는 HTTPS 또는 localhost 에서만 된다.
- 먼저 볼 곳: ① 실제 SSO 서비스에서 돌아올 때 리다이렉트 URI 가 접속 주소로 나오는지(Next rewrite 뒤). ② 서비스 워커가 실제 FCM 메시지를 띄우는지(메시지 모양은 문서를 보고 썼다). ③ Active Directory 의 필터·속성.
- 알아둘 것: 같은 시간에 다른 세션이 이 저장소를 고치고 있었다(결정 번호 D-085·D-086 을 그쪽이 먼저 써서 번호를 옮겼다). 결정이나 마이그레이션 번호를 쓰기 전에 파일을 다시 읽는다.

### 2026-10-12 — 화면 점검에서 찾은 문제 고침

- 사용자 요청: 에이전트가 화면을 직접 눌러 보며 찾은 문제(버그 7, 개선 여럿)를 모두 고친다. 관리자 설정의 LDAP/SSO 는 다른 작업이 하고 있어 그쪽 파일(`admin/layout.tsx`, 로그인 폼, 구성원 관리)은 건드리지 않았다.
- 고친 것(화면):
  - 전체 프로젝트 표: 건강도 열은 1280px 부터, 리드·목표일은 1024px 부터 보인다(이름 칸이 한두 글자로 줄던 문제).
  - 새 이슈 창: `/` 메뉴의 `Esc` 가 창까지 닫던 문제, 제목이 옆으로 밀리던 것(줄바꿈되는 입력란으로), 프로젝트 이름 배지의 앞이 잘리던 것.
  - 이슈를 대화상자로 열어도 뒤의 목록이 보기(캘린더·목표 차트)와 필터를 유지한다(`useListAddress`). 대화상자는 가운데가 아니라 위쪽에 걸어 내용이 바뀌어도 뛰지 않는다.
  - 표 도구 모음: 본문 폭을 넘지 않고 한 줄로 두며(넘치면 옆으로 민다), 편집 중인 표 위에 그만큼 여백을 둔다(`.rich-text-editable`).
  - **문서 블록을 눌러도 열리지 않던 문제**(사용자 지적): Tiptap 이 확장의 storage 를 복사해 쓰는데 `configure` 가 원본 객체에 `open` 을 넣고 있었다. `this` 로 쓰게 고쳤다(`page-link.ts`).
  - 좁은 화면의 메뉴 버튼(`mobile-nav.tsx`, D-086), 경로 표시와 탭을 위쪽에 고정, 프로젝트·이슈·문서 화면의 탭 제목.
  - 프로젝트 업데이트: 지우기(D-085, 두 번 눌러 확인), 글도 없고 건강도도 그대로면 남기지 못함. 목표는 연달아 추가할 수 있다.
  - "내 이슈 > 담당"에서 만든 이슈는 담당자가 본인으로 시작한다. 삭제된 이슈에는 이모지를 달 수 없다. 목록의 하위 이슈 순서를 상세와 같게(만든 순서).
  - 아바타 글자(작은 아바타는 한 글자, 워크스페이스는 이름 앞 글자), 달력의 오늘 표시, 목표 차트의 긴 제목, 문구(검색 안내, 새 이슈 본문 안내, 닫기, 체크리스트 접근성 이름).
  - `admin/loading.tsx` 추가(관리자 설정 탭 사이를 오갈 때의 "uncached data" 오류).
- 고친 것(백엔드): `DELETE /api/v1/projects/{id}/updates/{updateId}`, 통합 테스트 1개.
- 확인한 범위: typecheck, eslint, `ProjectsIntegrationTest`(`build-check`). 화면은 개발 서버에서 직접 봤다: 프로젝트 표, 탭 제목, 고정된 머리줄, 캘린더 뒤 배경, 새 이슈 창의 `Esc`·긴 제목, 문서 블록 열기, 표 도구 모음, 좁은 화면 메뉴. **업데이트 지우기는 버튼과 확인 문구까지만 봤다**(그때 떠 있던 백엔드가 새 API 가 없는 예전 빌드였다).
- 남은 것:
  - `/my-issues/created` 등에서 탭을 오갈 때 콘솔에 "URL data ... outside of `<Suspense>`" 가 남는다(페이지가 `searchParams` 를 최상위에서 읽는다). 고치지 않았다.
  - 관리자 설정 레이아웃(`admin/layout.tsx`)이 최상위에서 `requireUser()` 를 기다린다(04 가이드의 규칙과 다름). LDAP/SSO 작업이 끝난 뒤 `<Suspense>` 안으로 옮긴다.
  - 캘린더·목표 차트의 끌어 옮기기, 파일 올리기, 두 계정의 실시간 반영은 여전히 아무도 화면에서 확인하지 않았다.
  - 프로젝트 라벨(글자만)과 이슈의 워크스페이스 라벨(색 있음)이 따로다. 합칠지는 사용자가 정한다.
  - 보기 역할만 가진 사람도 프로젝트 없는 이슈는 만들 수 있다(명세대로). 의도인지 확인이 필요하다.

### 2026-10-12 — 프로젝트 목록의 실시간 반영

- 사용자 확인 요청 후 진행: 이슈 변경은 이미 실시간이었지만, 프로젝트 권한을 새로 받거나 잃은 사람과 새 프로젝트는 사이드바·홈 목록에 바로 반영되지 않았다.
- 한 일: 실시간 주제 `projects`(`RealtimeHub.PROJECTS`, 로그인한 누구나). 프로젝트 생성·수정·아이콘·구성원 변경 때 알리고, `(app)/layout.tsx` 가 듣는다. 테스트 1개.
- 확인한 범위: `./gradlew check`(`build-check`), typecheck, eslint. 화면은 보지 않았다.
- 남은 것: 관리자 설정에서 사람의 역할(관리자/구성원)이나 사용 중지를 바꾸는 경우는 알리지 않는다.

### 2026-10-12 — 문서 이름 먼저 받기

- 사용자 지시: `/문서` 를 고르면 바로 만들지 말고 이름부터 받는다. 이름이 없으면 취소. "본문에 없는 문서" 목록은 없앤다 (D-083 보완).
- 한 일: `rich-text-editor.tsx` 에 커서 자리의 이름 입력 칸(Enter 로 만들기, 빈 이름·Esc·바깥 클릭은 취소), `onCreateDocument(title)`. `issue-documents.tsx`(`UnlinkedDocuments`) 삭제.
- 확인한 범위: typecheck, eslint, `next build`. 화면은 보지 않았다.
- 열린 질문: 본문에서 문서 블록을 지우면 그 문서는 DB 에 남지만 화면에서 갈 길이 없다. 블록을 지울 때 문서도 지울지(휴지통과 함께) 정해야 한다.

### 2026-10-12 — 스키마 변경 방식

- 사용자 지시: 이제부터 스키마 변경을 `V1__schema.sql` 에 하지 않는다 (D-084). 다음 변경부터 `V2__….sql` 로 추가한다. 이번 세션에서 V1 에 이미 넣은 것(`projects.key` 삭제, `documents.parent_id`)은 그대로다.

### 2026-10-12 — 문서를 편집기 블록으로, 하위 문서, 커서 동기화

- 사용자 지시: ① 상대가 글을 쓰는 동안 커서가 엉뚱한 곳에서 글자 수만큼 움직인다. 저장된 글을 불러올 때 커서도 같이, 커서만 옮기면 커서만 (D-053 보완). ② 문서를 노션처럼 편집기 안에 넣고 별도 문서 영역을 없앤다. 프로젝트 문서는 5단계, 이슈 문서는 1단계 (D-083).
- 한 일 ①: `use-autosave.ts` 에 `hasUnsavedEdits`/`onEditsSaved`. `presence-focus.tsx` 는 저장 안 된 입력이 있으면 커서를 보내지 않다가 저장이 끝나면 보낸다. 받는 쪽은 `MutationObserver` 로 글이 바뀔 때마다 커서를 다시 놓고, 가리키는 자리가 아직 없으면 숨긴다(`markEditor`).
- 한 일 ②: `documents.parent_id`, `POST /api/v1/documents/{id}/documents`, 문서 응답의 `ancestors`·`children`·`canAddChild`, `RichText` 가 `pageLink` 블록을 받음. 프런트 `components/editor/page-link.ts`(문서 블록), `/문서` 가 링크 대신 블록을 넣음, 문서 화면에서도 `/문서`(하위 문서), 경로 표시에 상위 문서. `IssueDocuments` 목록을 없애고 `UnlinkedDocuments`(블록이 지워진 문서만)로 바꿈. 테스트 1개.
- 확인한 범위: `./gradlew check`(`build-check`), typecheck, eslint, `next build`. 화면은 보지 않았다.
- **개발 DB 를 다시 만들어야 한다**(`documents.parent_id`).
- 예전 방식으로 넣은 문서 링크(글자 링크)는 그대로 링크로 남는다.

### 2026-10-12 — 프로젝트 키 제거

- 사용자 지시: 프로젝트 키 자체가 필요 없다 (D-082, D-071 대체).
- 한 일: `projects.key` 열 삭제(`V1__schema.sql`), `Project.key`·키 만들기(`freeKeyFor`)·`PROJECT_KEY_TAKEN` 삭제. 프로젝트를 가리키는 값은 전부 ID: API 경로 `/api/v1/projects/{id}`(ID 가 아닌 값은 404), 이슈의 `projectId`, 응답의 `project: { id, name }`, 실시간 주제 `project:{id}`. 프런트 경로 `project/[id]`, `no-access/[id]`, 개요·설정 화면의 키 표시 삭제. 테스트는 `IntegrationTest.remember/p` 로 프로젝트 ID 를 기억해 쓴다.
- 확인한 범위: `./gradlew check`(`build-check`), typecheck, eslint, `next build`. 화면은 보지 않았다.
- **개발 DB 를 다시 만들어야 한다**(스키마에서 `projects.key` 가 빠졌다). 백엔드도 다시 시작.

### 2026-10-12 — 편집기 안의 포커스 표시는 블록 단위로

- 사용자 지적: 다른 사람이 편집 중일 때 편집기 전체에 테두리가 생긴다. 편집기의 어느 부분에 있는지를 표시해야 한다 (D-053 보완).
- 한 일: `presence-focus.tsx` 가 편집기 안에서는 대상에 블록 번호를 붙여 보낸다(`body#3`, 커서가 블록을 옮기면 `selectionchange` 로 다시 보냄). 받는 쪽은 그 블록 위에 테두리 상자를 겹쳐 놓는다(편집기 DOM 은 건드리지 않음). 상자의 위치 스타일은 코드에서 직접 준다: 처음에는 스타일 파일에 두었는데 실행 중인 개발 서버의 CSS 에 실리지 않아 상자가 편집기 맨 아래에 놓였다. 서버 변경 없음.
- 이어서(사용자 지시): 입력 중인 사람은 블록 테두리가 아니라 **커서**로 보인다. 보내는 쪽이 블록 안의 글자 위치를 붙이고(`body#3@17`), 받는 쪽이 그 위치에 세로 막대와 이름 깃발을 그린다(`caretRect`, `markBlock`). 마우스만 올린 경우는 블록 테두리 그대로.
- 한계: 글 내용은 저장될 때까지 서로 다르므로, 상대가 아직 저장하지 않은 블록을 더했으면 번호가 어긋날 수 있다. 해당 블록이 없으면 편집기 전체를 표시한다.
- 확인한 범위: typecheck, eslint. 화면은 보지 않았다.

### 2026-10-12 — 라벨 콤보박스, 자잘한 문구

- 사용자 지시: 라벨을 고르는 곳 전부를 Linear 식 콤보박스로 (D-081). 그 밖에 로고 "빼기" → "제거", 맥에서는 단축키를 ⌘ 로 표시, 이슈 삭제 버튼 아래 안내 문구 제거, 이슈·프로젝트의 "자료" → "문서".
- 한 일: `components/multi-combobox.tsx`(찾기, 여러 개 체크, 추가, 화살표·Enter)를 만들어 `LabelChip`(이슈 라벨), `LabelFilterChip`(목록 필터, 여러 값 `?label=a,b,none`), 프로젝트 속성의 라벨이 함께 쓴다. `components/mod-key.tsx`(`ModKey`).
- 확인한 범위: typecheck, eslint, `next build`. 화면은 보지 않았다.

### 2026-10-12 — 초기 설정 화면의 단계 표시와 안내 문구

- 사용자 지적: 초기 설정 화면에서 단계 표시의 "워크스페이스"가 두 줄로 나온다. 안내 문구의 "만든 뒤 바로 로그인됩니다."는 뺀다.
- 한 일: `setup-steps.tsx` 의 단계 이름을 한 줄로 고정(`whitespace-nowrap`)하고 간격을 줄였다. 폭이 375px 보다 좁으면 지금 단계의 이름만 보이고 나머지는 스크린 리더용으로만 남는다. `messages.setup.description` 에서 문구 제거. 동작(설정 뒤 바로 로그인, D-015)은 그대로다.
- 확인한 범위: typecheck, eslint. 화면은 보지 않았다.

### 2026-10-12 — 하위 이슈의 프로젝트·목표, 새 이슈 창의 속성 패널

- 사용자 지적: ① 하위 이슈는 상위 이슈의 프로젝트·목표를 따라가야 하지 않느냐 (D-080). ② 새 이슈 창의 속성이 편집할 때와 다르게 생겼다.
- 한 일: ① `IssueService.create` 가 상위의 프로젝트·목표를 물려줌, `moveSubIssuesAlong`(상위가 옮겨 가면 하위도). 테스트 1개. ② `IssuePropertyFields`(값과 변경만 주고받는 패널)를 만들어 이슈 편집(`IssueProperties`)과 새 이슈 창이 함께 쓴다.
- 확인한 범위: `./gradlew check`(`build-check`), typecheck, eslint, `next build`. 화면은 보지 않았다.

### 2026-10-12 — 목표 차트, 캘린더 조작, 이슈 기간, 삭제된 이슈

- 사용자 지시: ① 삭제된 이슈를 내 이슈·전체 이슈·프로젝트 이슈에서 볼 수 있게(히스토리 용도). ② 목표 보기는 간트 차트로, 필터도 걸 수 있게. ③ 이슈 날짜를 하루뿐 아니라 시작일~종료일로도. ④ 캘린더에서 끌어 날짜 이동, 날짜를 누르거나 끌어 이슈 등록.
- 한 일 (D-077~D-079):
  - 백엔드: `issues.start_date`, `deleted_at`·`deleted_by`, 삭제·복원 API와 목록의 `deleted`, 이슈 응답의 `startDate`·`deletedAt`·목표의 `targetDate`, 집계에서 삭제된 이슈 제외. 테스트 2개.
  - 프런트: `issue-timeline.tsx`(간트), `issue-calendar.tsx`(기간 표시, 끌어 옮기기, 눌러/끌어 만들기), `DateRangeChip` 의 `singleIsEnd`, `issue-deletion.tsx`, `issues-view.tsx` 의 삭제된 이슈 전환, 내 이슈가 `IssuesView` 를 쓰도록(`my-issues-views.tsx` 삭제).
- 확인한 범위: `./gradlew check`(`build-check`), typecheck, eslint, `next build`. **화면은 보지 않았다.** 끌어 놓기와 차트 배치는 실제로 봐야 아는 부분이 많다.
- **`V1__schema.sql` 이 바뀌었다**(`start_date`, `deleted_at`, `deleted_by`, `documents`). 개발 DB 를 다시 지워야 백엔드가 뜬다.

### 2026-10-12 — 목표 보기, 캘린더 보기

- 사용자 지시: 내 이슈, 전체 이슈, 프로젝트 이슈를 마일스톤 뷰어, 캘린더 뷰어로 볼 수 있게 (D-076).
- 한 일: `issue-views.tsx`(보기 전환, 목표 보기, 캘린더 보기), `issue-row.tsx`(읽기 전용 줄), `my-issues-views.tsx`, `issues-view.tsx` 에 전환 버튼과 분기. 백엔드 변경 없음.
- 확인한 범위: typecheck, eslint, `next build`. 화면은 보지 않았다.

### 2026-10-12 — 프로젝트 문서

- 사용자 지시: 프로젝트 개요의 에디터에서 페이지를 추가할 수 있게 (D-075).
- 한 일: 테이블 `issue_documents` → `documents`(이슈 또는 프로젝트 소유), `IssueService` 의 문서 함수들이 주인을 가려 권한을 판단, `GET/POST /api/v1/projects/{key}/documents`, 개요 편집기의 `/문서`, 개요의 "자료" 목록, 문서 화면이 프로젝트 문서도 처리. 테스트 1개.
- 확인한 범위: `./gradlew check`(`build-check`), typecheck, eslint, `next build`. 화면은 보지 않았다.
- **`V1__schema.sql` 이 바뀌었다.** 개발 DB 를 다시 지워야 백엔드가 뜬다.

### 2026-10-12 — 새로 고쳐도 이슈는 대화상자로

- 사용자 지시: 이슈 모달을 보다가 새로 고치면 페이지로 나오는데, 똑같이 모달로 나와야 한다 (D-074).
- 한 일: `issue/[key]/page.tsx` 가 전체 이슈 목록(`all-issues.tsx`) 위에 `IssueDialog` 를 띄운다. `IssueDialog` 에 `closeTo`, `path`.
- 확인한 범위: typecheck, eslint, `next build`. 화면은 보지 않았다.
- `IssueDetail` 의 페이지용 배치(뒤로 가기 링크, `inDialog`)는 사용자 지시로 지웠다. 이슈는 대화상자로만 보인다. `issue/[key]/page.tsx` 는 주소를 직접 열었을 때 목록 위에 대화상자를 띄우는 경로라 남아 있다.

### 2026-10-12 — 이슈 옆 패널을 세 묶음으로, 하위 이슈 줄의 오른쪽

- 사용자 지시: ① 이슈 모달의 속성 영역을 Linear 사진처럼 나누고 프로젝트·목표 선택도 같은 방식으로 (D-073). ② 이슈 상세의 하위 이슈 목록 오른쪽이 비어 있으니 라벨 등을 표시.
- 한 일: `issue-properties.tsx` 를 한 컴포넌트(속성·라벨·프로젝트 묶음)로 다시 씀, `ChipSelect`·`DateChip` 의 `plain`, `LabelChip` 의 `asPills`, `issue-detail.tsx` 의 옆 패널에서 카드 제거. 하위 이슈 줄에 진행 수·라벨·기한·우선순위·담당자.
- 확인한 범위: typecheck, eslint, `next build`. 화면은 보지 않았다.

### 2026-10-12 — 목록에서 하위 이슈를 들여 보여 주기

- 사용자 지시: 내 이슈, 모든 이슈, 프로젝트의 이슈에서 하위 이슈가 있으면 Linear 처럼 단계로 표현 (D-072).
- 한 일: 목록 응답에 `subIssueCount`·`subIssueDoneCount`, `sub-issue-tree.ts`(묶음 안에서 순서 정하기), `sub-issue-rows.tsx`(꺾인 선, 진행 수, 흐린 상위 줄), `issues-view.tsx`·`my-issues-list.tsx` 에 적용.
- 확인한 범위: `./gradlew check`(`build-check`), typecheck, eslint, `next build`. 화면은 보지 않았다.

### 2026-10-12 — 초기 설정 두 단계, 프로젝트 키 자동

- 사용자 지시: ① 초기 설정을 워크스페이스 설정 → 관리자 계정 설정 두 단계로 (D-070). ② 새 프로젝트를 만들 때 왜 키를 또 받느냐 (D-071).
- 한 일: ① `setup-form.tsx` 에 단계 상태, `SetupSteps` 가 현재 단계를 받음. ② `ProjectService.freeKeyFor`, `POST /api/v1/projects` 의 `key` 를 선택으로, 새 프로젝트 창에서 키 칸 제거. 테스트 1개.
- 확인한 범위: `./gradlew check`(`build-check` 폴더), typecheck, eslint. 화면은 보지 않았다.
- 프로젝트 키는 개요의 `# KEY` 와 속성 카드, 설정 화면에 여전히 보인다(주소에 쓰이는 값이라 남겼다).

### 2026-10-12 — 초기 설정 전인데 로그인 화면이 뜨던 문제

- 사용자 보고: 초기 설정 전에 `/` 로 들어가면 로그인 화면이 뜬다. 로그인 화면에서 설정이 안 돼 있으면 설정 화면으로 넘어가야 한다.
- 원인: `proxy.ts` 가 "초기 설정 완료"를 메모리에 한 번 기억하면 다시 묻지 않았다. DB 를 지우고 Next 서버를 그대로 두면 완료된 줄 알고 `/login` 으로 보냈다.
- 고친 것: 세션 쿠키가 없는 요청(로그인 화면, 첫 방문)에서는 매번 백엔드에 묻는다. 로그인한 사용자의 페이지 이동에는 여전히 추가 호출이 없다(D-038 유지).
- 확인한 범위: typecheck, eslint. 백엔드가 꺼져 있어 실제 이동은 확인하지 못했다.

### 2026-10-12 — 초기 설정에서 로고 올리기가 멈춘 문제

- 사용자 보고: 초기 설정에서 "설정하는 중"에서 넘어가지 않다가 "초기 설정은 끝났지만 로고를 올리지 못했습니다. 서버에서 문제가 생겼습니다"가 뜬다.
- 확인한 사실: DB 에는 설정 완료와 로고(`logo_key`)가 모두 저장돼 있었다. 즉 서버는 로고를 받았고 응답만 실패했다. 사용자의 서버는 20:18 에 떴고, 에이전트의 `./gradlew check` 가 20:21 에 `backend/build/classes` 를 새 코드로 바꿔 놓았고, 사용자의 초기 설정은 20:23 이었다. 통합 테스트(설정 뒤 로고 올리기)는 통과한다.
- 판단: 실행 중인 서버의 클래스 파일을 빌드가 바꿔 놓아 생긴 일로 본다(재현해서 확정하지는 못했다). 위 "알아둘 것"에 적고, 검사용 빌드 폴더를 따로 쓸 수 있게 했다.
- 사용자가 할 일: DB 를 지우고(컬럼 이름이 `workspace_key` 로 바뀌었다) 백엔드를 다시 시작한 뒤 초기 설정을 다시 한다. 같은 증상이 또 나오면 코드 문제이므로 다시 본다.

### 2026-10-12 — "이슈 키"를 "워크스페이스 키"로

- 사용자 지시: 필드 이름을 이슈 키가 아니라 워크스페이스 키로 (나중에 다른 데도 쓸 수 있게). D-069.
- 한 일: 화면 문구, API 필드(`workspaceKey`), 엔티티·컬럼(`workspace_key`, `V1__schema.sql` 을 직접 고침), 테스트, 문서. 아래 기록들의 "이슈 키"는 같은 것이다.
- 확인한 범위: `./gradlew check`, typecheck, eslint.
- `V1__schema.sql` 이 바뀌었으므로 **그사이 새로 만든 개발 DB 가 있다면 다시 지워야 한다**.

### 2026-10-12 — 초기 설정의 이슈 키

- 사용자 지시: 워크스페이스 이름과 이슈 키를 따로 받을 것 (D-068).
- 한 일: `POST /api/v1/setup` 의 `workspaceKey`, `/setup` 의 "이슈 키" 칸(기본 `FB`), 테스트 1개.
- 확인한 범위: `./gradlew check`, typecheck, eslint. 화면은 보지 않았다.

### 2026-10-12 — `/setup` 404

- 사용자 보고: `/setup` 에 들어가면 404.
- 원인: 이슈 대화상자를 앱 전체로 옮기면서 만든 `(app)/@modal/[...catchAll]` 이 `(public)` 의 주소(`/setup`, `/unavailable`)까지 가로챘다.
- 고친 것: catch-all 을 지우고, 대화상자가 주소를 보고 스스로 닫히게 했다. 04 가이드에 규칙 추가.
- 확인: 개발 서버에서 `/setup`, `/unavailable` 이 다시 200 으로 응답한다(`curl`).

### 2026-10-12 — 마이그레이션을 하나로

- 사용자 지시: Flyway DDL 을 하나로 합칠 것. 개발 단계라 DB 는 지울 테니, DB 때문에 어쩔 수 없이 소스에 둔 예외 처리를 되돌릴 것.
- 한 일 (D-067): `V1`~`V12` 를 지우고 최종 스키마를 `V1__schema.sql` 하나로. 옛 데이터용 SQL 제거. `issues.preset`(컬럼, 엔티티, API, 프런트 타입·아이콘·목차 템플릿·문구)과 `projects.next_issue_number`(컬럼, 엔티티 필드) 제거. `issues.project_id` 를 `ON DELETE SET NULL` 로, `issues(created_by)` 인덱스 추가.
- 확인한 범위: `./gradlew check`(새 파일로 테스트 DB 가 만들어지고 전체 통과), typecheck, eslint, `next build`.
- **개발 DB 를 지워야 백엔드가 뜬다.** 예전 이력이 남은 DB 에서는 Flyway 가 시작을 거부한다. 사용자가 직접 지우기로 했다. 지운 뒤에는 초기 설정부터 다시 하고, Next 개발 서버도 다시 시작한다(`proxy.ts` 가 "초기 설정 완료"를 기억한다).

### 2026-10-12 — "가리키는 위치 표시"가 안 되던 문제, 편집기 손잡이

- 사용자 보고: ① 포커스 하이라이트가 적용되지 않는다. ② 편집기 블록 손잡이로 마우스를 옮기면 손잡이가 사라진다.
- ① 원인: `RealtimeHub` 가 Reactor Sink 에 여러 스레드에서(그리고 신호 처리 도중에) 직접 신호를 넣어 `EmissionException`("signaled serially")이 나고, 신호를 넣던 WebSocket 연결이 끊겼다. 두 사람이 같은 화면에 들어오는 순간 일어나서 "보는 사람" 아바타와 가리키는 위치가 전달되지 않았다. 처음부터 있던 버그다(두 계정으로 확인한 적이 없었다).
- 고친 것: 신호를 한 스레드에서 차례로 넣도록(`emit`). `RealtimeIntegrationTest` 2개 추가(두 연결의 프레즌스·가리키는 위치 전달, 변경 알림과 권한). 이슈 목록의 줄에도 `data-presence-id` 를 달았다.
- ② 원인: 손잡이와 글 사이의 틈을 지나는 순간 편집기 밖으로 나간 것으로 보고 바로 숨겼다. 숨기기를 0.3초 늦추고 틈을 없앴다.
- 확인한 범위: `./gradlew check`(실시간 테스트 포함), typecheck, eslint, `next build`. 화면은 보지 않았다.
- 알아둘 것: 표시는 **같은 화면(같은 프레즌스 키)을 보는 다른 계정**에게만 뜬다. 같은 계정으로 두 브라우저를 열면 서로 "나"라서 보이지 않는다. 이슈 대화상자를 연 사람과 목록만 보는 사람은 서로 다른 화면으로 친다.

### 2026-10-12 — 독립 이슈, 하위 이슈, 이모지, 파일 첨부, 이슈 문서, 구성원 추가 버튼

- 사용자 지시: Linear 이슈 화면처럼 ① 이모지와 파일 첨부 ② 하위 이슈 ③ 에디터에서 페이지 추가 ④ 속성의 "프로젝트"에서 프로젝트와 그 하위 목표를 고르게, **이슈는 독립적이고 연결만** ⑤ 프로젝트 개요의 구성원 카드에 관리자용 "+" 버튼.
- 사용자에게 물어 정한 것: 이슈 키는 워크스페이스 키 하나, 권한은 연결된 프로젝트를 따름, "페이지"는 이슈에 딸린 문서.
- 한 일 (D-062~D-066):
  - 백엔드: `V12`, `IssueService`·`IssueQueries`·`IssueController` 를 번호 기준으로 다시 씀, `IssueExtras.kt`(리액션·첨부·문서), `FileStorage`, `ProjectService.accessById`, 워크스페이스 `workspaceKey`, 실시간 주제 `issue:{번호}`·`issues`. 테스트 8개 추가(전체 통과).
  - 프런트: `/issue/[key]`, `(app)/@modal/(.)issue/[key]`(앱 전체에서 대화상자), `/issues`, `/doc/[id]`, 이슈 상세의 새 구역들(`issue-reactions`, `issue-attachments`, `issue-sub-issues`, `issue-documents`), "프로젝트" 카드(`IssueProjectLink`), 새 이슈 창의 프로젝트 선택, 편집기 `/문서`, 사이드바 "전체 이슈", 관리자 설정의 이슈 키, `add-project-member-button.tsx`.
  - 없앤 것: `/project/[key]/issues/[number]` 와 프로젝트 수준의 `@modal`.
- 확인한 범위: `./gradlew check`, typecheck, eslint, `next build`. **화면은 보지 않았다.** 이번 변경은 범위가 커서 화면에서 깨진 곳이 있을 수 있다. 먼저 볼 곳: 이슈를 눌렀을 때의 대화상자, 프로젝트 바꾸기, 새 이슈 창의 프로젝트 선택, 파일 올리기·내려받기.
- **기존 이슈 번호가 바뀐다.** `V12` 가 만든 순서대로 `FB-1` 부터 다시 매긴다. 옛 주소(`/project/KEY/issues/N`)는 더 열리지 않는다.
- 개발용 백엔드를 다시 시작하면 `V10`~`V12` 가 적용된다.

### 2026-10-12 — 이슈 활동 기록과 구독

- 사용자 지시: 이슈의 히스토리를 Linear 처럼 기록. 담당자와 등록자는 자동 구독, 관련 없는 사람도 구독 가능. 나중에 SMTP 와 Firebase 웹 푸시를 붙일 것을 감안.
- 한 일 (D-061): `V11`(`issue_activities`, `issue_subscriptions`), `IssueActivities.kt`(기록·합치기·구독), `IssueService` 가 변경 전후를 비교해 기록하고 `IssueChange` 를 돌려줌, 활동·구독 API, `notification/Notifications.kt`(통로 인터페이스와 디스패처), 프런트 `issue-activity.tsx`. 테스트 3개 추가.
- 확인한 범위: `./gradlew check`, typecheck, eslint, `next build`. 화면은 보지 않았다.
- **알림은 실제로 아무것도 보내지 않는다.** 붙이는 방법은 [02-architecture.md](02-architecture.md) "알림". SMTP 는 `users.email` 입력 화면이, 웹 푸시는 토큰 테이블과 서비스 워커가 먼저 필요하다.
- 댓글은 만들지 않았다(사용자 화면 사진에는 있었지만 지시에 없었다).

### 2026-10-12 — 내 이슈, 이슈 라벨(프리셋 대체)

- 사용자 지시: ① 왼쪽 메뉴에 "내 이슈"와 그 페이지 (Linear 화면 참고). ② 이슈 속성에서 프리셋을 지우고 라벨을 추가. 기본 버그·기능·중요, 사용자가 라벨을 더할 수 있게 (Linear 라벨 메뉴 참고).
- 한 일:
  - D-059: `GET /api/v1/me/issues`(`IssueQueries.listForUser`), `/my-issues`·`/my-issues/created`, `my-issues-list.tsx`, 사이드바 메뉴.
  - D-060: `V10`(`labels`, `issue_labels`, 기본 3개, 기존 프리셋 옮기기), `label/Labels.kt`(목록·추가 API), 이슈의 `labelIds`/`labels`, `label-chip.tsx`, 새 이슈 창·이슈 상세·목록·필터에서 프리셋 제거. 테스트 5개 추가.
- 확인한 범위: `./gradlew check`, typecheck, eslint, `next build`. 화면은 보지 않았다.
- 알아둘 것: 프리셋 목차(새 이슈 본문에 제목이 깔리던 기능)도 함께 사라졌다. 라벨을 고치거나 지우는 화면은 없다. 내 이슈에서 이슈를 누르면 대화상자가 아니라 이슈 페이지로 간다(대화상자는 프로젝트 안에서만 뜬다).
- 개발용 백엔드를 다시 시작하면 `V10` 이 적용된다.

### 2026-10-12 — 편집기 블록 끌어 옮기기

- 사용자 요청: 편집기의 블록을 드래그로 위아래로 옮기기.
- 한 일 (D-058): `block-drag-handle.tsx`. 새 이슈 창의 편집기는 스크롤 상자라 손잡이가 잘리지 않게 왼쪽으로 16px 넓혔다(`-ml-4 pl-4`).
- 확인한 범위: typecheck, eslint, `next build`. **화면은 보지 않았다.** 끌어 놓기는 실제로 해 봐야 아는 부분이 많다: 손잡이 위치, 표·이미지·목록을 옮길 때, 놓는 위치 표시선.

### 2026-10-12 — "마일스톤"을 "목표"로, 속성 메뉴 닫기

- 사용자 지시: 화면의 "마일스톤"을 "목표"로 바꿀 것 (D-057). 속성 메뉴에서 항목을 고르면 닫히게.
- 한 일: `messages/ko.ts` 와 `MilestoneService`·`IssueService` 의 오류 문구. 코드 이름과 API 는 그대로. `chip-select.tsx` 의 선택 항목에 `closeOnClick`.
- 확인한 범위: `./gradlew check`, typecheck, eslint, `next build`. 화면은 보지 않았다.

### 2026-10-12 — 이슈 상세 대화상자, 이슈 필터

- 사용자 지시: ① 이슈 상세를 새 페이지가 아니라 새 이슈 창처럼 대화상자로. ② 이슈 필터 항목을 더. ③ (보고) `/project/[key]/issues` 에서 "uncached data ... outside of `<Suspense>`" 오류.
- 한 일: ① D-055: `@modal` 슬롯과 가로채기 경로, `issue-detail.tsx`(페이지·대화상자 공용), `issue-dialog.tsx`. ② D-056: `issues-view.tsx` 에 검색·상태·우선순위·담당자·프리셋 필터. ③ `issues/loading.tsx` 추가(이슈 상세 ↔ 목록 이동에 경계가 없었다).
- 확인한 범위: typecheck, eslint, `next build`(가로채기 경로가 경로 목록에 나온다). **화면은 보지 않았다.** 가로채기 경로는 실제 동작(목록에서 눌렀을 때 대화상자, 닫기, 탭 이동 시 사라짐)을 사용자가 확인해야 한다.

### 2026-10-12 — 새 이슈 창의 속성을 오른쪽으로

- 사용자 지시: 이슈를 만들거나 고칠 때 속성을 편집기 아래가 아니라 프로젝트 개요처럼 오른쪽에서 고르게.
- 한 일 (D-054): `new-issue-dialog.tsx` 를 두 열로(왼쪽 제목·본문, 오른쪽 `PropertyRow` 속성 열), 창 너비 확대. 이슈 상세는 이미 `lg`(1024px) 이상에서 오른쪽 카드라 그대로 뒀다.
- 확인한 범위: typecheck, eslint, `next build`. 화면은 보지 않았다.

### 2026-10-12 — 새 이슈 창의 `/` 메뉴 위치

- 사용자 보고: 새 이슈 창에서 `/` 를 치면 메뉴가 커서가 아니라 화면 아래쪽에 뜬다. 이미지·표 항목도 안 보인다.
- 원인: 메뉴가 `position: fixed` 인데 대화상자가 `transform` 으로 놓여 있어 좌표 기준이 창이 아니라 대화상자였다. 그만큼 밀려 내려가 목록 아래쪽(표, 이미지)이 화면 밖으로 잘렸다. 편집기는 처음부터 공통(`RichTextEditor`)이라 항목 자체는 있었다.
- 고친 것: `fixed-origin.ts` 로 기준점을 재서 뺀다. 표 도구 모음도 같은 방식의 `fixed` 로 바꿨다(새 이슈 창의 편집기는 스크롤 상자라 `absolute` 면 맨 위 표의 도구 모음이 잘린다).
- 확인한 범위: typecheck, eslint, `next build`. 화면은 보지 않았다.
- 일반 파일(이미지가 아닌 것) 첨부는 없다. 사용자에게 필요한지 물었다.

### 2026-10-12 — 마일스톤을 만들 때의 `Date.now()` 오류

- 사용자 보고: 마일스톤을 새로 만들면 "Route /project/[key]: Next.js encountered the unstable value `Date.now()` while prerendering" 오류.
- 원인: `timeAgo`(프로젝트 업데이트의 "3분 전")와 `formatDueDate`(올해면 연도 생략)가 렌더 중에 현재 시각을 읽었다. 마일스톤을 만들면 화면을 다시 그리면서(`router.refresh`) 서버 렌더가 이 코드를 지난다.
- 고친 것: `components/dates.tsx` 의 `<TimeAgo>`, `<DueDate>` 를 만들어 부르는 곳 다섯 군데를 모두 바꿨다. 04 가이드에 규칙 추가.
- 확인한 범위: typecheck, eslint, `next build`. **사용자 화면에서 오류가 사라졌는지는 확인하지 않았다**(사용자가 화면 테스트는 하지 않아도 된다고 했다). 어느 호출이 실제로 걸렸는지 특정하지 못해, 시각을 읽는 곳을 전부 고쳤다.

### 2026-10-12 — 다크 모드의 표 테두리, 가리키는 위치 표시

- 사용자 지시: ① 다크 모드에서 표 테두리가 안 보인다. ② A 가 무언가 고치려고 포인터를 올리면 B 화면에서 A 의 포인터가 여기 있다는 표시.
- 한 일: ① 표 칸 테두리를 `--border`(다크에서 8% 흰색) 대신 글자색 20% 로. ② D-053: `RealtimeHub.setFocus`, WebSocket `focus` 메시지, 프레즌스 목록의 `focus`, 프런트 `presence-focus.tsx`, 고칠 수 있는 부분의 `data-presence-id`, `globals.css` 의 테두리·이름표. `RealtimeHubTest` 3개.
- 확인한 범위: `./gradlew check`(96개), 프런트 typecheck·eslint·`next build`. **화면은 보지 않았다(사용자가 화면 테스트는 하지 않아도 된다고 했다).** 두 계정으로 실제 표시가 뜨는지, 이름표가 잘리는 곳은 없는지는 확인되지 않았다.
- 개발용 백엔드를 다시 시작해야 동작한다.

### 2026-10-12 — 초기 설정의 워크스페이스, 편집기의 이미지와 표

- 사용자 지시: ① 초기 설정에서 워크스페이스 로고와 이름을 아이디·비밀번호와 함께 정하게. ② 편집기에 사진·이미지를 넣을 수 있게, 표 기능과 표 도구 모음(가운데 정렬, 셀 합치기 등).
- 한 일 (D-051, D-052):
  - 백엔드: `POST /api/v1/setup` 에 `workspaceName`, `POST /api/v1/images`(`ImageUploadController`, `ImageSpec.CONTENT`), `RichText` 가 `image`·`table`·`tableRow`·`tableHeader`·`tableCell` 을 받고 이미지 주소와 칸 속성을 검증. 테스트 6개 추가.
  - 프런트: `/setup` 에 워크스페이스 구역(`logo-picker.tsx`), 편집기에 `@tiptap/extension-image`·`@tiptap/extension-table`, `table-extensions.ts`, `table-toolbar.tsx`, `upload-image.ts`, `globals.css` 의 표·이미지 모양.
- 확인한 범위: `./gradlew check`(93개), 프런트 typecheck·eslint·`next build`. 화면은 테스트 전용 DB 서버(8081/3100)에서 직접 봤다: 초기 설정에서 이름과 로고를 넣고 시작 → 사이드바에 반영, 개요 편집기에서 `/table` 로 표 넣기, 가운데 정렬, 여러 칸 끌어 고른 뒤 합치기, 이미지 붙여넣기, 새로 고침 뒤 유지, 다크 모드.
- 직접 보지 못한 것: `/` 메뉴의 "이미지"(파일 선택 창), 끌어다 놓기, 열 너비 조절, 세로 맞춤, 행·열 추가/삭제, 초기 설정의 로고 올리기 실패 화면, 모바일 폭.
- 알아둘 것: 문서에서 지운 이미지의 파일은 저장소에 남는다(정리 기능 없음). 표 도구 모음은 표 바로 위에 떠서 그 위의 글 한 줄을 가릴 수 있다.
- **개발용 백엔드(8080)는 예전 코드로 떠 있다.** 다시 시작해야 새 API(`/api/v1/images`, `workspaceName`)가 동작한다.

### 2026-10-12 — 테마 기본값을 시스템 설정으로

- 사용자 지시: 기본적으로 다크 모드 설정이 시스템을 따라가게.
- 한 일: `providers.tsx` 의 `defaultTheme` 을 `light` → `system` (D-050). 내 설정의 테마 선택도 고른 적이 없으면 "기기 설정 따르기"가 선택된 것으로 보인다. 이미 밝게/어둡게를 고른 브라우저는 그대로다.
- 스킬 저장소 9개를 `agent-skills/` 에 다시 받았다(이 PC 에 없었다).

### 2026-10-12 — 밀린 백엔드 검사

- 사용자가 JDK 25 를 설치해, 컴파일하지 못한 채 쌓여 있던 백엔드 변경(실시간, 마일스톤, 전체 프로젝트 표, 건강도, 프로젝트 아이콘)을 `./gradlew check` 로 확인했다.
- 결과: 컴파일 오류 없음, 테스트 87개 모두 통과(`V7`~`V9` 마이그레이션도 Testcontainers 에서 적용됨). ktlint 위반 5건(`RealtimeWebSocketHandler.kt` 의 줄바꿈)만 있어 `ktlintFormat` 으로 고쳤다.
- `backend/gradlew` 에 실행 권한이 없어(`100644`) `chmod +x` 했다.
- 남은 것은 그대로다: 새 기능들의 전용 통합 테스트, 화면 확인.

### 2026-10-12 — 프로젝트 아이콘

- 사용자 지시: 프로젝트 설정에서 프로젝트 아이콘을 올릴 수 있게.
- 한 일: 위 체크리스트. 워크스페이스 로고의 올리기 화면 조각(`ImageUploadField`)과 백엔드 변환(`ImageProcessor`, `ImageStorage`)을 그대로 썼다.
- 확인한 범위: 프런트 typecheck, eslint. 백엔드는 컴파일하지 못했다(JDK 25 없음).

### 2026-10-12 — 로그인 정보가 주소에 들어가던 문제

- 사용자 보고: `http://192.168.0.75:3000/login?loginId=…&password=…` 처럼 로그인 정보가 주소에 붙는다. POST 여야 한다.
- 원인: 개발 서버를 `192.168.0.75` 로 열면 Next 16 이 localhost 가 아닌 호스트의 `/_next` 스크립트 요청을 403 으로 막는다(`allowedDevOrigins`). 스크립트가 없으니 폼이 브라우저 기본 동작(GET)으로 제출됐다. 로그인 API 자체(`POST /api/v1/auth/login`)는 처음부터 POST 다.
- 고친 것: 위 체크리스트. 사용자는 `frontend/.env.local` 에 `FLOWBIT_DEV_ORIGINS=192.168.0.75` 를 넣고 개발 서버를 다시 시작해야 한다. 같은 원인으로 그 주소에서는 실시간 연결도 시작되지 않았을 수 있다.
- 사용자의 비밀번호가 주소(브라우저 기록, 서버 로그)와 대화에 노출되어 비밀번호 변경을 권했다.

### 2026-10-12 — 마일스톤 필터 고침

- 사용자 보고: 개요에서 마일스톤의 이슈를 눌러 이슈 탭으로 가도 그 마일스톤 것만 나오지 않는다. 거기서 새로 만들 때 마일스톤이 설정되어야 하고, 필터 초기화가 있어야 한다.
- 원인: ① 필터가 `useState` 의 처음 값이라, Next 16 이 살려 둔 이전 화면 상태가 다시 쓰일 수 있었다. ② 새 이슈 창의 기본 마일스톤을 창의 `onOpenChange` 에서 넣었는데, 창은 밖에서(버튼, `C`) 열려 그 함수가 불리지 않았다.
- 고친 것: 필터를 주소(`?milestone=`)로, 창이 열릴 때마다 기본 마일스톤을 넣도록, "필터 초기화" 버튼 추가. 04 가이드에 규칙 추가.
- 확인한 범위: typecheck, eslint. 화면은 보지 않았다.

### 2026-10-12 — 건강도, 모든 프로젝트 표시

- 사용자 지시: Health 열("On track") 기능을 넣을 것. 프로젝트에 할당되지 않았어도 목록에는 보이고, 들어가려 하면 권한이 없으니 소유자 누구에게 문의하라고 뜨게.
- 한 일: 위 체크리스트. "소유자"는 프로젝트의 관리 역할로 해석했다(여럿일 수 있다).
- 확인한 범위: 프런트 typecheck, eslint. 백엔드는 컴파일하지 못했다(JDK 25 없음).

### 2026-10-12 — 전체 프로젝트 표

- 사용자 지시: 전체 프로젝트 화면을 Linear 의 프로젝트 목록 사진처럼.
- 한 일: 위 체크리스트. Health 열, 체크박스, 필터·표시 버튼은 뒷받침할 기능이 없어 뺐다(D-046).
- 확인한 범위: 프런트 typecheck, eslint. 백엔드는 컴파일하지 못했다(JDK 25 없음).

### 2026-10-12 — 마일스톤

- 사용자 지시: Linear 의 마일스톤처럼 기능 단위로 이슈를 묶고 진행률을 보여 주는 기능, 이슈를 마일스톤에 묶는 기능.
- 한 일: 위 체크리스트. 지난 작업에서 `IssueController` 의 import 순서가 ktlint 규칙에 어긋나 있던 것도 고쳤다.
- 확인한 범위: 프런트 typecheck, eslint. 백엔드는 이번에도 컴파일하지 못했다(JDK 25 없음, Maven Central 429). import 순서만 ktlint 규칙대로 스크립트로 확인했다.

### 2026-10-12 — 실시간 반영과 보는 사람 아바타

- 사용자 지시: A 계정이 프로젝트 설명·속성을 바꾸면 B 계정 브라우저에 바로 반영. 설명 편집도. 같은 화면을 보는 사람의 아바타를 오른쪽 위에, 마우스를 올리면 이름.
- 사용자는 "웹소켓을 적용했는데 안 된다"고 했지만, 저장소(원격의 모든 브랜치)에는 WebSocket 코드가 없었다. 문서의 설계대로 새로 만들었다.
- 확인한 범위: 프런트 typecheck, eslint. 백엔드는 컴파일도 못 했다(위 체크리스트). Next 의 rewrite 가 WebSocket 을 넘기는지는 `node_modules/next` 코드를 읽어 확인했다.

### 2026-10-12 — 달력 날짜 선택

- 사용자 지시: 날짜 선택을 보기 좋은 라이브러리로, 기간은 달력에서 시작일·종료일을 바로 고르게. 앞으로 작업은 `main` 에 바로 한다(PR #1 머지).
- 한 일: `react-day-picker` 9 설치, `components/ui/calendar.tsx`·`popover.tsx` 작성(shadcn 레지스트리가 막혀 직접), `due-date-chip.tsx` 를 `date-chips.tsx`(`DateChip`, `DateRangeChip`)로 교체.
- 확인한 범위: typecheck, eslint. 화면은 보지 않았다.

### 2026-10-12 — 프로젝트 속성

- 사용자 지시: 프로젝트 상세 오른쪽 속성에 프로젝트별 상태, 우선순위, 리드, 시작일 → 종료일, 라벨(태그)을 추가 (Linear 의 상태·우선순위 메뉴 사진).
- 한 일: `V6` 마이그레이션, `ProjectService.update` 에서 속성 검증과 저장, 응답에 속성 추가, 프런트의 `ProjectProperties`(칩 메뉴, 날짜 칩, 라벨 입력과 제안). `DueDateChip` 에 `label`, `clearLabel`, `min`, `max` 를 더해 시작일·종료일에 썼다.
- 확인한 범위: 프런트 typecheck, eslint. 백엔드는 컴파일과 ktlint 만 (테스트는 사용자 지시로 돌리지 않았다).

### 2026-10-12 — Linear 식 화면, 이슈, 문서 편집기, 설정 분리, 속도

- 사용자 지시 (시간순): ① 반응이 느리다 → ② 내 설정과 관리자 설정을 다른 화면으로, 테마 선택은 내 설정 안으로 → ③ Linear 최신 UI 사진을 참고해 리팩토링 (단, Linear 는 SaaS 이고 이것은 설치형임을 감안) → ④ 새 이슈 창이 받는 항목 사진 → ⑤ 프로젝트 설명은 설정이 아니라 개요에서 바로, `/` 메뉴가 있는 편집기로.
- 한 일: 위 체크리스트의 "뒤에 고친 것"과 "M3 2·3단계".
- 속도: 측정해 보니 백엔드는 요청당 2~4ms 였고, 느린 원인은 개발 모드(처음 여는 화면마다 1~2초 컴파일)와 페이지 이동마다 `proxy.ts` 가 백엔드를 두 번 부르던 것이었다. 후자를 없앴다.
- 겪은 것: Base UI 대화상자 안에서는 `autoFocus` 가 듣지 않아 `initialFocus` 를 써야 한다. 편집기는 시작할 때 스스로 문서를 한 번 고쳐서, 사용자가 손대기 전의 변경을 저장하지 않게 막아야 했다.

### 2026-10-12 — 프로젝트와 구성원 (M3 1단계)

- 사용자 지시: Linear 기능의 일부로, 프로젝트를 만들면 권한 있는 사용자가 들어와 이슈·고도화 같은 글을 등록·정리하게 하고 싶다. Jira처럼 프리셋을 먼저 채우고 Outline처럼 세부를 문서로 적는 방식에 대한 의견을 물었고, 에이전트의 제안 중 세 가지를 확정했다: 팀과 프로젝트를 합침, 프리셋은 기본 3종 + 목차만 수정, 구성원 추가를 범위에 넣음.
- 한 일: M3 전체 명세 작성. 그중 1단계(구성원 관리, 프로젝트, 권한)를 백엔드와 화면까지 구현. 글쓰기(프리셋, 편집기)는 아직 없다.
- 확인한 범위: 백엔드 `./gradlew check`(69개). 프론트엔드 lint, typecheck, `next build`. 화면은 보지 못했다.
- 개발 DB 에 `V3` 가 적용됐고 백엔드 개발 서버를 새 코드로 다시 띄웠다.

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

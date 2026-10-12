# 진행 상황

작업을 시작할 때 읽고, 끝낼 때 갱신한다. 다음 에이전트는 이전 대화를 볼 수 없으므로, 이어서 작업하는 데 필요한 것은 모두 여기에 적는다.

## 현재 상태

- **M1(로그인 및 초기 설정)이 구현되어 동작한다.** 사용자가 브라우저에서 초기 설정 → 로그아웃 → 로그인을 직접 확인했다 (2026-10-12).
- 제품 방향은 **Linear식**이다 (D-020). 기준 문서는 [01-product.md](01-product.md). M2 이후의 화면은 아직 하나도 없다.
- 백엔드는 **WebFlux + 코루틴 + R2DBC** 다 (D-019). `./gradlew check` 통과: ktlint, 테스트 87개(통합 80, 단위 7), PostgreSQL 18 Testcontainers.
- 프론트엔드는 `npm run lint`, `npm run typecheck`, `npx next build` 통과. **자동 테스트는 없다** (Vitest, Playwright 는 설치만 됨).
- **M2a(설정 화면과 이미지 업로드)가 구현됐다** ([features/M2a-settings-and-images.md](features/M2a-settings-and-images.md)). 백엔드는 테스트로 확인했고, **화면은 아무도 아직 확인하지 않았다** (에이전트는 사용자 계정의 비밀번호를 몰라 로그인 후 화면을 볼 수 없었다).
- **M3 1단계(구성원 관리, 프로젝트, 프로젝트 권한)가 구현됐다** ([features/M3-projects-and-issues.md](features/M3-projects-and-issues.md)). 팀 개념은 없어지고 프로젝트로 합쳐졌다 (D-032). 2~4단계(프리셋, 문서형 이슈, 목록, 댓글)는 명세만 있다.
- **이슈를 만들고, 목록에서 보고, 문서 편집기로 본문을 쓸 수 있다** (M3 2·3단계의 핵심). 화면 구조는 Linear 의 현재 UI를 따라 다시 짰다 (D-039). 무엇이 되고 안 되는지는 [features/M3-projects-and-issues.md](features/M3-projects-and-issues.md) 의 "지금 구현된 범위".
- 실시간 동기화(WebSocket)는 설계만 있다 (D-024, [02-architecture.md](02-architecture.md) "실시간 동기화"). M3 에서 구현한다.
- 개발 DB(`flowbit`)에는 사용자가 만든 관리자 계정이 있다. 지우지 않는다.

## 다음 작업

**다음 차례는 M3 의 남은 것이다**: 라벨, 필터, 완료 조건 진행률, 댓글과 변경 이력, 실시간 반영, 낙관적 업데이트, 편집기의 이미지 블록 (명세의 "아직 없는 것"). 사용자가 순서를 정해 주기 전에는 하나를 임의로 고르지 않는다.

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

### M3. 프로젝트와 이슈

- [x] 1단계 백엔드: `V3` 마이그레이션, 구성원 관리 API(`/api/v1/admin/members`), 사람 목록(`/api/v1/users`), 프로젝트와 프로젝트 구성원 API, 접근 판단(`ProjectService.access`), 통합 테스트 21개
- [x] 1단계 프론트엔드: `/settings/members`, 홈의 프로젝트 목록, 사이드바 프로젝트 목록, 새 프로젝트 대화상자, `/project/{key}`, `/project/{key}/settings`
- [ ] 1단계 화면 확인 (에이전트는 로그인할 수 없어 보지 못했다)
- [ ] 2단계: 프리셋, 문서형 이슈 쓰기
- [ ] 3단계: 이슈 목록, 상태 변경, 실시간
- [ ] 4단계: 댓글, 변경 이력

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
- [ ] 프로젝트 속성의 백엔드 통합 테스트와 화면 확인 (달력 팝오버 포함) (사용자가 "작업 뒤 테스트하지 말 것"을 지정해 하지 않았다)

## 화면을 직접 확인하는 방법 (사용자 계정 없이)

개발 DB 의 계정은 사용자의 것이라 에이전트는 로그인할 수 없다. 대신 테스트 전용 DB 로 서버를 따로 띄운다.

1. `flowbit_e2e` DB 를 비운다 (`docker compose exec -T postgres psql -U flowbit -d postgres` 에서 drop/create).
2. 백엔드: `./gradlew bootJar` 뒤 환경 변수 `FLOWBIT_DB_NAME=flowbit_e2e`, `SERVER_PORT=8081`, `FLOWBIT_STORAGE_PATH=build/e2e-uploads` 로 jar 실행 (JDK 25 는 `~/.jdks/corretto-25.*`).
3. 프론트엔드: `BACKEND_URL=http://localhost:8081` 로 `next build` 후 `next start -p 3100`. (개발 서버는 같은 폴더에서 두 개를 띄울 수 없다.)
4. `http://localhost:3100` 에서 초기 설정부터 한다. 데이터는 API 로 넣는 편이 빠르다.
5. 끝나면 두 서버를 끄고, **`BACKEND_URL` 없이 `next build` 를 다시 한다** (rewrite 주소가 빌드에 들어가기 때문).

브라우저 창이 가려져 있으면 화면 캡처가 자주 실패한다. 그때는 페이지 글자를 읽어 확인한다.

## 알아둘 것 (코드를 고치기 전에)

- **블로킹 코드 금지.** 백엔드는 WebFlux 다. JDBC/JPA/`.block()` 을 쓰지 않는다. 규칙은 [03-backend-guide.md](03-backend-guide.md) 맨 위.
- R2DBC 엔티티는 변경 감지가 없다. 값을 바꾸면 `save` 를 직접 부르고, 새 행은 `R2dbcEntityTemplate.insert` 로 넣는다.
- `@AuthenticationPrincipal` 로 받는 `AuthenticatedUser` 에 `java.security.Principal` 을 구현하면 타입 오류가 난다 (실제로 겪었다).
- shadcn 은 Base UI 기반이다. `asChild` 가 아니라 `render` prop 을 쓴다.
- Next.js 16: `middleware.ts` 가 아니라 `src/proxy.ts`. `cacheComponents` 가 켜져 있어 요청별 데이터를 읽는 서버 컴포넌트는 `<Suspense>` 안에 둔다.
- 개발 DB 를 지우고 초기 설정부터 다시 하려면 **Next 서버도 다시 시작**해야 한다. `proxy.ts` 가 "초기 설정 완료"를 메모리에 기억한다.
- Next 개발 서버를 켠 채로 `providers.tsx` 같은 최상위 클라이언트 모듈을 새로 만들면 "No QueryClient set" 오류가 날 수 있다. 개발 서버를 다시 시작하면 사라진다.
- 프론트엔드에 `npm install` 로 패키지를 추가할 때 peer 충돌이 나면 `@types/node` 버전부터 확인한다 (Vitest 가 22 이상을 요구해서 22 로 맞췄다).
- Gradle 은 JDK 17 이상으로 실행해야 한다. 이 PC 의 기본 `JAVA_HOME` 은 JDK 11 이라, 에이전트는 `JAVA_HOME` 을 `~/.jdks/corretto-21.0.12.1` 로 지정해서 실행했다.
- 같은 저장소를 다른 세션이나 에디터가 동시에 고치고 있을 수 있다. 문서를 통째로 다시 쓰기 전에 현재 내용을 다시 읽는다.

## 열린 질문

[09-decisions.md](09-decisions.md) 의 "미결정" 표 참고. 작업 중 새로 생긴 질문은 아래에 적는다.

- 사용자가 "실시간 기능을 추가했는데 설명 편집이 다른 클라이언트에 반영되지 않는다"고 했다. 그러나 저장소(원격 `main` 포함)에는 WebSocket 코드가 없다. 사용자의 로컬 변경이 아직 올라오지 않은 것으로 보인다. 요청 내용: 설명 편집·설정 변경이 다른 클라이언트에 바로 반영, 같은 화면을 보는 사람의 아바타를 오른쪽 위에 표시(마우스를 올리면 이름).

## 작업 기록

최신 항목을 위에 추가한다. 한 번의 작업 세션마다 한 항목.

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

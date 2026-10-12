# 04. 프론트엔드 가이드 (Next.js + shadcn/ui)

화면의 모양과 동작의 기준은 [01-product.md](01-product.md) 다(3~5장: 내비게이션, 화면별 설계, 디자인 시스템). 이 문서는 그것을 코드로 옮기는 규칙이다.

UI 작업을 시작하기 전에 [07-skills-guide.md](07-skills-guide.md) 의 "프론트엔드" 항목에서 해당 스킬을 읽는다. shadcn 컴포넌트를 다룰 때는 `agent-skills/shadcn-ui-ui/skills/shadcn/SKILL.md` 를 먼저 본다. Next.js 16 은 이전 버전과 다른 점이 많으므로 `frontend/node_modules/next/dist/docs/` 의 해당 문서를 확인하고 쓴다(`frontend/AGENTS.md`).

## 폴더 구조

```
frontend/
├─ src/
│  ├─ proxy.ts               페이지 요청의 리다이렉트 규칙 (Next.js 16 에서 middleware 의 새 이름)
│  ├─ app/
│  │  ├─ (public)/           로그인 없이 보는 화면: setup, login, unavailable
│  │  ├─ (app)/              로그인 후 화면. layout 이 사용자를 확인하고 AppShell(사이드바)을 그린다
│  │  │  ├─ settings/        내 설정 (개인이 정하는 것: 프로필, 화면 테마)
│  │  │  ├─ admin/           관리자 설정 (워크스페이스, 구성원). 내 설정과 섞지 않는다
│  │  │  ├─ project/[id]/   프로젝트
│  │  │  ├─ loading.tsx      페이지를 옮길 때 바로 보이는 스켈레톤
│  │  │  └─ error.tsx        데이터를 못 불러왔을 때
│  │  ├─ session-expired/    쓸 수 없게 된 세션 쿠키를 지우고 로그인으로 보낸다
│  │  ├─ layout.tsx          lang="ko", Providers
│  │  └─ globals.css         디자인 토큰 (기준: docs/01-product.md 5장)
│  ├─ components/
│  │  ├─ ui/                 shadcn CLI 가 생성한 컴포넌트 (직접 만든 파일을 두지 않는다)
│  │  ├─ brand/, auth/       여러 화면에서 쓰는 조합 컴포넌트
│  │  └─ providers.tsx       테마, TanStack Query
│  ├─ features/<기능>/       기능별 코드: types.ts, schema.ts, api.ts, components/
│  ├─ lib/
│  │  ├─ api/                브라우저용 API 클라이언트(client.ts), 오류 타입(errors.ts)
│  │  ├─ backend.ts          서버에서 백엔드를 직접 부르는 함수 (proxy.ts, 서버 컴포넌트용)
│  │  ├─ current-user.ts     현재 사용자 (요청당 한 번만 조회)
│  │  └─ safe-next-path.ts   로그인 후 이동할 경로 검증
│  └─ messages/              UI 문구 (ko.ts)
└─ next.config.ts            /api → 백엔드 rewrite
```

한 화면에서만 쓰는 컴포넌트는 그 라우트 폴더 안에 `_components/` 로 둔다.

## 서버 컴포넌트와 클라이언트 컴포넌트

- 기본은 서버 컴포넌트다. 상태, 이벤트 핸들러, 브라우저 API 가 필요한 부분만 `"use client"` 로 분리한다.
- `cacheComponents` 가 켜져 있다. 쿠키나 검색 파라미터처럼 요청마다 달라지는 값을 읽는 서버 컴포넌트는 `<Suspense>` 안에 있어야 한다. `(app)/layout.tsx` 가 그 경계이고, 대체 화면은 실제 배치와 같은 스켈레톤이다.
- 클라이언트 컴포넌트에서 검색 파라미터가 필요하면 `useSearchParams()` 대신 이벤트 시점에 `window.location.search` 를 읽는 편이 단순하다(Suspense 경계가 필요 없다).
- 폼은 react-hook-form + zod 로 만든다. 검증 스키마는 `features/<기능>/schema.ts` 에 둔다.
- 화면 이동은 `router.replace()`/`router.push()` 를 쓴다. 로그인·로그아웃처럼 세션이 바뀐 직후에는 `router.refresh()` 도 불러 캐시를 비운다.

## 속도 (Linear식 핵심 원칙)

- 사용자 조작으로 일어나는 변경은 **낙관적 업데이트**가 기본이다: TanStack Query 의 `onMutate` 에서 캐시를 먼저 바꾸고, 실패하면 `onError` 에서 되돌리고 토스트로 알린다. 변경 중에 스피너를 보여주지 않는다.
- 예외는 로그인과 초기 설정처럼 결과를 미리 알 수 없는 요청이다. 이때는 버튼을 비활성화하고 진행 중 문구를 보여준다.
- 목록은 캐시에서 즉시 그리고 뒤에서 새로 고친다. 스켈레톤은 캐시가 없는 첫 로딩에만 쓴다.
- 변경 요청은 자동 재시도하지 않는다(`mutations: { retry: false }`). 로그인 재시도는 잠금 횟수에 들어간다.
- **다른 사람의 변경은 WebSocket 이벤트로 받아 같은 캐시에 합친다** (M3 부터, [02-architecture.md](02-architecture.md) "실시간 동기화"). 화면은 캐시만 보므로 내 변경과 남의 변경이 같은 경로로 그려진다. 연결은 `(app)` 레이아웃에서 하나만 열고, 끊겨도 화면이 멈추지 않아야 한다.

### 페이지 이동이 느려지지 않게 하는 규칙 (2026-10-12 측정 후 정함)

- **`proxy.ts` 에서 백엔드를 부르지 않는다.** 모든 페이지 요청 앞에서 실행되므로 여기서 쓰는 시간은 전부 대기 시간이 된다. 세션 쿠키가 있는지만 보고, 쿠키가 실제로 유효한지는 페이지가 데이터를 읽을 때(`requireUser`) 확인한다. 초기 설정 완료 여부는 한 번 확인하면 메모리에 두되, **세션 쿠키가 없는 요청에서는 매번 다시 묻는다**(로그인 화면과 첫 방문뿐이라 드물다. 개발 DB 를 지우고 다시 시작했을 때 `/login` 이 아니라 `/setup` 으로 가게 하려는 것이다).
- 유효하지 않은 쿠키는 `requireUser` 가 `/session-expired` 로 보내고, 거기서 쿠키를 지운 뒤 로그인으로 간다. 서버 컴포넌트에서 `redirect("/login")` 을 직접 쓰지 않는다(쿠키가 남아 있으면 proxy 가 다시 돌려보내 무한히 돈다).
- 한 요청 안에서 같은 데이터를 두 번 가져오지 않는다. 여러 곳에서 쓰는 조회는 `lib/current-user.ts` 에 React `cache()` 로 감싸 둔다(`getCurrentUser`, `getWorkspace`, `getProjects`). 서로 무관한 조회는 `Promise.all` 로 함께 보낸다.
- `(app)/loading.tsx` 가 있어서 링크를 누르면 내용 영역이 바로 스켈레톤으로 바뀐다. 데이터를 오래 읽는 구역을 새로 만들면 그 구역에 맞는 `loading.tsx` 를 둔다.
- **비밀번호를 받는 폼은 `method="post"` 를 단다.** 스크립트가 붙기 전에(느린 로딩, 개발 서버의 다른 호스트 차단) 사용자가 제출하면 브라우저가 폼을 직접 보내는데, `method` 가 없으면 GET 이라 아이디와 비밀번호가 주소에 들어간다(실제로 겪었다). `proxy.ts` 는 주소에 `password` 가 있으면 지우고 다시 보낸다.
- **목록의 필터처럼 주소로 열 수 있는 화면 상태는 `useState` 가 아니라 주소(`useSearchParams`)에 둔다.** Next 16 은 다녀간 화면을 살려 두므로, 처음 값만 받는 `useState` 는 다른 주소로 다시 들어와도 예전 값을 보여 줄 수 있다(마일스톤 필터에서 겪었다). 바꿀 때는 `window.history.replaceState` 로 주소만 바꾼다(Next 가 `useSearchParams` 와 맞춰 주고 서버를 다시 부르지 않는다).
- **레이아웃은 최상위에서 `params` 나 백엔드 데이터를 `await` 하지 않는다.** `cacheComponents` 에서는 같은 레이아웃 안에서 이동할 때(예: 프로젝트의 개요 → 이슈 탭) 그 레이아웃 아래에 `<Suspense>` 가 없으면 "uncached data ... outside of `<Suspense>`" 오류가 난다. 데이터를 읽는 부분은 별도 비동기 컴포넌트로 빼서 `<Suspense>` 로 감싸고, 그 레이아웃 바로 아래 페이지들을 위해 같은 폴더에 `loading.tsx` 를 둔다 (`project/[id]/layout.tsx`, `project/[id]/loading.tsx` 가 예).
- **`serverGet` 은 같은 주소를 한 요청에서 한 번만 부른다** (React `cache()`). 레이아웃, 페이지, `generateMetadata` 가 같은 것을 각자 읽어도 백엔드 호출은 하나다(프로젝트, 이슈, 라벨, 사용자 목록). 그래서 돌려받은 값은 여럿이 함께 쓰는 것이니 고치지 않는다(`map`/`filter` 로 새로 만든다).
- **열어야 쓰는 무거운 것은 `next/dynamic` 으로 늦게 불러온다** (D-099). 문서 편집기를 품은 새 이슈 창(`new-issue-dialog.tsx`), 달력(`components/ui/calendar.tsx`, react-day-picker), 프로젝트 만들기 폼(`create-project-form.tsx`, react-hook-form·zod), 이슈 목록의 목표·캘린더 보기가 그렇다. 사이드바나 목록처럼 모든 화면에 깔리는 컴포넌트에서 이런 것을 정적으로 import 하지 않는다. 버튼에 포인터나 포커스가 오면 미리 받아 두고(`onPointerEnter`/`onFocus` 에서 같은 `import()` 호출), 처음 열 때부터 렌더한다. 늦게 붙는 컴포넌트는 "처음 렌더될 때 이미 열려 있을 수 있다"는 것을 전제로 쓴다(초기값을 열림 상태에서도 채우기, 포커스는 마운트 뒤 직접 주기).
- **긴 목록의 줄은 `memo` 한 컴포넌트로 두고, 걸러 내기·묶기는 `useMemo` 로 한다** (`issues-view.tsx` 의 `IssueListRow`). 검색어는 `useDeferredValue` 로 넘겨 입력란이 목록을 기다리지 않게 한다. 줄에 넘기는 콜백은 `useCallback` 으로 고정한다(안 그러면 `memo` 가 소용없다).
- 개발 모드(`npm run dev`)는 처음 여는 화면마다 1~2초 컴파일하고 링크 미리 가져오기도 하지 않는다. 속도를 판단할 때는 `npm run build && npm run start` 로 본다. 측정값: `/login` 응답이 개발 모드 약 75ms, 프로덕션 약 13ms.

## 화면을 만들 때 쓰는 조각 (D-039)

| 조각 | 파일 | 쓰는 곳 |
|---|---|---|
| 경로 표시 | `components/page-header.tsx` | 모든 화면의 맨 위. 마지막 항목이 그 화면의 `<h1>` 이다 |
| 알약 탭 | `components/pill-tabs.tsx` | 한 대상의 여러 보기(개요 / 이슈 / 설정) |
| 속성 카드 | `components/property-card.tsx` | 오른쪽 열의 카드와 그 안의 "이름: 값" 줄 |
| 속성 칩 | `features/issues/components/chip-select.tsx`, `date-chips.tsx` | 값을 보여 주고 눌러서 바꾸는 둥근 칩 |
| 날짜 칩 | `features/issues/components/date-chips.tsx` | `DateChip`(날짜 하나: 기한), `DateRangeChip`(기간: 첫 클릭이 시작일, 두 번째 클릭이 종료일). 달력은 `components/ui/calendar.tsx`(react-day-picker, 한국어), 팝오버는 `components/ui/popover.tsx` (D-043) |
| 문서 편집기 | `components/editor/rich-text-editor.tsx` | 프로젝트 설명, 이슈 본문 |
| 자동 저장 | `lib/use-autosave.ts`, `components/editor/save-status.tsx` | 저장 버튼 없이 저장하는 모든 곳 |
| 사람 | `components/person.tsx` | `personName()`(표시 이름. `displayName ?? loginId` 를 직접 쓰지 않는다), `PersonAvatar`(작은 아바타), `PersonMark`(아바타 + 이름 툴팁), `Person`(아바타·이름·아이디) |
| 이슈 줄의 조각 | `features/issues/components/issue-row.tsx` | `PriorityMark`, `StatusMark`, `IssueTitleLink`, `IssueProjectName`, `IssueLabels`, `IssueDue`. 이슈를 한 줄로 그리는 곳(목록, 하위 이슈)은 이것을 조합한다 |
| 내비게이션 항목 | `app/(app)/_components/nav-destinations.ts` | 사이드바와 좁은 화면 메뉴가 함께 쓰는 목적지 목록, `isCurrentPath()` |

- **자주 고치는 내용은 설정 화면에 넣지 않는다.** 보이는 그 자리에서 고치고 자동 저장한다(프로젝트 요약·설명, 이슈 제목·본문). 설정 화면에는 드물게 바꾸는 것(이름, 구성원)만 둔다.
- 편집기에 새 블록을 추가하려면 세 곳을 함께 고친다: 편집기의 `/` 메뉴(`SLASH_ITEMS`), 백엔드의 허용 목록(`RichText.kt` 의 `NODE_TYPES`), 글꼴·간격(`globals.css` 의 `.rich-text`).
- 표는 `components/editor/table-extensions.ts`(칸의 `align`, `verticalAlign` 속성)와 `table-toolbar.tsx`(커서가 표 안에 있을 때 뜨는 도구 모음)에 있다. 칸에 새 속성을 더하면 `RichText.kt` 의 칸 검증도 함께 고친다.
- 편집기의 이미지는 `components/editor/upload-image.ts` 로 먼저 올리고 받은 주소를 `image` 블록에 넣는다. 밖의 주소나 base64 는 넣지 않는다(서버가 거부한다).
- 지금 시각이 있어야 그릴 수 있는 화면(캘린더의 "이번 달")은 `useInBrowser()`(`components/dates.tsx`)로 브라우저에서만 그린다.
- **렌더 중에 현재 시각을 읽지 않는다** (`Date.now()`, `new Date()`). `cacheComponents` 에서는 서버 렌더 중의 시각 읽기가 오류다("unstable value `Date.now()` while prerendering"). "3분 전"이나 "올해면 연도 생략"처럼 지금 시각에 기대는 글자는 `components/dates.tsx` 의 `<TimeAgo>`, `<DueDate>` 로 그린다(서버는 날짜 그대로, 브라우저가 바꿔 보여 준다).
- **대화상자로 여는 페이지**는 가로채기 경로로 만든다. `(app)/layout.tsx` 가 `modal` 슬롯을 그리고, `(app)/@modal/(.)issue/[key]/page.tsx` 가 대화상자다(앱 어느 화면에서 이슈를 눌러도 그 위에 뜬다). 슬롯에는 `default.tsx` 와 `page.tsx`(모두 null)를 둔다. **슬롯에 `[...catchAll]` 을 두지 않는다**: `(app)` 은 주소에 나타나지 않는 묶음이라 그 catch-all 이 `/setup`, `/unavailable` 같은 `(public)` 의 주소까지 가로채 404 를 만든다(실제로 겪었다). 대신 대화상자(`issue-dialog.tsx`)가 주소를 보고, `/issue/…` 가 아니면 스스로 사라진다. 닫기는 `router.back()`. 주소를 직접 연 경우(`issue/[key]/page.tsx`)는 전체 이슈 목록(`all-issues.tsx`)을 뒤에 그리고 같은 대화상자를 띄우며, 닫으면 `/issues` 로 간다. 그 대화상자 안에서 다른 이슈(하위 이슈)를 누르면 가로챈 대화상자가 뜨므로, 페이지의 대화상자는 자기 주소가 아닐 때 숨는다(`path`).
- **대화상자 뒤의 목록은 자기 주소를 기억한다.** 이슈를 대화상자로 열면 브라우저 주소가 `/issue/…` 로 바뀌어 목록의 보기(`?view=calendar`)와 필터가 주소에서 사라진다. 목록은 `usePathname`/`useSearchParams` 를 직접 읽지 않고 `useListAddress()`(`issue-views.tsx`)로 읽는다: 주소가 `/issue/…` 인 동안에는 마지막으로 보던 목록 주소를 돌려준다.
- 편집기나 팝오버 안에서 `Esc` 로 닫는 것(`/` 메뉴 등)은 그 키 이벤트를 `stopPropagation()` 한다. 안 그러면 바깥의 대화상자까지 닫힌다.
- 경로 표시(`PageHeader`)와 알약 탭(`PillTabs`)은 `sticky` 로 내용 패널 위쪽에 붙어 있다(`z-30`). 내용 안에서 `sticky` 를 쓰면 그보다 낮은 `z-index` 를 준다.
- 사진 없는 아바타의 글자는 `lib/initials.ts`: 사람은 `initials()`(한글 이름은 뒤 두 글자), 24px 보다 작은 아바타는 `initial()`(한 글자), 사람이 아닌 것(워크스페이스)은 `mark()`(앞 글자).
- 같은 폴더 안에서 페이지끼리 오가면(이슈 상세 ↔ 이슈 목록) 그 폴더에도 `loading.tsx` 가 있어야 한다(`project/[id]/issues/loading.tsx`). 없으면 "uncached data ... outside of `<Suspense>`" 오류가 난다.
- 편집기 위에 띄우는 것(`/` 메뉴, 표 도구 모음)은 `position: fixed` 로 두되 좌표에서 `fixedOrigin()`(`components/editor/fixed-origin.ts`)을 뺀다. 대화상자는 `transform` 으로 가운데에 놓이기 때문에 그 안의 `fixed` 는 창이 아니라 대화상자 모서리가 기준이다. 빼지 않으면 새 이슈 창에서 메뉴가 엉뚱한 곳에 뜬다.
- 편집기는 `RichTextEditor` 하나뿐이다(개요 설명, 이슈 본문, 새 이슈 창). 블록이나 도구를 더하면 세 곳 모두에 생긴다.
- 편집기 내용을 밖에서 바꿔야 하면(프리셋 적용 등) `key` 를 바꿔 새로 시작시킨다.
- 대화상자는 열릴 때 커서가 갈 곳을 `DialogContent` 의 `initialFocus` 로 지정한다. 입력란의 `autoFocus` 는 Base UI 대화상자 안에서 믿을 수 없다.
- 한 글자 단축키(`C`)는 `event.code` 로 판단하고, 입력란에 포커스가 있거나 대화상자가 열려 있으면 무시한다(`issues-view.tsx` 참고).

## 키보드

- 전역·목록 단축키는 [01-product.md](01-product.md) 3장과 4장의 표를 따른다. 새 단축키를 만들면 그 표에 먼저 추가한다.
- 단축키는 입력란에 포커스가 있을 때 동작하지 않게 하고, 한글 입력 상태에서도 같은 키로 동작하게 `event.code` 로 판단한다.
- 한글 조합 중의 `Enter` 는 무시한다(`event.nativeEvent.isComposing`).
- 단축키가 있는 액션은 메뉴와 툴팁에 `Kbd` 컴포넌트로 키를 표시한다.

## 백엔드 호출

- 브라우저는 항상 같은 출처의 `/api/v1/...` 를 부른다. Next.js 가 백엔드로 넘긴다 ([02-architecture.md](02-architecture.md)).
- 브라우저 쪽 호출은 `lib/api/client.ts` 의 `apiFetch` 하나를 거친다. 컴포넌트에서 `fetch` 를 직접 쓰지 않는다. `apiFetch` 가 CSRF 헤더 추가(토큰이 없으면 먼저 받아 옴, 거절되면 한 번 재시도), 문제 문서 → `ApiError` 변환, 네트워크 실패 → `NetworkError` 변환을 맡는다.
- 파일 하나를 올리는 요청의 본문은 `fileForm(file)`(`lib/api/client.ts`)로 만든다.
- 프로젝트에 속하지 않은 이슈의 담당자 후보(전체 사용자)는 `usersAsMembers()`(`features/projects/types.ts`)로 구성원 모양으로 바꾼다.
- 분기는 `ApiError.code` 로 한다. 서버가 준 문구로 분기하지 않는다.
- 요청/응답 타입은 지금은 `features/<기능>/types.ts` 에 직접 적는다. 엔드포인트가 늘어나면 OpenAPI 에서 생성하는 방식으로 바꾼다 (D-023).
- 서버 오류의 필드별 메시지(`errors[]`)는 해당 폼 필드 아래에 표시한다. 필드와 무관한 오류는 폼 위의 `Alert` 로 보여준다.
- 클라이언트 검증은 편의 기능이다. 같은 규칙을 백엔드도 반드시 검증한다.

## shadcn/ui 규칙

- 스타일은 `base-nova`, 프리미티브는 **Base UI** 다. 트리거를 다른 컴포넌트로 그릴 때 `asChild` 가 아니라 `render={<Button />}` 를 쓴다.
- 컴포넌트는 shadcn CLI 로 추가한다(`npx shadcn@latest add <이름>`). 이미 있는 컴포넌트를 새로 만들지 않는다.
- 폼은 `FieldGroup` + `Field` + `FieldLabel` + `FieldDescription`/`FieldError` 로 짠다. 오류일 때 `Field` 에 `data-invalid`, 입력에 `aria-invalid` 를 둘 다 준다.
- 입력 안의 버튼은 `InputGroup` + `InputGroupAddon` + `InputGroupButton` 으로 만든다(예: 비밀번호 보기).
- 안내는 `Alert`, 빈 화면은 `Empty`, 로딩 자리는 `Skeleton`, 버튼의 진행 표시는 `Spinner` + `data-icon`.
- `className` 은 배치(크기, 간격, 정렬)에만 쓴다. 색과 글자 모양을 덮어쓰지 않는다. 색은 의미 토큰(`bg-primary`, `text-muted-foreground`)만 쓰고 `dark:` 색 지정을 직접 쓰지 않는다.
- 세로 쌓기는 `flex flex-col gap-*`. `space-y-*` 를 쓰지 않는다.
- `components/ui/` 의 파일은 꼭 필요할 때만 고친다.
- 아이콘은 lucide-react 하나만 쓴다. 컴포넌트 안의 아이콘에는 크기 클래스를 주지 않는다.

## 디자인 원칙

[01-product.md](01-product.md) 5장이 기준이다. 코드에서 특히 지킬 것:

- **토큰만 쓴다.** 컴포넌트에 임의의 hex 값을 넣지 않는다. 새 색이 필요하면 `globals.css` 와 01-product 5장에 함께 추가한다.
- **모든 상태를 만든다.** 로딩, 빈 상태, 오류, 비활성, 긴 텍스트, 데이터가 아주 많을 때.
- **없는 것을 그리지 않는다.** 아직 구현하지 않은 메뉴, 가짜 숫자, 자리만 차지하는 장식을 넣지 않는다.
- **애니메이션은 드문 동작에만.** 자주 반복되는 동작은 즉시 반응한다. `transition-all` 을 쓰지 않고 바뀌는 속성만 지정한다.
- **주 버튼은 한 화면에 하나.**
- 라이트/다크를 함께 만든다. 기본은 라이트.

## 문구

- 문구는 컴포넌트에 직접 쓰지 않고 `messages/ko.ts` 에 둔다. 지금은 한국어만 있고 다국어 라이브러리는 쓰지 않는다(파일 하나를 직접 import). 영어를 추가할 때 도입한다.
- 한국어로 처음부터 쓴다. 간결한 합니다체와 명사형. 버튼은 동작을 말한다("관리자 계정 만들기"). 오류는 무엇이 잘못됐고 무엇을 하면 되는지 말한다. 사과하지 않는다.
- 같은 동작은 화면 어디서나 같은 이름이다. 용어는 [01-product.md](01-product.md) 2장.

## 접근성

- WCAG 2.2 AA 를 기준으로 한다.
- 폼 필드에는 항상 연결된 레이블이 있다. 오류와 도움말은 `aria-describedby` 로 필드와 연결하고, 제출 실패 시 첫 오류 필드로 포커스를 옮긴다.
- 입력 테두리는 바탕과 3:1 이상 대비(`--input` 토큰).
- 색만으로 정보를 전달하지 않는다.
- 터치 화면에서 주요 조작 대상은 44px (`max-md:h-11`).

## 테스트와 검사

- 작업을 끝내기 전에 `npm run lint`, `npm run typecheck` 가 통과해야 한다.
- 단위 테스트(Vitest)와 E2E(Playwright)는 도구만 설치되어 있고 **아직 테스트가 없다.** 추가할 때 `package.json` 에 `test`, `test:e2e` 스크립트를 만들고 이 절을 고친다. E2E 는 개발 데이터를 건드리지 않도록 `flowbit_e2e` DB 를 쓰는 별도 백엔드 인스턴스로 돌린다.
- UI 를 바꿨으면 실제 브라우저에서 라이트/다크, 좁은 폭(360px)을 확인한다.

## 화면 완료 전 점검

1. 디자인 리뷰 스킬로 점검한다 ([07-skills-guide.md](07-skills-guide.md) 의 "리뷰" 항목).
2. 극단적인 데이터(아주 긴 이름, 빈 값, 이모지, 한글/영문 혼합)로 깨지지 않는지 확인한다.
3. 키보드만으로 흐름 전체를 끝까지 진행해 본다.

## 설정 폼, 서비스 워커 (M2b, M7a)

- **연동 설정 화면의 틀 (D-096)**: 서로 다른 설정을 한 페이지에 폼째로 늘어놓지 않는다. 항목마다 `components/settings-card.tsx` 의 `SettingsCard`(아이콘, 이름, 한 줄 설명, 켜짐 배지, 펼치기) 하나로 두고 처음에는 접어 둔다. 펼친 폼은 `FieldSection`(묶음 제목 + 두 열 격자)으로 나누고, 긴 칸은 `wide` 로 한 줄을 다 쓴다. 방식에 따라 칸이 달라지면 `SegmentedSetting` 으로 고르게 하고 해당하는 칸만 보여 준다. 잘 안 쓰는 값은 `AdvancedSettings` 안에, 저장·테스트 버튼은 맨 아래 `SettingsActions` 에 둔다. 자주 쓰는 대상의 값은 "예시 넣기" 버튼으로 채운다.
- 카드를 접어도 안의 폼은 사라지지 않는다(`hidden`). 입력하던 값이 남는다.
- 칸은 `components/setting-fields.tsx` 의 `TextSetting`·`CheckSetting`·`TextAreaSetting`·`SelectSetting` 을 쓴다. 검증은 서버가 하고(`fieldErrorsOf`), 비밀 값 칸은 비워 두면 "저장된 값 유지"다(빈 문자열 대신 `null` 을 보낸다).
- 서비스 워커는 `public/push-sw.js` 하나뿐이고 푸시 알림만 다룬다. 캐시나 요청 가로채기를 넣지 않는다. `proxy.ts` 는 확장자가 있는 주소를 건드리지 않으므로 그대로 내려간다.
- `firebase` 는 푸시를 켜는 순간에만 필요하다. 정적으로 import 하지 말고 `features/notifications/push.ts` 처럼 동적으로 불러온다.
- 브라우저에만 있는 상태(이 브라우저에서 푸시를 켰는지)는 `useSyncExternalStore` 로 읽는다. 렌더 중에 `localStorage` 나 `Notification` 을 직접 읽으면 서버 렌더와 어긋난다.


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
│  │  │  ├─ admin/           관리자 설정 (워크스페이스, 구성원, 로그인 연동, 알림, 백업 및 복원, 데이터 보관, Git 연동). `search/` 는 티켓·문서 검색 결과 페이지(D-133). 내 설정과 섞지 않는다. 폭은 max-w-2xl 이고, 두 단으로 짜는 화면은 뿌리에 data-wide 를 달아 넓힌다
│  │  │  ├─ project/[id]/   프로젝트
│  │  │  ├─ loading.tsx      페이지를 옮길 때 바로 보이는 스켈레톤
│  │  │  └─ error.tsx        데이터를 못 불러왔을 때
│  │  ├─ session-expired/    쓸 수 없게 된 세션 쿠키를 지우고 로그인으로 보낸다
│  │  ├─ layout.tsx          lang="ko", Providers
│  │  ├─ manifest.ts         웹 앱 매니페스트 (앱으로 설치, D-110)
│  │  ├─ apple-icon.png      iOS 홈 화면 아이콘 (scripts/generate-icons.mjs 가 만든다)
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
- 화면 이동은 `router.replace()`/`router.push()` 를 쓴다. 로그인 직후에는 `router.refresh()` 도 불러 캐시를 비운다.
- **로그아웃은 예외로 문서를 새로 연다** (`window.location.assign("/login")`, `user-menu.tsx`). Next 16 은 다녀간 화면을 상태째 살려 두므로, 앱 안에서 `/login` 으로 옮긴 뒤 다시 로그인하면 예전 화면이 그대로 돌아온다. 그래서 로그아웃 버튼이 "로그아웃함" 상태로 남아 계속 비활성이었고(2026-10-13 에 재현), 이전 사람의 캐시(TanStack Query, 실시간 연결)도 메모리에 남았다. **"한 번 눌렀으니 다시 못 누르게" 같은 상태를 `(app)` 레이아웃에 깔리는 컴포넌트의 `useState` 로 두지 않는다.**

## 속도 (Linear식 핵심 원칙)

- 사용자 조작으로 일어나는 변경은 **낙관적 업데이트**가 기본이다. 변경 중에 스피너를 보여주지 않고, 값을 고르는 컨트롤을 저장하는 동안 비활성으로 만들지 않는다. 방법은 아래 "변경을 화면에 반영하는 두 가지 훅".
- 예외는 로그인과 초기 설정처럼 결과를 미리 알 수 없는 요청이다. 이때는 버튼을 비활성화하고 진행 중 문구를 보여준다.
- 목록은 캐시에서 즉시 그리고 뒤에서 새로 고친다. 스켈레톤은 캐시가 없는 첫 로딩에만 쓴다.
- 변경 요청은 자동 재시도하지 않는다(`mutations: { retry: false }`). 로그인 재시도는 잠금 횟수에 들어간다.
- **다른 사람의 변경은 WebSocket 이벤트로 받아 같은 캐시에 합친다** (M3 부터, [02-architecture.md](02-architecture.md) "실시간 동기화"). 화면은 캐시만 보므로 내 변경과 남의 변경이 같은 경로로 그려진다. 연결은 `(app)` 레이아웃에서 하나만 열고, 끊겨도 화면이 멈추지 않아야 한다.

### 변경을 화면에 반영하는 두 가지 훅 (D-114)

화면의 데이터는 서버 컴포넌트가 읽어 props 로 내려준다. 그래서 변경 뒤에는 `router.refresh()` 로 다시 그리는데, 요청과 다시 그리기 사이에 **화면이 예전 값으로 남는 구간**이 생긴다. 이 구간을 그대로 두면 깜빡인다: 칩이 흐려졌다(비활성) 돌아오고, 예전 값이 잠깐 다시 보였다가 새 값으로 바뀌고, 고른 값으로 그리는 `<select>` 는 예전 값으로 튕겨 돌아간다(2026-10-13 에 프레임 단위로 기록해 확인했다). 둘 다 `lib/use-refreshing-mutation.ts` 에 있다.

- **`useOptimisticMutation(value, apply, mutationFn)`**: 그 자리에서 값을 고르는 변경(상태, 우선순위, 담당자, 날짜, 라벨, 역할, 구독, 이모지, 목록의 줄 이동). `value` 는 서버가 준 값이고, 돌려받는 `value` 는 거기에 아직 가는 중인 변경을 `apply` 로 얹은 값이다. 화면은 이 값을 그린다. React 의 `useOptimistic` 이라 새로 그린 화면이 도착하는 프레임에 한 번에 바뀌고, 서버가 거절하면 저절로 예전 값으로 돌아가며 `error` 에 이유가 담긴다. **이 훅을 쓰는 컨트롤에 `disabled={isPending}` 을 달지 않는다.** 변경은 만든 순서대로 하나씩 보낸다(나란히 보내면 뒤의 것이 먼저 도착해 앞의 값이 남을 수 있다).
- **`useRefreshingMutation(mutationFn, onSuccess)`**: 결과를 미리 그릴 수 없는 변경(만들기, 삭제, 올리기, 폼 저장). `isPending` 은 **다시 그린 화면이 도착할 때까지** 참이고, `onSuccess` 에서 바꾼 상태(입력란 비우기, 창 닫기)는 새 데이터와 **같은 프레임**에 반영된다. 그래서 보낸 댓글이 입력란에서는 사라졌는데 목록에는 아직 없는 순간이 없다. `onSuccess` 안에서 "지금 당장" 반영돼야 하는 것은 하지 않는다(전환 안에서 실행된다).
- 새 화면을 만들 때: 고르는 값이면 앞의 것, 만들거나 지우는 것이면 뒤의 것. `useMutation` 을 직접 쓰고 `onSuccess` 에서 `router.refresh()` 를 부르는 코드를 새로 만들지 않는다(위의 구간이 다시 생긴다). TanStack Query 로 읽은 데이터(`member-project-roles.tsx`)는 그 캐시를 `onMutate` 에서 먼저 바꾼다.
- `apply` 는 서버가 할 일을 흉내 내는 데까지만 한다. 지금 가진 데이터로 알 수 없는 것(다른 프로젝트의 목표 목록, 이모지를 단 사람 이름)은 비워 두고 새 데이터가 채우게 한다.
- 다시 그린 뒤에는 Next 가 화면의 링크들을 다시 미리 가져온다(변경 한 번에 요청 10개 안팎). 지금은 그대로 둔다. 느려지면 사이드바 링크의 미리 가져오기부터 줄인다.

### 페이지 이동이 느려지지 않게 하는 규칙 (2026-10-12 측정 후 정함)

- **`proxy.ts` 에서 백엔드를 부르지 않는다.** 모든 페이지 요청 앞에서 실행되므로 여기서 쓰는 시간은 전부 대기 시간이 된다. 세션 쿠키가 있는지만 보고, 쿠키가 실제로 유효한지는 페이지가 데이터를 읽을 때(`requireUser`) 확인한다. 초기 설정 완료 여부는 한 번 확인하면 메모리에 두되, **세션 쿠키가 없는 요청에서는 매번 다시 묻는다**(로그인 화면과 첫 방문뿐이라 드물다. 개발 DB 를 지우고 다시 시작했을 때 `/login` 이 아니라 `/setup` 으로 가게 하려는 것이다).
- 유효하지 않은 쿠키는 `requireUser` 가 `/session-expired` 로 보내고, 거기서 쿠키를 지운 뒤 로그인으로 간다. 서버 컴포넌트에서 `redirect("/login")` 을 직접 쓰지 않는다(쿠키가 남아 있으면 proxy 가 다시 돌려보내 무한히 돈다).
- 한 요청 안에서 같은 데이터를 두 번 가져오지 않는다. 여러 곳에서 쓰는 조회는 `lib/current-user.ts` 에 React `cache()` 로 감싸 둔다(`getCurrentUser`, `getWorkspace`, `getProjects`). 서로 무관한 조회는 `Promise.all` 로 함께 보낸다.
- `(app)/loading.tsx` 가 있어서 링크를 누르면 내용 영역이 바로 스켈레톤으로 바뀐다. 데이터를 오래 읽는 구역을 새로 만들면 그 구역에 맞는 `loading.tsx` 를 둔다.
- **비밀번호를 받는 폼은 `method="post"` 를 단다.** 스크립트가 붙기 전에(느린 로딩, 개발 서버의 다른 호스트 차단) 사용자가 제출하면 브라우저가 폼을 직접 보내는데, `method` 가 없으면 GET 이라 아이디와 비밀번호가 주소에 들어간다(실제로 겪었다). `proxy.ts` 는 주소에 `password` 가 있으면 지우고 다시 보낸다.
- **목록의 필터처럼 주소로 열 수 있는 화면 상태는 `useState` 가 아니라 주소(`useSearchParams`)에 둔다.** Next 16 은 다녀간 화면을 살려 두므로, 처음 값만 받는 `useState` 는 다른 주소로 다시 들어와도 예전 값을 보여 줄 수 있다(마일스톤 필터에서 겪었다). 바꿀 때는 `useReplaceListQuery()`(`issue-views.tsx`)를 쓴다: `window.history.replaceState` 로 주소를 바로 바꾸고(화면이 기다리지 않는다) **이어서 `router.replace` 로 라우터에도 같은 주소를 알린다.** `history` 만 바꾸면 라우터는 예전 주소에 있다고 믿는다. 그 상태에서 티켓을 대화상자로 열고 무엇이든 저장하면 `router.refresh()` 의 응답이 라우터가 아는 화면과 맞지 않아 Next 가 문서를 통째로 다시 불러온다: 화면이 깜빡이고, 뒤의 목록이 보기·필터를 잃고 전체 티켓 목록으로 돌아온다(2026-10-13 에 재현하고 고쳤다). **주소를 `history` 로만 바꾸는 코드를 새로 만들지 않는다.**
- **레이아웃은 최상위에서 `params` 나 백엔드 데이터를 `await` 하지 않는다.** `cacheComponents` 에서는 같은 레이아웃 안에서 이동할 때(예: 프로젝트의 개요 → 티켓 탭) 그 레이아웃 아래에 `<Suspense>` 가 없으면 "uncached data ... outside of `<Suspense>`" 오류가 난다. 데이터를 읽는 부분은 별도 비동기 컴포넌트로 빼서 `<Suspense>` 로 감싸고, 그 레이아웃 바로 아래 페이지들을 위해 같은 폴더에 `loading.tsx` 를 둔다 (`project/[id]/layout.tsx`, `project/[id]/loading.tsx` 가 예).
- **`serverGet` 은 같은 주소를 한 요청에서 한 번만 부른다** (React `cache()`). 레이아웃, 페이지, `generateMetadata` 가 같은 것을 각자 읽어도 백엔드 호출은 하나다(프로젝트, 티켓, 라벨, 사용자 목록). 그래서 돌려받은 값은 여럿이 함께 쓰는 것이니 고치지 않는다(`map`/`filter` 로 새로 만든다).
- **열어야 쓰는 무거운 것은 `next/dynamic` 으로 늦게 불러온다** (D-099). 문서 편집기를 품은 새 티켓 창(`new-issue-dialog.tsx`), 달력(`components/ui/calendar.tsx`, react-day-picker), 프로젝트 만들기 폼(`create-project-form.tsx`, react-hook-form·zod), 티켓 목록의 목표·캘린더 보기가 그렇다. 사이드바나 목록처럼 모든 화면에 깔리는 컴포넌트에서 이런 것을 정적으로 import 하지 않는다. 버튼에 포인터나 포커스가 오면 미리 받아 두고(`onPointerEnter`/`onFocus` 에서 같은 `import()` 호출), 처음 열 때부터 렌더한다. 늦게 붙는 컴포넌트는 "처음 렌더될 때 이미 열려 있을 수 있다"는 것을 전제로 쓴다(초기값을 열림 상태에서도 채우기, 포커스는 마운트 뒤 직접 주기).
- **긴 목록의 줄은 `memo` 한 컴포넌트로 두고, 걸러 내기·묶기는 `useMemo` 로 한다** (`issues-view.tsx` 의 `IssueListRow`). 검색어는 `useDeferredValue` 로 넘겨 입력란이 목록을 기다리지 않게 한다. 줄에 넘기는 콜백은 `useCallback` 으로 고정한다(안 그러면 `memo` 가 소용없다).
- 개발 모드(`npm run dev`)는 처음 여는 화면마다 1~2초 컴파일하고 링크 미리 가져오기도 하지 않는다. 속도를 판단할 때는 `npm run build && npm run start` 로 본다. 측정값: `/login` 응답이 개발 모드 약 75ms, 프로덕션 약 13ms.

## 화면을 만들 때 쓰는 조각 (D-039)

| 조각 | 파일 | 쓰는 곳 |
|---|---|---|
| 경로 표시 | `components/page-header.tsx` | 모든 화면의 맨 위. 마지막 항목이 그 화면의 `<h1>` 이다 |
| 알약 탭 | `components/pill-tabs.tsx` | 한 대상의 여러 보기(개요 / 티켓 / 설정) |
| 속성 카드 | `components/property-card.tsx` | 오른쪽 열의 카드와 그 안의 "이름: 값" 줄 |
| 속성 칩 | `features/issues/components/chip-select.tsx`, `date-chips.tsx` | 값을 보여 주고 눌러서 바꾸는 둥근 칩 |
| 날짜 칩 | `features/issues/components/date-chips.tsx` | `DateChip`(날짜 하나: 기한), `DateRangeChip`(기간: 첫 클릭이 시작일, 두 번째 클릭이 종료일). 달력은 `components/ui/calendar.tsx`(react-day-picker, 한국어), 팝오버는 `components/ui/popover.tsx` (D-043) |
| 찾아서 고르는 목록 | `components/multi-combobox.tsx` | 팝오버 안에서 입력으로 좁히고 고르는 목록: 티켓 라벨, 프로젝트 라벨, 프로젝트 구성원 추가. 항목에 `hint`(같이 검색되는 흐린 글자), 목록 아래에 `footer`(고른 것에 함께 적용할 값)를 줄 수 있다. 고르는 일에 대화상자를 새로 만들지 않는다 |
| 문서 편집기 | `components/editor/rich-text-editor.tsx` | 프로젝트 설명, 티켓 본문 |
| 자동 저장 | `lib/use-autosave.ts`, `components/editor/save-status.tsx` | 저장 버튼 없이 저장하는 모든 곳 |
| 사람 | `components/person.tsx` | `personName()`(표시 이름. `displayName ?? loginId` 를 직접 쓰지 않는다), `PersonAvatar`(작은 아바타), `PersonMark`(아바타 + 이름 툴팁), `Person`(아바타·이름·아이디) |
| 티켓 줄의 조각 | `features/issues/components/issue-row.tsx` | `PriorityMark`, `StatusMark`, `IssueTitleLink`, `IssueProjectName`, `IssueLabels`, `IssueDue`. 티켓을 한 줄로 그리는 곳(목록, 하위 티켓)은 이것을 조합한다 |
| 내비게이션 항목 | `app/(app)/_components/nav-destinations.ts` | 사이드바와 좁은 화면 메뉴가 함께 쓰는 목적지 목록, `isCurrentPath()` |

- **자주 고치는 내용은 설정 화면에 넣지 않는다.** 보이는 그 자리에서 고치고 자동 저장한다(프로젝트 요약·설명, 티켓 제목·본문). 설정 화면에는 드물게 바꾸는 것(이름, 구성원)만 둔다.
- 편집기에 새 블록을 추가하려면 세 곳을 함께 고친다: 편집기의 `/` 메뉴(`SLASH_ITEMS`), 백엔드의 허용 목록(`RichText.kt` 의 `NODE_TYPES`), 글꼴·간격(`globals.css` 의 `.rich-text`).
- 링크를 누르면 `components/editor/link-card.tsx` 의 `LinkCard`(실제 주소 + "바로가기")가 편집기 상자 안에 `absolute` 로 뜨고, 같은 링크를 한 번 더 누르면 새 탭에서 열린다(D-175). `RichTextEditor` 의 상자에 `onClick` 하나로 처리하고, 블록 안의 링크(`data-node-view-wrapper`)는 건드리지 않는다. 새 블록에 `<a>` 를 넣으면 이 규칙에 걸리는지 본다.
- 표는 `components/editor/table-extensions.ts`(칸의 `align`, `verticalAlign` 속성, 도구 모음이 뜨는 표에만 `table-has-toolbar` 를 다는 `TableToolbarRoom`)와 `table-toolbar.tsx`(커서가 표 안에 있을 때 뜨는 도구 모음)에 있다. 도구 모음 자리(2.75rem)는 `globals.css` 의 `.tableWrapper.table-has-toolbar` 에만 있고 `TOOLBAR_SPACE` 와 같아야 한다. 칸에 새 속성을 더하면 `RichText.kt` 의 칸 검증도 함께 고친다.
- 편집기의 임베드(Figma, Google 문서·스프레드시트·프레젠테이션, Office, Outline)는 `components/editor/embed.ts` 다(D-139). 받는 주소 규칙(`PATTERNS`)은 `RichText.kt` 의 `EMBED_URLS` 와 같아야 한다. 서비스를 더하려면 두 곳과 iframe 주소(`frameSource`)를 함께 고친다.
- 문서 화면의 목차는 `components/editor/document-outline.tsx` 의 `DocumentOutline` 이다(D-170). `RichTextEditor` 의 `overlay` 로 꽂아 쓰고(`document-editor.tsx`), 편집기 상자의 오른쪽 바깥(`left-full`)에 `sticky` 로 선다. 제목은 저장 형식이 아니라 화면의 `h1~h6` 에서 읽고, 폭은 화면이 스크롤되는 `<main>` 의 폭으로 정한다(1280px 이상 패널, 640px 이상 줄, 그 밑은 없음). 컨테이너 쿼리를 쓰려고 `<main>` 이나 편집기 상자에 `@container` 를 달지 않는다(레이아웃 격리가 되어 `fixed` 의 기준이 바뀐다). 다른 편집기에 붙이려면 그 `overlay` 에 넣고, 그 화면에 스크롤 상자가 `<main>` 인지 확인한다.
- 편집기의 이미지는 `components/editor/upload-image.ts` 로 먼저 올리고 받은 주소를 `image` 블록에 넣는다. 밖의 주소나 base64 는 넣지 않는다(서버가 거부한다).
- 지금 시각이 있어야 그릴 수 있는 화면(캘린더의 "이번 달")은 `useInBrowser()`(`components/dates.tsx`)로 브라우저에서만 그린다.
- **렌더 중에 현재 시각을 읽지 않는다** (`Date.now()`, `new Date()`). `cacheComponents` 에서는 서버 렌더 중의 시각 읽기가 오류다("unstable value `Date.now()` while prerendering"). "3분 전"이나 "올해면 연도 생략"처럼 지금 시각에 기대는 글자는 `components/dates.tsx` 의 `<TimeAgo>`, `<DueDate>` 로 그린다(서버는 날짜 그대로, 브라우저가 바꿔 보여 준다). 값이 필요하면 같은 파일의 `useThisYear()`(올해), `useToday()`(오늘 YYYY-MM-DD), `useShownAt()`(화면이 처음 보인 시각)를 쓴다. 셋 다 서버와 하이드레이션 중에는 null 이다. `useState(() => Date.now())` 도 서버에서 실행되므로 안 된다.
- **대화상자로 여는 페이지**는 가로채기 경로로 만든다. `(app)/layout.tsx` 가 `modal` 슬롯을 그리고, `(app)/@modal/(.)issue/[key]/page.tsx` 가 대화상자다(앱 어느 화면에서 티켓을 눌러도 그 위에 뜬다). 슬롯에는 `default.tsx` 와 `page.tsx`(모두 null)를 둔다. **슬롯에 `[...catchAll]` 을 두지 않는다**: `(app)` 은 주소에 나타나지 않는 묶음이라 그 catch-all 이 `/setup`, `/unavailable` 같은 `(public)` 의 주소까지 가로채 404 를 만든다(실제로 겪었다). 대신 대화상자(`issue-dialog.tsx`)가 주소를 보고, `/issue/…` 가 아니면 스스로 사라진다. 닫기는 `router.back()`. 대화상자 맨 위에는 스크롤되지 않는 줄(보는 사람 아바타 `PresenceAvatars page="issue:"`, 닫기 버튼)이 있고 그 아래가 스크롤 상자다. 기본 닫기 버튼(`showCloseButton`)은 끈다: 스크롤 상자 위에 떠서 스크롤 막대를 가렸다. 스크롤 막대는 둥근 모서리에 잘리지 않으므로 아래에도 여백(`pb-3`)을 둔다(2026-10-15). 주소를 직접 연 경우(`issue/[key]/page.tsx`)는 전체 티켓 목록(`all-issues.tsx`)을 뒤에 그리고 같은 대화상자를 띄우며, 닫으면 `/issues` 로 간다. 그 대화상자 안에서 다른 티켓(하위 티켓)를 누르면 가로챈 대화상자가 뜨므로, 페이지의 대화상자는 자기 주소가 아닐 때 숨는다(`path`).
- **목표 상세 창도 같은 방식이다** (D-160). 주소는 `/project/{id}/milestone/{목표 id}`(`milestoneHref`). 가로채기는 프로젝트 안에서만 한다: `project/[id]/layout.tsx` 가 `milestone` 슬롯을 그리고, `project/[id]/@milestone/(.)milestone/[milestoneId]/page.tsx` 가 개요 위의 창이다(슬롯에는 `default.tsx` 만). 프로젝트 밖에서 오거나 주소를 직접 열면 `project/[id]/milestone/[milestoneId]/page.tsx` 가 개요(`ProjectOverview`, `features/projects/components/project-overview.tsx`)와 창을 함께 그리고 닫으면 개요로 간다. 창(`milestone-route-dialog.tsx`)은 주소가 자기 것이거나 `/issue/…`(창 안에서 연 티켓이 위에 뜬 경우)일 때만 보인다. 이름·목표일은 창이 낙관적으로 보여 주고, 뒤의 개요는 `router.refresh()` 로 따라온다.
- **대화상자 뒤의 목록은 자기 주소를 기억한다.** 티켓을 대화상자로 열면 브라우저 주소가 `/issue/…` 로 바뀌어 목록의 보기(`?view=calendar`)와 필터가 주소에서 사라진다. 목록은 `usePathname`/`useSearchParams` 를 직접 읽지 않고 `useListAddress()`(`issue-views.tsx`)로 읽는다: 주소가 `/issue/…` 인 동안에는 마지막으로 보던 목록 주소를 돌려준다.
- 편집기나 팝오버 안에서 `Esc` 로 닫는 것(`/` 메뉴 등)은 그 키 이벤트를 `stopPropagation()` 한다. 안 그러면 바깥의 대화상자까지 닫힌다.
- 경로 표시(`PageHeader`)와 알약 탭(`PillTabs`)은 `sticky` 로 내용 패널 위쪽에 붙어 있다(`z-30`). 내용 안에서 `sticky` 를 쓰면 그보다 낮은 `z-index` 를 준다.
- 사진 없는 아바타의 글자는 `lib/initials.ts`: 사람은 `initials()`(한글 이름은 뒤 두 글자), 24px 보다 작은 아바타는 `initial()`(한 글자), 사람이 아닌 것(워크스페이스)은 `mark()`(앞 글자).
- 같은 폴더 안에서 페이지끼리 오가면(티켓 상세 ↔ 티켓 목록) 그 폴더에도 `loading.tsx` 가 있어야 한다(`project/[id]/issues/loading.tsx`). 없으면 "uncached data ... outside of `<Suspense>`" 오류가 난다.
- 편집기 위에 띄우는 것(`/` 메뉴, 표 도구 모음)은 `position: fixed` 로 두되 좌표에서 `fixedOrigin()`(`components/editor/fixed-origin.ts`)을 뺀다. 대화상자는 `transform` 으로 가운데에 놓이기 때문에 그 안의 `fixed` 는 창이 아니라 대화상자 모서리가 기준이다. 빼지 않으면 새 티켓 창에서 메뉴가 엉뚱한 곳에 뜬다.
- `@` 메뉴는 `/` 메뉴(고정 폭 `w-56`)와 달리 내용만큼 넓어진다: 이름은 줄이지 않고, 이메일까지 보이도록 편집기의 오른쪽 끝까지 커진다(사용자 지정 2026-10-13: 이름이 "김…"으로 잘려 누구인지 알 수 없었다). 그래도 넘치면 이메일 쪽이 줄어든다.
- `@` 태그(D-101): `RichTextEditor` 에 `mentionables`(`mentionablesOf(members)`)를 주면 `@` 메뉴가 생긴다. `/` 메뉴와 같은 코드(`readSlash`, `menuItems`)를 쓴다. 블록은 `components/editor/mention.tsx`(React 노드 뷰: 칩 + 올리면 뜨는 사람 카드. 사람 정보는 `MentionPeopleContext` 로 받는다. 모양은 `globals.css` 가 아니라 컴포넌트의 Tailwind 클래스다), 서버 검증은 `RichText.kt` 의 `mention`. 주지 않으면 `@` 는 보통 글자다.
- 댓글(`features/issues/components/issue-comments.tsx`)도 `RichTextEditor` 다. 읽을 때는 `editable={false}`, 보낸 뒤 비우기와 고치기 취소는 `key` 를 바꿔서 한다.
- 편집기는 `RichTextEditor` 하나뿐이다(개요 설명, 티켓 본문, 새 티켓 창, 댓글). 블록이나 도구를 더하면 세 곳 모두에 생긴다.
- 편집기 내용을 밖에서 바꿔야 하면(프리셋 적용 등) `key` 를 바꿔 새로 시작시킨다.
- 대화상자는 열릴 때 커서가 갈 곳을 `DialogContent` 의 `initialFocus` 로 지정한다. 입력란의 `autoFocus` 는 Base UI 대화상자 안에서 믿을 수 없다.
- 온보딩 투어가 가리키는 곳은 `data-tour="nav"` 같은 속성으로 표시한다(`features/tour/steps.ts` 의 `target`). 앱 틀(사이드바, 위쪽 막대)을 고칠 때 이 속성을 지우지 않는다. 단계를 더하려면 `steps.ts` 와 `messages.tour.steps`, 명세의 표를 함께 고친다. 투어는 `z-40` 이라 메뉴·팝오버(`z-50`)가 그 위에 뜬다.
- 한 글자 단축키(`C`)는 `event.code` 로 판단하고, 입력란에 포커스가 있거나 대화상자가 열려 있으면 무시한다(`issues-view.tsx` 참고).

### 글의 블록에 댓글 (D-165)

- 편집기 `RichTextEditor` 는 `blockIds`(최상위 블록마다 안 바뀌는 번호 `attrs.blockId`, DOM 에는 `data-block-id`), `overlay`(편집기 위에 얹는 조각을 `({ editor, container }) => 노드` 로 받는다), `plain`(댓글용: 문단·글·`@` 태그만, `/` 메뉴·이미지·표 없음)을 받는다. 댓글을 달 수 있는 편집기 네 곳(프로젝트 개요, 문서, 티켓 본문, 목표 내용)이 `blockIds` 와 `overlay={… <BlockComments …/>}` 를 쓴다.
- 스레드·활동 데이터는 서버 컴포넌트가 아니라 `features/content/use-content-feed.ts` 의 `useContentFeed(target, watch)` 로 읽는다(`GET /api/v1/content/{type}/{id}/feed`, react-query). `watch` 에는 서버가 그린 객체(`project`, `document`, `issue`, `milestone`)를 넘기면 실시간 갱신(`router.refresh()`)으로 그 객체가 새로 내려올 때 다시 읽는다. 바꾼 뒤에는 `useFeedRefresh(target)()` 로 다시 읽게 한다.
- 티켓의 일반 댓글과 프로젝트·문서·목표의 일반 댓글은 같은 컴포넌트(`CommentComposer`·`CommentItem`·`useCommentFiles`)를 쓰고, 서버 호출은 `CommentApi`(`features/issues/comment-api.ts`)로 갈아 끼운다. 활동의 한 줄은 `ActivityEvent`(`describe` 결과와 `react` 를 받는다), 시간순 목록은 `timelineOf`.
- 위치를 재는 타이머는 `requestAnimationFrame` 이 아니라 `setTimeout` 이다: 백그라운드 탭에서는 프레임이 안 돌아 말풍선이 자리를 못 잡는다.

## 키보드

- 전역·목록 단축키는 [01-product.md](01-product.md) 3장과 4장의 표를 따른다. 새 단축키를 만들면 그 표에 먼저 추가한다.
- 단축키는 입력란에 포커스가 있을 때 동작하지 않게 하고, 한글 입력 상태에서도 같은 키로 동작하게 `event.code` 로 판단한다.
- 한글 조합 중의 `Enter` 는 무시한다(`event.nativeEvent.isComposing`).
- 단축키가 있는 액션은 메뉴와 툴팁에 `Kbd` 컴포넌트로 키를 표시한다.
- 마우스를 올렸을 때 보일 설명은 브라우저 기본 `title` 이 아니라 `components/ui/tooltip.tsx` 의 `Tooltip`(커스텀)으로 띄운다 (2026-10-21, 사용자 지정). 이모지 알약(누른 사람들)·이모지 추가 버튼·활동의 구독자 아바타가 이 방식이다. 버튼이 열기 버튼이기도 하면 `<TooltipTrigger render={<PopoverTrigger render={…} />}>` 로 겹친다. 읽기 전용으로 만들 버튼은 `disabled` 를 쓰지 않는다(눌러도 되지 않는 것과 포인터 이벤트가 없어 툴팁이 안 뜨는 것은 다르다): `aria-disabled` 와 클릭 무시를 쓴다. 여러 사람의 아바타 줄(화면 오른쪽 위의 "보는 사람" `PresenceAvatars`, 활동의 구독자 `SubscriberAvatars`)은 24px 아바타가 8px, 즉 3분의 1쯤 겹치고(처음엔 절반이었는데 글자가 가려져 안 보였다. 사용자 지시 2026-10-21) `avatar-fan`(globals.css)으로 올린 사람을 앞으로 내고 나머지를 양옆으로 11px(= 겹침 8 + 커지는 3) 벌린다: `AvatarGroup` 에 `avatar-fan -space-x-2`, 흐리게 할 때는 아바타 전체가 아니라 안쪽(`*:opacity-40`)만(아래 아바타가 비치지 않게). 이전부터 `title` 을 쓰던 곳(`PersonMark`, 댓글의 수정·삭제 버튼 등)은 아직 그대로다.

## 백엔드 호출

- 브라우저는 항상 같은 출처의 `/api/v1/...` 를 부른다. Next.js 가 백엔드로 넘긴다 ([02-architecture.md](02-architecture.md)).
- 브라우저 쪽 호출은 `lib/api/client.ts` 의 `apiFetch` 하나를 거친다. 컴포넌트에서 `fetch` 를 직접 쓰지 않는다. `apiFetch` 가 CSRF 헤더 추가(토큰이 없으면 먼저 받아 옴, 거절되면 한 번 재시도), 문제 문서 → `ApiError` 변환, 네트워크 실패 → `NetworkError` 변환을 맡는다.
- 파일 하나를 올리는 요청의 본문은 `fileForm(file)`(`lib/api/client.ts`)로 만든다.
- 프로젝트에 속하지 않은 티켓의 담당자 후보(전체 사용자)는 `usersAsMembers()`(`features/projects/types.ts`)로 구성원 모양으로 바꾼다.
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
- **목록의 도구 줄은 어떤 폭에서도 한 줄이다** (`issues-view.tsx`, 2026-10-16). 보기 전환(목록·목표 차트·캘린더)은 오른쪽 끝에 고정하고, 검색·필터·정렬처럼 옆에 다 들어가지 않는 것은 그 왼쪽에서 가로로 스크롤한다(`milestone-strip.tsx` 와 같은 방식). `flex-wrap` 으로 줄을 넘기면 보기 전환만 따로 다음 줄로 밀린다. 버튼 글자는 `whitespace-nowrap` 으로 꺾이지 않게 한다.
- 라이트/다크를 함께 만든다. 기본은 라이트.

## 문구

- 문구는 컴포넌트에 직접 쓰지 않고 `messages/ko.ts` 에 둔다. 지금은 한국어만 있고 다국어 라이브러리는 쓰지 않는다(파일 하나를 직접 import). 영어를 추가할 때 도입한다.
- 한국어로 처음부터 쓴다. 간결한 합니다체와 명사형. 버튼은 동작을 말한다("관리자 계정 만들기"). 오류는 무엇이 잘못됐고 무엇을 하면 되는지 말한다. 사과하지 않는다.
- **버튼과 메뉴의 동작 이름은 한국어 서비스에서 흔히 쓰는 말을 쓴다.** 내용을 바꾸는 동작은 "수정"이다("고치기", "고쳤습니다"라고 쓰지 않는다. 사용자 지정, 2026-10-13). 서버가 돌려주는 오류 문구와 알림 문구도 같다.
- 같은 동작은 화면 어디서나 같은 이름이다. 용어는 [01-product.md](01-product.md) 2장. **"이슈"라고 쓰지 않고 "티켓"이라고 쓴다** (D-122. 코드 식별자는 `issue` 그대로).

## 접근성

- WCAG 2.2 AA 를 기준으로 한다.
- 폼 필드에는 항상 연결된 레이블이 있다. 오류와 도움말은 `aria-describedby` 로 필드와 연결하고, 제출 실패 시 첫 오류 필드로 포커스를 옮긴다.
- 입력 테두리는 바탕과 3:1 이상 대비(`--input` 토큰).
- 색만으로 정보를 전달하지 않는다.
- 터치 화면에서 주요 조작 대상은 44px (`max-md:h-11`).

## 테스트와 검사

- 작업을 끝내기 전에 `npm run lint`, `npm run typecheck` 가 통과해야 한다.
- **E2E (Playwright)**: `frontend/e2e/`, `npm run test:e2e`. 서버를 스스로 띄우지 않는다. 개발 데이터를 건드리지 않도록 **빈 DB 를 쓰는 별도 백엔드(8081)와 그쪽을 보는 프로덕션 빌드(3100)** 를 먼저 띄운다([PROGRESS.md](PROGRESS.md) "화면을 직접 확인하는 방법"). 주소는 `E2E_BASE_URL`(기본 `http://localhost:3100`), 브라우저는 `E2E_BROWSER_CHANNEL`(기본 `msedge`: 시스템 브라우저를 쓴다)로 바꾼다. 테스트는 빈 DB 에 자기 계정과 프로젝트를 만들고, 돌릴 때마다 새 프로젝트를 더한다. 운영 이미지(`docker build`)를 3100 에 띄워서도 돌릴 수 있다(백엔드가 이미지 안에 있으므로 `BACKEND_URL` 을 따로 줄 필요가 없다). `settled()` 는 Next.js 의 링크 미리 읽기(`Next-Router-Prefetch` 요청)를 세지 않는다: Windows 의 Edge·Chrome 에서는 그 요청이 끝났다고 보고되지 않아 `settled()` 가 30초를 기다리다 실패했다(2026-10-21).
  - `changes.spec.ts`: **변경하는 동안 화면에 무엇이 보였는지**를 본다. `recordFrames`(`e2e/support.ts`)가 그려진 프레임마다 값을 기록하고, 네트워크를 일부러 늦춰(`slowDown`) 잠깐 보였다 사라지는 상태도 잡는다. 기대값은 "예전 값 → 새 값" 두 줄이다. 값을 고르는 컨트롤을 새로 만들면 여기에 한 건 더한다.
  - `flows.spec.ts`: 만들기·수정·삭제·보기 권한·다른 사람의 변경이 실시간으로 오는 것까지, 화면에서 하는 일이 끝까지 되는지.
  - `features.spec.ts`: 목표의 상세(티켓 목록, 정리, 그 위에 티켓 열기), 프로젝트 아카이브와 복원, 다른 사람에게 보이는 하이라이트(올리기만 하면 안 뜨고 누르거나 입력하면 뜬다).
  - `git.spec.ts`: Git 연동([features/git-integration.md](features/git-integration.md)). 관리자 화면에서 Gitea 연결을 만들고, 받은 비밀 값으로 서명한 PR 웹훅을 직접 보낸 뒤 티켓 옆 패널의 PR 줄과 브랜치 이름 복사 메뉴를 본다. 돌릴 때마다 연결이 하나씩 늘어난다.
  - `tour.spec.ts`: 온보딩 투어([features/onboarding-tour.md](features/onboarding-tour.md)). **다른 테스트에서 계정으로 로그인할 때는 `logIn()`(`e2e/support.ts`)을 쓴다.** 투어를 본 것으로 표시해 준다. `/api/v1/auth/login` 을 직접 부르면 투어가 화면을 덮어 아무것도 누를 수 없다. 로그인은 IP당 분당 10회로 제한되니 새 계정 로그인을 늘릴 때 주의한다.
  - 대화상자가 열려 있는 동안 뒤의 화면은 보조 기술에서 숨겨진다. 그때 뒤의 요소는 `getByRole` 이 아니라 `locator` 로 찾는다.
  - 화면이 서버에서 온 그대로 보여도 스크립트가 붙기 전에는 단축키가 동작하지 않는다. 키를 누르기 전에 `settled(page)` 로 기다린다.
- 단위 테스트(Vitest): `npm test`(`vitest run --dir src`). 설정 파일 없이 `src` 아래의 `*.test.ts` 만 돌린다(`e2e/` 의 Playwright 파일은 빼야 해서 `--dir src`). 첫 테스트는 `features/git/branch-name.test.ts`(2026-10-16).
- UI 를 바꿨으면 실제 브라우저에서 라이트/다크, 좁은 폭(360px)을 확인한다.

- **"이 화면에 무엇이 있는가"를 CSS 의 `:has()` 로 문서 전체에서 찾지 않는다.** 방금 떠난 화면이 숨겨진 채 문서에 남아 있어(`cacheComponents`) 그 화면의 요소까지 걸린다. 화면이 prop 으로 알려 준다(예: `IssuesView` 의 `underTabs`).
- 본문 `<main>` 은 `relative` 다. `sr-only` 같은 `position: absolute` 요소가 창 전체를 기준으로 놓여 창이 스크롤되는 것을 막는다. 지우지 않는다.

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

## 앱으로 설치 (PWA, D-110)

- `app/manifest.ts` 가 `/manifest.webmanifest` 로 내려가고 Next 가 `<link rel="manifest">` 를 붙인다. 브라우저의 설치 메뉴(Chrome·Edge 주소창의 설치 아이콘, iOS Safari 의 "홈 화면에 추가")로 설치하면 주소창 없는 독립 창으로 열린다. 앱 안에 설치 버튼은 없다.
- **설치까지만이다.** 오프라인에서는 열리지 않는다. 서비스 워커는 여전히 푸시 전용이고 캐시를 넣지 않는다(위 절).
- **HTTPS 가 있어야 설치된다** (`localhost` 는 예외). `http://` 사내 주소로 연 경우 브라우저가 설치 메뉴를 보여 주지 않는다. iOS 의 웹 푸시도 홈 화면에 추가한 뒤에만 된다.
- 아이콘은 `public/icons/`(192, 512, maskable 512)와 `app/apple-icon.png`(180)다. 모양을 바꾸려면 `scripts/generate-icons.mjs` 를 고치고 `frontend/` 에서 `node scripts/generate-icons.mjs` 를 돌린다(색은 `globals.css` 의 `--primary` 와 같은 값을 직접 적어 두었다). PNG 를 손으로 고치지 않는다.
- 매니페스트와 아이콘은 확장자가 있는 주소라 `proxy.ts` 를 거치지 않는다. 로그인 전에도 받아진다. 그래서 워크스페이스 이름·로고 같은 로그인 뒤의 값은 넣지 않는다.
- 독립 창에는 브라우저의 뒤로 가기·새로 고침 버튼이 없다. 화면마다 앱 안에서 돌아갈 길(경로 표시, 사이드바)이 있어야 한다.


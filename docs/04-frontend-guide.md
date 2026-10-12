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

## 키보드

- 전역·목록 단축키는 [01-product.md](01-product.md) 3장과 4장의 표를 따른다. 새 단축키를 만들면 그 표에 먼저 추가한다.
- 단축키는 입력란에 포커스가 있을 때 동작하지 않게 하고, 한글 입력 상태에서도 같은 키로 동작하게 `event.code` 로 판단한다.
- 한글 조합 중의 `Enter` 는 무시한다(`event.nativeEvent.isComposing`).
- 단축키가 있는 액션은 메뉴와 툴팁에 `Kbd` 컴포넌트로 키를 표시한다.

## 백엔드 호출

- 브라우저는 항상 같은 출처의 `/api/v1/...` 를 부른다. Next.js 가 백엔드로 넘긴다 ([02-architecture.md](02-architecture.md)).
- 브라우저 쪽 호출은 `lib/api/client.ts` 의 `apiFetch` 하나를 거친다. 컴포넌트에서 `fetch` 를 직접 쓰지 않는다. `apiFetch` 가 CSRF 헤더 추가(토큰이 없으면 먼저 받아 옴, 거절되면 한 번 재시도), 문제 문서 → `ApiError` 변환, 네트워크 실패 → `NetworkError` 변환을 맡는다.
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

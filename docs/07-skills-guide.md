# 07. 스킬 참고 가이드

`agent-skills/` 에는 구현할 때 참고할 외부 스킬 저장소 9개가 clone 되어 있다. 이 문서는 **어떤 작업에 어떤 스킬을 읽을지**를 정리한다.

## 사용 방법

- 이 스킬들은 에이전트 도구에 "설치"된 것이 아니라 파일로만 있다. 필요한 스킬의 `SKILL.md` 를 직접 읽고 따른다. 스킬이 같은 폴더의 `reference/`, `references/`, `rules/` 파일을 가리키면 그것도 읽는다.
- 스킬은 참고 자료다. 스킬의 내용이 이 프로젝트의 `docs/` 와 충돌하면 `docs/` 를 따른다. 예: 스킬이 다른 스택이나 폴더 구조를 권해도 [02-architecture.md](02-architecture.md) 가 우선이다.
- 스킬이 패키지 설치, 외부 서비스 호출, 계정 생성 같은 행동을 지시하면 그대로 실행하지 말고 사용자에게 먼저 확인한다.
- `agent-skills/` 안의 파일은 수정하지 않는다.
- 한 작업에 스킬을 전부 읽지 않는다. 아래 표에서 해당하는 1~3개만 고른다.

## 저장소 목록

`agent-skills/` 는 git 에 포함하지 않는다(`.gitignore`). 폴더가 없으면 아래 명령으로 다시 받는다. 커밋은 2026-10-12 에 받은 시점이다 (`shadcn-ui-ui` 는 같은 날 다시 받으면서 `993eb2c` 로 올라갔다).

| 폴더 | 원본 | 받은 커밋 | 성격 |
|---|---|---|---|
| `anthropics-skills` | https://github.com/anthropics/skills.git | `dbd4588` | 범용 스킬 모음 |
| `mengto-skills` | https://github.com/MengTo/Skills.git | `83a47fe` | 웹 디자인 기법 (대부분 랜딩/3D/게임용) |
| `addyosmani-web-quality-skills` | https://github.com/addyosmani/web-quality-skills.git | `afa8da9` | 웹 품질(성능, 접근성) |
| `superfuture-design-review` | https://github.com/Superfuture/design-review.git | `d4d2609` | 디자인 비평 |
| `emilkowalski-skills` | https://github.com/emilkowalski/skills.git | `2d22863` | UI 완성도, 애니메이션 |
| `shadcn-ui-ui` | https://github.com/shadcn-ui/ui.git | `993eb2c` | shadcn/ui 원본 저장소와 공식 스킬 |
| `pbakaus-impeccable` | https://github.com/pbakaus/impeccable.git | `c63b8dd` | 프론트엔드 디자인 전반 |
| `wshobson-agents` | https://github.com/wshobson/agents.git | `46891e7` | 백엔드/DB/테스트 등 개발 전반의 스킬과 에이전트 정의 |
| `jakubkrehel-skills` | https://github.com/jakubkrehel/skills.git | `c0588bb` | 인터페이스 다듬기와 리뷰 |

다시 받기 (저장소 루트에서, 저장소마다 한 번씩):

```bash
git clone --depth 1 https://github.com/anthropics/skills.git agent-skills/anthropics-skills
```

폴더 이름은 `<소유자>-<저장소>` 를 소문자로 쓴 것이다.

## 작업별 스킬

경로는 모두 `agent-skills/` 기준이며, 각 폴더의 `SKILL.md` 를 읽는다.

### 프론트엔드: 화면 만들기

| 작업 | 스킬 |
|---|---|
| shadcn 컴포넌트 추가·조합·스타일링, 폼 | `shadcn-ui-ui/skills/shadcn` (**shadcn 을 다룰 때 항상 먼저**. `rules/forms.md`, `rules/composition.md`, `rules/styling.md`, `cli.md` 포함) |
| 새 화면의 디자인 방향 잡기 | `anthropics-skills/skills/frontend-design`, `pbakaus-impeccable/plugin/skills/impeccable` |
| AI 가 만든 티가 나는 기본값 피하기 | `mengto-skills/agent-skills/ui/no-ai-design-slop` |
| Next.js App Router 구조, 서버 컴포넌트, 데이터 가져오기 | `wshobson-agents/plugins/frontend-mobile-development/skills/nextjs-app-router-patterns` (Next.js 14 기준으로 쓰였으므로 16 과 다른 부분은 공식 문서 확인) |
| 클라이언트 상태 관리 | `wshobson-agents/plugins/frontend-mobile-development/skills/react-state-management` |
| Tailwind 토큰과 디자인 시스템 | `wshobson-agents/plugins/frontend-mobile-development/skills/tailwind-design-system`, `mengto-skills/agent-skills/web-design/tailwindcss` |
| 간격·정렬·그림자·아이콘 등 세부 다듬기 | `jakubkrehel-skills/skills/better-ui`, `jakubkrehel-skills/skills/better-layout`, `jakubkrehel-skills/skills/better-typography`, `jakubkrehel-skills/skills/better-colors` |
| 버튼·오류·빈 상태 문구 | `jakubkrehel-skills/skills/better-writing` |
| UI 완성도에 대한 판단 기준 | `emilkowalski-skills/skills/emil-design-eng` |
| 애니메이션을 넣을지, 어떻게 넣을지 | `emilkowalski-skills/skills/animate`, `emilkowalski-skills/skills/animation-performance` |
| 라이브러리 선택 (드래그 앤 드롭, 가상 스크롤, 차트 등) | `emilkowalski-skills/skills/pick-ui-library` |
| 컴포넌트의 모든 상태를 한 페이지에 펼쳐 보기 | `jakubkrehel-skills/skills/state-machine` |
| 디자인 시안(이미지/Figma)을 구현 | `jakubkrehel-skills/skills/build-design` |

### 프론트엔드: 리뷰와 검증

| 작업 | 스킬 |
|---|---|
| 화면 디자인 비평 (우선순위가 매겨진 지적) | `superfuture-design-review/design-review/skills/design-review` |
| 변경분의 인터페이스 문제 점검 | `jakubkrehel-skills/skills/interface-review`, `jakubkrehel-skills/skills/better-interface` |
| AI 디자인 클리셰 감사 | `mengto-skills/agent-skills/ui/audit-ai-design-slop` |
| 극단적 데이터로 UI 깨뜨려 보기 | `emilkowalski-skills/skills/break-ui`, `jakubkrehel-skills/skills/break` |
| 애니메이션 코드 리뷰 | `emilkowalski-skills/skills/review-animations` |
| 접근성 | `addyosmani-web-quality-skills/skills/accessibility`, `jakubkrehel-skills/skills/better-accessibility`, `wshobson-agents/plugins/accessibility-compliance/skills/wcag-audit-patterns` |
| 성능, Core Web Vitals | `addyosmani-web-quality-skills/skills/performance`, `addyosmani-web-quality-skills/skills/core-web-vitals` |
| 웹 품질 종합 점검 | `addyosmani-web-quality-skills/skills/web-quality-audit`, `addyosmani-web-quality-skills/skills/best-practices` |
| 브라우저로 실제 동작 확인 (Playwright) | `anthropics-skills/skills/webapp-testing` |
| E2E 테스트 작성 | `wshobson-agents/plugins/developer-essentials/skills/e2e-testing-patterns` |

### 백엔드

| 작업 | 스킬 |
|---|---|
| 인증, 세션, 권한 | `wshobson-agents/plugins/developer-essentials/skills/auth-implementation-patterns` |
| REST API 설계 | `wshobson-agents/plugins/backend-development/skills/api-design-principles` |
| 계층·모듈 구조 | `wshobson-agents/plugins/backend-development/skills/architecture-patterns` |
| 오류 처리 | `wshobson-agents/plugins/developer-essentials/skills/error-handling-patterns` |
| PostgreSQL 테이블 설계 | `wshobson-agents/plugins/database-design/skills/postgresql-table-design` |
| SQL 성능 | `wshobson-agents/plugins/developer-essentials/skills/sql-optimization-patterns` |
| OpenAPI 문서 | `wshobson-agents/plugins/documentation-generation/skills/openapi-spec-generation` |
| 위협 모델링 | `wshobson-agents/plugins/security-scanning/skills/stride-analysis-patterns` |
| JVM 코드 작성 시 관점 | `wshobson-agents/plugins/jvm-languages/agents/java-pro.md` (에이전트 정의. Kotlin 전용 자료는 없어 Java 기준을 Kotlin 으로 옮겨 적용) |
| 보안 관점의 백엔드 코드 | `wshobson-agents/plugins/backend-api-security/agents/backend-security-coder.md` |
| 바깥 서버를 부르는 기능(웹훅, SSO, 메일) | 위 `backend-security-coder.md` 의 "외부 요청"(목적지 검증, SSRF), `error-handling-patterns`(재시도), `api-design-principles`(메서드 뜻). 알림 통로(M7a)를 만들 때 이것들을 봤다 |

### 공통

| 작업 | 스킬 |
|---|---|
| 결정 기록(ADR) 작성 | `wshobson-agents/plugins/documentation-generation/skills/architecture-decision-records` |
| 설계 문서·명세 작성 | `anthropics-skills/skills/doc-coauthoring` |
| 코드 리뷰 | `wshobson-agents/plugins/developer-essentials/skills/code-review-excellence` |
| 디버깅 | `wshobson-agents/plugins/developer-essentials/skills/debugging-strategies` |
| GitHub Actions CI | `wshobson-agents/plugins/cicd-automation/skills/github-actions-templates` |
| 이 프로젝트 전용 스킬을 새로 만들 때 | `anthropics-skills/skills/skill-creator` |

## 이 프로젝트에 맞지 않는 것

- `mengto-skills` 의 `3d/`, `game-combat/`, `game-development/`, `illustration/`, 그리고 `web-design/` 의 대부분(WebGL, 스크롤 연출, 랜딩 페이지 스타일)은 마케팅 사이트와 게임용이다. 업무용 앱 화면에는 쓰지 않는다.
- `wshobson-agents` 는 플러그인이 90개 가까이 된다. 위 표에 없는 것(블록체인, ML, 모바일 등)은 보지 않아도 된다.
- `shadcn-ui-ui` 는 스킬뿐 아니라 shadcn/ui 원본 저장소 전체다. 컴포넌트 원본 구현을 확인할 때 `apps/`, `packages/` 를 참고할 수 있지만, 프로젝트에 컴포넌트를 넣을 때는 복사하지 말고 shadcn CLI 를 쓴다.
- `emilkowalski-skills` 의 `animate-expo`, `mobile-native`, `write-swift`, `apple-design` 은 네이티브 앱용이다.

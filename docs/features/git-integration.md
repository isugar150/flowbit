# Git 연동 (Gitea · GitLab · GitHub)

상태 (2026-10-15): **명세만 있다. 구현하지 않았다.** 사용자가 Git 서버 연동의 구현 계획을 요청했다. 결정은 D-141. D-039 에서 "SaaS 전용"이라 빼 두었던 GitHub 연결을 설치형에 맞는 모양(사내 Git 서버의 웹훅)으로 들인다.

브랜치 이름, PR 제목, 커밋 메시지에 티켓 키(`FB-12`)를 쓰면 Flowbit 이 그 티켓에 PR·브랜치·커밋을 연결하고, 작업 흐름에 따라 티켓 상태를 바꾼다. 개발자는 Flowbit 에서 따로 할 일이 없다.

## 범위

**연결은 조직 단위로 관리자가 한 번 한다.** 개인이 할 설정은 없다(사용자와 합의, 2026-10-15). 사람은 Git 이벤트의 이메일·아이디로 Flowbit 계정과 맞춘다.

| 기능 | 이 명세 |
|---|---|
| 관리자 설정 "Git 연동" 탭: 연결 추가, 웹훅 주소·비밀 값 발급, 허용 프로젝트, 자동 상태 변경 켜기/끄기, 마지막 수신 결과 | 포함 |
| 들어오는 웹훅 받기: Gitea(Forgejo 포함), GitLab, GitHub 의 push · PR(MR) 이벤트, 서명 확인 | 포함 |
| 티켓 키 인식 → 티켓에 PR·브랜치·커밋 연결, 옆 패널의 "Git" 묶음, 활동 기록 | 포함 |
| 상태 자동 변경: 작업 시작 → 진행 중, PR 머지 → 완료 | 포함 |
| 티켓에서 "브랜치 이름 복사" | 포함 |

범위 밖(필요해지면 따로 명세): 개인 Git 계정 연결(이메일이 다른 사람 맞추기, 내 이름으로 댓글), Flowbit 이 Git 서버에 쓰기(PR 에 티켓 링크 댓글 — API 토큰 필요), CI 결과 표시, Git 이슈 가져오기·양방향 동기화, 저장소 목록 불러오기, 배포 추적.

## 쓰는 흐름

1. 관리자가 **관리자 설정 > Git 연동**에서 "연결 추가": 이름, 종류(Gitea·GitLab·GitHub), 서버 주소, 허용 프로젝트를 정하고 저장한다.
2. 화면에 **웹훅 주소**와 **비밀 값**이 나온다. 관리자는 이 둘을 Git 서버의 웹훅 설정에 넣고 이벤트로 push 와 PR(MR)을 고른다(아래 "Git 서버에 등록하기").
3. 개발자가 `kimjm/fb-12-login-error` 브랜치를 올리면 FB-12 에 브랜치가 연결되고 상태가 **진행 중**이 된다.
4. PR 을 열면 PR 이 연결된다. 머지하면 티켓이 **완료**가 된다. 변경은 활동 기록에 "kimjm 님이 Git 에서 …" 로 남고, 보고 있는 사람 화면에 바로 반영되고, 구독자에게 알림이 간다.

## 티켓 키 인식

- 키는 **지금의 워크스페이스 티켓 키** + `-` + 번호다(`FB-12`). 대소문자를 가리지 않는다(브랜치는 보통 소문자 `fb-12`). 앞뒤가 글자·숫자가 아니어야 한다(`xfb-12` 는 아니다, `feature/FB-12-login`, `[FB-12]` 는 맞다).
- 찾는 곳: 브랜치 이름, PR 제목, PR 본문, 커밋 메시지(제목과 본문).
- 한 곳에 키가 여럿이면 모두 연결한다. 한 이벤트에서 연결하는 티켓은 20개까지(넘으면 나머지는 무시하고 연결 기록에 남긴다).
- 없는 번호, 삭제된 티켓, **연결이 허용하지 않은 프로젝트의 티켓**은 무시한다.
- 워크스페이스 티켓 키를 바꾸면 예전 키는 더 이상 인식하지 않는다(이미 만든 연결은 남는다).

**닫는 연결과 참고 연결**

| 연결 방식 | 종류 | 머지하면 |
|---|---|---|
| 브랜치 이름이나 PR 제목에 키 | 닫는 연결 | 티켓 완료 |
| PR 본문·커밋 메시지에 `Fixes FB-12`, `Closes FB-12`, `Resolves FB-12` (활용형 `fix`·`fixed`·`close`·`closed`·`resolve`·`resolved` 포함, 대소문자 무시) | 닫는 연결 | 티켓 완료 |
| 그 밖에 본문·커밋 메시지에 키만 있거나 `Refs FB-12`, `Part of FB-12` | 참고 연결 | 상태를 바꾸지 않는다 |

같은 PR 이 한 티켓에 두 방식으로 걸리면 닫는 연결이 이긴다.

## 상태 자동 변경

연결마다 "작업을 시작하면 진행 중으로", "머지하면 완료로"를 따로 켜고 끈다(기본 둘 다 켬).

| Git 에서 일어난 일 | 티켓 상태 | 조건 |
|---|---|---|
| 키가 든 브랜치를 처음 올림 | 진행 중 `IN_PROGRESS` | 지금 `BACKLOG` 이나 `READY` 일 때만 |
| PR 열림 (초안 포함) | 진행 중 | 위와 같음 |
| PR 머지 | 완료 `DONE` | 닫는 연결이고, 그 티켓에 닫는 연결로 걸린 **다른 열린 PR 이 없을 때** |
| 기본 브랜치에 바로 push, 커밋 메시지에 닫는 말 | 완료 | PR 없이 일하는 팀을 위해. 지금 `DONE`·`CANCELED` 가 아닐 때 |
| PR 이 머지되지 않고 닫힘 | 바꾸지 않음 | 패널의 PR 표시만 "닫힘" |

- **뒤로 되돌리지 않는다.** 완료된 티켓을 진행 중으로 돌리거나, 취소된 티켓을 건드리지 않는다. 사람이 손으로 바꾼 상태가 Git 이벤트 때문에 다시 바뀌는 일이 없게 하려는 것이다.
- 하위 티켓·상위 티켓은 따라 바뀌지 않는다. 키가 걸린 티켓만 바뀐다.
- 상태가 고정 5개라 "리뷰 중"은 없다. PR 이 열려 있다는 것은 패널에서 보인다.

## 누가 한 것으로 남기는가

- 이벤트의 사람: push 는 올린 사람(pusher), PR 열림은 PR 을 만든 사람, 머지는 머지한 사람.
- Flowbit 계정 찾기: ① 이벤트의 이메일 = `users.email`(대소문자 무시, 활성 계정) ② 없으면 Git 사용자 이름 = Flowbit 아이디(`login_id`). 사내 Git 서버도 LDAP·SSO 로 가입하는 경우가 많아 둘 중 하나는 대개 맞는다.
- 맞는 계정이 없으면 Git 사용자 이름만 남긴다: "kimjm(Git) 님이 상태를 바꿨습니다".
- 제공자마다 오는 값이 다르다. GitHub 의 PR 이벤트에는 이메일이 없고, GitLab 의 MR 이벤트는 이메일을 가리기도 하고(`[REDACTED]`), Gitea 는 사용자가 이메일을 숨기면 `…@noreply.…` 가 온다. 그래서 ②가 필요하다.
- **사람의 프로젝트 권한은 보지 않는다.** 관리자가 그 연결에 허용한 프로젝트인지만 본다. Git 서버에서 그 저장소에 올리고 머지할 수 있다는 것이 근거다.

## 화면

**티켓 옆 패널의 "Git" 묶음** (속성·라벨·프로젝트 다음, 연결이 있을 때만)

- 줄마다 PR: 상태 아이콘(열림·초안·머지됨·닫힘), `저장소이름 #34`, PR 제목, 닫는 연결이 아니면 "참고". 누르면 Git 서버의 PR 을 새 탭으로 연다.
- PR 이 없는 브랜치: 브랜치 아이콘과 이름.
- 커밋은 패널에 두지 않고 활동에만 남긴다.

**활동 기록**에 새 줄

| 종류 | 문구 예 |
|---|---|
| `GIT_BRANCH_LINKED` | 김지민 님이 브랜치를 연결했습니다: `kimjm/fb-12-login` |
| `GIT_PR_LINKED` | 김지민 님이 PR 을 연결했습니다: `web#34 로그인 오류 수정` |
| `GIT_PR_MERGED` / `GIT_PR_CLOSED` | 이수진 님이 PR 을 머지했습니다 / 닫았습니다: `web#34` |
| `GIT_COMMITS_PUSHED` | 김지민 님이 커밋 3개를 올렸습니다 (펼치면 커밋 제목 5개까지, 각 줄은 Git 서버 링크) |
| `STATUS_CHANGED` (Git 출처) | 이수진 님이 Git 에서 상태를 바꿨습니다: 진행 중 → 완료 |

**알림**: Git 이 바꾼 **상태**는 손으로 바꾼 것처럼 구독자에게 알림으로 간다(D-126 의 모아 보내기도 같다). PR·브랜치 연결과 커밋은 활동 기록과 화면에만 남고 알림은 보내지 않는다(커밋마다 알림이 오면 너무 많다). API 알림(웹훅)의 "보낼 변경"에서 Git 종류를 고르면 그것도 보낼 수 있다.

**브랜치 이름 복사**: 티켓 상세 위의 키 옆 메뉴에 "브랜치 이름 복사". 형식은 `<내 아이디>/fb-12-<제목 슬러그>`. 슬러그는 제목에서 영문·숫자만 남겨 `-` 로 잇고(한글 제목이면 비어서 `kimjm/fb-12`), 전체 60자까지.

**관리자 설정 "Git 연동" 탭** (`/admin/git`)

| 항목 | 내용 |
|---|---|
| 이름, 사용 | |
| 종류 | Gitea(Forgejo 포함), GitLab, GitHub |
| 서버 주소 | `https://git.example.com`. GitHub 는 `https://github.com` 이 기본. 표시와 등록 안내에만 쓴다 |
| 허용 프로젝트 | "모든 프로젝트" 또는 고른 프로젝트. 고르지 않은 프로젝트의 티켓은 이 연결로 바뀌지 않는다 |
| 자동 상태 변경 | "작업을 시작하면 진행 중으로", "머지하면 완료로" 각각 |
| 웹훅 주소 | `{FLOWBIT_PUBLIC_URL}/api/v1/integrations/git/{연결 id}/webhook`. 복사 버튼. `FLOWBIT_PUBLIC_URL` 이 없으면 경고를 보인다(Git 서버가 부를 주소를 알 수 없다) |
| 비밀 값 | Flowbit 이 무작위로 만든다(32바이트). **만들 때와 "다시 만들기"를 누를 때만** 보인다. 그 뒤로는 "설정됨" |
| 마지막 수신 | 시각, 이벤트 종류, 결과(연결한 티켓 수 또는 오류). 처음 등록했을 때 잘 되는지 보는 용도 |
| 등록 안내 | 종류별로 Git 서버의 어디에 무엇을 넣는지 짧게(아래 표) |

## Git 서버에 등록하기

| | Gitea · Forgejo | GitLab | GitHub |
|---|---|---|---|
| 등록 위치 | 조직 설정 > 웹훅 > "Gitea" 종류 (조직 아래 저장소 전부) | 그룹 설정 > Webhooks (그룹 웹훅은 **Premium 이상**. Free 는 프로젝트마다 등록) | 조직 Settings > Webhooks, Content type `application/json` |
| 이벤트 | Push, Pull Request | Push events, Merge request events | Pushes, Pull requests |
| 비밀 값 넣는 곳 | Secret | Secret token | Secret |
| Flowbit 이 확인하는 헤더 | `X-Gitea-Signature` (본문의 HMAC-SHA256, hex) | `X-Gitlab-Token` (비밀 값 그대로) | `X-Hub-Signature-256` (`sha256=` + HMAC-SHA256) |
| 이벤트 종류 헤더 | `X-Gitea-Event`: `push`, `pull_request` | `X-Gitlab-Event`: `Push Hook`, `Merge Request Hook` | `X-GitHub-Event`: `push`, `pull_request`, `ping` |

**네트워크**: Git 서버가 Flowbit 에 닿아야 한다. 사내 Gitea·GitLab 은 같은 내부망이라 그대로 된다. **GitHub.com 을 쓰려면 Flowbit 의 웹훅 주소가 인터넷에서 열려 있어야 한다**(리버스 프록시에서 이 경로만 열어도 된다).

## 받는 방식

```
Git 서버 ─ POST /api/v1/integrations/git/{id}/webhook ─▶ GitWebhookController
   ├ 연결 찾기 (없거나 꺼져 있으면 404)
   ├ 본문을 바이트 그대로 읽고 종류별로 서명 확인 (틀리면 401, 상수 시간 비교)
   ├ GitProvider.parse → 공통 이벤트(GitPush, GitPullRequest). 모르는 이벤트는 200 으로 무시
   └ GitLinkService.apply (트랜잭션)
        ├ IssueKeyMatcher 로 키 찾기 → 허용 프로젝트의 티켓만
        ├ 연결·커밋 저장 (이미 있으면 갱신만: 같은 배달이 다시 와도 두 번 쌓이지 않는다)
        ├ 상태 규칙 적용 → IssueService 의 Git 전용 경로로 상태 변경
        └ 활동 기록 → 커밋 뒤 IssueAnnouncer (실시간 반영, 상태 변경은 알림)
```

- 응답: 200 `{ linkedIssues, statusChanged }`. Git 서버는 응답 코드와 본문을 배달 기록에 보여 주므로 관리자가 거기서도 결과를 볼 수 있다.
- 본문은 5MB 까지(그보다 크면 413). push 한 번의 커밋은 앞의 100개만 본다.
- 이 경로는 로그인이 없다: 보안 설정에서 공개 POST 로 열고 CSRF 검사에서 뺀다. 서명이 인증이다. 같은 연결로 1분에 600번을 넘으면 429(`common/ratelimit`).
- 같은 PR 의 이벤트가 순서 없이 와도 되게, PR 상태는 이벤트 안의 값(`merged`, `state`)으로 덮어쓴다(되돌리지 않는 규칙은 티켓 상태에만 있다).

## 데이터 (`V11`)

| 테이블 | 컬럼 | 비고 |
|---|---|---|
| `git_connections` | `id`, `name`, `provider`(`GITEA`\|`GITLAB`\|`GITHUB`), `base_url`, `secret`, `enabled`, `all_projects`, `auto_in_progress`, `auto_done`, `last_received_at`, `last_event`, `last_result`, `last_error`, `created_at`, `updated_at` | `secret` 은 평문(다른 연동 설정과 같다, D-090) |
| `git_connection_projects` | `connection_id`(CASCADE), `project_id`(CASCADE) | `all_projects` 가 거짓일 때만 쓴다 |
| `issue_git_links` | `id`, `issue_id`(CASCADE), `connection_id`(CASCADE), `kind`(`BRANCH`\|`PULL_REQUEST`), `repository`(`org/web`), `branch`, `pr_number`, `title`, `url`, `pr_state`(`OPEN`\|`DRAFT`\|`MERGED`\|`CLOSED`), `closes` bool, `created_at`, `updated_at` | 유일: `(issue_id, connection_id, repository, kind, coalesce(pr_number, 0), branch)`. PR 이 생기면 같은 브랜치 줄은 화면에서 PR 줄에 합친다 |
| `issue_git_commits` | `issue_id`(CASCADE), `connection_id`(CASCADE), `repository`, `sha`, `message`(첫 줄), `url`, `author_name`, `committed_at` | 유일 `(issue_id, sha)`. 다시 온 배달과 다른 브랜치로 옮긴 커밋을 두 번 기록하지 않으려고 둔다 |
| `issue_activities` | `actor_id` 를 **null 허용**으로 바꾼다 | Git 출처면 `detail.via = { "source": "git", "name": "kimjm" }`. 계정이 맞으면 `actor_id` 도 채운다 |

- 연결을 지우면 그 연결로 만든 티켓의 연결·커밋도 지운다. 활동 기록은 남는다.
- 백업 및 복원(D-112)에 `GIT` 항목을 더한다(비밀 값 포함). 데이터 보관(D-132)은 티켓이 영구 삭제되면 CASCADE 로 함께 지워진다.

## API

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| POST | `/api/v1/integrations/git/{id}/webhook` | 없음 (서명) | 위 "받는 방식" |
| GET, POST | `/api/v1/admin/git/connections` | 관리자 | 목록(비밀 값 대신 `secretSet`, `webhookUrl`), 추가(201, 응답에 한 번만 `secret`) |
| PUT, DELETE | `/api/v1/admin/git/connections/{id}` | 관리자 | `{ name, enabled, provider, baseUrl, allProjects, projectIds, autoInProgress, autoDone }` |
| POST | `/api/v1/admin/git/connections/{id}/secret` | 관리자 | 비밀 값 다시 만들기 → `{ secret }`. 예전 값은 바로 무효 |
| GET | `/api/v1/issues/{번호}` | 기존 | 응답에 `git: [{ kind, repository, branch, prNumber, title, url, prState, closes }]` 를 더한다 |

오류: 422 `INVALID_URL`(서버 주소), 422 `REQUIRED`, 404 `GIT_CONNECTION_NOT_FOUND`.

## 코드 구조

- 백엔드 `com.flowbit.git` 패키지
  - `GitWebhookController`, `GitConnectionController`, `GitConnectionService`
  - `GitProvider`(인터페이스: `verify(headers, body, secret)`, `parse(headers, body): List<GitEvent>`), `GiteaProvider`, `GitLabProvider`, `GitHubProvider`. Gitea 와 GitHub 는 본문 모양이 거의 같아 파싱을 함께 쓴다.
  - `IssueKeyMatcher`(워크스페이스 키로 정규식을 만들고 키가 바뀌면 다시 만든다), `GitLinkService`
- `IssueService` 에 사람 대신 **연결이 바꾸는 경로**를 더한다: 권한 검사 대신 허용 프로젝트를 보고, 활동의 `actor_id`·`via` 를 받는다. 지금 `update()` 와 `IssueAnnouncer` 는 `AuthenticatedUser` 를 받으므로, 바꾼 사람을 `Actor`(사람 또는 Git) 로 넓히는 작은 리팩터링이 먼저 필요하다. 알림 문구(`NotificationText`)는 `actorName` 만 받으므로 그대로 쓴다.
- 프런트엔드: `app/(app)/admin/git/page.tsx` 와 관리자 탭, `features/issues/` 의 옆 패널 "Git" 묶음, 활동 줄 문구, 키 메뉴의 브랜치 이름 복사.

## 구현 순서

각 단계가 끝나면 테스트와 함께 커밋한다.

1. **연결 관리**: `V11`, 관리자 API, `/admin/git` 화면, 비밀 값 발급, 백업 항목.
2. **받기와 연결 (Gitea 먼저)**: 웹훅 경로, 서명 확인, 키 인식, `issue_git_links`·`issue_git_commits` 저장, 마지막 수신 결과. 옆 패널 "Git" 묶음과 활동 줄.
3. **상태 자동 변경**: `Actor` 리팩터링, 규칙 적용, 실시간 반영, 알림.
4. **GitLab, GitHub**: 제공자 두 개를 더한다. 실제 페이로드 견본으로 테스트한다.
5. **브랜치 이름 복사**.

## 인수 기준

`GitIntegrationTest`(통합 테스트, 세 제공자의 실제 페이로드 견본을 `src/test/resources/git/` 에 둔다)가 확인한다.

- 연결 관리는 관리자만 하고, 비밀 값은 만들 때와 다시 만들 때만 돌려준다. 다시 만들면 예전 값의 서명은 401 이다
- 서명이 틀리면 401, 없는·꺼진 연결이면 404, 모르는 이벤트와 GitHub `ping` 은 200 으로 무시한다
- 키 인식: 대소문자, 브랜치·제목·본문·커밋 메시지, 여러 키, 단어 경계. 없는 번호·삭제된 티켓·허용하지 않은 프로젝트는 무시한다
- 브랜치를 올리거나 PR 을 열면 백로그·준비됨 티켓이 진행 중이 되고, 완료·취소 티켓은 바뀌지 않는다
- 닫는 연결의 PR 이 머지되면 완료가 되고, 참고 연결과 머지 없이 닫힌 PR 은 상태를 바꾸지 않는다. 닫는 연결로 걸린 다른 PR 이 열려 있으면 완료가 되지 않는다
- 자동 상태 변경을 끈 연결은 연결만 한다
- 같은 배달이 다시 와도 연결·커밋·활동이 두 번 쌓이지 않는다
- 사람 맞추기: 이메일, 그다음 아이디, 둘 다 없으면 Git 이름만. 상태 변경은 구독자에게 알림으로 가고 연결·커밋은 가지 않는다
- 화면(E2E): 연결을 추가하면 주소와 비밀 값이 보이고, 연결이 있는 티켓의 옆 패널에 PR 줄이 보인다

## 확인이 필요한 것

- **어떤 Git 서버를 먼저 붙일지.** 구현 순서는 Gitea 먼저로 적었다(사내 설치형에서 흔하고 GitHub 와 모양이 비슷하다). 실제로 쓰는 서버가 다르면 순서를 바꾼다.
- "리뷰 중" 상태가 필요한지. 지금 상태는 고정 5개라, 두려면 상태 구조부터 바꿔야 한다.
- 커밋을 활동에 남길지, PR·브랜치만 남길지(커밋이 많은 팀은 활동이 길어진다).
- 이메일·아이디가 둘 다 안 맞는 사람이 많으면 "내 설정 > Git 계정"(개인 연결)을 더할지.
- GitHub.com 을 쓸 경우 외부에 열 주소와 리버스 프록시 구성.
- 비밀 값이 DB 에 평문이다(다른 연동 설정과 같다, D-090).

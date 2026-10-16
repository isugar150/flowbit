# Git 연동 (Gitea · GitLab · GitHub)

상태 (2026-10-16): **명세 전부를 구현했다**: Gitea(Forgejo)·GitLab 은 웹훅, GitHub 는 GitHub App(웹훅 또는 API 가져오기), 브랜치 이름 복사, API 알림의 Git 종류(D-145~D-147). 관리자의 "사람 맞추기"는 D-152 에서 없앴다. 실제 Gitea·GitLab·GitHub 서버로는 아직 시험하지 않았다(견본과 가짜 GitHub 로 테스트했다). 처음 계획은 D-141. D-039 에서 "SaaS 전용"이라 빼 두었던 GitHub 연결을 설치형에 맞는 모양으로 들인다.

브랜치 이름, PR 제목, 커밋 메시지에 티켓 키(`FB-12`)를 쓰면 Flowbit 이 그 티켓에 PR·브랜치·커밋을 연결하고, 작업 흐름에 따라 티켓 상태를 바꾼다. 개발자는 Flowbit 에서 따로 할 일이 없다.

## 범위

**연결은 조직 단위로 관리자가 한 번 한다.** 개인이 할 설정은 없다(사용자와 합의, 2026-10-15). 사람은 Git 이벤트의 이메일·아이디로 Flowbit 계정과 맞춘다.

| 기능 | 이 명세 |
|---|---|
| 관리자 설정 "Git 연동" 탭: 연결 추가, 웹훅 주소·비밀 값 발급, 자동 상태 변경 켜기/끄기, 마지막 수신 결과 | 포함 |
| 들어오는 웹훅 받기: Gitea(Forgejo 포함), GitLab, GitHub 의 push · PR(MR) 이벤트, 서명 확인 | 포함 |
| 티켓 키 인식 → 티켓에 PR·브랜치·커밋 연결, 옆 패널의 "Git" 묶음, 활동 기록 | 포함 |
| 상태 자동 변경: 작업 시작 → 진행 중, PR 머지 → 완료 | 포함 |
| 티켓에서 "브랜치 이름 복사" | 포함 |

범위 밖(필요해지면 따로 명세): 개인 Git 계정 연결(이메일이 다른 사람 맞추기, 내 이름으로 댓글), Flowbit 이 Git 서버에 쓰기(PR 에 티켓 링크 댓글 — API 토큰 필요), CI 결과 표시, Git 이슈 가져오기·양방향 동기화, 저장소 목록 불러오기, 배포 추적.

## 프로젝트별 저장소와 티켓의 저장소 (D-148)

**프로젝트 설정 > Git 저장소** (프로젝트 관리 역할): 이 프로젝트의 작업이 있는 저장소를 여러 개 정한다(50개까지, 이름은 소문자로 비교). **Git 연결을 먼저 고르고, 그 연결의 저장소 목록에서 고른다**(D-151). 목록에 없으면 "목록에 없음: 직접 입력"으로 이름을 넣는다. Git 이 쓰이지 않으면 이 절은 보이지 않는다.

저장소 목록(표 `git_repositories`, `V17`)이 채워지는 곳: GitHub App 은 설치된 저장소를 GitHub API(`GET /installation/repositories`)로 읽어 통째로 바꾼다. 설치 확인 때, API 가져오기의 매 회차, 프로젝트 설정을 열 때(1분에 한 번까지), "목록 새로 고침"(`GET /api/v1/projects/{id}/git?refresh=true`) 때 읽는다. Gitea·GitLab 은 Flowbit 이 그 서버의 API 토큰이 없어 물어볼 수 없으므로, 이벤트를 보낸 저장소가 목록에 쌓인다(웹훅 연결이라도 첫 push 전에는 비어 있다). 응답의 `available` 은 이 목록과 티켓에 연결된 적 있는 저장소를 합친 것에서 프로젝트가 이미 고른 것을 뺀 것이다(전의 `suggestions`).

**티켓 옆 패널 > Git > 저장소**: 프로젝트에 저장소가 둘 이상이면 티켓마다 하나를 고른다(편집 역할). 고른 저장소가 있으면 키 옆 메뉴에 "<저장소> 열기"가 생긴다(저장소가 하나뿐인 프로젝트는 그 저장소).

**저장소는 저절로 정해진다** (사용자 지정, D-152, D-153): 아직 고른 저장소가 없는 티켓에 처음으로 브랜치나 PR 이 연결되면 그 저장소가 티켓의 저장소가 된다(예: `org/web` 에 `feature/fb-12-login` 브랜치를 만들면 FB-12 의 저장소는 `org/web`). 저장소를 정한 프로젝트에서는 그 가운데 하나일 때만이다. 저장소를 정하지 않은 프로젝트·독립 티켓·저장소가 하나뿐인 프로젝트도 정해지며, 옆 패널 "Git" 에 그 저장소가 링크로 보인다(고를 것이 둘 이상일 때만 고르는 칸). 커밋만으로는 정하지 않는다. 이미 고른 저장소는 바꾸지 않으므로, 다른 저장소로 옮기려면 옆 패널에서 직접 바꾼다. 저장소를 정하지 않은 프로젝트에서 정해진 저장소는 보여 주기만 하고 받는 이벤트를 좁히지 않는다(아래 3번).

**어느 이벤트가 티켓에 닿는가** (위에서부터 먼저 맞는 것):

1. 티켓에 고른 저장소가 있고 그것이 아직 프로젝트의 저장소면: 그 저장소에서 온 이벤트만.
2. 프로젝트가 저장소를 정했으면: 그 저장소들에서 온 이벤트만.
3. 그 밖(저장소를 정하지 않은 프로젝트, 독립 티켓): 연결된 모든 저장소에서 온 이벤트.

**연결의 "허용 프로젝트"는 없앴다** (사용자 지정, D-149): 관리자 설정의 Git 연결에서는 프로젝트를 고르지 않고, 어느 프로젝트에 어느 저장소가 닿는지는 프로젝트 설정에서만 정한다. 표 `git_connection_projects` 와 `git_connections.all_projects` 는 `V15` 에서 지웠다.

API: `GET /api/v1/projects/{id}/git`(들어갈 수 있는 사람) → `{ enabled, connections, repositories, suggestions }`, `PUT /api/v1/projects/{id}/git/repositories`(관리 역할) `{ repositories: [{ connectionId, repository }] }`, `PUT /api/v1/issues/{번호}/git-repository`(편집 역할) `{ connectionId, repository }` 또는 `{}`로 지우기(204, 프로젝트의 저장소가 아니면 422 `NOT_IN_PROJECT`). 티켓 응답에 `gitRepository`, `gitRepositoryOptions` 가 더해졌다. 표는 `project_git_repositories`, `issue_git_repositories`(`V14`).

## 쓰는 흐름

1. 관리자가 **관리자 설정 > Git 연동**에서 "연결 추가": 이름, 종류(Gitea·GitLab·GitHub), 서버 주소를 정하고 저장한다.
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
| PR 본문에 `Fixes FB-12`, `Closes FB-12`, `Resolves FB-12` (활용형 `fix`·`fixed`·`close`·`closed`·`resolve`·`resolved` 포함, 대소문자 무시) | 닫는 연결 | 티켓 완료 |
| 커밋 메시지에 같은 닫는 말 | 커밋 연결 | 기본 브랜치에 push 된 커밋일 때만 티켓 완료(아래 "상태 자동 변경"). 다른 브랜치의 커밋은 PR 의 닫는 연결을 만들지 않는다 |
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

**Git 이 연결되지 않았을 때** (사용자 지정, D-145): Git 은 선택 사항이다. 일반 사용자에게 보이는 Git UI(옆 패널 "Git" 묶음, 활동 줄, 5단계의 "브랜치 이름 복사")는 쓸 연결이 없으면 나오지 않는다. 지금의 묶음과 활동 줄은 그 티켓에 연결이 있을 때만 나오므로 저절로 그렇다. 브랜치 이름 복사는 사용 중인 연결이 있는지 알려 주는 값(예: 워크스페이스 응답의 `gitEnabled`)과 함께 만든다. 관리자 설정의 "Git 연동" 탭은 연결을 만드는 곳이라 늘 보인다.

**활동 기록**에 새 줄

| 종류 | 문구 예 |
|---|---|
| `GIT_BRANCH_LINKED` | 김지민 님이 브랜치를 연결했습니다: `kimjm/fb-12-login` |
| `GIT_PR_LINKED` | 김지민 님이 PR 을 연결했습니다: `web#34 로그인 오류 수정` |
| `GIT_PR_MERGED` / `GIT_PR_CLOSED` | 이수진 님이 PR 을 머지했습니다 / 닫았습니다: `web#34` |
| `GIT_COMMITS_PUSHED` | 김지민 님이 커밋 3개를 올렸습니다 (펼치면 커밋 제목 5개까지, 각 줄은 Git 서버 링크) |
| `STATUS_CHANGED` (Git 출처) | 이수진 님이 Git 에서 상태를 바꿨습니다: 진행 중 → 완료 |

**알림**: Git 이 바꾼 **상태**는 손으로 바꾼 것처럼 구독자에게 알림으로 간다. **D-126 의 1분 모아 보내기는 하지 않고 바로 보낸다**(사용자 지정, D-155): Git 서버 호출 한 번(웹훅 한 번, API 가져오기 한 회차)에서 한 티켓에 생긴 연결·커밋·상태 변경은 **한 통**으로 묶여, 그 가운데 마지막 일을 한 사람 이름으로 간다. 같은 사람이 손으로 한 변경이 모여 있으면 그것도 함께 나간다. 다른 호출(예: 브랜치를 만든 회차와 커밋을 올린 다음 회차)은 따로 간다. PR·브랜치 연결과 커밋은 활동 기록과 화면에만 남고 메일·웹 푸시는 보내지 않는다(커밋마다 알림이 오면 너무 많다). API 알림(웹훅)의 "보낼 변경"에서 Git 종류(`GIT_BRANCH_LINKED`, `GIT_PR_LINKED`, `GIT_PR_MERGED`, `GIT_PR_CLOSED`, `GIT_COMMITS_PUSHED`)를 **직접 고른** API 알림에만 그것도 보낸다. 아무것도 고르지 않은 API 알림("모든 변경")에는 가지 않는다. Git 이 쓰이지 않으면 이 선택지를 보이지 않는다.

**브랜치 이름 복사**: 티켓 상세 위의 키 옆 메뉴에 "브랜치 이름 복사". 형식은 `<내 아이디>/fb-12-<제목 슬러그>`. 슬러그는 제목에서 영문·숫자만 남겨 `-` 로 잇고(한글 제목이면 비어서 `kimjm/fb-12`), 전체 60자까지.

**관리자 설정 "Git 연동" 탭** (`/admin/git`)

| 항목 | 내용 |
|---|---|
| 이름, 사용 | |
| 종류 | Gitea(Forgejo 포함), GitLab, GitHub |
| 서버 주소 | `https://git.example.com`. GitHub 는 `https://github.com` 이 기본. 표시와 등록 안내에만 쓴다 |
| ~~허용 프로젝트~~ | 없앴다(D-149). 프로젝트 설정의 "Git 저장소"에서 정한다 |
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

## GitHub: GitHub App 으로 연결 (D-146)

상태 (2026-10-16): **구현했다.** 사용자가 GitHub(github.com)는 조직 웹훅이 아니라 **GitHub App** 으로 붙이기로 정했다(D-146). 아래는 GitHub 공식 문서(docs.github.com)를 보고 정리한 등록·연동 방법이고, 코드는 이대로다. 이 절이 위의 "Git 서버에 등록하기" 표의 GitHub 칸을 대신한다(GitLab 은 그대로 웹훅).

**왜 GitHub App 인가**: 사람 계정에 묶이지 않는 토큰(설치 토큰)을 쓰고, 권한을 저장소 읽기로 좁힐 수 있고, 조직이 고른 저장소에만 설치된다. Flowbit 이 인터넷에서 열려 있으면 웹훅으로 바로 받고, 열려 있지 않으면 같은 앱의 토큰으로 GitHub API 를 주기적으로 읽어 온다. **어느 쪽이든 GitHub 쪽 설정은 한 번이다.** 개인 OAuth 토큰은 그 사람이 나가면 멈추고, 비공개 저장소를 읽으려면 `repo`(쓰기 포함) 권한을 받아야 해서 쓰지 않는다.

### 등록: 앱 만들기 (관리자 화면에서 버튼 하나, "매니페스트 흐름")

GitHub 은 앱 설정을 JSON(매니페스트)으로 넘겨 앱을 만들게 해 준다. 관리자가 칸을 손으로 채우지 않아도 되고, 비공개 키를 내려받아 옮길 일도 없다. 모두 브라우저 이동이라 **Flowbit 이 사내망에만 있어도 된다.**

1. 관리자가 **Git 연동 > 연결 추가 > GitHub** 에서 GitHub 조직 이름(`acme`)과 받는 방식(웹훅/가져오기)을 고르고 "GitHub 에서 앱 만들기"를 누른다.
2. Flowbit 이 브라우저에서 `https://github.com/organizations/{조직}/settings/apps/new?state={일회용 값}` 로 `manifest` 필드를 POST 한다. 매니페스트:
   ```json
   {
     "name": "Flowbit (회사 이름)",
     "url": "{FLOWBIT_PUBLIC_URL 또는 브라우저가 연 주소}",
     "hook_attributes": { "url": "{…}/api/v1/integrations/git/{id}/webhook", "active": true },
     "redirect_url": "{…}/admin/git/github/created",
     "setup_url": "{…}/admin/git/github/installed",
     "public": false,
     "default_permissions": { "contents": "read", "pull_requests": "read", "metadata": "read" },
     "default_events": ["push", "pull_request"]
   }
   ```
   가져오기 방식이면 `hook_attributes` 를 **아예 넣지 않는다**(이벤트 구독도 비운다). GitHub 은 꺼 둔 웹훅(`active: false`)의 주소도 인터넷에서 닿는지 검사해서 `localhost` 면 "Hook url is not supported because it isn't reachable over the public Internet" 으로 앱 만들기를 거절한다(사용자가 겪음, 2026-10-16). 웹훅 방식인데 Flowbit 주소가 localhost·사설 IP(10·172.16~31·192.168)·도메인 없는 이름이면 GitHub 으로 보내기 전에 422 `WEBHOOK_NOT_REACHABLE`(필드 `receiveMode`)로 막는다. 가져오기로 만든 앱을 나중에 웹훅으로 바꾸려면 연결을 새로 만든다(앱에 웹훅 비밀 값이 없다).
3. GitHub 화면에서 관리자가 이름을 확인하고 **Create GitHub App** 을 누른다. **조직 소유자(owner) 권한이 필요하다.** 앱 이름은 GitHub 전체에서 유일하고 34자까지다.
4. GitHub 이 `redirect_url?code=…&state=…` 로 돌려보낸다. Flowbit 은 `state` 를 확인하고 서버에서 `POST https://api.github.com/app-manifests/{code}/conversions` 를 부른다. 응답의 `id`(앱 ID), `slug`, `client_id`, `client_secret`, `webhook_secret`, `pem`(비공개 키)을 연결에 저장한다. **세 단계를 1시간 안에 끝내야 하고** `code` 는 한 번만 쓴다. 이 호출에 인증이 필요한지는 문서 예시에 헤더가 없어 "필요 없음"으로 보지만, 구현할 때 실제로 확인한다.

손으로 만들 수도 있다(매니페스트가 막힌 조직용 안내): 조직 Settings > Developer settings > GitHub Apps > **New GitHub App** 에서 같은 값을 넣고(Homepage URL, Webhook **Active**·URL·Secret, Permissions 의 Contents·Pull requests **Read-only**, Subscribe to events 의 **Push**·**Pull request**, "Only on this account"), **Generate a private key** 로 받은 `.pem` 과 App ID 를 Flowbit 에 붙여 넣는다.

### 설치: 저장소 고르기

5. 앱을 만든 뒤 Flowbit 이 "조직에 설치" 버튼으로 `https://github.com/apps/{slug}/installations/new` 를 연다. 관리자가 **All repositories** 또는 **Only select repositories** 를 고르고 Install 을 누른다.
6. GitHub 이 `setup_url?installation_id=…&setup_action=install` 로 돌려보낸다. Flowbit 은 `installation_id` 를 그대로 믿지 않고 앱 JWT 로 설치를 찾는다: 먼저 `GET /orgs/{조직}/installation`, 못 찾으면 `GET /app/installations` 에서 계정이 그 조직인 것(없으면 하나뿐인 설치). "설치 확인"이 그래도 못 찾으면 409 `GITHUB_NOT_INSTALLED` 로 이유(설치된 곳이 없음, 또는 다른 계정에 설치됨)를 보인다. GitHub 이 거절하면 502 `GITHUB_UNAVAILABLE` 에 GitHub 의 말(`message`)을 붙여 화면에 그대로 보인다.
7. 저장소를 나중에 더하거나 빼는 것은 GitHub 의 앱 설치 설정에서 한다. 웹훅 방식이면 `installation_repositories` 이벤트로, 가져오기 방식이면 다음 주기의 저장소 목록으로 Flowbit 이 안다.

### 인증 (서버가 GitHub API 를 부를 때)

- **앱 JWT**: RS256 으로 서명. `iat` = 지금 − 60초(시계 차이), `exp` = 지금 + 최대 10분, `iss` = 앱의 client ID(앱 ID 도 된다). `Authorization: Bearer <JWT>`. 설치 토큰을 만들 때와 설치 목록을 볼 때만 쓴다.
- **설치 토큰**: `POST /app/installations/{installation_id}/access_tokens` (JWT 필요). **1시간 뒤 만료**되므로 만료 몇 분 전에 다시 만든다(메모리에만 두고 DB 에 저장하지 않는다). 본문에 `repositories`(최대 500개)·`permissions` 를 넣어 더 좁힐 수 있다. 토큰 모양이 바뀌는 중(`ghs_…`)이라 길이를 가정하지 않는다.
- JWT 는 블로킹 없이 만들 수 있다(JDK 의 `Signature`). 바깥 호출은 `WebClient` 에 시간 제한(03-backend-guide).
- 비밀 값(`pem`, `client_secret`, `webhook_secret`)은 다른 연동 설정처럼 DB 평문(D-090)이고, 화면에는 "설정됨"만 보인다. 백업의 `GIT` 항목에 들어간다.

### 받는 방식 A: 웹훅 (Flowbit 을 인터넷에 열 수 있을 때, 권장)

- 지금의 웹훅 경로를 그대로 쓴다. 헤더 `X-GitHub-Event`(`push`, `pull_request`, `ping`, `installation`, `installation_repositories`), `X-GitHub-Delivery`(배달 GUID), `X-Hub-Signature-256: sha256=<HMAC-SHA256 hex>`(앱의 웹훅 비밀 값). 본문에 `installation.id` 가 있어 어느 설치의 이벤트인지 안다.
- `ping` 은 앱을 만들 때 한 번 오고 200 으로 무시한다. `installation`(created·deleted·suspend·unsuspend)은 연결 상태를 바꾸는 데 쓴다. GitHub 의 이벤트 본문은 최대 25MB 다. Flowbit 의 한도는 5MB 라, 아주 큰 push 는 413 이 될 수 있다(그때는 한도를 올린다).
- 본문 모양은 Gitea 와 같아 `ForgePayloads(branchPath = "tree")` 를 쓴다.

### 받는 방식 B: 가져오기 (Flowbit 이 사내망에만 있을 때)

Flowbit 이 `api.github.com` 으로 나가는 연결만 있으면 된다. 실시간이 아니라 **주기(기본 2분)만큼 늦다.** GitHub 은 가능하면 웹훅을 쓰라고 권하고, 가져올 때는 정해진 주기, 조건부 요청, 필요한 것만 읽기를 권한다.

한 주기에 연결마다:

1. `GET /installation/repositories` — 설치된 저장소 목록(ETag).
2. 저장소마다 `GET /repos/{o}/{r}/pulls?state=all&sort=updated&direction=desc&per_page=50` — 지난번에 본 `updated_at` 보다 새것만 처리하고 오래된 것이 나오면 멈춘다. 목록에는 `merged_at`·`draft`·`head.ref`·`user.login`·`body` 가 있고 **`merged_by` 가 없어**, 새로 머지된 PR 만 `GET /repos/{o}/{r}/pulls/{번호}` 로 한 번 더 읽는다.
3. 저장소마다 `GET /repos/{o}/{r}/branches?per_page=100` — 키가 든 새 브랜치를 찾는다. 그 브랜치와 기본 브랜치는 `GET /repos/{o}/{r}/commits?sha={브랜치}&since={지난 시각}` 으로 새 커밋을 읽는다.
4. 읽은 것을 웹훅과 같은 `GitPush`·`GitPullRequest` 로 바꿔 `GitLinkService.apply` 에 넘긴다. 같은 것을 다시 읽어도 두 번 쌓이지 않는 지금의 규칙이 그대로 지켜 준다. 가져오기에서는 "올린 사람"을 알 수 없어 커밋 작성자(`author.login`)를 쓴다.
5. 어디까지 봤는지(저장소별 PR `updated_at`, 브랜치별 마지막 커밋, ETag)는 새 표에 둔다(`git_sync_cursors`, 마이그레이션은 구현 때).

**호출 한도**: 설치 토큰은 시간당 5,000회(저장소·사용자가 20을 넘으면 하나당 50회씩 늘어 최대 12,500회, Enterprise Cloud 는 15,000회). 저장소 50개면 한 주기에 150~200회지만, **`If-None-Match`(ETag)로 보낸 요청이 304 면 한도에 세지 않으므로** 바뀐 저장소만 실제로 센다. 한도에 걸리면 `retry-after`/`x-ratelimit-reset` 만큼 쉬고, 동시 요청은 저장소 몇 개씩만 한다. 관리자 화면에 마지막 동기화 시각과 남은 한도를 보인다.

### 화면 (관리자 설정 > Git 연동, GitHub)

- 연결 추가에서 종류 GitHub 를 고르면: 조직 이름, 받는 방식(웹훅/가져오기, Flowbit 주소가 없으면 가져오기만), "GitHub 에서 앱 만들기" 버튼.
- 만든 뒤: 앱 이름·링크, 설치 상태("설치 안 됨 → 조직에 설치" 버튼 / 설치됨: 모든 저장소 또는 저장소 N개), 마지막 수신(웹훅) 또는 마지막 동기화와 남은 호출 수(가져오기), "지금 동기화" 버튼(가져오기).
- 웹훅 비밀 값은 GitHub 이 앱에 넣어 두므로 관리자가 따로 옮길 일이 없다. "다시 만들기"는 GitHub 앱 설정과 함께 바꿔야 해서 GitHub 에는 두지 않는다(앱 설정 화면 링크로 안내).

### 사람 맞추기

**내 설정 > GitHub 계정** (D-150): 각자 GitHub 계정을 연결한다. 같은 GitHub App 의 사용자 인증(OAuth 웹 흐름)을 쓴다: "GitHub 계정 연결" → `<GitHub>/login/oauth/authorize?client_id=…&redirect_uri=<Flowbit>/settings/github/callback&state=…` → GitHub 이 돌려보낸 code 를 `POST <GitHub>/login/oauth/access_token`(`Accept: application/json`)으로 바꿔 `GET /user` 로 login·id 만 읽고, 사용자 토큰은 저장하지 않고 버린다. 표 `git_github_accounts`(`V16`). 같은 GitHub 계정을 두 사람이 연결할 수 없다(409 `GITHUB_ACCOUNT_TAKEN`). state 는 서버 메모리에 10분(단일 인스턴스). redirect URI 는 앱의 Callback URL 이어야 해서, 매니페스트에 `callback_urls` 로 넣는다. 그 전에 만든 앱은 GitHub 의 앱 설정에서 더해야 한다(관리자 화면이 주소를 보여 준다). 연결할 GitHub App 이 없으면 이 설정은 보이지 않는다. API: `GET /api/v1/me/git/github`(`{ available, login, linkedAt }`), `POST /api/v1/me/git/github/authorize?origin=`(`{ url }`, 앱이 없으면 409 `GITHUB_APP_MISSING`), `POST /api/v1/me/git/github/callback`(`{ code, state }`), `DELETE /api/v1/me/git/github`.

**사람 찾기 순서** (D-152): 푸시한 사람(커밋 작성자·PR 을 만든 사람)의 이메일을 Flowbit 의 이메일과 아이디에 맞춰 본다 → (GitHub 이벤트일 때) 본인이 연결한 GitHub 계정(같은 GitHub 서버) → Git 사용자 이름을 Flowbit 아이디에 맞춰 본다 → 모두 없으면 Git 이름만 남는다.

**활동 줄의 이름** (D-153): Flowbit 계정과 맞으면 그 사람 이름, 맞지 않으면 Git 서버의 이름(`kimjm`, 마우스를 올리면 "Flowbit 계정과 맞춰지지 않은 GitHub 사용자")이고, 줄 끝에 시각과 함께 서버 종류(`· GitHub`)가 붙는다. 이벤트가 아무도 말하지 않으면(GitHub API 가져오기에서 커밋 없이 만든 브랜치처럼, API 가 누가 만들었는지 알려 주지 않는다) "GitHub에서 브랜치를 연결했습니다"로 쓴다. 상태 변경 줄의 "Git에서"는 서버 종류 표시로 대신했다. 그래서 `detail.via` 는 `{source:"git", provider:"GitHub", name}`(name 은 없으면 null)이다. 알림 제목은 `kimjm(GitHub) 님이 Git에서 티켓을 수정했습니다`, 아무도 말하지 않으면 `GitHub에서 티켓을 수정했습니다`.

**관리자의 "사람 맞추기"는 없앴다** (사용자 지정, D-152): 관리자가 구성원마다 Git 아이디를 적던 것(D-147, 표 `git_user_accounts`)은 `V18` 에서 표째 지웠다. 이메일이나 아이디가 맞지 않는 사람은 Git 서버의 이메일이나 아이디를 Flowbit 과 같게 하거나, GitHub 라면 내 설정에서 GitHub 계정을 연결한다. GitHub 의 PR 이벤트·목록에는 이메일이 없어서, GitHub 에서는 연결한 GitHub 계정이나 같은 아이디가 아니면 PR 이 사람에게 이어지지 않는다(커밋에는 이메일이 있다).

### 확인이 필요한 것 (GitHub App)

- 실제 배포에서 Flowbit 의 웹훅 경로를 인터넷에 열지(받는 방식 A/B). 사용자: "배포하면 가능은 하다". 둘 다 구현돼 있어 연결마다 고른다.
- 매니페스트로 앱을 만드는 사람이 조직 소유자인지. 아니면 소유자에게 부탁해야 한다.
- 가져오기 주기(기본 2분, `flowbit.git.sync-interval`)가 괜찮은지.
- `POST /app-manifests/{code}/conversions` 를 인증 없이 부르는데, 실제 github.com 에서 되는지 확인하지 않았다.

**구현하며 정한 것 (D-146)**: 받는 방식은 연결마다 `receive_mode`(웹훅이면 매니페스트의 웹훅을 켜고 push·pull_request 를 구독, 가져오기면 웹훅을 끈다). 앱의 웹훅 비밀 값은 GitHub 이 만든 것을 그대로 쓰고 Flowbit 에서 다시 만들지 않는다(422). 앱을 만든 뒤에는 종류와 조직을 바꿀 수 없다(422 `LOCKED`). 설치는 `setup_url` 의 `installation_id` 를 믿지 않고 앱 JWT 로 `GET /orgs/{조직}/installation` 을 읽는다. `installation`·`installation_repositories` 웹훅이 오면 다시 읽는다. 가져오기의 첫 동기화는 저장소마다 기준(PR 의 최신 `updated_at`, 브랜치 머리)만 잡는다. 새 커밋은 `GET /repos/{저장소}/compare/{이전}...{지금}` 으로 읽어(브랜치를 처음 보면 기본 브랜치와 비교) 기본 브랜치의 예전 커밋이 걸리지 않게 했다. GitHub Enterprise Server 는 서버 주소를 넣으면 `<주소>/api/v3` 를 부른다.

## 받는 방식

```
Git 서버 ─ POST /api/v1/integrations/git/{id}/webhook ─▶ GitWebhookController
   ├ 연결 찾기 (없거나 꺼져 있으면 404)
   ├ 본문을 바이트 그대로 읽고 종류별로 서명 확인 (틀리면 401, 상수 시간 비교)
   ├ GitProvider.parse → 공통 이벤트(GitPush, GitPullRequest). 모르는 이벤트는 200 으로 무시
   └ GitLinkService.apply (트랜잭션)
        ├ IssueKeyMatcher 로 키 찾기 → 티켓·프로젝트가 정한 저장소에서 온 것만(D-148)
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
| GET | `/api/v1/git/status` | 로그인 | `{ enabled }`: 켜진 연결(GitHub 는 설치까지 끝난 것)이 있는지. 없으면 브랜치 이름 복사와 API 알림의 Git 종류를 보이지 않는다 |
| GET | `/api/v1/admin/git/connections/{id}/github/manifest?origin=` | 관리자 | `{ action, manifest }`: 브라우저가 GitHub 에 POST 할 폼. 앱이 이미 있으면 409 `GITHUB_APP_EXISTS` |
| POST | `/api/v1/admin/git/github/created` | 관리자 | `{ code, state }` → 연결. GitHub 이 돌려보낸 code 를 앱 ID·키로 바꾼다. state 가 틀리면 422, GitHub 오류는 502 `GITHUB_UNAVAILABLE` |
| POST | `/api/v1/admin/git/github/installations` | 관리자 | GitHub 연결 전부의 설치를 다시 읽고 목록을 돌려준다(설치 뒤 돌아오는 페이지가 부른다) |
| POST | `/api/v1/admin/git/connections/{id}/github/installation` | 관리자 | 한 연결의 설치를 다시 읽는다 |
| POST | `/api/v1/admin/git/connections/{id}/sync` | 관리자 | 가져오기를 지금 한 번 → `{ linkedIssues, statusChanged, skipped }` |

연결 요청·응답에서 `allProjects`, `projectIds` 는 빠졌다(D-149). 연결 요청에 `receiveMode`(`WEBHOOK`\|`POLL`, POLL 은 GitHub 만), `githubOrganization`(GitHub 만, 필수)이 더해졌고, 응답에 `receiveMode`, `github: { organization, appId, appSlug, appUrl, installUrl, settingsUrl, installationId, repositorySelection }`, `lastSyncedAt`, `syncError`, `rateLimitRemaining` 이 더해졌다.

오류: 422 `INVALID_URL`(서버 주소), 422 `REQUIRED`, 404 `GIT_CONNECTION_NOT_FOUND`.

## 코드 구조

- 백엔드 `com.flowbit.git` 패키지
  - `GitWebhookController`, `GitConnectionController`, `GitConnectionService`
  - `GitProvider`(인터페이스: `verify(headers, body, secret)`, `parse(headers, body): List<GitEvent>`), `GiteaProvider`, `GitLabProvider`, `GitHubProvider`. Gitea 와 GitHub 는 본문 모양이 거의 같아 파싱을 함께 쓴다.
  - `IssueKeyMatcher`(워크스페이스 키로 정규식을 만들고 키가 바뀌면 다시 만든다), `GitLinkService`
- `IssueService` 에 사람 대신 **연결이 바꾸는 경로**를 더한다: 권한 검사 없이(어느 저장소가 닿는지는 Git 모듈이 정한다), 활동의 `actor_id`·`via` 를 받는다. 지금 `update()` 와 `IssueAnnouncer` 는 `AuthenticatedUser` 를 받으므로, 바꾼 사람을 `Actor`(사람 또는 Git) 로 넓히는 작은 리팩터링이 먼저 필요하다. 알림 문구(`NotificationText`)는 `actorName` 만 받으므로 그대로 쓴다.
- 프런트엔드: `app/(app)/admin/git/page.tsx` 와 관리자 탭, `features/issues/` 의 옆 패널 "Git" 묶음, 활동 줄 문구, 키 메뉴의 브랜치 이름 복사.

## 구현 순서

각 단계가 끝나면 테스트와 함께 커밋한다. 1~3단계는 2026-10-15(Gitea), 4~5단계와 API 알림의 Git 종류, 구성원 Git 아이디는 2026-10-16 에 됐다.

구현하며 정한 것 (D-145):

- (1~3단계 동안은 Gitea 만 고를 수 있었다. 지금은 세 종류 모두 된다.)
- GitLab 은 서명이 없고 `X-Gitlab-Token` 에 비밀 값이 그대로 온다. `Push Hook`·`Merge Request Hook` 만 본다. MR 은 `iid` 를 번호로 PR 처럼 저장하고, 상태는 `state`(opened·closed·locked·merged)와 `draft`, 한 사람은 이벤트의 `user`(연 사람·머지한 사람). 이메일 `[REDACTED]` 는 버린다. 브랜치 주소는 `<프로젝트>/-/tree/<브랜치>`.
- Gitea 는 `X-Gitea-Signature`(Forgejo 는 `X-Forgejo-Signature` 도) 와 `X-Gitea-Event`(`X-Forgejo-Event`) 를 본다. PR 의 모든 변경(열림, 닫힘, 수정, 동기화, 담당 지정…)이 `pull_request` 로 오고 동작은 본문의 `action` 이다. 초안은 `draft` 값이나 제목 앞의 `WIP:`·`[WIP]` 로 안다.
- 키가 든 브랜치에 올린 커밋은 메시지에 키가 없어도 그 티켓에 연결한다. 기본 브랜치에 올린 커밋의 닫는 말만 완료로 바꾼다.
- 상태를 진행 중으로 바꾸는 것은 닫는 연결이 **처음** 생길 때(브랜치를 처음 올림, PR 이 처음 연결됨)와 PR 이 다시 열릴 때뿐이다. 그래서 손으로 되돌린 상태를 같은 브랜치의 다음 push 가 다시 바꾸지 않는다.
- 응답과 `last_result`: `{ linkedIssues: ["FB-12"], statusChanged: ["FB-12"], skipped: 0 }`(티켓 키). 모르는 이벤트는 같은 모양의 빈 값이다. 서명이 틀린 호출, 읽을 수 없는 본문(400 `INVALID_PAYLOAD`)도 "마지막 수신"에 오류로 남긴다. 본문이 크면 413 `PAYLOAD_TOO_LARGE`.
- 활동의 Git 줄 `detail`: 브랜치 `{repository, branch, url}`, PR `{repository, number, title, url, closes}`, 커밋 `{repository, branch, count, commits: [{sha(10자), message(첫 줄), url}] (5개까지)}`. 모두 `via` 를 단다.

1. **연결 관리**: `V11`, 관리자 API, `/admin/git` 화면, 비밀 값 발급, 백업 항목.
2. **받기와 연결 (Gitea 먼저)**: 웹훅 경로, 서명 확인, 키 인식, `issue_git_links`·`issue_git_commits` 저장, 마지막 수신 결과. 옆 패널 "Git" 묶음과 활동 줄.
3. **상태 자동 변경**: `Actor` 리팩터링, 규칙 적용, 실시간 반영, 알림.
4. **GitLab, GitHub**: 제공자 두 개를 더한다. 실제 페이로드 견본으로 테스트한다. **GitHub 는 GitHub App 으로 붙인다(D-146, 위의 "GitHub: GitHub App 으로 연결")**: 4a 매니페스트로 앱 만들기·설치·JWT·설치 토큰, 4b 웹훅 받기, 4c 가져오기.
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
- 사람 맞추기: 이메일(Flowbit 의 이메일·아이디와), 그다음 본인이 연결한 GitHub 계정, 그다음 Git 사용자 이름을 Flowbit 아이디로, 모두 없으면 Git 이름만. 상태 변경은 구독자에게 알림으로 가고 연결·커밋은 그 종류를 고른 API 알림에만 간다
- 화면(E2E): 연결을 추가하면 주소와 비밀 값이 보이고, 연결이 있는 티켓의 옆 패널에 PR 줄이 보인다 (`frontend/e2e/git.spec.ts`)
- GitHub App(`GitHubIntegrationTest`, 가짜 GitHub): 매니페스트·code 교환·설치 읽기, 앱 JWT 서명, 웹훅 서명과 `ping`·설치 이벤트, 가져오기의 기준 잡기·ETag 304·브랜치·커밋·PR·머지

## 확인이 필요한 것

- ~~어떤 Git 서버를 먼저 붙일지~~ → Gitea (사용자 확정, D-145).
- "리뷰 중" 상태가 필요한지. 지금 상태는 고정 5개라, 두려면 상태 구조부터 바꿔야 한다.
- 커밋을 활동에 남길지, PR·브랜치만 남길지(커밋이 많은 팀은 활동이 길어진다).
- 관리자가 Git 아이디를 적는 것으로 모자라면 "내 설정 > Git 계정"(개인 OAuth 연결)을 더할지.
- GitHub.com 을 웹훅으로 받을 경우 외부에 열 주소와 리버스 프록시 구성(가져오기로 받으면 필요 없다).
- 비밀 값이 DB 에 평문이다(다른 연동 설정과 같다, D-090).

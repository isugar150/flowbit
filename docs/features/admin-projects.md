# 관리자 설정 > 프로젝트

관리자가 **프로젝트의 구성원이 아니어도** 모든 프로젝트의 설정을 바꾸는 화면이다(2026-10-28 사용자 요청, [D-236](../09-decisions.md)). 관리자 설정의 탭 가운데 "구성원" 바로 오른쪽에 있다. 관리자가 아닌 구성원은 관리자 설정 자체에 들어올 수 없다(`admin/layout.tsx` 가 `/` 로 보내고, 백엔드는 `/api/v1/admin/**` 를 403 으로 막는다).

## 정한 것

| 항목 | 내용 | 정한 사람 |
|---|---|---|
| 위치 | 관리자 설정 탭 "프로젝트", 주소 `/admin/projects?project=<프로젝트 id>`. 왼쪽에 프로젝트 목록, 오른쪽에 고른 프로젝트의 설정(구성원 탭의 두 칸 모양과 같다). 목록은 이름으로 찾고, 스크롤해도 화면에 붙어 있다 | 사용자 |
| 설정 내용 | **프로젝트 설정 화면(`/project/[id]/settings`)과 같은 섹션을 그대로 쓴다**: 프로젝트 정보(아이콘·이름), 공개 범위, 사이클, 트리아지, 티켓 속성(공통 속성), 자동화, 추정치, 구성원과 승인 대기, Git 저장소(Git 을 쓸 때만), 아카이브. 컴포넌트를 복사하지 않고 `ProjectSettingsSections` 를 두 화면이 함께 쓴다 | 사용자 |
| 목록에 오르는 프로젝트 | 기존 프로젝트 목록(`GET /api/v1/projects`)과 같다: **사용 중인 프로젝트 전부**(D-047, 구성원이 아니어도 목록에는 보인다). 보관된 프로젝트는 기존 "아카이브됨" 목록처럼 **목록 아래에 따로** 두고, 설정은 바꿀 수 없고(보관된 프로젝트는 들어갈 수 없다) **복원만** 한다. 관리자가 여기서 보관하고도 되돌릴 길이 있어야 해서, 이 화면의 복원은 모든 프로젝트에 된다(프로젝트의 자기 복원은 관리 역할만, D-131) | 에이전트 |
| 관리자가 얻는 것 | 설정에 한해서만 그 프로젝트의 관리 역할로 본다. 프로젝트의 **티켓·문서·개요는 여전히 못 본다**(D-131 유지). `PATCH` 는 이름·공개 범위·추정치·트리아지만 받고, 개요 문서·속성(상태·우선순위·리드·기간·라벨)은 거부하며, 응답의 `content` 는 늘 비어 있다 | 에이전트 |
| 기존 API | `/api/v1/projects/**` 는 바꾸지 않았다. 관리자도 구성원이 아닌 프로젝트에서는 그쪽이 그대로 404(복원은 403)다. 우회는 아래 관리자 경로뿐이다 | 에이전트 |
| 감사 | 이 화면에서 한 변경은 감사 로그 `PROJECT_ADMIN_CHANGED`(화면 이름 "프로젝트 설정 변경(관리자 설정)")로 남는다. 아카이브·복원은 서비스가 원래 남기는 `PROJECT_ARCHIVED`·`PROJECT_RESTORED` 가 있어 겹쳐 남기지 않는다 | 에이전트 |

## API

모두 `/api/v1/admin/projects/{projectId}` 아래이고 관리자 역할이 필요하다(`SecurityConfig`). 요청·응답은 프로젝트 설정 화면이 부르는 `/api/v1/projects/{projectId}` 쪽과 같다. 서비스는 `administrator` 인자를 받아 그 호출에서만 관리 역할로 보고, 관리자 역할이 아니면 403 으로 한 번 더 막는다(`ProjectService.access`). 사용 중이 아닌(보관된) 프로젝트는 `restore` 를 빼고 404.

| 요청 | 설명 |
|---|---|
| `PATCH ` | `name`, `openToAll`, `estimatesEnabled`, `triageEnabled` 만. 그 밖의 항목은 403 |
| `PUT·DELETE /icon` | 프로젝트 아이콘 바꾸기·되돌리기 |
| `POST /archive` · `POST /restore` | 보관(204) · 복원(200). 감사는 `PROJECT_ARCHIVED`·`PROJECT_RESTORED` |
| `GET /members` · `PUT·DELETE /members/{userId}` | 구성원 목록, 역할 정하기·내보내기. 프로젝트에는 관리 역할이 한 명 이상 있어야 한다(409 `LAST_MANAGER`) |
| `GET /access-requests` · `POST …/{userId}/approve` · `DELETE …/{userId}` | 승인 대기 목록, 역할을 골라 승인, 거절 |
| `GET /cycles` · `PUT /cycles/settings` | 사이클 설정 |
| `GET /git` · `PUT /git/repositories` | Git 저장소(`?refresh=true` 도 된다) |
| `…/issue-fields`, `…/automations` | 티켓 속성(공통 속성)과 자동화. 각 명세([project-issue-fields.md](project-issue-fields.md))의 관리자 경로 |

`PROJECT_ADMIN_CHANGED` 의 `detail` 은 `{ projectId, change, … }` 이고 `change` 는 `settings`(+`fields`: 바꾼 항목 이름), `icon`, `member`·`member-removed`(+`memberId`, `role`), `access-approved`·`access-rejected`(+`memberId`, `role`), `cycles`(+`enabled`), `git` 이다. 값은 기록하지 않는다.

## 화면

- 프런트는 `ProjectApiScopeProvider` 의 범위(`project` | `admin`)로 같은 컴포넌트가 부를 주소를 고른다(`features/projects/scope.ts` 의 `projectPath`). 이 화면의 섹션은 모두 `admin` 범위다.
- 아카이브하면 이 화면의 목록으로 돌아온다(프로젝트의 자기 설정에서는 "아카이브됨" 목록). 보관된 프로젝트의 복원 버튼은 이 화면의 아카이브 목록에 있다.
- 구성원이 아닌 프로젝트를 고르면 오른쪽 맨 위에 "이 프로젝트의 구성원이 아닙니다. 관리자 권한으로 바꿉니다"가 나온다.
- 문구는 `ko.ts` 의 `admin.projectsTab`·`adminProjects` 이고, 설정 섹션의 문구는 프로젝트 설정과 같다.

## 인수 기준

- 관리자 설정에 "구성원" 오른쪽에 "프로젝트" 탭이 있고, 일반 구성원은 관리자 설정에 들어올 수 없다.
- 관리자가 구성원이 아닌 프로젝트의 이름·공개 범위·추정치·트리아지·사이클·Git 저장소·아이콘·구성원·승인 대기를 바꾸면 저장되고, 프로젝트의 관리 역할이 보는 설정에도 그대로 나타난다. 관리자는 그 프로젝트의 구성원이 되지 않는다.
- 관리자가 프로젝트를 보관하고 복원할 수 있다.
- 바꾼 내용이 감사 로그에 남는다.

위는 `AdminProjectSettingsIntegrationTest`(정상 동작만)로 확인한다.

## 아직 없는 것

- 프로젝트 만들기·영구 삭제, 프로젝트 목록의 정렬·묶기·보관된 프로젝트의 설정 보기.
- 거부 경로(허용되지 않은 `PATCH` 항목, 일반 구성원의 403)의 전용 테스트(사용자 지시로 정상 동작만 본다).

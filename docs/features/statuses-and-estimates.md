# 상태 추가와 추정치

상태 (2026-10-28): **구현됨** (D-228). `docs/01-product.md` §2 "상태"가 말한 대로, 다섯 **범주**는 고정이고 그 안에 상태를 더하거나 이름을 바꿀 수 있다. 추정치는 프로젝트마다 켜는 선택 기능이고 기본은 꺼짐이다.

## 상태

- 범주: `BACKLOG`(백로그) · `READY`(준비됨) · `IN_PROGRESS`(진행 중) · `DONE`(완료) · `CANCELED`(취소). **범주가 의미를 정한다**: 끝난 티켓인지, Git 이 티켓을 어디로 옮기는지(`GitStatusRules`), 하루 요약·캘린더·진행률이 무엇을 세는지. 모두 `issues.status`(범주)를 그대로 쓴다.
- 상태(`issue_statuses`)는 범주 하나에 속하고 이름·순서(범주 안의 `position`)를 가진다. 처음에는 범주마다 하나씩 다섯 개(고정 id `…0a1`~`…0a5`)다. **관리자 설정 > 상태** 탭에서 이름 바꾸기, 위·아래로 옮기기, 더하기("리뷰 중" 을 "진행 중" 범주에), 지우기를 한다. 이름은 30자까지, 대소문자 구분 없이 유일(409 `STATUS_NAME_TAKEN`). 범주의 마지막 상태는 지울 수 없고(409 `LAST_IN_CATEGORY`), 지우면 그 상태의 티켓은 같은 범주의 첫 상태로 옮겨진다. 감사 로그 `ISSUE_STATUSES_CHANGED`.
- 티켓은 `status`(범주)와 `status_id`(상태)를 함께 가진다. 요청: `statusId`(우선), 없으면 `status`(범주)만 말해도 된다(그 범주에 이미 있으면 그대로, 아니면 범주의 첫 상태). Git 이 옮길 때는 범주의 첫 상태. 활동 `STATUS_CHANGED` 의 `detail` 에는 범주(`from`·`to`)와 그때의 이름(`fromName`·`toName`)이 든다.
- 화면: 상태 메뉴(티켓 창, 목록 줄), 목록 묶음("상태별"), 필터, 보드의 열이 모두 상태 목록을 따른다. 필터·링크의 `?status=` 는 상태 id 이거나 범주 이름(`IN_PROGRESS`)이다(프로젝트 개요의 상태별 링크는 범주를 쓴다). 상태 목록은 앱 틀이 한 번 받아 `IssueStatusProvider` 로 나눠 준다.
- API: `GET /api/v1/issue-statuses`(모두), `POST·PATCH·DELETE /api/v1/admin/issue-statuses`(관리자; `PATCH` 는 `{name}` 또는 `{direction: UP|DOWN}`).
- 아직 없는 것: 범주 이름 바꾸기, 프로젝트마다 다른 상태, 상태 색, 캘린더·타임라인·검색·공유 화면의 상태 이름(범주 이름으로 보인다).

## 추정치

- 프로젝트 설정 > "추정치"(관리 역할)에서 켠다(`projects.estimates_enabled`, 기본 꺼짐). 켠 프로젝트의 티켓 속성에 **추정치** 칸이 생겨 0·1·2·3·5·8·13·21점 중에서 고른다(그 밖의 값이 이미 있으면 그 값도 보인다). 보드 카드에 점수가 보인다.
- 값은 `issues.estimate`(정수 0~1000, 비어 있을 수 있음). 끄면 칸만 숨고 값은 남는다. 합계·번다운 같은 집계는 아직 없다.
- API: 티켓의 `estimate`(요청·응답), 프로젝트의 `estimatesEnabled`(`PATCH /projects/{id}`).

코드: 백엔드 `issue/application/IssueStatuses.kt`·`IssueStatusController.kt`, 마이그레이션 `V7__issue_statuses_and_estimates.sql`. 프런트 `features/issues/status-defaults.ts`·`statuses.tsx`·`components/status-settings.tsx`, `app/(app)/admin/statuses`. 테스트 `StatusesIntegrationTest`.

# 저장한 뷰

상태 (2026-10-28): **구현됨** (D-229). 목록의 필터·정렬·묶음·보기 방식을 이름을 붙여 저장하고, 나만 쓰거나 모든 구성원에게 공유한다.

- 내 티켓 · 요청한 티켓 · 전체 티켓 목록의 도구 줄에 **뷰** 메뉴가 있다. 뷰 하나를 고르면 그 뷰가 만들어진 목록이 저장된 조건으로 열린다(다른 목록의 뷰면 그 주소로 간다). 메뉴 아래에 "지금 보기를 뷰로 저장…"과 "뷰 관리…".
- **저장되는 것**: 그 목록의 주소 조건 `project`·`status`·`priority`·`assignee`·`due`·`label`·`q`(검색어)·`sort`·`group`·`completed`·`view`·`cycle`. 쪽(`pages`)과 삭제된 티켓 보기는 저장하지 않는다. 뷰는 *주소*이므로 조건이 늘어도(새 필터가 생겨도) 저장 방식은 그대로다.
- **공유**: 저장할 때 또는 관리에서 "공유"를 켜면 다른 구성원의 뷰 메뉴 "공유된 뷰"에도 나온다. 고치기(이름·조건 바꾸기·공유 켜고 끄기)는 만든 사람만, 지우기는 만든 사람과 관리자. 한 사람이 100개까지, 이름은 40자까지.
- **관리**: 내가 만든 뷰(관리자는 공유된 뷰도)를 공유하거나, "지금 조건으로 바꾸기"로 조건을 덮어쓰거나, 두 번 눌러 지운다.
- API: `GET /api/v1/views`(내 것 + 공유된 것), `POST`(`{name, path, query, shared}`), `PATCH /{id}`(`{name?, query?, shared?}`), `DELETE /{id}`. `path` 는 `/issues`·`/my-issues`·`/my-issues/created` 중 하나. 응답에 `mine`·`canDelete`.
- 표 `saved_views`(마이그레이션 `V8`). 코드: `backend/…/savedview/SavedViews.kt`, `frontend/…/issues/components/saved-views-menu.tsx`. 테스트 `SavedViewsIntegrationTest`.
- 아직 없는 것: 뷰를 사이드바에 고정하기, 뷰 순서 바꾸기, 뷰 알림.

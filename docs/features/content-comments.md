# 블록 댓글과 활동 (프로젝트 개요·문서·티켓 본문·목표)

상태 (2026-10-21): **구현됨, 새 백엔드로는 아직 아무도 확인하지 않았다**(컴파일·ktlint·타입 검사·eslint 만, 테스트는 안 돌림. 화면은 샘플 데이터를 그린 임시 페이지로 말풍선·팝업·활동 목록만 봤다). 결정은 [09-decisions.md](../09-decisions.md) D-165. 사용자가 네 번에 나눠 답한 것을 그대로 옮겼고, "제안"이라고 적은 것만 에이전트가 정했다.

## 무엇을 만드는가

편집기를 쓰는 네 곳 — **프로젝트 개요의 설명, 프로젝트의 문서(티켓에 딸린 문서 포함), 티켓 본문, 목표 내용** — 의 **블록 하나하나에 댓글**을 달 수 있게 하고, 티켓 상세처럼 **"활동"**(변경 기록 + 댓글)이 프로젝트 개요·문서·목표 상세에도 나오게 한다. 티켓은 활동이 이미 있으므로 블록 댓글만 더한다.

## 확정 (사용자)

| 항목 | 내용 |
|---|---|
| 댓글 대상 | **블록 단위**. 글 일부가 아니라 블록 전체에 단다. 블록은 **최상위 블록**(문단·제목·인용·코드·이미지·임베드·페이지 블록은 각각 하나, 목록·표는 통째로 하나). 목록 항목·표 칸까지 쪼개지 않는다 |
| 위치 표시 | 블록 옆 말풍선 아이콘. 눌러서 **블록 옆 팝업**에서 스레드를 보고 답글을 단다(Notion 방식). 좁은 티켓 창·목표 창에서도 된다 |
| 누가 | **보기 역할도** 블록 댓글을 단다. 그래서 위치를 본문에 표시로 넣지 않고 **본문과 따로 저장**한다(보기 역할은 본문을 저장할 수 없다) |
| 스레드 | 댓글마다 **답글**, 끝난 논의는 **해결**(다시 열 수 있음). 해결하고 다시 열 수 있는 사람은 **댓글을 달 수 있는 누구나** |
| 지운 블록 | 그 블록의 댓글은 지우지 않고 **"삭제된 블록의 댓글"로 목록에 남긴다**. 블록이 돌아오면(실행 취소 등) 다시 붙는다. 블록을 옮기면 댓글이 따라간다 |
| 블록 댓글의 내용 | **글과 `@` 태그만**. 이모지·파일 올리기 없음. 티켓 본문의 블록 댓글도 같다 |
| 활동 | 프로젝트 개요·프로젝트 문서·티켓에 딸린 문서·목표 상세에 티켓 상세의 "활동"과 같은 모양으로 붙는다. 시간순으로 변경 기록, 일반 댓글, 블록 댓글 스레드가 섞여 나온다 |
| 활동에 쌓이는 것 | 본문 수정(티켓처럼 같은 사람의 연속 수정은 한 줄로, 알림도 모아서 한 번), **속성 변경 전부**, 문서 만들기·삭제·복원, 구성원 변경, 프로젝트 업데이트, 목표 만들기·삭제, 블록 댓글 |
| 문서 만들기·삭제·복원 | **그 문서를 품은 쪽의 활동**에 남는다: 프로젝트 개요에 만든 문서는 프로젝트 활동, 문서 안에 만든 하위 문서는 상위 문서 활동, 티켓의 문서는 티켓 활동(지금처럼). 문서 자신의 활동에는 제목·본문 수정만 |
| 블록 댓글이 활동에 보이는 모양 | **스레드 한 덩어리**: 인용한 블록의 글 아래에 댓글과 답글이 한 카드로 묶이고, **첫 댓글이 달린 시각 자리**에 있다. 해결된 스레드는 접히고 누가 해결했는지 나온다 |
| 일반 댓글 | 프로젝트 개요·문서·목표의 활동 맨 아래에 **블록과 관계없는 일반 댓글** 입력 칸을 둔다. 티켓의 것처럼 **이모지와 파일 올리기**가 있고, 활동 줄에도 이모지를 단다. 쓸 수 있는 사람은 **보기 역할도**(티켓의 기존 일반 댓글은 지금처럼 편집 역할 이상) |
| 알림 | 프로젝트 개요·문서·목표의 활동과 블록 댓글: **프로젝트 구독자**, **`@`로 태그된 사람**, **스레드에 참여한 사람**(그 스레드의 새 답글). 티켓은 지금처럼 티켓 구독자(와 태그된 사람, 댓글 쓴 사람은 구독하게 됨). 티켓에 딸린 문서는 그 티켓의 구독자. 본문 수정 알림은 티켓처럼 모아서 한 번 |
| 티켓에 딸린 문서 | 이 기능에 **포함**한다 |
| 티켓 옆 목표 패널 | 읽기 전용 목표 패널(D-157)에서도 **댓글 말풍선과 활동을 보고 댓글을 달 수 있다**(본문 편집은 여전히 안 된다) |
| 프로젝트 활동에 더하는 것 | 구성원 추가·제거·역할 변경, 프로젝트 업데이트(건강도) 올림, 목표 만들기·삭제 |

## 제안 (에이전트가 정함, 바꾸려면 말한다)

- **블록 번호**: 최상위 블록마다 안 바뀌는 번호(`attrs.blockId`)를 붙인다. 서버가 저장할 때 번호 없는 블록과 겹친 번호에 새로 붙이고, 마이그레이션 `V24` 가 이미 있는 본문 전체에 한 번 붙인다. 편집기도 새 블록(Enter 로 나뉜 것, 붙여 넣은 것)에 붙인다. 편집 화면에서 막 만든 블록은 자동 저장된 뒤에 댓글을 달 수 있다.
- **스레드 지우기**: 스레드를 연 첫 댓글을 지우면 스레드째 지워진다. 답글은 하나만 지운다. 지울 수 있는 사람은 글쓴이와 워크스페이스 관리자. 글쓴이만 고친다("수정됨").
- **읽기 전용일 때**: 삭제된 티켓, 삭제(보관)된 문서에는 새 댓글·답글·해결을 못 한다(403). 읽기만 된다.
- **일반 댓글에는 답글이 없다**(티켓의 댓글처럼). 답글은 블록 스레드에만 있다.
- **인용**: 스레드를 열 때 블록의 글 앞 200자를 같이 저장해서(`quote`), 블록이 지워진 뒤에도 무엇에 대한 댓글인지 보인다. 이미지·임베드처럼 글이 없는 블록은 "이미지", "임베드" 같은 종류 이름.
- **활동의 합치기**: 본문 수정은 같은 사람이 10분 안에 이어서 고치면 한 줄(제목은 첫 값 → 마지막 값), 알림은 1분 조용하거나 처음부터 10분이면 한 통(티켓의 D-126 과 같은 수치).
- **알림 안 가는 것**: 구성원 변경, 아이콘 변경, 아카이브·복원은 활동에만 남고 알림은 없다.
- **본문 수정 알림의 대상**: 프로젝트 구독자 전부(티켓처럼 구독한 사람에게만).
- **해결 알림**: 해결·다시 열기는 알리지 않는다. 새 댓글·답글만 알린다.
- **검색**: 블록 댓글·일반 댓글은 검색 대상이 아니다(티켓 댓글 검색(D-133)과 따로).
- **영구 삭제(D-132)**: 지운 댓글은 "데이터 보관"의 "삭제된 댓글" 기간을 따른다.
- **자리**: 활동은 본문 아래(프로젝트 개요는 "프로젝트 업데이트" 아래, 문서는 본문 아래, 목표는 티켓 목록 아래가 아니라 목표 글 아래)에 놓는다.

## 데이터 (`V24__content_comments.sql`)

| 표 | 내용 |
|---|---|
| (마이그레이션) | `projects`·`issues`·`documents`·`milestones` 의 `content` 의 최상위 블록마다 `attrs.blockId`(UUID 문자열)를 붙인다 |
| `content_threads` | `id`, `target_type`(`PROJECT`·`ISSUE`·`DOCUMENT`·`MILESTONE`), `target_id`, `block_id`, `quote`, `created_by`, `created_at`, `resolved_at`, `resolved_by`. 대상 행이 지워져도 따라 지워지지 않아 보관 작업이 주기적으로 정리한다 |
| `content_thread_comments` | `id`, `thread_id`(`ON DELETE CASCADE`), `author_id`, `content`(편집기 문서: 문단·글·`@`만), `body`(글만), `created_at`, `updated_at`, `deleted_at` |
| `content_activities` | `id`, `seq`, `target_type`(`PROJECT`·`DOCUMENT`·`MILESTONE`), `target_id`, `actor_id`, `type`, `detail` jsonb, `created_at` |
| `content_comments` | 일반 댓글. `issue_comments` 와 같은 모양에 `target_type`·`target_id` |
| `content_comment_reactions`, `content_activity_reactions` | 이모지 (`comment_id`/`activity_id`, `user_id`, `emoji`) |
| `content_files` | 일반 댓글의 파일. `issue_attachments` 와 같은 모양에 `target_type`·`target_id`·`comment_id`·`pending` |

## API

대상 종류는 주소의 `{type}`: `project`·`issue`·`document`·`milestone`, `{id}` 는 그 행의 UUID(티켓은 `Issue.id`, 번호가 아니다). 대상을 볼 수 없으면 404.

- `GET /api/v1/content/{type}/{id}/feed` → `{ canComment, readOnly, threads, items, comments }`. 티켓은 `threads` 만 채워진다(활동은 티켓의 `GET …/activity`).
- `POST /api/v1/content/{type}/{id}/threads` `{ blockId, content }` → 스레드. 블록이 저장된 본문에 없으면 409 `BLOCK_NOT_FOUND`.
- `POST /api/v1/content-threads/{id}/comments` `{ content }` → 스레드(답글).
- `PATCH /api/v1/content-thread-comments/{id}` `{ content }`, `DELETE …/{id}`.
- `POST /api/v1/content-threads/{id}/resolution`(해결), `DELETE …/resolution`(다시 열기).
- 일반 댓글(티켓 제외): `POST /api/v1/content/{type}/{id}/comments` `{ content, attachmentIds }`, `PATCH/DELETE /api/v1/content-comments/{id}`, `POST …/{id}/reactions`, `POST /api/v1/content-activities/{id}/reactions`, `POST /api/v1/content/{type}/{id}/comment-files`(multipart), `GET/DELETE /api/v1/content-files/{id}`.

스레드 응답: `{ id, blockId, quote, orphaned, resolved, resolvedAt, resolvedBy, createdAt, comments: [{ id, author, content, createdAt, updatedAt, canEdit, canDelete }] }`. `orphaned` 는 지금 저장된 본문에 그 블록 번호가 없을 때 true.

## 구현 메모

- 백엔드는 `com.flowbit.content` 패키지: `ContentTypes`(대상 종류·이벤트 이름), `ContentDirectory`(사용자와 무관하게 대상 찾기·구독자·알림 대상), `ContentAccess`(누가 볼 수 있나: 프로젝트를 열 수 있는 사람, 티켓·티켓 문서는 티켓을 볼 수 있는 사람), `ContentLog`(활동 기록 + 알림, 서비스가 쓴 변경마다 한 줄 호출), `ContentThreads`(스레드 저장소·서비스), `ContentComments`(일반 댓글·이모지·파일), `ContentController`. 순환 의존을 피하려고 `ContentLog` 와 `ContentDirectory` 는 서비스(`ProjectService` 등)에 기대지 않는다.
- 활동을 쓰는 곳: `ProjectService.update`(이름·본문·상태·우선순위·리드·기간·라벨), `archive`·`restore`·`replaceIcon`·`removeIcon`·구성원 넷(`putMember`·`removeMember`·`adminPutMember`·`adminRemoveMember`), `ProjectUpdateService.post`, `MilestoneService.create`·`update`·`delete`, `IssueService` 의 문서 함수들.
- 본문 저장은 `RichText.parse(…, blockIds = true)` 가 블록 번호를 붙인다(티켓 생성·수정, 프로젝트·문서·목표). 본문이 같은지는 `RichText.same` 으로 JSON 구조를 비교한다(저장된 jsonb 글자와 직렬화한 글자는 모양이 달라서 글자 비교는 안 된다).
- 알림은 `ContentNotification`(`NotificationChannel.sendContent`)이고 티켓의 `NotificationDispatcher` 와 같은 모으기를 쓴다(`dispatch(ContentNotification)`). API 알림의 `event` 는 `project|document|milestone` + `.updated|.commented`, 표준 JSON 에 `target: { type, id, title, url, projectId }` 가 붙는다.
- 보관 작업(`RetentionService.purge`)이 지운 댓글(블록 스레드 댓글·일반 댓글·파일 포함)과, 지워진 대상이 남긴 스레드·활동·댓글·파일(`sweepOrphans`), 하루 지난 보내지 않은 파일을 정리한다.
- 프런트는 `features/content/`: `use-content-feed`(대상마다 `GET …/feed` 를 react-query 로 읽고, 서버가 페이지를 다시 그려 `watch` 가 바뀌면 다시 읽는다), `components/block-comments.tsx`(편집기에 얹는 말풍선·팝업. 위치는 `setTimeout` 으로 잰다: 백그라운드 페이지는 `requestAnimationFrame` 이 안 돌아 말풍선이 자리를 못 잡았다), `thread-parts.tsx`, `content-activity.tsx`, `describe.ts`. 티켓의 `IssueActivity` 와 일반 댓글 컴포넌트는 `CommentApi`(`features/issues/comment-api.ts`)를 받아 두 곳이 함께 쓴다. 편집기 `RichTextEditor` 의 새 속성: `blockIds`(블록 번호, `block-id.ts`), `overlay`, `plain`(댓글용: 문단·글·태그만).

## 화면

- 편집기(`RichTextEditor`)에 `blockIds`(블록 번호 붙이기)와 `overlay`(편집기 위에 얹는 조각)를 받는다. `features/content/components/block-comments.tsx` 가 overlay 로 블록 옆 말풍선(댓글이 있는 블록은 늘 보이고 개수, 없는 블록은 포인터가 올라간 블록·커서가 있는 블록에서 흐리게)과 팝업을 그린다.
- 활동은 `ActivityFeed`(티켓의 `IssueActivity` 를 일반화)가 그린다. 프로젝트 개요·문서·목표는 `ContentActivity` 가 `GET …/feed` 를 읽어 넘긴다. 실시간 갱신은 티켓처럼 `router.refresh()` 가 새 본문 객체를 내려 주면 다시 읽는다.
- 도움말(`features/help/content.ts`)의 "댓글과 활동"에 반영한다.

## 확인이 필요한 것

- 위 "제안"의 것들. 특히 알림 안 가는 항목, 해결 알림 없음, 영구 삭제 기간.
- 블록 사이를 옮기는 드래그와 댓글 말풍선이 한 줄에서 겹치는 좁은 화면(375px)의 모양.

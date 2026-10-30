# 블록 댓글과 활동 (프로젝트 개요·문서·티켓 본문)

상태 (2026-10-21): **구현됨, 새 백엔드로는 아직 아무도 확인하지 않았다**(컴파일·ktlint·타입 검사·eslint 만, 테스트는 안 돌림. 화면은 샘플 데이터를 그린 임시 페이지로 말풍선·팝업·활동 목록만 봤다). 결정은 [09-decisions.md](../09-decisions.md) D-165, **알림과 구독의 규칙은 D-166 이 바꿨고**(아래 "구독과 알림"), **D-167 이 활동의 순서(최신순)와 답글을 더했다**(아래 "답글과 활동의 순서"). 사용자가 네 번에 나눠 답한 것을 그대로 옮겼고, "제안"이라고 적은 것만 에이전트가 정했다.

## 무엇을 만드는가

편집기를 쓰는 세 곳 — **프로젝트 개요의 설명, 프로젝트의 문서(티켓에 딸린 문서 포함), 티켓 본문** — 의 **블록 하나하나에 댓글**을 달 수 있게 하고, 티켓 상세처럼 **"활동"**(변경 기록 + 댓글)이 프로젝트 개요·문서에도 나오게 한다. 티켓은 활동이 이미 있으므로 블록 댓글만 더한다.

## 확정 (사용자)

| 항목 | 내용 |
|---|---|
| 댓글 대상 | **블록 단위**. 글 일부가 아니라 블록 전체에 단다. 블록은 **최상위 블록**(문단·제목·인용·코드·이미지·임베드·페이지 블록은 각각 하나, 목록·표는 통째로 하나). 목록 항목·표 칸까지 쪼개지 않는다 |
| 위치 표시 | 블록 옆 말풍선 아이콘. 눌러서 **블록 옆 팝업**에서 스레드를 보고 답글을 단다(Notion 방식). 좁은 티켓 창에서도 된다 |
| 누가 | **보기 역할도** 블록 댓글을 단다. 그래서 위치를 본문에 표시로 넣지 않고 **본문과 따로 저장**한다(보기 역할은 본문을 저장할 수 없다) |
| 스레드 | 댓글마다 **답글**, 끝난 논의는 **해결**(다시 열 수 있음). 해결하고 다시 열 수 있는 사람은 **댓글을 달 수 있는 누구나** |
| 지운 블록 | 그 블록의 댓글은 지우지 않고 **"삭제된 블록의 댓글"로 목록에 남긴다**. 블록이 돌아오면(실행 취소 등) 다시 붙는다. 블록을 옮기면 댓글이 따라간다 |
| 블록 댓글의 내용 | **글과 `@` 태그만**. 이모지·파일 올리기 없음. 티켓 본문의 블록 댓글도 같다 |
| 활동 | 프로젝트 개요·프로젝트 문서·티켓에 딸린 문서에 티켓 상세의 "활동"과 같은 모양으로 붙는다. 시간순으로 변경 기록, 일반 댓글, 블록 댓글 스레드가 섞여 나온다(**D-167 부터 최신순, 입력 칸이 위**) |
| 활동에 쌓이는 것 | 본문 수정(티켓처럼 같은 사람의 연속 수정은 한 줄로, 알림도 모아서 한 번), **속성 변경 전부**, 문서 만들기·삭제·복원, 구성원 변경, 프로젝트 업데이트, 블록 댓글 |
| 문서 만들기·삭제·복원 | **그 문서를 품은 쪽의 활동**에 남는다: 프로젝트 개요에 만든 문서는 프로젝트 활동, 문서 안에 만든 하위 문서는 상위 문서 활동, 티켓의 문서는 티켓 활동(지금처럼). 문서 자신의 활동에는 제목·본문 수정만 |
| 블록 댓글이 활동에 보이는 모양 | **스레드 한 덩어리**: 인용한 블록의 글 아래에 댓글과 답글이 한 카드로 묶이고, **첫 댓글이 달린 시각 자리**에 있다. 해결된 스레드는 접히고 누가 해결했는지 나온다 |
| 일반 댓글 | 프로젝트 개요·문서의 활동 **위쪽**(D-167, 처음에는 맨 아래)에 **블록과 관계없는 일반 댓글** 입력 칸을 둔다. 티켓의 것처럼 **이모지와 파일 올리기**가 있고, 활동 줄에도 이모지를 단다. 쓸 수 있는 사람은 **보기 역할도**(티켓의 기존 일반 댓글은 지금처럼 편집 역할 이상) |
| 알림 | **D-218 로 바뀜(아래 "구독과 알림")**: 새 댓글은 그 쪽의 구독자 모두와 `@`로 태그된 사람에게, 답글은 태그된 사람·원 댓글을 쓴 사람·앞서 답글을 단 사람에게만. 구독한 사람은 활동(바뀐 것)도 받는다. 본문 수정 알림은 티켓처럼 모아서 한 번 |
| 티켓에 딸린 문서 | 이 기능에 **포함**한다 |
| 프로젝트 활동에 더하는 것 | 구성원 추가·제거·역할 변경, 프로젝트 업데이트(건강도) 올림 |

## 제안 (에이전트가 정함, 바꾸려면 말한다)

- **블록 번호**: 최상위 블록마다 안 바뀌는 번호(`attrs.blockId`)를 붙인다. 서버가 저장할 때 번호 없는 블록과 겹친 번호에 새로 붙이고, 마이그레이션 `V24` 가 이미 있는 본문 전체에 한 번 붙인다. 편집기도 새 블록(Enter 로 나뉜 것, 붙여 넣은 것)에 붙인다. 편집 화면에서 막 만든 블록은 자동 저장된 뒤에 댓글을 달 수 있다.
- **스레드 지우기**: 스레드를 연 첫 댓글을 지우면 스레드째 지워진다. 답글은 하나만 지운다. 지울 수 있는 사람은 글쓴이와 워크스페이스 관리자. 글쓴이만 고친다("수정됨").
- **읽기 전용일 때**: 삭제된 티켓, 삭제(보관)된 문서에는 새 댓글·답글·해결을 못 한다(403). 읽기만 된다.
- **일반 댓글의 답글**은 D-167 부터 있다(위 "답글과 활동의 순서").
- **인용**: 스레드를 열 때 블록의 글 앞 200자를 같이 저장해서(`quote`), 블록이 지워진 뒤에도 무엇에 대한 댓글인지 보인다. 이미지·임베드처럼 글이 없는 블록은 "이미지", "임베드" 같은 종류 이름.
- **활동의 합치기**: 본문 수정은 같은 사람이 10분 안에 이어서 고치면 한 줄(제목은 첫 값 → 마지막 값), 알림은 1분 조용하거나 처음부터 10분이면 한 통(티켓의 D-126 과 같은 수치).
- **알림 안 가는 것**: 구성원 변경, 아이콘 변경, 아카이브·복원은 활동에만 남고 알림은 없다.
- **본문 수정 알림의 대상**: 그 쪽을 구독한 사람(문서는 문서 구독자, 프로젝트 개요는 프로젝트 구독자). D-166.
- **해결 알림**: 해결·다시 열기는 알리지 않는다. 새 댓글·답글만 알린다.
- **검색**: 블록 댓글·일반 댓글은 검색 대상이 아니다(티켓 댓글 검색(D-133)과 따로). 프로젝트 개요·문서에 **쓴 본문**은 D-166 부터 검색된다([search.md](search.md)).
- **영구 삭제(D-132)**: 지운 댓글은 "데이터 보관"의 "삭제된 댓글" 기간을 따른다.
- **자리**: 활동은 본문 아래(프로젝트 개요는 "프로젝트 업데이트" 아래, 문서는 본문 아래)에 놓는다. (목표 창의 오른쪽 열 배치는 목표와 함께 없앴다, D-189.)

## 답글과 활동의 순서 (D-167, 사용자 지정 2026-10-21)

사용자가 정한 것:

- **활동은 최신순이다**(티켓·프로젝트 개요·문서 모두). 새것이 맨 위, 가장 오래된 것(티켓이면 "티켓을 만들었습니다")이 맨 아래. **댓글 입력 칸은 목록 위**에 있다. **15개를 넘으면** 최신 15개만 보이고 목록 아래에 **"이전 활동 N개 더 보기"** 버튼이 있다. 누르면 나머지가 모두 펼쳐진다(다시 접는 버튼은 없다). 센 것은 변경 기록·댓글(답글은 제외)·블록 댓글 스레드를 합친 항목 수다.
- **활동의 댓글(티켓의 댓글, 프로젝트·문서의 일반 댓글)에 답글(대댓글)을 달 수 있다.** 한 단계만: 답글에는 답글을 달 수 없다. 답글은 원 댓글 아래 **오래된 것부터** 들여 써서 보인다. 답글에도 댓글과 똑같이 글·사진·파일·이모지·`@` 태그가 되고, 쓰고 고치고 지우는 권한도 같다(티켓은 편집 역할부터, 프로젝트·문서는 보기 역할도).
- **답글의 알림**: 답글을 쓰면 **원 댓글의 작성자와 앞서 그 댓글에 답글을 단 사람**(그리고 답글에서 태그된 사람)에게만 간다. 구독한 사람에게는 가지 않는다(D-218: 새 댓글은 구독자에게도 간다). 쓴 사람 자신은 뺀다. 알림의 머리말은 "○○ 님이 댓글에 답글을 남겼습니다". 블록 댓글(스레드)의 답글도 같은 규칙으로, 그 스레드의 앞선 댓글을 쓴 모든 사람에게 간다(스레드의 첫 댓글은 새 댓글이라 구독자와 태그된 사람에게 간다).
- **답글이 달린 댓글을 지우면** 그 자리에 "삭제된 댓글입니다"만 남고 답글은 그대로 보인다(내용·글쓴이·파일·이모지는 보내지 않는다). 답글이 없는 댓글은 지금처럼 사라진다. 지워진 댓글에는 답글을 더 달 수 없다. 블록 댓글 스레드의 첫 댓글을 지우면 스레드째 지워지는 것(D-165)은 그대로다.

에이전트가 정한 것:

- 최신순에서도 댓글은 **쓴 시각** 자리에 있고(답글이 달려도 올라오지 않는다), 답글은 그 댓글 안에 모인다.
- 답글을 쓰려면 댓글 아래 "답글" 버튼을 눌러 그 자리에 입력 칸이 열린다(취소할 수 있다).
- 대화에 낀 사람이 프로젝트에서 빠졌거나 비활성이면 알림이 가지 않는다. 답글만 쓰고 태그하지 않아도 티켓·문서를 구독하게 되지는 않는다(티켓은 댓글을 쓰면 구독하게 되는 것이 그대로).
- 보관 작업(D-132)은 지워진 댓글에 아직 지울 때가 아닌(살아 있거나 최근에 지운) 답글이 있으면 그 댓글을 남겨 둔다. 답글이 먼저 지워지지 않게 하려는 것이다.
- API: 새 댓글에 `parentId` 를 주면 답글이다(`POST /api/v1/issues/{번호}/comments`, `POST /api/v1/content/{type}/{id}/comments`). 응답과 활동·피드의 댓글에 `parentId`(없으면 null)와 `deleted` 가 있다. 답글에 답글(422 `INVALID`), 다른 곳·지워진 댓글(404 `COMMENT_NOT_FOUND`). 표는 `issue_comments.parent_id`·`content_comments.parent_id`(`V26`).

## 구독과 알림 (D-166, 사용자 지정 2026-10-21)

사용자가 정한 것:

- **새 댓글은 그 쪽의 구독자 모두와 `@`로 태그된 사람에게 알린다. 답글은 태그된 사람, 원 댓글의 작성자, 앞서 답글을 단 사람에게만 알린다**(사용자 지정, D-218. D-166 의 "댓글은 태그된 사람에게만"을 바꿨다. 답글은 D-167 그대로다). 블록 댓글(스레드의 첫 댓글이 새 댓글, 이어지는 댓글이 답글)과 일반 댓글이 같고, 수정으로 새로 태그된 사람에게만 다시 간다. **티켓도 똑같다**: 티켓 댓글, 티켓 본문의 블록 댓글이 새 댓글이면 티켓의 구독자(프로젝트 구독자 포함)와 태그된 사람에게 간다. 본문에서 사람을 태그한 알림은 태그된 사람에게만 간다.
- **구독한 사람은 활동(바뀐 것)과 새 댓글을 알림으로 받는다**: 이름·본문 수정, 속성 변경, 문서 삭제·복원 같은 것, 그리고 새 댓글. 답글은 받지 않는다.
- **태그하면 태그된 사람이 그 쪽을 구독하게 된다.** 태그된 사람은 구독을 끊을 수 있다. (티켓은 D-101 부터 그랬다.)
- **문서마다 따로 구독한다**: 문서 화면의 활동 머리글에 "구독" 버튼과 구독자 아바타가 있다(티켓 활동과 같은 모양). 문서를 만든 사람은 처음부터 구독하고, 보는 권한이 있는 누구나(보기 역할 포함) 켜고 끈다. 문서 활동의 알림은 **그 문서의 구독자에게만** 가고, 프로젝트 구독자와 티켓 구독자에게는 더 가지 않는다(티켓에 딸린 문서도 같다). 이미 있던 문서는 만든 사람이 구독한 것으로 시작한다(`V25`).

에이전트가 정한 것(바꾸려면 말한다):

- 프로젝트 개요에는 따로 구독이 없고 **프로젝트 구독**을 쓴다(개요의 활동 머리글의 "구독" 버튼과 구독자 아바타, D-176). 거기서 태그된 사람은 프로젝트 전체를 구독하게 하지 않고(티켓 변경이 모두 오게 되므로) 그 댓글 알림만 받는다.
- 댓글·답글 알림은 작성할 때마다 **즉시** 보낸다(2026-10-29 사용자 재확정). 티켓·프로젝트·문서의 일반 댓글과 블록 댓글 모두 창 닫기·1분 대기 없이 전달한다. 같은 사람이 연속으로 써도 댓글마다 따로 보내며, 대기 중인 제목·본문·속성 변경을 함께 내보내거나 댓글에 합치지 않는다.
- 댓글을 쓰기만 해서 문서를 구독하게 되지는 않는다(티켓은 댓글을 쓰면 구독하게 되는 것이 지금처럼 남는다).
- 삭제된 문서의 구독 버튼은 숨긴다. 구독자 가운데 프로젝트에서 빠진 사람, 비활성인 사람에게는 알림이 가지 않는다.

API: `PUT /api/v1/content/document/{id}/subscription`(구독), `DELETE …`(끊기) → `{ subscribers, subscribed }`. 문서 이외의 종류는 404. `GET …/feed` 의 응답에 문서일 때만 `subscription: { subscribers, subscribed }` 가 들어 있다.

## 데이터 (`V24__content_comments.sql`, `V25__document_subscriptions.sql`)

| 표 | 내용 |
|---|---|
| (마이그레이션) | `projects`·`issues`·`documents` 의 `content` 의 최상위 블록마다 `attrs.blockId`(UUID 문자열)를 붙인다 |
| `content_threads` | `id`, `target_type`(`PROJECT`·`ISSUE`·`DOCUMENT`), `target_id`, `block_id`, `quote`, `created_by`, `created_at`, `resolved_at`, `resolved_by`. 대상 행이 지워져도 따라 지워지지 않아 보관 작업이 주기적으로 정리한다 |
| `content_thread_comments` | `id`, `thread_id`(`ON DELETE CASCADE`), `author_id`, `content`(편집기 문서: 문단·글·`@`만), `body`(글만), `created_at`, `updated_at`, `deleted_at` |
| `content_activities` | `id`, `seq`, `target_type`(`PROJECT`·`DOCUMENT`), `target_id`, `actor_id`, `type`, `detail` jsonb, `created_at` |
| `content_comments` | 일반 댓글. `issue_comments` 와 같은 모양에 `target_type`·`target_id` |
| `content_comment_reactions`, `content_activity_reactions` | 이모지 (`comment_id`/`activity_id`, `user_id`, `emoji`) |
| `content_files` | 일반 댓글의 파일. `issue_attachments` 와 같은 모양에 `target_type`·`target_id`·`comment_id`·`pending` |
| `content_comments.parent_id`, `issue_comments.parent_id` | (`V26`) 답글이면 원 댓글(`ON DELETE CASCADE`), 아니면 null (D-167) |
| `document_subscriptions` | (`V25`) 문서를 구독한 사람: `document_id`(`ON DELETE CASCADE`), `user_id`(`ON DELETE CASCADE`), `created_at` |

## API

대상 종류는 주소의 `{type}`: `project`·`issue`·`document`, `{id}` 는 그 행의 UUID(티켓은 `Issue.id`, 번호가 아니다). 대상을 볼 수 없으면 404.

- `GET /api/v1/content/{type}/{id}/feed` → `{ canComment, readOnly, threads, items, comments }`. 티켓은 `threads` 만 채워진다(활동은 티켓의 `GET …/activity`).
- `POST /api/v1/content/{type}/{id}/threads` `{ blockId, content }` → 스레드. 블록이 저장된 본문에 없으면 409 `BLOCK_NOT_FOUND`.
- `POST /api/v1/content-threads/{id}/comments` `{ content }` → 스레드(답글).
- `PATCH /api/v1/content-thread-comments/{id}` `{ content }`, `DELETE …/{id}`.
- `POST /api/v1/content-threads/{id}/resolution`(해결), `DELETE …/resolution`(다시 열기).
- 일반 댓글(티켓 제외): `POST /api/v1/content/{type}/{id}/comments` `{ content, attachmentIds }`, `PATCH/DELETE /api/v1/content-comments/{id}`, `POST …/{id}/reactions`, `POST /api/v1/content-activities/{id}/reactions`, `POST /api/v1/content/{type}/{id}/comment-files`(multipart), `GET/DELETE /api/v1/content-files/{id}`.

스레드 응답: `{ id, blockId, quote, orphaned, resolved, resolvedAt, resolvedBy, createdAt, comments: [{ id, author, content, createdAt, updatedAt, canEdit, canDelete }] }`. `orphaned` 는 지금 저장된 본문에 그 블록 번호가 없을 때 true.

## 구현 메모

- 백엔드는 `com.flowbit.content` 패키지: `ContentTypes`(대상 종류·이벤트 이름), `ContentDirectory`(사용자와 무관하게 대상 찾기·구독자·알림 대상), `ContentAccess`(누가 볼 수 있나: 프로젝트를 열 수 있는 사람, 티켓·티켓 문서는 티켓을 볼 수 있는 사람), `ContentLog`(활동 기록 + 알림, 서비스가 쓴 변경마다 한 줄 호출), `ContentThreadStore`·`ContentThreadService`(스레드), `ContentCommentStore`·`ContentCommentService`(일반 댓글·이모지·파일), `ContentController`. 순환 의존을 피하려고 `ContentLog` 와 `ContentDirectory` 는 서비스(`ProjectService` 등)에 기대지 않는다.
- 활동을 쓰는 곳: `ProjectService.update`(이름·본문·상태·우선순위·리드·기간·라벨), `archive`·`restore`·`replaceIcon`·`removeIcon`·구성원 넷(`putMember`·`removeMember`·`adminPutMember`·`adminRemoveMember`), `ProjectUpdateService.post`, `IssueService` 의 문서 함수들.
- 본문 저장은 `RichText.parse(…, blockIds = true)` 가 블록 번호를 붙인다(티켓 생성·수정, 프로젝트·문서). 본문이 같은지는 `RichText.same` 으로 JSON 구조를 비교한다(저장된 jsonb 글자와 직렬화한 글자는 모양이 달라서 글자 비교는 안 된다).
- 알림은 `ContentNotification`(`NotificationChannel.sendContent`)이다. 바뀐 것은 티켓의 `NotificationDispatcher` 와 같은 모으기를 쓰고(`dispatch(ContentNotification)`), 댓글은 모으지 않고 바로 보낸다(`dispatchTalk`, 티켓의 댓글·태그 알림도 같다). `ContentLog.announce` 가 이벤트를 나눠, 댓글(`COMMENT_ADDED`·`THREAD_COMMENT`)은 태그된 사람에게, 나머지는 `ContentDirectory.followers`(문서: 문서 구독자, 프로젝트: 프로젝트 구독자)에게 보낸다. 문서 구독은 `ContentSubscriptions.kt`(`DocumentSubscriptionStore`·`ContentSubscriptionService`). API 알림의 `event` 는 `project|document` + `.updated|.commented`, 표준 JSON 에 `target: { type, id, title, url, projectId }` 가 붙는다.
- 보관 작업(`RetentionService.purge`)이 지운 댓글(블록 스레드 댓글·일반 댓글·파일 포함)과, 지워진 대상이 남긴 스레드·활동·댓글·파일(`sweepOrphans`), 하루 지난 보내지 않은 파일을 정리한다.
- 프런트는 `features/content/`: `use-content-feed`(대상마다 `GET …/feed` 를 react-query 로 읽고, 서버가 페이지를 다시 그려 `watch` 가 바뀌면 다시 읽는다), `components/block-comments.tsx`(편집기에 얹는 말풍선·팝업. 위치는 `setTimeout` 으로 잰다: 백그라운드 페이지는 `requestAnimationFrame` 이 안 돌아 말풍선이 자리를 못 잡았다), `thread-parts.tsx`, `content-activity.tsx`, `describe.ts`. 티켓의 `IssueActivity` 와 일반 댓글 컴포넌트는 `CommentApi`(`features/issues/comment-api.ts`)를 받아 두 곳이 함께 쓴다. 편집기 `RichTextEditor` 의 새 속성: `blockIds`(블록 번호, `block-id.ts`), `overlay`, `plain`(댓글용: 문단·글·태그만).

## 화면

- 편집기(`RichTextEditor`)에 `blockIds`(블록 번호 붙이기)와 `overlay`(편집기 위에 얹는 조각)를 받는다. `features/content/components/block-comments.tsx` 가 overlay 로 블록 옆 말풍선(댓글이 있는 블록은 늘 보이고 개수, 없는 블록은 포인터가 올라간 블록·커서가 있는 블록에서 흐리게)과 팝업을 그린다.
- 활동은 `ActivityFeed`(티켓의 `IssueActivity` 를 일반화)가 그린다. 프로젝트 개요·문서는 `ContentActivity` 가 `GET …/feed` 를 읽어 넘긴다. 실시간 갱신은 티켓처럼 `router.refresh()` 가 새 본문 객체를 내려 주면 다시 읽는다.
- 도움말(`features/help/content.ts`)의 "댓글과 활동"에 반영한다.

## 확인이 필요한 것

- 위 "제안"의 것들. 특히 알림 안 가는 항목, 해결 알림 없음, 영구 삭제 기간.
- 블록 사이를 옮기는 드래그와 댓글 말풍선이 한 줄에서 겹치는 좁은 화면(375px)의 모양.

## 일반 댓글 해결 (2026-10-30, 사용자 확정)

- 티켓·프로젝트·문서의 일반 댓글에 해결 플래그를 둔다. 원 댓글과 답글을 한 묶음으로 해결하며 답글 자체에는 해결 버튼을 두지 않는다.
- 해결하면 원 댓글 아코디언이 자동으로 접힌다. 접힌 요약과 펼친 원 댓글 본문에 취소선을 표시하고, 댓글 카드 배경과 글자는 회색 디자인 토큰을 쓴다. 전체 내용·답글·첨부는 펼쳐서 볼 수 있다.
- 해결한 사람과 시각을 표시한다. 펼치기는 해결 상태를 바꾸지 않으며 ‘다시 열기’로 해결을 취소하고 내용을 펼친다. 새 답글은 해결 상태를 자동으로 바꾸지 않는다.
- 해당 위치에 댓글을 쓸 수 있는 사람이 해결·다시 열기를 할 수 있다. 삭제된 대상·댓글, 접근할 수 없는 대상, 답글 자체는 변경할 수 없다. 해결은 별도 알림을 발송하지 않고 실시간으로 동기화한다.
- `issue_comments`·`content_comments`에 nullable `resolved_at`, `resolved_by`(users FK)를 V21로 추가한다. 기존 댓글은 미해결이다. 응답에 `resolved`, `resolvedAt`, `resolvedBy`를 추가한다. 해결·해제는 각각 `POST`·`DELETE /api/v1/comments/{id}/resolution`, `/api/v1/content-comments/{id}/resolution`(204)이다. 중복 해결은 최초 처리자·시각을 보존한다.

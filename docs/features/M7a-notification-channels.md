# M7a. 알림 통로: 메일(SMTP), 웹 푸시(Firebase), API 알림

티켓이 바뀌면 앱 밖으로 알린다. 통로는 세 가지이고 관리자 설정의 **"알림"** 탭(`/admin/notifications`)에서 켠다. 사용자 지정(2026-10-12), 결정은 D-091~D-093. D-061 에서 자리만 만들어 둔 `NotificationChannel` 의 구현이다.

**모든 사람에게 보내는 알림은 없다(D-102, 2026-10-13).** 세 통로 모두 그 티켓이나 그 티켓의 프로젝트를 구독한 사람에게만 간다.

**새 댓글은 구독자 모두에게, 답글은 태그된 사람과 대화한 사람에게만 간다(D-218, 2026-10-26. D-166·D-167 을 바꿨다).** 아래 "받는 사람"의 구독자는 티켓·프로젝트·문서의 *바뀐 것*(활동 기록에 남는 변경)과 **새 댓글**을 받는다. 새 댓글은 구독자에 더해 댓글에서 태그된 사람에게도 바로 간다(모으지 않는다). **답글은 답글에서 태그된 사람, 원 댓글을 쓴 사람, 앞서 그 댓글에 답글을 단 사람에게만** 가고 구독자에게는 가지 않는다. 본문에서 태그한 알림(`MENTIONED`)은 여전히 태그된 사람에게만 바로 간다. 태그된 사람은 그 티켓(문서)의 구독자가 되고 끊을 수 있다.

M7(수신함과 트리아지)의 일부를 먼저 만든 것이다. 앱 안에서 보는 **수신함(사이드바의 종)** 과 정한 시각에 보내는 **하루 요약**은 [inbox-and-daily-summary.md](inbox-and-daily-summary.md) 에 따로 있다(D-216): 수신함은 이 문서의 통로가 알리는 것을 같은 받는 사람에게 앱 안에도 쌓는 통로 하나(`InboxNotificationChannel`)다.

## 범위

| 통로 | 누구에게 | 켜는 곳 |
|---|---|---|
| 메일 (SMTP) | 받는 사람 중 이메일이 있고 메일을 켠 사람 | 관리자: 서버 설정. 각자: 내 설정에서 주소와 수신 여부 |
| 웹 푸시 (Firebase Cloud Messaging) | 받는 사람이 등록한 브라우저 | 관리자: Firebase 값. 각자: 내 설정에서 브라우저마다 켬 |
| API 알림 (웹훅) | 등록한 주소를 **받는 사람마다 한 번씩** 부른다 | 관리자: 주소·헤더·본문을 정한 항목을 여러 개 |

- **받는 사람 = 티켓 구독자 + 프로젝트 구독자**, 바꾼 사람 본인은 뺀다. 활성 계정만.
  - 티켓 구독자 = 티켓을 만든 사람, 담당자(여러 명이면 모두, D-125), 직접 구독한 사람, **댓글을 쓴 사람, `@` 로 태그된 사람**(D-101).
  - 프로젝트 구독자 = 그 티켓이 연결된 프로젝트 개요의 활동 머리글에서 "구독"을 누른 사람(D-102, 위치는 D-176, [M3 명세](M3-projects-and-issues.md) "프로젝트 구독"). 지금도 그 프로젝트에 들어갈 수 있어야 한다(구성원이거나 워크스페이스 관리자). 프로젝트에 연결되지 않은 티켓에는 프로젝트 구독자가 없다.
  - 티켓 구독을 취소해도 그 프로젝트를 구독하고 있으면 계속 받는다.
  - 받는 사람이 한 명도 없는 변경은 어디로도 나가지 않는다.
- 알리는 변경 = 티켓 활동 기록에 남는 것 전부(`IssueActivityType`, 받는 사람은 위의 구독자)와 **댓글**(새 댓글은 구독자 모두와 태그된 사람, 답글은 태그된 사람과 대화한 사람에게만, D-218. `COMMENT_ADDED`: 머리말 `○○ 님이 댓글을 남겼습니다`, 줄은 댓글 앞 300자와 `언급: 이름, …`), **본문의 태그**(`MENTIONED`: `본문에서 언급: 이름, …`, 이것도 태그된 사람에게만). 댓글의 `event` 는 `issue.commented`.
- **프로젝트·문서의 변경과 댓글**(D-165): 티켓이 아닌 알림이 하나 더 있다(`ContentNotification`). 받는 사람(D-218) = 바뀐 것은 그 쪽의 구독자(문서는 **그 문서의 구독자**, 프로젝트 개요는 프로젝트 구독자), 새 댓글(블록 댓글·일반 댓글)은 구독자와 태그된 사람, 답글은 태그된 사람과 대화 참여자다. 변경은 티켓처럼 모아서 한 통으로 보내고, 댓글·답글은 작성 건마다 즉시 보낸다. 머리말은 `○○ 님이 프로젝트를(문서를, 목표를) 수정했습니다` 또는 `댓글을 남겼습니다`, 제목은 `[프로젝트 이름] 문서 제목` 꼴이고 링크 글자는 "열기". 구성원 변경·아이콘·보관과 복원은 알리지 않는다. API 알림의 `event` 는 `project|document|milestone` + `.updated|.commented`, 변경 종류는 `NAME_CHANGED`·`CONTENT_EDITED`·`STATUS_CHANGED`·`PRIORITY_CHANGED`·`LEAD_CHANGED`·`ASSIGNEE_CHANGED`(목표의 담당자, D-185)·`DATES_CHANGED`·`TARGET_DATE_CHANGED`·`LABELS_CHANGED`·`UPDATE_POSTED`·`MILESTONE_ADDED`·`MILESTONE_REMOVED`·`DOCUMENT_ADDED`·`DOCUMENT_REMOVED`·`DOCUMENT_RESTORED`·`COMMENT_ADDED`·`THREAD_COMMENT`("보낼 변경"에서 고를 수 있다). 표준 JSON 의 `issue` 는 `null` 이고 `target: { type, id, title, url, projectId }` 가 붙는다. 자리표시자는 `{{target.type}}` `{{target.id}}` `{{target.title}}` `{{target.url}}`. 티켓 본문의 블록 댓글은 티켓의 `COMMENT_ADDED`(`issue.commented`)로 가고 `detail.quote` 가 블록의 글이다.
- **하루 요약**(D-216, D-220): 티켓이 바뀐 것이 아닌 알림이 하나 더 있다. 관리자가 구성원마다 켠 구성원에게 하루에 한 번 "오늘의 요약"을 메일·웹 푸시·API 알림으로 보낸다. API 알림은 "보낼 변경"을 비워 둔 것과 `DAILY_SUMMARY`("하루 요약")를 고른 것이 받고, `event` 는 `summary.daily`, 받는 사람은 그 구성원 한 사람이다. 자세히는 [inbox-and-daily-summary.md](inbox-and-daily-summary.md).
- **프로젝트 접근 요청**(D-128): 티켓 변경이 아닌 알림이 하나 있다. 들어갈 수 없는 프로젝트에 누군가 접근을 요청하면 그 프로젝트의 관리 역할(없으면 워크스페이스 관리자)에게 세 통로로 바로 간다(모으지 않는다). 메일의 링크 글자는 "설정 열기". API 알림의 `event` 는 `project.access_requested`, 변경 종류 `ACCESS_REQUESTED`("보낼 변경"에서 고를 수 있다), 표준 JSON 의 `issue` 는 `null` 이고 `project: { id, name, url }` 가 붙는다. 자리표시자 `{{issue.*}}` 는 빈 값, `{{project.name}}` `{{project.url}}` 이 있다. 통로는 `NotificationChannel.sendAccessRequest` 를 구현한다.
- 태그된 사람에게 가는 알림도 그 사람이 등록한 수단 전부(메일 주소와 수신 켬, 켜 둔 브라우저, API 알림)로 간다. 태그되면 그 티켓·문서의 구독자가 되어 앞으로 바뀐 것도 받는다(끊을 수 있다). 댓글을 고쳐 새로 태그한 경우에는 새로 태그된 사람들에게만 간다.

범위 밖: 앱 안의 수신함 전체 페이지(종의 목록은 D-216 으로 생겼다), 구독과 무관하게 전체 변경을 한 곳(메신저 채널 등)으로 보내기(D-102 로 없앰), 사람마다 알림 종류 고르기, 여러 티켓을 묶은 메일(변경 다이제스트. 한 티켓의 연속 변경은 D-126 으로 묶는다. 하루에 한 번 보내는 **요약**은 D-216 으로 생겼다), 이메일 주소 확인(인증 메일), 프로젝트별 웹훅, 보낸 기록 목록, 재시작 뒤 이어 보내기.

## 보내는 방식

```
IssueAnnouncer.changed ─ 커밋 뒤 ─▶ NotificationDispatcher.dispatch   (기다리지 않는다)
                                        └─ 통로마다 백그라운드 코루틴 하나
                                             ├─ MailNotificationChannel     Mailer (Jakarta Mail, Dispatchers.IO)
                                             ├─ PushNotificationChannel     FcmClient (FCM HTTP v1, WebClient)
                                             └─ WebhookNotificationChannel  WebhookSender (WebClient)
```

- **모아서 보낸다 (D-126, 2026-10-15).** 같은 사람이 같은 티켓에 한 변경은 바로 나가지 않고 모인다. 그 사람이 그 티켓을 **1분 동안 더 바꾸지 않으면**, 또는 **티켓 창을 닫으면**(다른 티켓으로 옮겨도) 한 통으로 간다. 계속 고치고 있어도 첫 변경 뒤 10분이면 보낸다. 다른 사람의 변경은 따로 모인다. Git 서버가 한 변경은 모으지 않고 호출 한 번에 한 통으로 바로 보낸다(D-155).
  - 합치는 법: 같은 항목을 여러 번 바꾸면 처음 값 → 마지막 값 한 줄(`상태: 백로그 → 완료`). 되돌려 같아지면 그 줄은 빠진다. 라벨·담당자는 최종으로 더해지고 빠진 것만. 본문 수정과 만들기는 한 번, 본문 언급은 이름을 합쳐 한 줄, 댓글·파일·문서는 각각 한 줄. 모두 빠지면 보내지 않는다.
  - 받는 사람은 묶인 변경들의 받는 사람을 합친 것이다. 머리말과 API 알림의 `event` 는 묶인 변경 전체로 정한다(만들기가 들어 있으면 `issue.created`).
  - 화면: 티켓 창이 닫히면(또는 다른 티켓으로 바뀌면) `POST /api/v1/issues/{번호}/notifications/flush` 를 부른다(204, 부른 사람이 그 티켓에 모아 둔 것만 보낸다. 없으면 아무 일 없음). 브라우저를 그냥 닫으면 1분 뒤에 간다.
  - 설정: `flowbit.notifications.batch-delay`(기본 `1m`, `0s` 면 모으지 않고 바로 보낸다), `flowbit.notifications.batch-max-delay`(기본 `10m`).
- **요청을 늦추지 않는다.** 티켓을 고친 요청은 알림을 기다리지 않고 끝난다. 통로 하나가 실패해도 다른 통로와 요청에는 영향이 없고 로그만 남는다.
- **디스크에 쌓지 않는다.** 보내는 중에 서버가 꺼지면 그 알림은 사라진다. 모아 두던 것은 꺼질 때 5초 동안 보내 본다. 아웃박스 테이블은 수신함을 만들 때 함께 만든다.
- 문구는 `NotificationText` 한 곳에서 만든다: 제목 `[FB-12] 티켓 제목`, 머리말 `김지민 님이 티켓을 수정했습니다`, 변경마다 한 줄(`상태: 백로그 → 진행 중`). 세 통로가 같은 말을 한다. **상태는 범주가 아니라 상태 이름으로 말한다**(D-242): 같은 범주 안의 이동은 `상태: 진행 중 → 리뷰 준비됨` 으로 나온다(전에는 범주 이름만 써서 `진행 중 → 진행 중` 이었다). 상태 이름이 없는 옛 기록은 범주 이름으로 말한다. 합칠 때도 이름으로 비교해서, 진행 중 → 리뷰 준비됨 → 진행 중 처럼 처음과 같아진 때만 그 줄이 빠진다.
- **Git 이 상태를 옮긴 알림에는 그 커밋이 붙는다**(D-242): `커밋 1개: main` 아래에 커밋마다 `해시 첫 줄 메시지`(해시는 10자, 메시지는 120자에서 자른다)를 한 줄씩, 5개를 넘으면 `외 N개`. 상태가 옮겨질 때만 붙고(상태가 그대로면 커밋만으로는 알림이 가지 않는다, D-155), 문구에만 있을 뿐 API 알림의 `changes` 에는 `STATUS_CHANGED` 만 있다(커밋 종류를 고른 API 알림은 `GIT_COMMITS_PUSHED` 도 받는다).
- 링크는 `FLOWBIT_PUBLIC_URL` 이 있으면 `https://…/issue/FB-12`, 없으면 경로(`/issue/FB-12`)만이다. **메일과 API 알림에 열 수 있는 링크를 넣으려면 `FLOWBIT_PUBLIC_URL` 을 정해야 한다.** 푸시는 같은 사이트라 경로만으로 열린다.

## 메일

- 설정: 사용 여부, 서버, 포트(기본 587), 보안 연결(`NONE`·`STARTTLS`·`SSL`), 사용자 이름·비밀번호(비우면 로그인 없이), 보내는 사람 주소·이름.
- STARTTLS 를 고르면 필수다(암호화가 안 되면 보내지 않는다). 시간 제한 10초.
- 한 사람에게 한 통씩 보낸다(받는 사람끼리 주소가 보이지 않게). 본문은 글자와 간단한 HTML 두 가지.
- 받는 주소는 `users.email`. 각자 **내 설정 > 알림**에서 입력한다. 이 주소는 로그인 아이디로도 쓰이므로(D-089) 다른 사람의 아이디·이메일과 겹칠 수 없다(409 `EMAIL_TAKEN`). 주소가 본인 것인지 확인하는 절차는 없다.
- "메일로 알림 받기"(`users.notify_email`, 기본 켜짐)를 끄면 주소가 있어도 보내지 않는다.
- 테스트: 저장하지 않은 입력값으로 한 통 보낸다.

## 웹 푸시

- 관리자가 넣는 값: 서비스 계정 키(JSON 전체, 서버가 보낼 때 씀), 웹 앱의 `apiKey`·`messagingSenderId`·`appId`, 웹 푸시 인증서 키(VAPID). `projectId` 는 서비스 계정 키에서 읽는다.
- 서버: `firebase-admin` SDK 를 쓰지 않는다(블로킹). 서비스 계정 키로 JWT 를 서명해 액세스 토큰을 받고(1시간, 메모리에 보관) FCM HTTP v1 API(`messages:send`)를 `WebClient` 로 부른다.
- 브라우저: **내 설정 > 알림 > "이 브라우저에서 켜기"** 를 누르면 알림 권한을 묻고, 서비스 워커 `public/push-sw.js` 를 등록하고, Firebase 에서 받은 토큰을 서버에 등록한다(`push_tokens`). 켠 것이 곧 동의다. 브라우저마다 따로 켠다. `firebase` 패키지는 이때만 불러온다(동적 import).
- 서비스 워커는 받은 알림을 띄우고, 누르면 그 티켓을 연다(이 사이트 안의 경로만). 캐시나 요청 가로채기는 하지 않는다. Firebase SDK 를 서비스 워커에서 쓰지 않으므로 외부 스크립트를 불러오지 않는다.
- 같은 티켓의 알림은 `tag`(티켓 키)가 같아 앞의 것을 덮는다.
- FCM 이 "등록이 사라졌다"(400·403·404)고 답한 토큰은 지운다.
- 테스트: 저장된 설정으로, 누른 관리자가 등록한 브라우저에 보낸다.
- 브라우저의 푸시는 HTTPS(또는 localhost)에서만 동작한다.

## API 알림 (웹훅)

한 사람에게 메시지를 보내는 API(메신저 봇 등)를 부르는 통로다. 변경 한 건마다 **받는 사람 수만큼** 부르고, 누구에게 보낼지는 `{{recipient.…}}` 로 주소나 본문에 넣는다. 범용으로 쓰도록 항목마다 다음을 정한다. 항목은 여러 개 둘 수 있다.

| 항목 | 내용 |
|---|---|
| 이름, 사용 | |
| 주소 | `http://`·`https://`. 사내 주소도 된다. **주소에도 `{{자리표시자}}` 를 쓸 수 있고 값은 URL 인코딩된다** |
| 메서드 | `GET`·`POST`·`PUT`·`PATCH`·`DELETE`. `GET`·`DELETE` 는 본문과 서명 없이 주소만 보낸다 |
| Content-Type | 기본 `application/json` |
| 추가 헤더 | 이름: 값 (20개까지). 인증 토큰은 여기에. `Host`·`Content-Type`·`Content-Length` 등은 정할 수 없다 |
| 본문 템플릿 | 비우면 표준 JSON. 쓰면 `{{자리표시자}}` 를 값으로 바꾼다 |
| 서명 비밀 값 | 넣으면 본문의 HMAC-SHA256 을 `X-Flowbit-Signature: sha256=<hex>` 로 보낸다 |
| 보낼 변경 | 변경 종류를 고른다. 아무것도 고르지 않으면 전부 |

**표준 JSON**

```json
{
  "event": "issue.updated",
  "deliveryId": "…", "occurredAt": "2026-10-12T13:00:00Z",
  "issue": { "key": "FB-12", "number": 12, "title": "…", "url": "https://…/issue/FB-12", "projectId": null },
  "actor": { "id": "…", "name": "김지민" },
  "changes": [ { "type": "STATUS_CHANGED", "detail": { "from": "BACKLOG", "to": "IN_PROGRESS" } } ],
  "recipient": { "id": "…", "loginId": "kimjm", "name": "김지민", "email": "kim@example.com" },
  "title": "[FB-12] …", "headline": "김지민 님이 티켓을 수정했습니다",
  "lines": ["상태: 백로그 → 진행 중"], "text": "제목 — 머리말\n줄…\nhttps://…/issue/FB-12"
}
```

**`text` 의 마지막 줄은 티켓을 여는 주소다** (사용자 지정, 2026-10-13: 내용만 오면 바로 들어갈 수 없다). `{{text}}` 자리표시자도 같다. `FLOWBIT_PUBLIC_URL` 이 없으면 붙이지 않는다(경로 `/issue/FB-12` 만으로는 열 수 없다). 그래서 `{{text}}` 를 쓰는 템플릿에 `{{issue.url}}` 을 따로 더하면 주소가 두 번 나온다. `title`·`headline`·`lines`·`{{changes}}` 에는 주소가 없다.

`changes[].detail` 은 티켓 활동과 같다([05-data-model.md](../05-data-model.md) `issue_activities`): 대개 `{from, to}`, 라벨과 담당자는 `{added, removed}`(이름 목록, 담당자는 D-125 부터).

`event` 는 `issue.created`·`issue.updated`·`issue.deleted`·`issue.restored`·`issue.commented`. `recipient` 는 이 호출이 향하는 한 사람이다(`email` 은 없으면 `null`). 다른 받는 사람의 목록(`recipientIds`)은 D-102 로 뺐다. 같은 값이 `X-Flowbit-Event` 헤더로도 간다. `X-Flowbit-Delivery` 는 호출마다 다르다(재시도는 같다).

**자리표시자**: `{{text}}` `{{title}}` `{{headline}}` `{{changes}}`(줄바꿈으로 이은 변경) `{{event}}` `{{issue.key}}` `{{issue.title}}` `{{issue.url}}` `{{issue.number}}` `{{actor.name}}` `{{deliveryId}}` `{{payload}}`(표준 JSON 전체, 그대로 들어간다. 본문에서만) `{{recipient.email}}` `{{recipient.name}}` `{{recipient.loginId}}` `{{recipient.id}}`. 모르는 이름은 빈 값. 값은 Content-Type 에 맞게 이스케이프한다: JSON 이면 JSON 문자열로, `x-www-form-urlencoded` 면 URL 인코딩, 그 밖에는 그대로. 화면의 예시 버튼은 "표준 JSON"과 "NAVER WORKS 메시지 (hrm)" 둘이다. 채널에 올리는 Slack·Discord·Teams 예시는 없앴다(받는 사람 수만큼 같은 글이 올라간다).

**받는 사람마다 호출**: 받는 사람(위 "범위")마다 한 번씩 부른다. 주소나 본문에 `{{recipient.email}}` 을 썼는데 이메일이 없는 사람은 건너뛴다. 내 설정의 "메일로 알림 받기"와는 무관하다. 받는 사람이 없는 변경에는 호출이 없다. 채널 수신 웹훅(Slack 등)처럼 받는 사람을 가리지 않는 주소를 넣으면 같은 글이 사람 수만큼 올라가므로 맞지 않는다.

**hrm 의 NAVER WORKS 메시지** (`GET /api/works/send`): 화면의 "예시 넣기 > NAVER WORKS 메시지 (hrm)" 이 채워 준다. 메서드 `GET`, 주소 `https://hrm.motherk.link/api/works/send?botId=봇ID&email={{recipient.email}}&message={{text}}`, 헤더 `Authorization: Bearer 토큰`. 관리자는 봇 ID 와 토큰을 넣는다. 받는 사람은 내 설정의 이메일(또는 LDAP·SSO 로 가입할 때 들어온 이메일)로 정해진다. 메시지 맨 아래에 티켓 주소가 붙으려면 서버에 `FLOWBIT_PUBLIC_URL` 이 있어야 한다. 메시지가 주소에 실리므로 hrm 과 중간 서버의 접근 로그에 남는다(그 API 의 설계다).

**전달**: 시간 제한 10초. 응답이 없거나 5xx 이면 1초·2초 간격으로 두 번 더 한다. 4xx 는 다시 하지 않는다. 리다이렉트는 따라가지 않는다. 마지막 결과(상태 코드, 오류, 시각)를 항목에 남겨 화면에 보여 준다. 테스트는 저장하지 않은 입력값으로 가짜 변경 한 건을 한 번만 보낸다. 받는 사람은 버튼을 누른 관리자다.

**보안**: 주소는 관리자만 정한다. 사내 메신저처럼 내부 주소를 부르는 것이 정상적인 쓰임이라 사설 IP 를 막지 않는다. 즉 관리자는 서버가 닿을 수 있는 곳 어디로든 요청을 보내게 할 수 있다. 헤더 값은 관리자에게 그대로 돌려준다. 서명 비밀 값은 돌려주지 않는다.

## API

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/v1/admin/notifications` | 관리자 | `{ smtp, push }`. 비밀번호·키 대신 `passwordSet`, `serviceAccountSet`·`projectId` |
| PUT | `/api/v1/admin/notifications/smtp` | 관리자 | `password` 를 빼면 저장된 값 유지 |
| POST | `/api/v1/admin/notifications/smtp/test` | 관리자 | 본문의 설정 + `testTo`. 항상 200, `{ ok, message }` |
| PUT | `/api/v1/admin/notifications/push` | 관리자 | `serviceAccount` 를 빼면 유지 |
| POST | `/api/v1/admin/notifications/push/test` | 관리자 | `{ ok, message }` |
| GET, POST | `/api/v1/admin/notifications/webhooks` | 관리자 | 목록, 추가(201) |
| PUT, DELETE | `/api/v1/admin/notifications/webhooks/{id}` | 관리자 | `signingSecret` 을 빼면 유지 |
| POST | `/api/v1/admin/notifications/webhooks/test` | 관리자 | 본문의 설정(+ 저장된 항목의 `id`)으로 한 번 호출. `{ ok, message }` |
| PUT, DELETE | `/api/v1/projects/{id}/subscription` | 프로젝트에 들어갈 수 있는 사람 | 프로젝트 구독·취소. 응답은 프로젝트(`subscribed`) |
| GET, PATCH | `/api/v1/me/notifications` | 로그인 | `{ email, notifyEmail, mailAvailable, push }`. `push` 는 브라우저가 등록에 쓸 Firebase 값(꺼져 있으면 null). PATCH 는 `email`(빈 값이면 지움)과 `notifyEmail` 을 함께 바꾼다 |
| POST | `/api/v1/me/push-tokens` | 로그인 | `{ token }` 등록. 204 |
| POST | `/api/v1/me/push-tokens/unregister` | 로그인 | `{ token }` 내 등록만 지운다. 204 |

토큰을 지우는 요청이 `DELETE` 가 아닌 것은 토큰을 주소에 넣지 않기 위해서다.

## 인수 기준

`NotificationsIntegrationTest`(JVM 안의 SMTP 서버 GreenMail, 가짜 FCM·토큰·웹훅 서버)가 확인한다.

- 내 알림 설정에서 이메일과 수신 여부를 바꾼다. 그 주소로 로그인도 된다. 틀린 형식(422), 남이 쓰는 주소(409)는 거부한다
- 알림 설정은 관리자만 다루고 비밀번호·서비스 계정 키·서명 비밀 값을 돌려주지 않는다. 틀린 설정으로는 켤 수 없다
- 티켓이 바뀌면 구독자 중 주소가 있고 메일을 켠 사람에게만 메일이 간다. 테스트 메일은 저장하지 않은 설정으로 간다
- API 알림은 받는 사람마다 한 번씩 부른다. GET 이면 값이 주소에 인코딩되어 들어간다. 이메일이 없는 사람은 건너뛴다
- 프로젝트를 구독한 사람은 그 프로젝트의 티켓 변경을 받는다. 들어갈 수 없는 프로젝트는 구독할 수 없고(404), 프로젝트에서 빠지거나 구독을 취소하면 받지 않는다. 아무도 구독하지 않은 변경(다른 프로젝트, 독립 티켓)은 보내지 않는다
- API 알림의 표준 JSON(`recipient` 포함)·헤더·서명이 맞다. 템플릿의 값은 JSON 을 깨지 않는다. 고른 변경에만 가고 꺼 둔 항목에는 가지 않는다. 잠깐 실패하면 다시 시도하고 결과를 남긴다. 추가·수정·삭제·테스트·검증
- 푸시: 브라우저가 등록할 값을 받는다. 등록한 브라우저로 FCM 요청이 가고(액세스 토큰, 제목·본문·경로), 사라진 등록은 지워진다. 남의 등록은 지울 수 없다

## 확인이 필요한 것

- hrm `/api/works/send` 의 인증은 JWT Bearer 다. 서버가 계속 쓸 수 있는 만료되지 않는 토큰(또는 외부 API 키)을 hrm 에서 받을 수 있는지 문서에 없다. 금방 만료되는 토큰뿐이면 헤더에 고정해 둘 수 없다.

- **화면과 실제 서비스는 확인하지 않았다.** 실제 SMTP 서버, 실제 Firebase 프로젝트(토큰 발급, 서비스 워커가 알림을 띄우는지, 눌렀을 때 이동), 실제 Slack·Teams 주소. 특히 서비스 워커가 받는 FCM 메시지의 모양(`notification`·`data`)은 문서를 보고 쓴 것이라 실제 브라우저에서 봐야 한다.
- 첨부 파일·문서를 더하거나 뺀 변경은 활동 기록에는 남지만 알림은 가지 않는다(그 API 들이 `IssueAnnouncer.changed` 를 부르지 않는다). 보낼지 정해야 한다.
- 프로젝트에서 빠진 사람이 예전에 구독한 **티켓**의 알림을 계속 받는다(티켓 구독은 정리하지 않는다. 프로젝트 구독은 빠지면 받지 않는다).
- 프로젝트 구독을 자동으로 걸지(만든 사람, 리드, 새 구성원), 프로젝트를 구독하면서 특정 티켓만 끄는 방법이 필요한지. (구독자 목록은 D-176 으로 프로젝트 활동에 보인다.)
- 메신저 채널로 전체 흐름을 보내는 쓰임이 다시 필요해지면 "프로젝트를 지정한 API 알림"으로 넣을 수 있다(D-102 에서 고르지 않은 선택지).
- 비밀번호·서비스 계정 키·서명 비밀 값·웹훅 헤더가 DB 에 평문이다(LDAP·SSO 와 같은 방식, D-090).
- 이메일 주소 확인 절차, 알림 종류별 선택, 수신함이 필요한지.

트리아지 분류 요청(`TRIAGE_REQUESTED`): 대기열에 새로 들어온 티켓을 프로젝트의 활성 관리 역할 구성원에게 즉시 알린다(발신자 제외). 구독과 관계없이 대상이며, 메일·푸시의 수신 설정과 API 알림의 보낼 변경 필터는 기존 규칙을 따른다.

프로젝트 자동화 알림 `AUTOMATION_TRIGGERED`: 규칙이 지정한 접근 가능한 활성 수신자에게 즉시 전달한다. 구독 여부와 무관하며 발신자 본인도 명시적인 대상이면 받는다. API 알림의 보낼 변경에서 **프로젝트 자동화**를 고를 수 있다.

## 댓글·답글 발송 시점 (2026-10-29 사용자 재확정)

일반 댓글·블록 댓글·답글은 티켓·프로젝트·문서 모두 저장 후 즉시 각 알림 통로에 전달한다. `batch-delay`가 켜져 있어도 댓글마다 별도로 발송하고, 창 닫기/flush를 기다리지 않는다. 대기 중인 제목·본문·속성 변경은 댓글과 합치지 않는다. 수신자는 D-218을 따른다. 실제 외부 통로의 도착 시간은 해당 서비스의 전송 시간에 따른다.

## PWA 전용 티켓 링크 (2026-10-30, 사용자 확정)

- Windows Chrome·Edge PWA는 manifest의 protocol_handlers로 web+flowbit를 등록한다. 형식은 web+flowbit://설치호스트/issue/MK-9이며 /open-app?url=%s에서 받는다. 기존 navigate-existing으로 실행 중인 앱 창을 재사용하도록 요청한다.
- 수신 페이지는 현재 설치 주소와 같은 호스트·포트의 올바른 티켓 경로만 허용한다. 자격증명·검색문자열·프래그먼트·다른 스킴·외부 호스트·임의 경로는 거부하고 안내한다. 허용된 상대 티켓 경로로만 이동하며 기존 로그인·티켓 권한을 적용한다.
- 티켓 알림의 API 알림 표준 JSON에 appUrl, issue.appUrl을 추가하고 {{appUrl}}·{{issue.appUrl}} 변수를 제공한다. {{text}}에는 앱 전용 링크와 기존 웹 링크를 함께 넣는다. FLOWBIT_PUBLIC_URL이 유효한 절대 티켓 주소를 만들 수 없으면 앱 링크는 null/빈 변수이며 표시하지 않는다. 일반 수신함·푸시·기존 issue.url은 HTTP 경로를 유지한다.
- PWA의 manifest 갱신과 OS 프로토콜 등록 뒤 사용한다. 이미 설치한 앱은 브라우저의 manifest 갱신을 기다리거나 필요하면 다시 설치한다. 첫 프로토콜 실행은 브라우저 허용이 필요하다. 메신저가 전용 스킴을 링크로 허용해야 클릭할 수 있으며 이를 지원하지 않으면 기존 HTTPS 링크로 연다. 여러 Flowbit 설치가 같은 스킴을 등록하면 OS 선택을 따르고 수신 앱은 다른 호스트를 거부한다.
- HTTPS 링크 자체를 자동 전환하는 기능이나 Windows 전용 실행 도우미, 최초 진입 alert은 이번 범위에 넣지 않는다.
구현 근거: [Chrome PWA URL protocol handlers](https://developer.chrome.com/docs/web-platform/best-practices/url-protocol-handler). manifest 갱신으로 프로토콜 등록이 동기화되며 최초 실행 허용과 외부 앱의 링크 지원이 필요하다.

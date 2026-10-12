# M7a. 알림 통로: 메일(SMTP), 웹 푸시(Firebase), API 알림

이슈가 바뀌면 앱 밖으로 알린다. 통로는 세 가지이고 관리자 설정의 **"알림"** 탭(`/admin/notifications`)에서 켠다. 사용자 지정(2026-10-12), 결정은 D-091~D-093. D-061 에서 자리만 만들어 둔 `NotificationChannel` 의 구현이다.

M7(수신함과 트리아지)의 일부를 먼저 만든 것이다. 앱 안의 수신함은 아직 없다.

## 범위

| 통로 | 누구에게 | 켜는 곳 |
|---|---|---|
| 메일 (SMTP) | 그 이슈의 구독자 중 이메일이 있고 메일을 켠 사람 | 관리자: 서버 설정. 각자: 내 설정에서 주소와 수신 여부 |
| 웹 푸시 (Firebase Cloud Messaging) | 그 이슈의 구독자가 등록한 브라우저 | 관리자: Firebase 값. 각자: 내 설정에서 브라우저마다 켬 |
| API 알림 (웹훅) | 등록한 주소. **구독과 무관하게 모든 이슈 변경**. "받는 사람마다"를 켠 항목은 구독자마다 한 번씩 | 관리자: 주소·헤더·본문을 정한 항목을 여러 개 |

- 구독자 = 이슈를 만든 사람, 담당자, 직접 구독한 사람. 바꾼 사람 본인은 뺀다(D-061 그대로).
- 알리는 변경 = 이슈 활동 기록에 남는 것 전부(`IssueActivityType`). 댓글은 아직 기능이 없다.

범위 밖: 앱 안의 수신함, 알림 종류별로 고르기, 메일 묶어 보내기(다이제스트), 이메일 주소 확인(인증 메일), 프로젝트별 웹훅, 보낸 기록 목록, 재시작 뒤 이어 보내기.

## 보내는 방식

```
IssueAnnouncer.changed ─ 커밋 뒤 ─▶ NotificationDispatcher.dispatch   (기다리지 않는다)
                                        └─ 통로마다 백그라운드 코루틴 하나
                                             ├─ MailNotificationChannel     Mailer (Jakarta Mail, Dispatchers.IO)
                                             ├─ PushNotificationChannel     FcmClient (FCM HTTP v1, WebClient)
                                             └─ WebhookNotificationChannel  WebhookSender (WebClient)
```

- **요청을 늦추지 않는다.** 이슈를 고친 요청은 알림을 기다리지 않고 끝난다. 통로 하나가 실패해도 다른 통로와 요청에는 영향이 없고 로그만 남는다.
- **디스크에 쌓지 않는다.** 보내는 중에 서버가 꺼지면 그 알림은 사라진다. 아웃박스 테이블은 수신함을 만들 때 함께 만든다.
- 문구는 `NotificationText` 한 곳에서 만든다: 제목 `[FB-12] 이슈 제목`, 머리말 `김지민 님이 이슈를 고쳤습니다`, 변경마다 한 줄(`상태: 백로그 → 진행 중`). 세 통로가 같은 말을 한다.
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
- 서비스 워커는 받은 알림을 띄우고, 누르면 그 이슈를 연다(이 사이트 안의 경로만). 캐시나 요청 가로채기는 하지 않는다. Firebase SDK 를 서비스 워커에서 쓰지 않으므로 외부 스크립트를 불러오지 않는다.
- 같은 이슈의 알림은 `tag`(이슈 키)가 같아 앞의 것을 덮는다.
- FCM 이 "등록이 사라졌다"(400·403·404)고 답한 토큰은 지운다.
- 테스트: 저장된 설정으로, 누른 관리자가 등록한 브라우저에 보낸다.
- 브라우저의 푸시는 HTTPS(또는 localhost)에서만 동작한다.

## API 알림 (웹훅)

범용으로 쓰도록 항목마다 다음을 정한다. 항목은 여러 개 둘 수 있다.

| 항목 | 내용 |
|---|---|
| 이름, 사용 | |
| 주소 | `http://`·`https://`. 사내 주소도 된다. **주소에도 `{{자리표시자}}` 를 쓸 수 있고 값은 URL 인코딩된다** |
| 메서드 | `GET`·`POST`·`PUT`·`PATCH`·`DELETE`. `GET`·`DELETE` 는 본문과 서명 없이 주소만 보낸다 |
| 받는 사람마다 | 끄면 변경 한 건에 한 번. 켜면 그 이슈의 구독자마다 한 번씩 호출하고 `{{recipient.…}}` 를 쓸 수 있다 (D-095) |
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
  "recipientIds": ["…"],
  "title": "[FB-12] …", "headline": "김지민 님이 이슈를 고쳤습니다",
  "lines": ["상태: 백로그 → 진행 중"], "text": "제목 — 머리말\n줄…"
}
```

`event` 는 `issue.created`·`issue.updated`·`issue.deleted`·`issue.restored`. 같은 값이 `X-Flowbit-Event` 헤더로도 간다. `X-Flowbit-Delivery` 는 호출마다 다르다(재시도는 같다).

**자리표시자**: `{{text}}` `{{title}}` `{{headline}}` `{{changes}}`(줄바꿈으로 이은 변경) `{{event}}` `{{issue.key}}` `{{issue.title}}` `{{issue.url}}` `{{issue.number}}` `{{actor.name}}` `{{deliveryId}}` `{{payload}}`(표준 JSON 전체, 그대로 들어간다. 본문에서만). 받는 사람마다 호출할 때는 `{{recipient.email}}` `{{recipient.name}}` `{{recipient.loginId}}` `{{recipient.id}}` 도 된다(표준 JSON 에는 `recipient` 객체). 모르는 이름은 빈 값. 값은 Content-Type 에 맞게 이스케이프한다: JSON 이면 JSON 문자열로, `x-www-form-urlencoded` 면 URL 인코딩, 그 밖에는 그대로. 화면에 Slack·Discord·Teams 예시 버튼이 있다(`{"text": "{{text}}"}` 등).

**받는 사람마다 호출**: 한 사람에게 메시지를 보내는 API(메신저 봇 등)에 맞춘 방식이다. 구독자(바꾼 사람 제외, 활성 계정)마다 한 번씩 부른다. 주소나 본문에 `{{recipient.email}}` 을 썼는데 이메일이 없는 사람은 건너뛴다. 내 설정의 "메일로 알림 받기"와는 무관하다. 구독자가 없는 변경에는 호출이 없다.

**hrm 의 NAVER WORKS 메시지** (`GET /api/works/send`): 화면의 "예시 넣기 > NAVER WORKS 메시지 (hrm)" 이 채워 준다. 메서드 `GET`, 받는 사람마다 켬, 주소 `https://hrm.motherk.link/api/works/send?botId=봇ID&email={{recipient.email}}&message={{text}}`, 헤더 `Authorization: Bearer 토큰`. 관리자는 봇 ID 와 토큰을 넣는다. 받는 사람은 내 설정의 이메일(또는 LDAP·SSO 로 가입할 때 들어온 이메일)로 정해진다. 메시지가 주소에 실리므로 hrm 과 중간 서버의 접근 로그에 남는다(그 API 의 설계다).

**전달**: 시간 제한 10초. 응답이 없거나 5xx 이면 1초·2초 간격으로 두 번 더 한다. 4xx 는 다시 하지 않는다. 리다이렉트는 따라가지 않는다. 마지막 결과(상태 코드, 오류, 시각)를 항목에 남겨 화면에 보여 준다. 테스트는 저장하지 않은 입력값으로 가짜 변경 한 건을 한 번만 보낸다.

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
| GET, PATCH | `/api/v1/me/notifications` | 로그인 | `{ email, notifyEmail, mailAvailable, push }`. `push` 는 브라우저가 등록에 쓸 Firebase 값(꺼져 있으면 null). PATCH 는 `email`(빈 값이면 지움)과 `notifyEmail` 을 함께 바꾼다 |
| POST | `/api/v1/me/push-tokens` | 로그인 | `{ token }` 등록. 204 |
| POST | `/api/v1/me/push-tokens/unregister` | 로그인 | `{ token }` 내 등록만 지운다. 204 |

토큰을 지우는 요청이 `DELETE` 가 아닌 것은 토큰을 주소에 넣지 않기 위해서다.

## 인수 기준

`NotificationsIntegrationTest`(JVM 안의 SMTP 서버 GreenMail, 가짜 FCM·토큰·웹훅 서버)가 확인한다.

- 내 알림 설정에서 이메일과 수신 여부를 바꾼다. 그 주소로 로그인도 된다. 틀린 형식(422), 남이 쓰는 주소(409)는 거부한다
- 알림 설정은 관리자만 다루고 비밀번호·서비스 계정 키·서명 비밀 값을 돌려주지 않는다. 틀린 설정으로는 켤 수 없다
- 이슈가 바뀌면 구독자 중 주소가 있고 메일을 켠 사람에게만 메일이 간다. 테스트 메일은 저장하지 않은 설정으로 간다
- 받는 사람마다 켠 항목은 구독자마다 GET 으로 한 번씩 부르고 값이 주소에 인코딩되어 들어간다. 이메일이 없는 구독자는 건너뛴다
- API 알림은 구독자가 없어도 가고, 표준 JSON·헤더·서명이 맞다. 템플릿의 값은 JSON 을 깨지 않는다. 고른 변경에만 가고 꺼 둔 항목에는 가지 않는다. 잠깐 실패하면 다시 시도하고 결과를 남긴다. 추가·수정·삭제·테스트·검증
- 푸시: 브라우저가 등록할 값을 받는다. 등록한 브라우저로 FCM 요청이 가고(액세스 토큰, 제목·본문·경로), 사라진 등록은 지워진다. 남의 등록은 지울 수 없다

## 확인이 필요한 것

- hrm `/api/works/send` 의 인증은 JWT Bearer 다. 서버가 계속 쓸 수 있는 만료되지 않는 토큰(또는 외부 API 키)을 hrm 에서 받을 수 있는지 문서에 없다. 금방 만료되는 토큰뿐이면 헤더에 고정해 둘 수 없다.

- **화면과 실제 서비스는 확인하지 않았다.** 실제 SMTP 서버, 실제 Firebase 프로젝트(토큰 발급, 서비스 워커가 알림을 띄우는지, 눌렀을 때 이동), 실제 Slack·Teams 주소. 특히 서비스 워커가 받는 FCM 메시지의 모양(`notification`·`data`)은 문서를 보고 쓴 것이라 실제 브라우저에서 봐야 한다.
- 첨부 파일·문서를 더하거나 뺀 변경은 활동 기록에는 남지만 알림은 가지 않는다(그 API 들이 `IssueAnnouncer.changed` 를 부르지 않는다). 보낼지 정해야 한다.
- 프로젝트에서 빠진 사람이 예전에 구독한 이슈의 알림을 계속 받는다(구독을 정리하지 않는다).
- 비밀번호·서비스 계정 키·서명 비밀 값·웹훅 헤더가 DB 에 평문이다(LDAP·SSO 와 같은 방식, D-090).
- 이메일 주소 확인 절차, 알림 종류별 선택, 수신함이 필요한지.

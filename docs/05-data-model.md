# 05. 데이터 모델

> **스키마의 바탕은 `backend/src/main/resources/db/migration/V1__schema.sql` 이다** (D-067). 이 파일은 더 고치지 않고, 이후의 변경은 `V2` 부터 새 파일로 추가한다 (D-084). 첫 운영 배포 전에 그동안의 단계별 마이그레이션을 **한 번 더, 마지막으로** 이 파일 하나로 합쳤다(2026-10-13, D-104). 지금 마이그레이션 파일은 `V1__schema.sql` 하나뿐이다. **아래 표에 남아 있는 `V2`~`V9` 같은 표시는 합치기 전 파일 번호로, 그 컬럼이 언제 생겼는지의 기록일 뿐이다.** 모두 `V1` 안에 있다. 이 문서의 표는 그 파일의 설명이고, 어긋나면 파일이 기준이다. `issues.project_id` 는 프로젝트가 지워지면 null 이 된다(티켓은 남는다).

스키마 설계나 검토 시 `agent-skills/wshobson-agents/plugins/database-design/skills/postgresql-table-design/SKILL.md` 를 참고한다.

## 공통 규칙

- 테이블과 컬럼 이름은 `snake_case`. 테이블 이름은 복수형 (`users`).
- 기본 키는 `id uuid`. 값은 UUID v7 을 쓴다 (시간순 정렬이 되어 인덱스에 유리하다).
- 시각은 모두 `timestamptz`, UTC 로 저장한다.
- 모든 테이블에 `created_at`, `updated_at` 을 둔다.
- 문자열은 `text` 를 쓰고 길이 제한은 `CHECK` 제약으로 건다.
- 정해진 값 집합은 `text` + `CHECK` 로 표현한다 (PostgreSQL enum 타입은 변경이 번거로워 쓰지 않는다).
- 외래 키에는 항상 제약을 걸고, 외래 키 컬럼에는 인덱스를 만든다.
- 삭제는 기본적으로 실제 삭제하지 않는다. 사용자는 `status` 로 비활성화한다. 다른 엔티티의 삭제 정책은 그 기능의 명세에서 정한다.
- 아이디, 이메일처럼 대소문자를 구분하지 않는 값은 소문자로 정규화해서 저장하고 유니크 제약을 건다.

## M1 테이블

### `instance_settings`

인스턴스 전체 설정. 행이 하나만 존재한다.

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | `smallint` PK | 항상 `1` (`CHECK (id = 1)`) |
| `organization_name` | `text` | 기본 `Flowbit`. M2 의 설정 화면에서 바꾼다 |
| `logo_key` | `text` null | 워크스페이스 로고의 저장 파일 이름(`<uuid>.webp`). `V2` 에서 추가 |
| `default_locale` | `text` | 기본 `ko` |
| `default_timezone` | `text` | 기본 `Asia/Seoul`. IANA 시간대 이름 |
| `setup_completed_at` | `timestamptz` null | null 이면 초기 설정 전. 관리자 계정이 만들어질 때 채운다 |
| `created_at`, `updated_at` | `timestamptz` | |

첫 마이그레이션에서 `id = 1` 행을 넣어 둔다.

### `users`

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | `uuid` PK | |
| `login_id` | `text` unique | 로그인 아이디 (API 필드 `loginId`). 소문자로 정규화. 일반 아이디(4~32자) 또는 이메일 주소. LDAP·SSO 가 준 아이디는 길이 하한이 없다 (`V2`, D-089) |
| `email` | `text` null unique | 소문자로 정규화. 이메일 아이디로 만든 계정과 LDAP·SSO 로 가입한 계정에 들어간다. 로그인할 때 아이디 대신 입력할 수 있다 |
| `display_name` | `text` null | 1~50자. M1 에서는 받지 않는다 (M2 프로필) |
| `avatar_key` | `text` null | 프로필 사진의 저장 파일 이름(`<uuid>.webp`). `V2` 에서 추가 |
| `password_hash` | `text` null | Argon2id. `auth_source` 가 `LOCAL` 일 때만 있다 (`V2`) |
| `auth_source` | `text` | `LOCAL` \| `LDAP` \| `SSO`. 비밀번호를 어디서 확인하는지 (`V2`, D-087) |
| `external_id` | `text` null | SSO 서비스가 준 식별자(OIDC `sub`). `(auth_source, external_id)` unique (`V2`) |
| `role` | `text` | `ADMIN` \| `MEMBER` |
| `status` | `text` | `ACTIVE` \| `DISABLED` |
| `locale` | `text` null | null 이면 인스턴스 기본값 |
| `timezone` | `text` null | null 이면 인스턴스 기본값 |
| `failed_login_count` | `int` | 기본 0. 로그인 성공 시 0 으로 |
| `locked_until` | `timestamptz` null | 이 시각까지 로그인 불가 |
| `last_login_at` | `timestamptz` null | |
| `tour_completed_at` | `timestamptz` null | 온보딩 투어를 처음 끝내거나 건너뛴 때. null 이면 앱을 열 때 투어가 뜬다. 다시 봐도 바뀌지 않는다 (`V4__user_tour.sql`, D-120, [features/onboarding-tour.md](features/onboarding-tour.md)) |
| `created_at`, `updated_at` | `timestamptz` | |

### `ldap_settings`, `sso_settings` (`V2`, D-087·D-088)

각각 행이 하나뿐이다(`id = 1`). 항목의 뜻은 [features/M2b-ldap-and-sso.md](features/M2b-ldap-and-sso.md) "설정 항목".

| 테이블 | 컬럼 |
|---|---|
| `ldap_settings` | `enabled`, `url`, `start_tls`, `bind_dn`, `bind_password`, `base_dn`, `user_filter`, `login_id_attribute`, `email_attribute`, `display_name_attribute`, `auto_provision`, `updated_at` |
| `sso_settings` | `enabled`, `display_name`, `issuer`, `client_id`, `client_secret`, `scopes`, `login_id_claim`, `auto_provision`, `updated_at`. `V4`(D-094): `protocol`(`OIDC`\|`OAUTH2`), `authorize_url`, `token_url`, `userinfo_url`, `token_auth`(`BASIC`\|`BEARER`\|`BODY`), `token_format`(`FORM`\|`JSON`), `token_body`, `subject_field`, `email_field`, `name_field`, `button_style`(`DEFAULT`\|`NAVER_WORKS`) |

`bind_password` 와 `client_secret` 은 평문이다. API 로는 내보내지 않는다. DB 백업을 다룰 때 비밀로 취급한다.

### 알림 테이블 (`V3`, D-091)

뜻은 [features/M7a-notification-channels.md](features/M7a-notification-channels.md).

| 테이블 | 컬럼 | 비고 |
|---|---|---|
| `users.notify_email` | `boolean` 기본 true | 메일 알림을 받을지. 주소는 `users.email` |
| `notification_settings` | `smtp_enabled`, `smtp_host`, `smtp_port`, `smtp_security`(`NONE`\|`STARTTLS`\|`SSL`), `smtp_username`, `smtp_password`, `smtp_from_address`, `smtp_from_name`, `push_enabled`, `fcm_service_account`, `fcm_api_key`, `fcm_messaging_sender_id`, `fcm_app_id`, `fcm_vapid_key`, `updated_at` | 행 하나(`id = 1`). `smtp_password`, `fcm_service_account` 는 평문이고 API 로 내보내지 않는다 |
| `page_views` | `page_key`(프레즌스 키: `project:<id>`, `issue:<번호>`) + `user_id` FK → `users`(함께 삭제) PK, `seen_at` | 그 사람이 그 화면을 마지막으로 본 때. 화면 오른쪽 위 아바타의 순서와 "마지막 확인"에 쓴다. 행이 없거나(본 적 없음) 30일보다 오래된 사람은 아바타에 나오지 않는다 (`V3__page_views.sql`, D-119). 실시간 연결이 화면에 들어올 때와 떠날 때 쓴다 |
| `push_tokens` | `token` PK, `user_id` FK → `users`(함께 삭제), `user_agent`, `created_at`, `last_seen_at` | 푸시를 켠 브라우저 하나당 한 행. 같은 토큰을 다른 계정이 등록하면 주인이 바뀐다 |
| `webhooks` | `id`, `name`, `enabled`, `url`, `method`(`GET`\|`POST`\|`PUT`\|`PATCH`\|`DELETE`, `V4`), `content_type`, `headers`, `body_template`, `signing_secret`, `event_types`, `last_status`, `last_error`, `last_attempt_at`, `created_at`, `updated_at` | `headers` 는 JSON 객체를 담은 `text`(엔티티에 그대로 매핑하려고 `jsonb` 를 쓰지 않았다). `event_types` 는 쉼표로 이은 `IssueActivityType` 이름, 빈 값은 전부. 항상 받는 사람마다 부르므로 `per_recipient` 컬럼은 없다(D-102) |

### `audit_events`

보안상 의미 있는 사건의 기록. 추가만 하고 수정/삭제하지 않는다.

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | `uuid` PK | |
| `occurred_at` | `timestamptz` | |
| `actor_user_id` | `uuid` null FK → `users` | 로그인 실패처럼 주체가 없으면 null |
| `event_type` | `text` | `SETUP_COMPLETED`, `LOGIN_SUCCEEDED`, `LOGIN_FAILED`, `ACCOUNT_LOCKED`, `LOGOUT` |
| `ip_address` | `inet` null | |
| `user_agent` | `text` null | |
| `detail` | `jsonb` | 추가 정보. 비밀번호를 넣지 않는다 |

인덱스: `(occurred_at)`, `(actor_user_id, occurred_at)`.

### 세션 테이블

`user_sessions`: 로그인한 브라우저 하나당 한 행. Spring Session 은 쓰지 않는다(WebFlux 에서 JDBC 저장소를 쓸 수 없다, D-019).

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | `uuid` PK | |
| `token_hash` | `text` unique | 쿠키에 담긴 무작위 토큰(32바이트)의 SHA-256. 토큰 원문은 저장하지 않는다 |
| `user_id` | `uuid` FK → `users` (삭제 시 함께 삭제) | |
| `created_at` | `timestamptz` | 로그인 시각. 여기서 30일이 지나면 만료 |
| `last_seen_at` | `timestamptz` | 마지막 사용 시각. 여기서 7일이 지나면 만료. 5분에 한 번만 갱신한다 |
| `ip_address` | `inet` null | 로그인한 IP |
| `user_agent` | `text` null | |

만료된 행은 다음 로그인 때 함께 지운다.

### 이미지 파일

이미지 자체는 DB 가 아니라 파일로 저장한다(`FLOWBIT_STORAGE_PATH/images/`). DB 에는 위의 `*_key` 컬럼에 파일 이름만 둔다. 규칙은 [features/M2a-settings-and-images.md](features/M2a-settings-and-images.md). 티켓 첨부처럼 한 대상에 이미지가 여러 개 붙게 되면 그때 별도 테이블을 만든다.

### `projects`, `project_members` (M3 1단계)

| 테이블.컬럼 | 타입 | 설명 |
|---|---|---|
| `projects.id` | `uuid` PK | |
| `projects.name` | `text` | 1~80자 |
| `projects.description` | `text` | 500자 이하, 기본 빈 문자열 |
| `projects.created_by` | `uuid` FK → `users` | |
| `projects.archived_at` | `timestamptz` null | 아카이브한 때(D-115). 값이 있으면 프로젝트와 그 티켓이 모든 목록에서 빠지고 들어갈 수 없다. 복원하면 null |
| `projects.status` (`V6`) | `text` | `BACKLOG` \| `PLANNED` \| `IN_PROGRESS` \| `COMPLETED` \| `CANCELED`, 기본 `BACKLOG` |
| `projects.priority` (`V6`) | `text` | `NONE` \| `URGENT` \| `HIGH` \| `MEDIUM` \| `LOW`, 기본 `NONE` |
| `projects.lead_id` (`V6`) | `uuid` null FK → `users` (지워지면 null) | 프로젝트 리드 |
| `projects.start_date`, `target_date` (`V6`) | `date` null | 기간. 둘 다 있으면 `start_date <= target_date` |
| `projects.labels` (`V6`) | `text[]` | 라벨 이름, 최대 10개. 기본 빈 배열 |
| `projects.icon_key` (`V9`) | `text` null | 올린 프로젝트 아이콘의 저장소 파일 이름(`ImageStorage`). 없으면 기본 아이콘 |
| `project_members.project_id`, `user_id` | `uuid` | 복합 기본 키. 프로젝트나 사용자가 지워지면 함께 지워진다 |
| `project_members.role` | `text` | `MANAGER` \| `EDITOR` \| `VIEWER` |

`project_members` 는 기본 키가 두 컬럼이라, `projects` 의 속성 컬럼은 배열(`labels`)이 있어서 Spring Data 리포지토리 대신 `ProjectMemberStore` 가 SQL 로 직접 다룬다.

### `project_access_requests` (`V7`, D-128)

들어갈 수 없는 프로젝트에 들어가게 해 달라는 요청. 대기 중인 것만 있다: 승인하거나 거절하면 지운다.

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `project_id` | `uuid` FK → `projects` (CASCADE) | `(project_id, user_id)` 가 PK |
| `user_id` | `uuid` FK → `users` (CASCADE) | 요청한 사람 |
| `created_at` | `timestamptz` | 요청한 때. 목록은 오래된 것부터 |

### `project_updates`

프로젝트가 어떻게 진행되는지 남기는 글. 가장 최근 것의 `health` 가 프로젝트의 건강도다 (D-048).

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | `uuid` PK | |
| `project_id` | `uuid` FK → `projects` (함께 삭제) | `(project_id, created_at desc)` 색인 |
| `author_id` | `uuid` FK → `users` | |
| `health` | `text` | `ON_TRACK` \| `AT_RISK` \| `OFF_TRACK` |
| `body` | `text` | 평문, 5000자 이하, 기본 빈 문자열 |
| `created_at` | `timestamptz` | |

### `milestones`

프로젝트 안의 단계. 티켓을 묶어 단계별 진행률을 본다 ([features/M3-projects-and-issues.md](features/M3-projects-and-issues.md) "마일스톤").

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | `uuid` PK | |
| `project_id` | `uuid` FK → `projects` (함께 삭제) | |
| `name` | `text` | 1~80자 |
| `target_date` | `date` null | 목표일 |
| `created_at`, `updated_at` | `timestamptz` | |

`milestones.content`(`V2__milestone_content.sql`, D-113): `jsonb` null. 목표의 "정리" 문서(편집기 JSON, `RichText` 가 검증). 엔티티(`Milestone`)에는 싣지 않고 `RichText.store`/목록 조회의 SQL 로 읽고 쓴다(프로젝트·티켓의 `content` 와 같다).

`issues.milestone_id`(`V7`): `uuid` null FK → `milestones`. 마일스톤을 지우면 null 이 된다. 같은 프로젝트의 마일스톤인지는 서비스가 확인한다.

### `issues`, 문서 컬럼

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | `uuid` PK | |
| `project_id` | `uuid` FK → `projects` (함께 삭제) | |
| `number` | `int` | 워크스페이스 전체의 일련번호. 유일(`issues_number_key`). `instance_settings.next_issue_number` 를 한 문장으로 증가시켜 발급 |
| `title` | `text` | 1~200자 |
| `content` | `jsonb` null | 본문. 편집기 문서(JSON). `V5` |
| `description` | `text` | 본문에서 뽑은 글자. 미리 보기와 검색용. 서버가 채운다 |
| `status` | `text` | `BACKLOG` \| `READY` \| `IN_PROGRESS` \| `DONE` \| `CANCELED` |
| `priority` | `text` | `NONE` \| `URGENT` \| `HIGH` \| `MEDIUM` \| `LOW` |
| `due_date` | `date` null | |
| `created_by` | `uuid` FK → `users` | |
| `version` | `int` | 바뀔 때마다 1 증가. 충돌 감지와 실시간 순서 판단에 쓸 예정 |

`projects.content jsonb` (`V5`): 프로젝트 개요의 설명 문서. `projects.description` 은 한 줄 요약이다.

`content` 컬럼은 엔티티에 매핑하지 않는다. `RichText` 가 SQL 로 직접 읽고 쓴다(목록 조회에 큰 문서가 딸려 오지 않게 하려는 것이기도 하다).

## 이후 마일스톤의 모델 (초안)

방향만 적어 둔다. 각 마일스톤을 시작할 때 기능 명세에서 확정하고 이 문서에 반영한다.

티켓·프리셋·상태·댓글의 모델 초안은 [features/M3-projects-and-issues.md](features/M3-projects-and-issues.md) 의 "데이터 모델"이 기준이다. 아래 그림은 팀 개념이 있던 때의 것이고, **`teams` 는 `projects` 로 읽는다** (D-032).

```
teams ──< team_members >── users
  │
  ├──< statuses (범주: backlog / unstarted / started / completed / canceled)
  ├──< cycles (번호, 시작일, 종료일)
  ├──< labels
  └──< issues ──< comments, issue_history, issue_relations
          │ └── parent_issue_id (하위 티켓, 10단계까지)
          ├── status_id, assignee_id, cycle_id, project_id
          └──< issue_labels

projects ──< project_updates          views (저장한 필터)
notifications (수신함)
```

- 티켓 키: `teams.key` + 팀별 일련번호 (`FLOW-123`). 번호는 팀 행의 카운터를 잠그고 증가시켜 발급한다.
- 목록·보드의 순서: 사이에 끼워 넣기가 쉬운 순위 문자열(LexoRank 계열)을 쓴다.
- 티켓에는 `version`(증가하는 정수)을 둔다. 낙관적 업데이트의 중복 제거와 실시간 이벤트의 순서 판단에 쓴다.
- 티켓 변경 이력은 필드 단위로 남긴다. 실행 취소와 활동 기록이 이것을 쓴다.
- 삭제는 `deleted_at` 으로 표시하고 일정 기간 뒤에 실제로 지운다(실행 취소가 가능해야 한다).

### `labels`, `issue_labels` (D-060)

워크스페이스 전체가 함께 쓰는 티켓 라벨. 티켓 하나에 여러 개 붙는다(최대 10개).

| 테이블 | 컬럼 | 설명 |
|---|---|---|
| `labels` | `id` uuid PK, `name` text(1~30자, 대소문자 무시하고 유일), `color` text(`#rrggbb`), `created_at` | 처음부터 `버그`, `기능`, `중요` 세 개가 들어 있다. 목록은 만든 순서 |
| `issue_labels` | `issue_id`, `label_id` (둘이 PK, 둘 다 `ON DELETE CASCADE`) | 티켓과 라벨의 연결 |

### `issue_assignees` (`V6`, D-125)

티켓의 담당자. 한 티켓에 여러 명(최대 10명). `V6` 이 `issues.assignee_id` 의 값을 여기로 옮기고 그 컬럼을 지웠다.

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `issue_id` | `uuid` FK → `issues` (CASCADE) | `(issue_id, user_id)` 가 PK |
| `user_id` | `uuid` FK → `users` (CASCADE) | 연결된 프로젝트의 활성 구성원(독립 티켓은 모든 활성 사용자). 인덱스 `issue_assignees_user_id_idx`(내 티켓) |
| `created_at` | `timestamptz` | 지정한 때. 화면은 이 순서로 보여 준다 |


### `issue_activities`, `issue_subscriptions` (D-061)

| 테이블 | 컬럼 | 설명 |
|---|---|---|
| `issue_activities` | `id` uuid PK, `seq` bigint(자동 증가, 기록 순서), `issue_id`(CASCADE), `actor_id`, `type` text, `detail` jsonb, `created_at` | 티켓의 변경 이력. `type` 은 `CREATED`, `TITLE_CHANGED`, `DESCRIPTION_CHANGED`, `STATUS_CHANGED`, `PRIORITY_CHANGED`, `ASSIGNEE_CHANGED`, `DUE_DATE_CHANGED`, `MILESTONE_CHANGED`, `LABELS_CHANGED`. `detail` 은 `{from,to}`(라벨·담당자는 `{added,removed}`. 담당자의 D-125 전 기록은 `{from,to}`)이고 사람·목표·라벨은 **그때의 이름**을 적는다 |
| `issue_subscriptions` | `issue_id`, `user_id`(둘이 PK, 둘 다 CASCADE), `created_at` | 티켓을 구독하는 사람. 알림의 받는 사람 |
| `project_subscriptions` (D-102) | `project_id`, `user_id`(둘이 PK, 둘 다 CASCADE), `created_at` | 프로젝트를 구독하는 사람. 그 프로젝트에 연결된 모든 티켓의 알림을 받는다. 프로젝트에서 빠져도 행은 남지만, 받는 사람을 고를 때 지금 구성원(또는 워크스페이스 관리자)인지 확인한다 |

### `issue_comments` (`V5`, D-101)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | uuid PK | |
| `issue_id` | uuid, CASCADE | |
| `author_id` | uuid → `users` | |
| `content` | jsonb | 편집기 문서. 태그된 사람은 이 안의 `mention` 블록(`attrs.id` = 사용자 id, `attrs.label` = 그때의 이름)이다. 따로 표를 두지 않는다 |
| `body` | text | `content` 에서 뽑은 글자(알림 문구, 검색용). 태그는 `@이름` |
| `created_at`, `updated_at` | timestamptz | 고치면 `updated_at` 만 바뀐다(화면의 "수정됨") |
| `deleted_at` | timestamptz null | 지운 댓글. 행은 남고 읽지 않는다 |

인덱스 `(issue_id, created_at, id)`. `COMMENT_ADDED`·`MENTIONED` 는 알림에만 쓰는 종류이고 `issue_activities` 에는 쌓이지 않는다.

마이그레이션이 기존 티켓마다 `CREATED` 한 줄을 넣고, 만든 사람과 담당자를 구독시켰다.

### 독립 티켓, 하위 티켓, 리액션, 첨부, 문서 (D-062~D-066)

| 대상 | 내용 |
|---|---|
| `instance_settings.workspace_key` | 모든 티켓 키의 앞부분(기본 `FB`). 관리자가 바꾼다. 형식은 프로젝트 키와 같다 |
| `instance_settings.next_issue_number` | 다음 티켓 번호. 워크스페이스 전체에서 하나의 일련번호 |
| `issues.project_id` | **null 허용**. 티켓이 연결된 프로젝트(없으면 독립 티켓). 바꿔도 번호는 그대로 |
| `issues.number` | 워크스페이스 전체에서 유일(`issues_number_key`) |
| `issues.parent_id` | 상위 티켓(`ON DELETE SET NULL`). 서로 물리는 관계는 서버가 막는다. 하위 티켓은 최상위 티켓 아래로 10단계까지이고, DB 제약이 아니라 서버가 검사한다 (D-118) |
| `issue_reactions` | `issue_id`, `user_id`, `emoji` (셋이 PK). 이모지는 서버가 정한 8개만 |
| `issue_attachments` | `id`, `issue_id`, `file_key`(저장소 `files/` 아래의 파일 이름, UUID), `file_name`(원래 이름), `size_bytes`, `uploaded_by`, `created_at` |
| `documents` | `id`, `issue_id` null, `project_id` null, `parent_id` null(안에 들어 있는 상위 문서. 프로젝트 문서만, 5단계까지, 상위가 지워지면 함께 지워진다. D-083), `title`, `content` jsonb(편집기 문서), `created_by`, `created_at`, `updated_at`. 티켓이나 프로젝트 **둘 중 하나에만** 속한다(`documents_one_owner`). 주인이 지워지면 함께 지워진다 (D-075) |
| `issues.start_date` | 티켓이 기간을 가질 때의 첫날. `due_date` 가 있어야 하고 그보다 늦을 수 없다(`issues_dates_order`) (D-077) |
| `issues.deleted_at`, `issues.deleted_by` | 삭제 표시. 삭제된 티켓은 목록·집계에서 빠지지만 행은 남는다 (D-079) |

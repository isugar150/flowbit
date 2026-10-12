# 05. 데이터 모델

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
| `login_id` | `text` unique | 로그인 아이디 (API 필드 `loginId`). 소문자로 정규화. 4~32자 |
| `email` | `text` null unique | M1 에서는 받지 않는다. 소문자로 정규화 |
| `display_name` | `text` null | 1~50자. M1 에서는 받지 않는다 (M2 프로필) |
| `avatar_key` | `text` null | 프로필 사진의 저장 파일 이름(`<uuid>.webp`). `V2` 에서 추가 |
| `password_hash` | `text` | Argon2id |
| `role` | `text` | `ADMIN` \| `MEMBER` |
| `status` | `text` | `ACTIVE` \| `DISABLED` |
| `locale` | `text` null | null 이면 인스턴스 기본값 |
| `timezone` | `text` null | null 이면 인스턴스 기본값 |
| `failed_login_count` | `int` | 기본 0. 로그인 성공 시 0 으로 |
| `locked_until` | `timestamptz` null | 이 시각까지 로그인 불가 |
| `last_login_at` | `timestamptz` null | |
| `created_at`, `updated_at` | `timestamptz` | |

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

이미지 자체는 DB 가 아니라 파일로 저장한다(`FLOWBIT_STORAGE_PATH/images/`). DB 에는 위의 `*_key` 컬럼에 파일 이름만 둔다. 규칙은 [features/M2a-settings-and-images.md](features/M2a-settings-and-images.md). 이슈 첨부처럼 한 대상에 이미지가 여러 개 붙게 되면 그때 별도 테이블을 만든다.

### `projects`, `project_members` (`V3`, M3 1단계)

| 테이블.컬럼 | 타입 | 설명 |
|---|---|---|
| `projects.id` | `uuid` PK | |
| `projects.key` | `text` unique | 이슈 키의 접두어. `^[A-Z][A-Z0-9]{1,9}$`. 만든 뒤 바꾸지 않는다 |
| `projects.name` | `text` | 1~80자 |
| `projects.description` | `text` | 500자 이하, 기본 빈 문자열 |
| `projects.next_issue_number` | `int` | 다음에 발급할 이슈 번호. 2단계에서 쓴다 |
| `projects.created_by` | `uuid` FK → `users` | |
| `projects.archived_at` | `timestamptz` null | 보관 기능용. 아직 쓰지 않는다 |
| `projects.status` (`V6`) | `text` | `BACKLOG` \| `PLANNED` \| `IN_PROGRESS` \| `COMPLETED` \| `CANCELED`, 기본 `BACKLOG` |
| `projects.priority` (`V6`) | `text` | `NONE` \| `URGENT` \| `HIGH` \| `MEDIUM` \| `LOW`, 기본 `NONE` |
| `projects.lead_id` (`V6`) | `uuid` null FK → `users` (지워지면 null) | 프로젝트 리드 |
| `projects.start_date`, `target_date` (`V6`) | `date` null | 기간. 둘 다 있으면 `start_date <= target_date` |
| `projects.labels` (`V6`) | `text[]` | 라벨 이름, 최대 10개. 기본 빈 배열 |
| `projects.icon_key` (`V9`) | `text` null | 올린 프로젝트 아이콘의 저장소 파일 이름(`ImageStorage`). 없으면 기본 아이콘 |
| `project_members.project_id`, `user_id` | `uuid` | 복합 기본 키. 프로젝트나 사용자가 지워지면 함께 지워진다 |
| `project_members.role` | `text` | `MANAGER` \| `EDITOR` \| `VIEWER` |

`project_members` 는 기본 키가 두 컬럼이라, `projects` 의 속성 컬럼은 배열(`labels`)이 있어서 Spring Data 리포지토리 대신 `ProjectMemberStore` 가 SQL 로 직접 다룬다.

### `project_updates` (`V8`)

프로젝트가 어떻게 진행되는지 남기는 글. 가장 최근 것의 `health` 가 프로젝트의 건강도다 (D-048).

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | `uuid` PK | |
| `project_id` | `uuid` FK → `projects` (함께 삭제) | `(project_id, created_at desc)` 색인 |
| `author_id` | `uuid` FK → `users` | |
| `health` | `text` | `ON_TRACK` \| `AT_RISK` \| `OFF_TRACK` |
| `body` | `text` | 평문, 5000자 이하, 기본 빈 문자열 |
| `created_at` | `timestamptz` | |

### `milestones` (`V7`)

프로젝트 안의 단계. 이슈를 묶어 단계별 진행률을 본다 ([features/M3-projects-and-issues.md](features/M3-projects-and-issues.md) "마일스톤").

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | `uuid` PK | |
| `project_id` | `uuid` FK → `projects` (함께 삭제) | |
| `name` | `text` | 1~80자 |
| `target_date` | `date` null | 목표일 |
| `created_at`, `updated_at` | `timestamptz` | |

`issues.milestone_id`(`V7`): `uuid` null FK → `milestones`. 마일스톤을 지우면 null 이 된다. 같은 프로젝트의 마일스톤인지는 서비스가 확인한다.

### `issues` (`V4`), 문서 컬럼 (`V5`)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | `uuid` PK | |
| `project_id` | `uuid` FK → `projects` (함께 삭제) | |
| `number` | `int` | 프로젝트 안의 일련번호. `(project_id, number)` 유일. `projects.next_issue_number` 를 한 문장으로 증가시켜 발급 |
| `title` | `text` | 1~200자 |
| `content` | `jsonb` null | 본문. 편집기 문서(JSON). `V5` |
| `description` | `text` | 본문에서 뽑은 글자. 미리 보기와 검색용. 서버가 채운다 |
| `preset` | `text` null | `BUG` \| `ENHANCEMENT` \| `FEATURE` |
| `status` | `text` | `BACKLOG` \| `READY` \| `IN_PROGRESS` \| `DONE` \| `CANCELED` |
| `priority` | `text` | `NONE` \| `URGENT` \| `HIGH` \| `MEDIUM` \| `LOW` |
| `assignee_id` | `uuid` null FK → `users` (지워지면 null) | 프로젝트의 활성 구성원만 |
| `due_date` | `date` null | |
| `created_by` | `uuid` FK → `users` | |
| `version` | `int` | 바뀔 때마다 1 증가. 충돌 감지와 실시간 순서 판단에 쓸 예정 |

`projects.content jsonb` (`V5`): 프로젝트 개요의 설명 문서. `projects.description` 은 한 줄 요약이다.

`content` 컬럼은 엔티티에 매핑하지 않는다. `RichText` 가 SQL 로 직접 읽고 쓴다(목록 조회에 큰 문서가 딸려 오지 않게 하려는 것이기도 하다).

## 이후 마일스톤의 모델 (초안)

방향만 적어 둔다. 각 마일스톤을 시작할 때 기능 명세에서 확정하고 이 문서에 반영한다.

이슈·프리셋·상태·댓글의 모델 초안은 [features/M3-projects-and-issues.md](features/M3-projects-and-issues.md) 의 "데이터 모델"이 기준이다. 아래 그림은 팀 개념이 있던 때의 것이고, **`teams` 는 `projects` 로 읽는다** (D-032).

```
teams ──< team_members >── users
  │
  ├──< statuses (범주: backlog / unstarted / started / completed / canceled)
  ├──< cycles (번호, 시작일, 종료일)
  ├──< labels
  └──< issues ──< comments, issue_history, issue_relations
          │ └── parent_issue_id (하위 이슈, 한 단계)
          ├── status_id, assignee_id, cycle_id, project_id
          └──< issue_labels

projects ──< project_updates          views (저장한 필터)
notifications (수신함)
```

- 이슈 키: `teams.key` + 팀별 일련번호 (`FLOW-123`). 번호는 팀 행의 카운터를 잠그고 증가시켜 발급한다.
- 목록·보드의 순서: 사이에 끼워 넣기가 쉬운 순위 문자열(LexoRank 계열)을 쓴다.
- 이슈에는 `version`(증가하는 정수)을 둔다. 낙관적 업데이트의 중복 제거와 실시간 이벤트의 순서 판단에 쓴다.
- 이슈 변경 이력은 필드 단위로 남긴다. 실행 취소와 활동 기록이 이것을 쓴다.
- 삭제는 `deleted_at` 으로 표시하고 일정 기간 뒤에 실제로 지운다(실행 취소가 가능해야 한다).

### `labels`, `issue_labels` (V10, D-060)

워크스페이스 전체가 함께 쓰는 이슈 라벨. 이슈 하나에 여러 개 붙는다(최대 10개).

| 테이블 | 컬럼 | 설명 |
|---|---|---|
| `labels` | `id` uuid PK, `name` text(1~30자, 대소문자 무시하고 유일), `color` text(`#rrggbb`), `created_at` | 처음부터 `버그`, `기능`, `중요` 세 개가 들어 있다. 목록은 만든 순서 |
| `issue_labels` | `issue_id`, `label_id` (둘이 PK, 둘 다 `ON DELETE CASCADE`) | 이슈와 라벨의 연결 |

`issues.preset` 컬럼은 남아 있지만 화면에서 쓰지 않는다. 마이그레이션이 기존 프리셋 `BUG` → `버그`, `FEATURE` → `기능` 라벨로 옮겼다(`ENHANCEMENT` 는 대응하는 라벨이 없어 옮기지 않았다).

### `issue_activities`, `issue_subscriptions` (V11, D-061)

| 테이블 | 컬럼 | 설명 |
|---|---|---|
| `issue_activities` | `id` uuid PK, `seq` bigint(자동 증가, 기록 순서), `issue_id`(CASCADE), `actor_id`, `type` text, `detail` jsonb, `created_at` | 이슈의 변경 이력. `type` 은 `CREATED`, `TITLE_CHANGED`, `DESCRIPTION_CHANGED`, `STATUS_CHANGED`, `PRIORITY_CHANGED`, `ASSIGNEE_CHANGED`, `DUE_DATE_CHANGED`, `MILESTONE_CHANGED`, `LABELS_CHANGED`. `detail` 은 `{from,to}`(라벨은 `{added,removed}`)이고 사람·목표·라벨은 **그때의 이름**을 적는다 |
| `issue_subscriptions` | `issue_id`, `user_id`(둘이 PK, 둘 다 CASCADE), `created_at` | 이슈를 구독하는 사람. 알림의 받는 사람 |

마이그레이션이 기존 이슈마다 `CREATED` 한 줄을 넣고, 만든 사람과 담당자를 구독시켰다.

### 독립 이슈, 하위 이슈, 리액션, 첨부, 문서 (V12, D-062~D-066)

| 대상 | 내용 |
|---|---|
| `instance_settings.issue_key` | 모든 이슈 키의 앞부분(기본 `FB`). 관리자가 바꾼다. 형식은 프로젝트 키와 같다 |
| `instance_settings.next_issue_number` | 다음 이슈 번호. 워크스페이스 전체에서 하나의 일련번호 |
| `issues.project_id` | **null 허용**. 이슈가 연결된 프로젝트(없으면 독립 이슈). 바꿔도 번호는 그대로 |
| `issues.number` | 워크스페이스 전체에서 유일(`issues_number_key`). 마이그레이션이 기존 이슈를 만든 순서대로 다시 매겼다. `projects.next_issue_number` 는 더 쓰지 않는다 |
| `issues.parent_id` | 상위 이슈(`ON DELETE SET NULL`). 서로 물리는 관계는 서버가 막는다 |
| `issue_reactions` | `issue_id`, `user_id`, `emoji` (셋이 PK). 이모지는 서버가 정한 8개만 |
| `issue_attachments` | `id`, `issue_id`, `file_key`(저장소 `files/` 아래의 파일 이름, UUID), `file_name`(원래 이름), `size_bytes`, `uploaded_by`, `created_at` |
| `issue_documents` | `id`, `issue_id`, `title`, `content` jsonb(편집기 문서), `created_by`, `created_at`, `updated_at` |

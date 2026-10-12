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

## 이후 마일스톤의 모델 (초안)

방향만 적어 둔다. 각 마일스톤을 시작할 때 기능 명세에서 확정하고 이 문서에 반영한다.

개념의 정의는 [01-product.md](01-product.md) 2장 (Linear식: 팀이 이슈의 집, 프로젝트는 목표, 사이클은 자동 반복).

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

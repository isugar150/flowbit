# 계정과 운영 보안 (내 비밀번호 변경, 감사 로그, 비밀 값 암호화)

상태 (2026-10-28): **구현됨.** 사용자가 지시한 세 가지(D-226)와, 같은 날 함께 한 목록 페이징(D-227)을 적는다. 모두 마일스톤 밖의 작업이다.

## 1. 내 비밀번호 변경

- 내 설정(`/settings`)의 프로필 아래에 **비밀번호** 구역이 생긴다: 현재 비밀번호, 새 비밀번호(10자 이상, 아이디와 같을 수 없음), 새 비밀번호 확인.
- **보이는 조건**: 관리자가 **LDAP 연동을 켜 두지 않았고**, 계정의 비밀번호가 Flowbit 에 있을 때(`auth_source = LOCAL`)만 나온다. LDAP 를 켜면 모두에게(관리자 포함) 숨긴다. SSO·LDAP 로 로그인하는 계정은 비밀번호가 없어 늘 숨긴다. (관리자가 다른 사람의 비밀번호를 정하는 "재설정"은 그대로다: LDAP 를 켜도 관리자 계정은 이 서버에 비밀번호를 둔다.)
- 바꾸면 **이 브라우저는 로그인된 채로 남고 같은 계정의 다른 세션은 모두 끝난다.** 감사 로그에 `MEMBER_PASSWORD_CHANGED`.
- 현재 비밀번호를 틀리면 필드 오류 `currentPassword`/`INCORRECT`(422). 시도는 계정마다 10분에 10번까지(`RateLimiter`, 메모리), 넘으면 429.

| API | 설명 |
|---|---|
| `GET /api/v1/me/password` | `{ available }`. 설정 화면이 구역을 보일지 정한다 |
| `PUT /api/v1/me/password` | `{ currentPassword, newPassword }` → 204. 409 `PASSWORD_IN_DIRECTORY`(LDAP 사용 중), 409 `EXTERNAL_ACCOUNT`, 422 `currentPassword`·`newPassword`(`TOO_SHORT`·`TOO_LONG`·`SAME_AS_LOGIN_ID`·`SAME_AS_CURRENT`) |

코드: `user/application/PasswordChangeService.kt`, `SessionRevoker.revokeOthers`, 화면 `features/settings/components/password-settings.tsx`.

## 2. 감사 로그 탭

- 관리자 설정에 **감사 로그** 탭(`/admin/audit`). `audit_events` 를 최근 것부터 한 쪽에 50개씩 보인다.
- 좁히기: 종류(모든 `AuditEventType`), 한 사람(아이디·표시 이름의 일부), 시작일·종료일(종료일 당일 포함, **워크스페이스 기본 시간대**의 날). 모두 주소(`?type=&actor=&from=&to=&page=`)에 들어간다. 일반 GET 폼이라 스크립트 없이 동작한다.
- 줄: 시각(내 시간대, `2026-10-28 14:03:21`), 일(한글 이름과 `detail` 의 키·값), 한 사람(없으면 "시스템·알 수 없음"), 접속 주소(마우스를 올리면 브라우저 정보).
- 보여 주기만 한다. 지우거나 고치는 길은 없다. 보관 기간 설정도 없다(오래 쌓이면 DB 가 커진다: 필요하면 "데이터 보관"에 항목을 더한다).

| API | 설명 |
|---|---|
| `GET /api/v1/admin/audit?type=&actor=&from=&to=&limit=&offset=` | `{ total, types, items }`. `limit` 기본 50·최대 100. `types` 는 로그가 가질 수 있는 모든 종류. `from`·`to` 는 `2026-10-28` 형식, 알 수 없는 `type` 은 422 |

코드: 백엔드 `auditlog/AuditLog.kt`(서비스+컨트롤러), 프런트 `features/audit/`, `app/(app)/admin/audit/page.tsx`, 문구 `messages.audit`(새 `AuditEventType` 이 생기면 `messages.audit.types` 에도 이름을 더한다).

## 3. 비밀 값 암호화와 기존 데이터 이전

DB 에 평문으로 있던 비밀 값을 **AES-256-GCM** 으로 암호화한다.

| 표·열 | 내용 |
|---|---|
| `ldap_settings.bind_password` | LDAP 검색 계정 비밀번호 |
| `sso_settings.client_secret` | SSO 클라이언트 시크릿 |
| `notification_settings.smtp_password`, `fcm_service_account` | 메일 비밀번호, Firebase 서비스 계정 키(JSON) |
| `webhooks.headers`, `signing_secret` | API 알림의 헤더(토큰이 들어갈 수 있다)와 서명 값 |
| `git_connections.secret`, `github_client_secret`, `github_private_key` | Git 웹훅 비밀 값, GitHub 앱 시크릿·비공개 키 |

- **키**: 환경 변수 `FLOWBIT_SECRET_KEY`(16자 이상 암호문). PBKDF2-HMAC-SHA256(20만 회)로 256비트 키를 만든다. `deploy.sh` 가 처음 만드는 `.env` 에는 자동으로 정해 넣는다. 이미 있는 `.env` 에는 `deploy.sh` 가 키가 없다고 알려 주고 만드는 법을 보여 준다.
- **저장 형태**: `enc:v1:` + Base64(IV 12바이트 + 암호문 + 태그). 저장할 때마다 IV 가 달라서 같은 값도 다르게 보인다. 빈 값은 빈 채로 둔다(설정 안 됨을 SQL 로 가릴 수 있게).
- **읽고 쓰기**: 엔티티의 비밀 열은 `Secret` 타입(`...Stored` 속성)이고, R2DBC 변환기(`SecretWritingConverter`·`SecretReadingConverter`)가 쓸 때 암호화하고 읽을 때 푼다. 레포지토리 메서드, `@Query`, 엔티티 템플릿 어느 길로 읽어도 같다. 코드는 평문 속성(`bindPassword` 등)을 그대로 쓴다. 로그에는 `Secret(***)` 로 찍힌다.
- **기존 데이터 이전**: 서버가 뜬 뒤(`ApplicationReadyEvent`) `SecretMigration` 이 `enc:v1:` 로 시작하지 않는 비어 있지 않은 값을 모두 암호화한다. **키를 적고 백엔드를 다시 띄우기만 하면 된다.** 여러 번 돌려도 안전하고(이미 암호화된 값은 건드리지 않음), 그 사이에 다른 곳에서 바뀐 행은 건너뛰고 다음 시작 때 처리한다. 이전이 끝나기 전에도 평문은 그대로 읽힌다.
- **키가 없을 때**: 비밀 값은 평문으로 저장되고 시작할 때 경고가 남는다(개발·테스트 편의). 암호화된 값이 있는데 키가 없으면 읽을 수 없고, 시작 로그에 오류가 남는다. **키를 잃어버리거나 바꾸면 암호화된 값을 되살릴 수 없다**(관리자 화면에서 다시 입력해야 한다). 키 교체 기능은 없다.
- **백업 파일**: 설정 백업(`admin-backup.md`)은 예전처럼 서비스가 읽은 평문 값을 담는다(파일 암호를 정하면 파일째 암호화된다). 복원하면 다시 암호화되어 저장된다.
- **범위 밖**: 공유 링크 토큰(`share_links.token`), 푸시 토큰, 세션 토큰 해시는 이번 암호화 대상이 아니다. 새 비밀 열을 만들면 엔티티를 `Secret` 으로 매핑하고 `SecretMigration.COLUMNS` 에 더한다.

테스트: `SecretsIntegrationTest`(키를 켠 별도 컨텍스트: 저장된 값이 `enc:v1:`, API 에서는 그대로 쓰임, 평문→암호화 이전과 반복 안전). 일반 통합 테스트는 키를 비워 평문 그대로 돈다(`IntegrationTest` 의 `flowbit.secrets.key=`).

감사 로그 `PROJECT_AUTOMATIONS_CHANGED`: 프로젝트 자동화 저장·삭제(`projectId`, `ruleId`, `change`).

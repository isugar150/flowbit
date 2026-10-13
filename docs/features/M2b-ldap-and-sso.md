# M2b. LDAP 연동, SSO 연동, 이메일 아이디

관리자가 사내 디렉터리(LDAP)와 SSO 서비스로 로그인하게 설정할 수 있다. 계정이 없는 사람은 처음 로그인할 때 가입까지 된다. 로그인 아이디는 일반 아이디와 이메일 주소 둘 다 된다. 사용자 지정(2026-10-12), 결정은 D-087~D-090, D-094, D-097, D-098.

## 범위

- 관리자 설정의 **"로그인 연동"** 탭(`/admin/auth`): LDAP, SSO. 항목마다 접혀 있는 카드이고 켜짐 여부가 보인다(D-096)
- 로그인 화면: 아이디 칸이 "아이디 또는 이메일", SSO 가 켜져 있으면 버튼
- SSO 방식 두 가지: **OpenID Connect**(표준)와 **직접 설정**(OAuth 2.0, 사내 SSO 같은 자체 방식)
- 자동 가입: LDAP·SSO 로 처음 로그인하면 구성원(MEMBER) 계정을 만든다. 설정마다 끌 수 있다
- 이메일 아이디: 아이디에 `@` 가 있으면 이메일 주소 규칙으로 검사한다

- 계정 전환: 아이디가 같은 기존 계정은 LDAP·SSO 로 로그인하는 순간 그쪽 계정으로 바뀐다 (D-097)
- LDAP 을 쓰는 동안 구성원 화면에서 구성원 추가와 (관리자가 아닌 사람의) 비밀번호 재설정을 막는다 (D-098)

범위 밖: SAML, SSO 서비스 여러 개, LDAP 그룹으로 역할·프로젝트 정하기, 주기적 동기화(퇴사자 자동 비활성화), SSO 로그아웃 연동.

## 아이디 규칙

| 종류 | 규칙 |
|---|---|
| 일반 아이디 | 영문 소문자·숫자로 시작, 영문 소문자·숫자·`.`·`_`·`-`, 4~32자 |
| 이메일 아이디 | `@` 가 들어 있으면 이메일로 본다. 254자 이하, `이름@도메인.최상위` 형식. 소문자로 정규화 |
| LDAP·SSO 가 알려 준 아이디 | 길이 하한이 없다. 문자는 영문 소문자·숫자·`.`·`_`·`@`·`+`·`-`. 맞지 않으면 가입을 거부한다 |

- 로그인할 때 입력한 값은 `login_id` 또는 `email` 과 비교한다. 둘 다 맞는 계정이 있으면 `login_id` 가 맞는 쪽이다.
- 이메일을 아이디로 만든 로컬 계정은 `email` 에도 같은 값이 들어간다.
- 새 계정의 아이디는 다른 계정의 `login_id` 와도 `email` 과도 겹칠 수 없다.

## 계정의 종류 (`users.auth_source`)

| 값 | 비밀번호 확인 | 비고 |
|---|---|---|
| `LOCAL` | Flowbit (Argon2) | 초기 설정의 관리자, 관리자가 만든 구성원 |
| `LDAP` | LDAP 서버에 그 사람으로 바인드 | `password_hash` 없음. 아이디로 구분 |
| `SSO` | SSO 서비스 | `password_hash` 없음. `external_id`(구분 값)로 구분. 비밀번호 로그인 불가 |

- LDAP·SSO 계정은 "비밀번호 재설정" 대상이 아니다(409 `EXTERNAL_ACCOUNT`). 구성원 목록에 `LDAP`·`SSO` 표시가 붙는다.
- 역할 변경과 비활성화는 로컬 계정과 같다. 비활성화하면 LDAP·SSO 로도 들어올 수 없다.

## 아이디·비밀번호 로그인의 순서 (`AuthService.login`)

1. **LDAP 이 켜져 있으면 먼저 디렉터리에 묻는다**(로컬 계정이 있는 아이디여도). 디렉터리가 비밀번호를 받아들이면 "자동 가입"·"계정 전환" 절을 따라 로그인한다. 비활성이거나 잠긴 계정의 비밀번호는 디렉터리에 보내지 않는다.
2. 디렉터리가 받아들이지 않았거나(틀린 비밀번호, 없는 사람, 서버 장애) LDAP 이 꺼져 있으면, Flowbit 에 저장된 비밀번호로 확인한다(실패 횟수, 잠금 포함). 이 길이 있어서 **LDAP 에 문제가 생겨도 비밀번호를 가진 로컬 관리자는 들어올 수 있다.**
3. LDAP·SSO 계정은 여기에 비밀번호가 없으므로 2에서 통과할 수 없다.

**LDAP 확인 (`LdapDirectory`)**: 연결 하나에서 차례로 한다. ① 검색 계정으로 바인드 ② 사용자 필터로 **정확히 한 명**을 찾음 ③ **같은 연결에서** 그 사람의 DN 과 입력한 비밀번호로 다시 바인드. 같은 연결을 쓰는 것은 사용자 바인드를 앞선 검색 계정에 묶어 판단하는 서버(hrm 의 LDAP 등)가 새 연결의 사용자 바인드를 거부하기 때문이다(D-094). 빈 비밀번호는 서버에 보내지 않고 거부한다(익명 바인드로 통과되는 것을 막는다).

- 실패는 전부 401 `INVALID_CREDENTIALS` 하나다(아이디 없음, 비밀번호 틀림, LDAP 서버 장애, 자동 가입 꺼짐, 아이디 충돌). 이유는 감사 로그와 서버 로그에만 남는다. 서버가 진단 메시지(`LOCKED: …`)를 주면 서버 로그에 남긴다.
- LDAP 계정에는 Flowbit 의 5회 잠금을 적용하지 않는다. 잠금은 디렉터리의 정책을 따른다. IP 기준 요청 제한은 그대로다.
- LDAP 장애 때 다른 인증으로 넘기지 않는다. 재시도하지 않는다.
- 시간 제한: 연결 5초, 응답 5초. LDAP 호출은 DB 트랜잭션 밖에서, `Dispatchers.IO` 에서 한다.
- `ldaps://`·StartTLS 의 인증서는 JVM 신뢰 저장소로 검증하고 호스트 이름을 확인한다. 사설 CA 는 JVM 에 등록해야 한다.

## SSO 로그인 흐름

```
로그인 화면 ─▶ GET /api/v1/auth/sso/start?next=/가려던/곳
                 │ FLOWBIT_SSO 쿠키(state, nonce, PKCE verifier, next · 10분 · HttpOnly)
                 ▼ 302
            SSO 서비스의 로그인 화면
                 ▼ 302  /api/v1/auth/sso/callback?code=…&state=…
            백엔드: state 확인 → 코드 교환 → 사용자 정보 → 계정 찾기/만들기 → 세션 쿠키
                 ▼ 302  next (또는 /)        실패하면 /login?error=sso_…
```

공통: state 는 서버가 만들어 쿠키에 두고 한 번만 쓴다. 코드 교환은 서버에서만 하고 다시 시도하지 않는다. 리다이렉트 URI 는 `{접속 주소}/api/v1/auth/sso/callback` 이고 관리자 화면이 보여 준다(요청의 `X-Forwarded-*` 에서 알아내고, 틀리면 `FLOWBIT_PUBLIC_URL`). `next` 는 이 사이트 안의 경로만 받는다.

| | OpenID Connect (`OIDC`) | 직접 설정 (`OAUTH2`) |
|---|---|---|
| 대상 | Keycloak, Entra ID, Google, Okta | 일반 OAuth 2.0(네이버·카카오·GitHub 식), 사내 SSO(hrm 등) |
| 주소 | 발급자 주소 하나. 나머지는 `/.well-known/openid-configuration` 에서 | 로그인 시작 주소, 코드 교환 주소, (선택) 사용자 정보 주소를 직접 |
| 시작할 때 보내는 값 | `response_type=code`, `client_id`, `redirect_uri`, `state`, `scope`, `nonce`, PKCE | `response_type=code`, `client_id`, `redirect_uri`, `state`, (있으면) `scope`. 모르는 값을 거부하는 서비스를 위해 nonce·PKCE 는 보내지 않는다 |
| 코드 교환 | 표준 폼, 시크릿은 서비스가 지원하는 방식 | 형식(폼·JSON), 시크릿 보내는 방법(HTTP Basic·`Authorization: Bearer`·본문), 본문 템플릿을 고른다 |
| 믿는 근거 | ID 토큰의 서명(JWKS), 발급자, 만료, `aud`, `nonce` | 설정한 주소에 서버가 직접 물어 받은 응답이라는 것. 서명 검증은 없다 |
| 사용자 값 | `sub`, 아이디 클레임(기본 `preferred_username`, 없으면 `email`), `email`, `name` | 구분 값·아이디·이메일·이름이 들어 있는 필드 이름을 정한다. 점으로 안쪽 값(`kakao_account.email`) |

**직접 설정의 코드 교환**: 본문 템플릿을 비우면 표준 OAuth 2.0 값(`grant_type`, `code`, `redirect_uri`, `client_id`, 시크릿을 본문으로 보낼 때 `client_secret`)을 보낸다. 쓰면 `{{code}}` `{{clientId}}` `{{clientSecret}}` `{{redirectUri}}` 가 형식에 맞게 이스케이프되어 들어간다. 사용자 정보 주소가 비어 있으면 교환의 응답이 곧 사용자 정보이고, 있으면 응답의 `access_token` 으로 그 주소를 읽는다.

**hrm (NAVER WORKS)**: 화면의 "예시 넣기 > hrm" 이 채워 준다. 로그인 시작 `https://hrm.motherk.link/api/sso/authorize`, 코드 교환 `https://hrm.motherk.link/api/sso/token`, 시크릿 `Authorization: Bearer`, 형식 JSON, 본문 `{"clientId": "{{clientId}}", "code": "{{code}}"}`, 구분 값·아이디·이메일 `email`, 이름 `userName`, 버튼 모양 NAVER WORKS. 관리자는 클라이언트 ID(`api_name`)와 API 키만 넣는다. hrm 이 주는 `administrator`·`roles` 는 쓰지 않는다(역할은 Flowbit 에서 정한다).

**로그인 버튼**: 버튼 이름과 모양(기본 / NAVER WORKS)을 정한다. NAVER WORKS 모양은 hrm 가이드의 사양(색, 아이콘 타일, 문구 "NAVER WORKS로 계속하기")을 따르고 아이콘은 `public/naver-works-app-icon.svg` 다. 버튼은 아이디·비밀번호 폼 아래, "또는" 구분선 다음에 둔다.

**실패 코드** (`/login?error=`): `sso_failed`, `sso_denied`(서비스가 `error=access_denied` 로 돌려보냄), `sso_conflict`, `sso_not_registered`, `sso_disabled`, `sso_invalid_login_id`, `sso_no_login_id`. 로그인 화면은 아는 코드의 문구만 보여 주고 주소의 글자를 그대로 싣지 않는다.

## 자동 가입 (`ExternalAccountService.signIn`)

| 상황 | 결과 |
|---|---|
| 이미 연결된 계정이 있다 (SSO: 구분 값 일치, LDAP: 아이디 일치 + `LDAP` 계정) | 그 계정으로 로그인 |
| 그 계정이 비활성 | 거부 (`DISABLED`) |
| 계정이 없고, **아이디가 같은** 기존 계정이 있다 | 그 계정을 이 방식의 계정으로 **전환**하고 로그인 (아래 "계정 전환") |
| 아이디가 다른 사람의 이메일이다, 또는 같은 SSO 의 다른 사람(구분 값이 다름)이 이미 그 아이디를 쓴다 | 거부 (`CONFLICT`) |
| 계정이 없고 "자동으로 만들기"가 꺼져 있다 | 거부 (`NOT_REGISTERED`) |
| 계정이 없고 켜져 있다 | 역할 `MEMBER` 로 만들고 로그인. 이름은 50자까지, 이메일은 다른 사람이 쓰고 있으면 비워 둔다 |

만든 뒤에는 디렉터리의 이름·이메일이 바뀌어도 따라가지 않는다(사용자가 프로필에서 고친 이름을 덮지 않기 위해).

## 계정 전환 (D-097)

LDAP 이나 SSO 로 본인임이 확인된 사람의 아이디가 기존 계정의 아이디와 같으면, 그 계정이 그 방식의 계정으로 바뀐다: `auth_source` 가 `LDAP`/`SSO` 가 되고 저장된 비밀번호를 지운다(`password_hash = null`). 그 뒤로 비밀번호는 LDAP(또는 SSO 서비스)에서만 확인한다.

- 계정은 그대로다: id, 역할, 이름, 프로젝트 권한, 만든 티켓이 유지된다. 감사 로그 `MEMBER_CONVERTED`(`from`, `to`).
- 로컬 → LDAP, 로컬 → SSO, LDAP ↔ SSO 모두 된다(마지막에 쓴 방식의 계정이 된다). SSO 계정이 LDAP 비밀번호로 로그인하면 LDAP 계정이 된다.
- 관리자 계정도 전환된다. 전환된 관리자는 LDAP 이 죽으면 들어올 수 없으므로, **LDAP 에 없는 아이디의 로컬 관리자(초기 설정의 관리자)를 하나 남겨 둔다.** 관리자 계정은 비밀번호를 재설정하면 다시 로컬 계정이 된다.
- 비활성 계정은 전환하지 않고 거부한다.
- 믿는 근거는 "LDAP·SSO 서비스가 그 아이디의 주인이라고 확인해 줬다"는 것이다. SSO 서비스가 사용자가 아이디를 마음대로 정할 수 있는 곳이면 남의 계정을 가져갈 수 있다. 아이디가 회사 이메일처럼 서비스가 보증하는 값일 때 쓴다.

## LDAP 을 쓰는 동안의 구성원 관리 (D-098)

| 기능 | LDAP 꺼짐 | LDAP 켜짐 |
|---|---|---|
| 구성원 추가 | 가능 | 불가 (409 `MEMBERS_FROM_DIRECTORY`). 화면에서 버튼 대신 안내문. 구성원은 LDAP 으로 처음 로그인할 때 생긴다 |
| 비밀번호 재설정 | 로컬 계정만 (그 밖에는 409 `EXTERNAL_ACCOUNT`) | **역할이 관리자인 계정만** (그 밖에는 409 `PASSWORD_IN_DIRECTORY`). LDAP·SSO 계정이던 관리자는 재설정하면 로컬 계정이 된다 |
| 역할 변경, 비활성화, 프로젝트 권한 | 가능 | 가능 |

**프로젝트 권한**: 구성원 줄의 "프로젝트 권한" 버튼이 모든 프로젝트와 그 사람의 권한(권한 없음·보기·편집·관리)을 보여 주고 바로 바꾼다. 자동 가입된 사람은 아무 프로젝트에도 속하지 않으므로 여기서 넣어 준다. 조회는 `GET /api/v1/admin/members/{id}/projects`, 변경은 기존의 `PUT`·`DELETE /api/v1/projects/{projectId}/members/{userId}`(관리자는 모든 프로젝트에서 쓸 수 있다). 워크스페이스 역할(관리자/구성원)은 전부터 같은 줄의 선택 상자로 바꾼다.

## 설정 항목

**LDAP** (`ldap_settings`): 사용 여부, 서버 주소(`ldap://`·`ldaps://`), StartTLS, 검색 계정 DN·비밀번호(비우면 익명), 기준 DN, 사용자 필터(기본 `(|(uid={0})(mail={0}))`, `{0}` 에 입력한 아이디), 아이디·이메일·이름 속성(기본 `uid`·`mail`·`cn`), 자동 가입.

| 서버 | 사용자 필터 | 아이디 / 이메일 / 이름 속성 |
|---|---|---|
| OpenLDAP | `(\|(uid={0})(mail={0}))` | `uid` / `mail` / `cn` |
| Active Directory | `(\|(sAMAccountName={0})(mail={0}))` | `sAMAccountName` / `mail` / `displayName` |
| hrm LDAP (`ldaps://ldap.motherk.link:636`) | `(&(objectClass=posixAccount)(\|(uid={0})(mail={0})))` | `uid`(회사 이메일) / `mail` / `displayName`. 검색 계정 `cn={계정},ou=services,dc=motherk,dc=co,dc=kr`, 기준 DN `ou=users,dc=motherk,dc=co,dc=kr`. hrm 에서 조회 계정 등록, Flowbit 서버의 CIDR 허용, 각자의 LDAP 앱 비밀번호 발급이 필요하다 |

**SSO** (`sso_settings`): 사용 여부, 방식, 버튼 이름·모양, 클라이언트 ID·시크릿, 범위, 아이디 값, 자동 가입. OIDC 는 발급자 주소. 직접 설정은 로그인 시작·코드 교환·사용자 정보 주소, 시크릿 보내는 방법, 요청 형식·본문, 구분 값·이메일·이름 필드.

- 켠 상태로 저장할 때만 값을 검사한다. OIDC 는 저장할 때 발급자의 설정 문서를 실제로 읽어 본다.
- 검색 계정 비밀번호와 클라이언트 시크릿은 **DB 에 평문으로 저장**하고 API 로는 돌려주지 않는다("저장됨" 여부만). 요청에서 빼거나 null 이면 저장된 값을 유지한다.

## API

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/v1/auth/methods` | 공개 | `{ ldap: boolean, sso: { displayName, buttonStyle } \| null }` |
| GET | `/api/v1/auth/sso/start?next=` | 공개 | SSO 서비스로 302 |
| GET | `/api/v1/auth/sso/callback` | 공개 | 로그인 처리 후 302 |
| GET, PUT | `/api/v1/admin/auth/ldap` | 관리자 | 응답에 `bindPasswordSet` |
| POST | `/api/v1/admin/auth/ldap/test` | 관리자 | 본문의 설정으로 연결해 본다(저장 안 함). `testLoginId` 를 주면 그 사람을 찾는다. 항상 200, `{ ok, message, loginId, email, displayName }` |
| GET | `/api/v1/admin/members/{id}/projects` | 관리자 | `[{ projectId, name, role }]`. `role` 은 구성원이 아니면 null |
| GET, PUT | `/api/v1/admin/auth/sso` | 관리자 | 응답에 `clientSecretSet`, `redirectUri`. `protocol` 은 `OIDC` \| `OAUTH2` |

`GET /api/v1/admin/members` 의 항목에 `authSource`, `email` 이 더해졌다. 감사 로그: `AUTH_SETTINGS_CHANGED`, `MEMBER_PROVISIONED`, 로그인 성공·실패의 `method`·`reason`.

## 인수 기준

`ExternalAuthIntegrationTest`(인메모리 LDAP 서버와 가짜 SSO 서버로 실제 흐름을 돈다)가 다음을 확인한다.

- 이메일 아이디로 구성원을 만들고 로그인한다. 형식이 틀린 이메일은 거부한다
- LDAP 설정은 관리자만 다루고 비밀번호를 돌려주지 않는다. 틀린 설정으로는 켤 수 없다. 연결 테스트는 저장하지 않는다
- LDAP 사용자가 아이디로도 이메일로도 로그인하고, 처음이면 계정이 만들어지며, 두 방법이 같은 계정이다
- 틀린·빈 비밀번호, LDAP 꺼짐, 자동 가입 꺼짐, 비활성 계정은 로그인할 수 없다
- 같은 아이디의 로컬 계정은 LDAP 비밀번호로 로그인하면 LDAP 계정이 되고(같은 계정, 그 뒤로 예전 비밀번호는 안 통한다), 그 전까지는 예전 비밀번호가 통한다. SSO 도 같다. 같은 SSO 의 다른 사람은 그 아이디를 가져갈 수 없다
- LDAP 을 쓰는 동안 구성원 추가와 관리자가 아닌 사람의 비밀번호 재설정은 409 다. 관리자 계정은 재설정하면 로컬 계정이 된다
- 관리자는 한 구성원의 프로젝트별 권한을 본다(`MembersIntegrationTest`)
- OIDC 로 처음 로그인하면 계정이 만들어지고 `next` 로 간다. 다시 로그인하면 같은 계정이다. state·nonce·aud 가 틀리면 로그인되지 않는다
- 직접 설정(hrm 과 같은 모양: JSON 본문, Bearer 키, 응답이 곧 사용자 정보)으로 로그인한다. 시작 주소에 nonce·PKCE 를 붙이지 않는다. `access_denied` 는 `sso_denied` 로 돌아간다. 주소가 빠지면 켤 수 없다
- 아이디 충돌, 자동 가입 꺼짐, SSO 꺼짐, 바깥 주소로의 `next` 를 막는다

## 확인이 필요한 것

- **화면은 아무도 확인하지 않았다.** 실제 hrm(LDAP·SSO), Active Directory, Keycloak 등으로도 확인하지 않았다. 문서를 보고 맞춘 것이다.
- hrm LDAP 이 요구하는 "같은 연결에서 바인드"는 구현했지만 테스트용 인메모리 서버는 이를 구분하지 않아 자동 테스트로는 확인되지 않는다.
- 계정 전환은 아이디가 같을 때만 한다. 아이디는 다르고 이메일만 같은 경우는 거부한다. 이메일로도 이을지.
- 시크릿을 DB 에 암호화해 저장할지(암호화 키를 환경 변수로 받아야 한다).
- LDAP 그룹으로 관리자 역할을 정할지, 디렉터리에서 사라진 사람을 자동으로 비활성화할지.
- Next.js 의 `/api` rewrite 뒤에서 리다이렉트 URI 가 접속 주소로 제대로 나오는지(틀리면 `FLOWBIT_PUBLIC_URL`).
